package com.strybok.sdk

import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Thrown when the instance is used, or was still working, after `stop()`. */
internal class StrybokStoppedException : IllegalStateException("Strybok has been stopped")

/**
 * All SDK behaviour, in plain Kotlin: no `android.*` type is used here, so the
 * whole contract is covered by JVM unit tests. [Strybok] adds the Intent glue.
 *
 * Thread-safe: mutable state is guarded by [lock]; nothing blocking and no
 * user code runs while the lock is held.
 */
internal class StrybokEngine(
    private val config: StrybokConfig,
    private val callbackExecutor: Executor,
    /** Monotonic clock in milliseconds: duplicate detection and the life of an attestation token. */
    private val clockMillis: () -> Long,
    private val platform: String,
    /** Wall clock in milliseconds since the epoch; only compared with an attestation token's expiry. */
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    /** Storage and package facts for the deferred-link check; `null` when no `Context` was given. */
    private val deferredPlatform: DeferredLinkPlatform? = null,
    /** How long one install referrer source may take before it is abandoned. */
    private val sourceTimeoutMillis: Long = 10_000,
) {
    private class Delivery(val link: ResolvedDeepLink, val sequence: Long) {
        /** Guarded by [lock]: put back into the queue by a departing subscriber. */
        var requeued = false

        /** Guarded by [lock]: subscribers that got it second-hand from a departing one. */
        val redeliveredTo = HashSet<Subscription>()
    }

    private class Subscription(val joinedAt: Long) {
        val channel = Channel<Delivery>(Channel.UNLIMITED)

        @Volatile
        var active = true
    }

    private val transport: StrybokTransport = config.transport ?: HttpUrlConnectionTransport()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    // --- guarded by lock -----------------------------------------------------
    private val subscribers = LinkedHashSet<Subscription>()
    private val listeners = IdentityHashMap<StrybokLinkListener, Subscription>()

    /** Links resolved while nobody was listening (typically the cold-start link). */
    private val pending = ArrayDeque<ResolvedDeepLink>()
    private var sequence = 0L
    private var initialLink: ResolvedDeepLink? = null
    private var launchJob: Job? = null
    private var lastUrl: String? = null
    private var lastUrlAt = 0L

    /** Created by the first `createLink` that needs it; never by `start` or an incoming link. */
    private var attestation: AttestationSession? = null

    private var deferredEnabled = config.deferredLinks
    private var deferredLaunched = false
    private val statusListeners = IdentityHashMap<StrybokDeferredLinkStatusListener, Job>()

    /** Whether a link of this app was accepted since the process started; a deferred link then loses. */
    private var normalLinkAccepted = false
    // -------------------------------------------------------------------------

    private val deferredStatus = MutableStateFlow(
        if (config.deferredLinks) DeferredLinkStatus.CHECKING else DeferredLinkStatus.DISABLED,
    )

    val deferredLinkStatus: StateFlow<DeferredLinkStatus> = deferredStatus.asStateFlow()

    init {
        require(!config.deferredLinks || deferredPlatform != null) { DEFERRED_NEEDS_CONTEXT }
    }

    @Volatile
    private var stopped = false

    val isStopped: Boolean get() = stopped

    /** For tests: number of attached Flow collectors and listeners. */
    val subscriberCount: Int get() = synchronized(lock) { subscribers.size }

    val links: Flow<ResolvedDeepLink> = flow {
        val subscription = synchronized(lock) { subscribeLocked() }
        try {
            for (delivery in subscription.channel) emit(delivery.link)
        } finally {
            unsubscribe(subscription)
        }
    }

    fun isStrybokUrl(url: String?): Boolean = url != null && parse(url) != null

    /** Whether [url] is a link of this app, with the configured `parameterLinks`; its lookup parts. */
    private fun parse(url: String): ParsedLink? =
        parseStrybokLink(url, config.allowedHosts, config.parameterLinks)

    /** See [Strybok.startWithUrl]. */
    fun start(launchUrl: String?): Boolean = synchronized(lock) {
        if (stopped) return false
        val accepted = acceptLocked(launchUrl)
        if (launchJob != null) {
            // Already started: this is simply another incoming link.
            if (accepted) scope.launch { handle(launchUrl!!, isInitial = false) }
            return accepted
        }
        launchJob = if (accepted) {
            scope.launch { handle(launchUrl!!, isInitial = true) }
        } else {
            Job().apply { complete() }
        }
        launchDeferredCheckLocked()
        accepted
    }

    /** See [Strybok.enableDeferredLinks]. */
    fun enableDeferredLinks() {
        synchronized(lock) {
            checkNotNull(deferredPlatform) { DEFERRED_NEEDS_CONTEXT }
            if (stopped || deferredEnabled) return
            deferredEnabled = true
            deferredStatus.value = DeferredLinkStatus.CHECKING
            launchDeferredCheckLocked()
        }
    }

    /**
     * Starts the deferred-link check when it is switched on, `start` was called and it has not
     * run yet. The check is a coroutine of its own that first waits for the launch link, so
     * `start`, the launch link and `getInitialLink` never wait for it.
     */
    private fun launchDeferredCheckLocked() {
        val started = launchJob ?: return
        val platform = deferredPlatform ?: return
        if (!deferredEnabled || deferredLaunched || stopped) return
        deferredLaunched = true
        val checker = DeferredLinkChecker(
            config = config,
            platform = platform,
            scope = scope,
            redeem = ::redeemDeferredLink,
            normalLinkAccepted = { synchronized(lock) { normalLinkAccepted } },
            deliver = ::deliverDeferredLink,
            parse = { url, body ->
                try {
                    parseResolved(url, body, isDeferred = true)
                } catch (_: StrybokException) {
                    null
                }
            },
            sourceTimeoutMillis = sourceTimeoutMillis,
            setStatus = { deferredStatus.value = it },
        )
        scope.launch(Dispatchers.IO) {
            started.join()
            checker.run()
        }
    }

    /** The redeem request, with timeout and retries as for resolving; `null` when no answer came. */
    private suspend fun redeemDeferredLink(body: String): StrybokHttpResponse? {
        val request = StrybokHttpRequest(
            method = "POST",
            url = "${config.apiBaseUrl}/v1/public/deferred-links/redeem",
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            body = body,
            timeoutMillis = config.timeoutMillis,
        )
        return try {
            send(request, null, "The redeem request timed out") { it }
        } catch (_: StrybokException) {
            null // Not reported anywhere: a later launch tries again.
        }
    }

    /** A normal link wins, also one that arrived while the request was in flight. No `app_open`. */
    private fun deliverDeferredLink(link: ResolvedDeepLink): DeferredDelivery = synchronized(lock) {
        when {
            stopped -> DeferredDelivery.STOPPED
            normalLinkAccepted -> DeferredDelivery.SUPERSEDED
            else -> {
                deliver(link, isInitial = false)
                DeferredDelivery.DELIVERED
            }
        }
    }

    fun addDeferredLinkStatusListener(listener: StrybokDeferredLinkStatusListener) {
        synchronized(lock) {
            if (stopped || statusListeners.containsKey(listener)) return
            statusListeners[listener] = scope.launch {
                deferredStatus.collect { status ->
                    callbackExecutor.execute {
                        val attached = synchronized(lock) { statusListeners.containsKey(listener) }
                        if (attached) listener.onDeferredLinkStatus(status)
                    }
                }
            }
        }
    }

    fun removeDeferredLinkStatusListener(listener: StrybokDeferredLinkStatusListener) {
        synchronized(lock) { statusListeners.remove(listener) }?.cancel()
    }

    /** See [Strybok.handleUrl]. */
    fun handleUrl(url: String?): Boolean = synchronized(lock) {
        if (stopped || !acceptLocked(url)) return false
        scope.launch { handle(url!!, isInitial = false) }
        true
    }

    suspend fun awaitStarted() {
        val job = synchronized(lock) { launchJob } ?: return
        job.join()
    }

    suspend fun getInitialLink(): ResolvedDeepLink? {
        awaitStarted()
        synchronized(lock) {
            val link = initialLink ?: return null
            initialLink = null
            val index = pending.indexOfFirst { it === link }
            if (index < 0) return null // A listener already received it.
            pending.removeAt(index)
            return link
        }
    }

    fun getInitialLink(callback: StrybokInitialLinkCallback) {
        if (stopped) {
            callbackExecutor.execute { callback.onResult(null) }
            return
        }
        scope.launch {
            val link = getInitialLink()
            callbackExecutor.execute { callback.onResult(link) }
        }
    }

    fun resolve(url: String, callback: StrybokResolveCallback) {
        checkNotStopped()
        scope.launch {
            try {
                val link = resolve(url)
                callbackExecutor.execute { if (!stopped) callback.onResolved(link) }
            } catch (error: StrybokException) {
                callbackExecutor.execute { if (!stopped) callback.onError(error) }
            } catch (_: StrybokStoppedException) {
                // Stopped while the request was in flight; nothing to report.
            }
        }
    }

    suspend fun resolve(url: String): ResolvedDeepLink {
        checkNotStopped()
        val parsed = parse(url) ?: throw UnsupportedLinkException("Not a link for this app", url)
        // Of the query only the campaign parameters (and with parameterLinks the payload keys) are
        // sent; the rest and the fragment may hold private data.
        val lookup = parsed.lookup
        val request = StrybokHttpRequest(
            method = "GET",
            url = "${config.apiBaseUrl}/v1/public/resolve?url=${URLEncoder.encode(lookup, "UTF-8")}",
            headers = mapOf("Accept" to "application/json"),
            body = null,
            timeoutMillis = config.timeoutMillis,
        )
        return send(request, url, "The resolve request timed out") { response ->
            when (val status = response.statusCode) {
                200 -> parseResolved(url, response.body)
                404 -> throw LinkNotFoundException(url)
                // Client errors will not improve.
                else -> throw StrybokServerException("Unexpected status $status", url, status)
            }
        }
    }

    fun createLink(
        routePath: String,
        payload: Map<String, Any?>?,
        title: String?,
        description: String?,
        imageUrl: String?,
        callback: StrybokCreateLinkCallback,
    ) {
        checkNotStopped()
        scope.launch {
            try {
                val link = createLink(routePath, payload, title, description, imageUrl)
                callbackExecutor.execute { if (!stopped) callback.onCreated(link) }
            } catch (error: StrybokException) {
                callbackExecutor.execute { if (!stopped) callback.onError(error) }
            } catch (_: StrybokStoppedException) {
                // Stopped while the request was in flight; nothing to report.
            }
        }
    }

    /**
     * See [Strybok.createLink]. Independent of `start`, of incoming links and of
     * `allowedHosts`; its errors go to the caller only, never to `onError`, and never contain
     * the client key or the payload.
     */
    suspend fun createLink(
        routePath: String,
        payload: Map<String, Any?>?,
        title: String?,
        description: String?,
        imageUrl: String?,
    ): CreatedLink {
        checkNotStopped()
        val clientKey = config.clientKey
            ?: throw StrybokUnauthorizedException("No clientKey is configured")
        // A real key is plain ASCII. Anything else can only be refused, and must not reach an
        // HTTP header (or an error message of the HTTP stack complaining about it).
        if (!clientKey.all { it in '!'..'~' }) {
            throw StrybokUnauthorizedException("The configured clientKey is not a valid key")
        }
        if (!routePath.startsWith("/") || routePath.startsWith("//") ||
            routePath.length > MAX_ROUTE_PATH_LENGTH
        ) {
            throw preflightFailure(
                "routePath",
                "routePath must start with a single '/' and be at most $MAX_ROUTE_PATH_LENGTH characters long",
            )
        }
        // Built once: every retry sends exactly the same body.
        val body = try {
            createLinkBody(routePath, payload, title, description, imageUrl)
        } catch (error: NotJsonException) {
            throw preflightFailure("payload", error.message.orEmpty())
        }
        val session = if (config.attestation) attestationSession(clientKey) else null
        // Lazily, here and nowhere else: the first call attests, later ones reuse the token.
        var token = session?.token()
        var response = sendCreateLink(clientKey, token, body)
        if (session != null && token != null && attestationErrorCode(response) == ATTESTATION_INVALID) {
            // The token expired, is used up or was refused: attest again and repeat the same
            // request, once. This does not count against maxRetries.
            val reason = attestationErrorReason(response)
            session.discard(token)
            token = session.token() ?: throw StrybokAttestationFailedException(reason)
            response = sendCreateLink(clientKey, token, body)
            // Refused as well: the next call starts with a new attestation.
            if (attestationErrorCode(response) == ATTESTATION_INVALID) session.discard(token)
        }
        return when (val status = response.statusCode) {
            200, 201 -> parseCreated(response.body)
                ?: throw StrybokServerException("Malformed create-link response", null, status)
            401 -> throw when (attestationErrorCode(response)) {
                ATTESTATION_REQUIRED -> StrybokAttestationRequiredException()
                ATTESTATION_INVALID -> StrybokAttestationFailedException(attestationErrorReason(response))
                else -> StrybokUnauthorizedException(KEY_REFUSED)
            }
            403 -> throw RouteNotAllowedException(routePath)
            429 -> throw StrybokRateLimitedException(retryAfterSeconds(response))
            400, 413, 422 -> throw validationFailure(status, response.body)
            else -> throw StrybokServerException("Unexpected status $status", null, status)
        }
    }

    /** One create-link request with the usual retries; returns the first answer below 500. */
    private suspend fun sendCreateLink(clientKey: String, token: String?, body: String): StrybokHttpResponse {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        headers[CLIENT_KEY_HEADER] = clientKey
        if (token != null) headers[ATTESTATION_HEADER] = token
        val request = StrybokHttpRequest(
            method = "POST",
            url = "${config.apiBaseUrl}/v1/public/links",
            headers = headers,
            body = body,
            timeoutMillis = config.timeoutMillis,
        )
        return send(request, null, "The create-link request timed out") { it }
    }

    private fun attestationSession(clientKey: String): AttestationSession = synchronized(lock) {
        attestation ?: AttestationSession(
            apiBaseUrl = config.apiBaseUrl,
            clientKey = clientKey,
            timeoutMillis = config.timeoutMillis,
            provider = config.integrityTokenProvider,
            scope = scope,
            clockMillis = clockMillis,
            wallClockMillis = wallClockMillis,
            execute = ::execute,
        ).also { attestation = it }
    }

    /** `error.code` of a 401 answer when it is one of the attestation codes; `null` otherwise. */
    private fun attestationErrorCode(response: StrybokHttpResponse): String? {
        if (response.statusCode != 401) return null
        val code = errorObject(response.body)?.opt("code") as? String
        return code.takeIf { it == ATTESTATION_INVALID || it == ATTESTATION_REQUIRED }
    }

    /** `error.details.reason` when it looks like one of the service's reason words; never free text. */
    private fun attestationErrorReason(response: StrybokHttpResponse): String? {
        val reason = (errorObject(response.body)?.opt("details") as? JSONObject)?.opt("reason") as? String
        return reason?.takeIf { it.length in 1..32 && it.all { char -> char in 'a'..'z' || char == '_' } }
    }

    private fun errorObject(body: String): JSONObject? = try {
        JSONObject(body).opt("error") as? JSONObject
    } catch (_: JSONException) {
        null
    }

    /**
     * Sends [request], retrying after a timeout, a transport failure or a 5xx answer: up to
     * `maxRetries` extra attempts, pausing `retryDelayMillis` before the first retry and twice
     * as long before each further one. Every other answer goes to [onAnswer], whose result
     * or exception is final.
     */
    private suspend fun <T> send(
        request: StrybokHttpRequest,
        url: String?,
        timeoutMessage: String,
        onAnswer: (StrybokHttpResponse) -> T,
    ): T {
        var lastError: StrybokException? = null
        for (attempt in 0..config.maxRetries) {
            if (attempt > 0) delay(config.retryDelayMillis * (1L shl (attempt - 1).coerceAtMost(20)))
            checkNotStopped()
            val response = try {
                execute(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: StrybokStoppedException) {
                throw error
            } catch (error: SocketTimeoutException) {
                null
            } catch (error: Exception) {
                lastError = StrybokNetworkException("Network error", url, error)
                continue
            }
            if (response == null) {
                lastError = StrybokTimeoutException(timeoutMessage, url)
                continue
            }
            val status = response.statusCode
            if (status < 500) return onAnswer(response)
            lastError = StrybokServerException("Unexpected status $status", url, status)
        }
        throw lastError!!
    }

    fun addLinkListener(listener: StrybokLinkListener) {
        val subscription = synchronized(lock) {
            if (stopped || listeners.containsKey(listener)) return
            subscribeLocked().also { listeners[listener] = it }
        }
        scope.launch {
            for (delivery in subscription.channel) {
                callbackExecutor.execute {
                    // Removed between resolution and this call: keep the link for the next listener.
                    if (subscription.active) listener.onLink(delivery.link) else redeliver(delivery)
                }
            }
        }
    }

    fun removeLinkListener(listener: StrybokLinkListener) {
        val subscription = synchronized(lock) { listeners.remove(listener) } ?: return
        unsubscribe(subscription)
    }

    fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            pending.clear()
            initialLink = null
            for (subscription in subscribers) {
                subscription.active = false
                // Drop what was not consumed yet, then let Flow collectors complete.
                while (subscription.channel.tryReceive().isSuccess) Unit
                subscription.channel.close()
            }
            subscribers.clear()
            listeners.clear()
            statusListeners.clear()
        }
        scope.cancel()
    }

    // -------------------------------------------------------------------------

    /** Whether [url] is ours and not a duplicate delivery of the previous tap. */
    private fun acceptLocked(url: String?): Boolean {
        if (url == null || parse(url) == null) return false
        normalLinkAccepted = true
        // The OS can hand over the same tap twice, e.g. to onCreate and to onNewIntent.
        val now = clockMillis()
        val duplicate = lastUrl == url && now - lastUrlAt < config.dedupeWindowMillis
        lastUrl = url
        lastUrlAt = now
        return !duplicate
    }

    private suspend fun handle(url: String, isInitial: Boolean) {
        val link = try {
            resolve(url)
        } catch (error: CancellationException) {
            throw error
        } catch (error: StrybokException) {
            val onError = config.onError
            if (onError != null && !stopped) {
                callbackExecutor.execute { if (!stopped) onError.onError(error) }
            }
            return
        } catch (_: StrybokStoppedException) {
            return // Stopped mid-flight; nothing to deliver.
        }
        if (deliver(link, isInitial) && config.reportAppOpens) reportAppOpen(link)
    }

    /** Hands [link] to every current subscriber, or queues it when there is none. */
    private fun deliver(link: ResolvedDeepLink, isInitial: Boolean): Boolean = synchronized(lock) {
        if (stopped) return false // Stopped while the request was in flight.
        if (isInitial) initialLink = link
        if (subscribers.isEmpty()) {
            pending.addLast(link)
        } else {
            val delivery = Delivery(link, ++sequence)
            for (subscription in subscribers) subscription.channel.trySend(delivery)
        }
        true
    }

    private fun subscribeLocked(): Subscription {
        val subscription = Subscription(joinedAt = sequence)
        if (stopped) {
            subscription.active = false
            subscription.channel.close()
            return subscription
        }
        subscribers += subscription
        while (true) {
            val link = pending.removeFirstOrNull() ?: break
            subscription.channel.trySend(Delivery(link, ++sequence))
        }
        return subscription
    }

    private fun unsubscribe(subscription: Subscription) {
        val leftovers = ArrayList<Delivery>()
        synchronized(lock) {
            if (!subscribers.remove(subscription)) return
            subscription.active = false
            subscription.channel.close()
            while (true) leftovers += subscription.channel.tryReceive().getOrNull() ?: break
        }
        // Links this subscriber was handed but never consumed must not be lost.
        for (delivery in leftovers) redeliver(delivery)
    }

    /**
     * Gives a link that a departing subscriber did not consume to the subscribers
     * that have not seen it (those that joined after it was dispatched), or back
     * to the queue when nobody listens.
     */
    private fun redeliver(delivery: Delivery) {
        synchronized(lock) {
            if (stopped || delivery.requeued) return
            if (subscribers.isEmpty()) {
                delivery.requeued = true
                pending.addLast(delivery.link)
                return
            }
            for (subscription in subscribers) {
                if (subscription.joinedAt >= delivery.sequence && delivery.redeliveredTo.add(subscription)) {
                    subscription.channel.trySend(delivery)
                }
            }
        }
    }

    /**
     * Runs one HTTP call on a background thread. Returns `null` when there was no
     * answer within the timeout; the blocked thread is interrupted and abandoned.
     */
    private suspend fun execute(request: StrybokHttpRequest): StrybokHttpResponse? {
        val call: Deferred<StrybokHttpResponse> = scope.async(Dispatchers.IO) {
            runInterruptible { transport.execute(request) }
        }
        try {
            return withTimeoutOrNull(request.timeoutMillis) { call.await() }
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive() // The caller itself was cancelled.
            throw StrybokStoppedException() // stop() cancelled the call.
        } finally {
            call.cancel()
        }
    }

    private fun parseResolved(url: String, body: String, isDeferred: Boolean = false): ResolvedDeepLink {
        try {
            val json = JSONObject(body)
            val linkId = json.opt("linkId") as? String
            val appId = json.opt("appId") as? String
            val routePath = json.opt("routePath") as? String
            if (linkId != null && appId != null && routePath != null &&
                routePath.startsWith("/") && !routePath.startsWith("//")
            ) {
                val payload = (json.opt("payload") as? JSONObject)?.let(::toMap) ?: emptyMap()
                val campaign = parseCampaign(json.opt("campaign"))
                return ResolvedDeepLink(url, linkId, appId, routePath, payload, campaign, isDeferred)
            }
        } catch (_: JSONException) {
            // Fall through to the error below.
        }
        throw StrybokServerException("Malformed resolve response", url, 200)
    }

    /** Keeps the five string fields; anything else, or no object at all, is not an error. */
    private fun parseCampaign(value: Any?): LinkCampaign {
        val json = value as? JSONObject ?: return LinkCampaign.EMPTY
        val campaign = LinkCampaign(
            source = json.opt("source") as? String,
            medium = json.opt("medium") as? String,
            campaign = json.opt("campaign") as? String,
            term = json.opt("term") as? String,
            content = json.opt("content") as? String,
        )
        return if (campaign.isEmpty) LinkCampaign.EMPTY else campaign
    }

    /** What resolve returned, unchanged; `null` when there is nothing to report. */
    private fun campaignJson(campaign: LinkCampaign): JSONObject? {
        if (campaign.isEmpty) return null
        val json = JSONObject()
        campaign.source?.let { json.put("source", it) }
        campaign.medium?.let { json.put("medium", it) }
        campaign.campaign?.let { json.put("campaign", it) }
        campaign.term?.let { json.put("term", it) }
        campaign.content?.let { json.put("content", it) }
        return json
    }

    /** `null` unless the answer has everything a created link needs. */
    private fun parseCreated(body: String): CreatedLink? {
        try {
            val json = JSONObject(body)
            val linkId = json.opt("linkId") as? String
            val slug = json.opt("slug") as? String
            val routePath = json.opt("routePath") as? String
            val url = json.opt("url") as? String
            if (linkId != null && slug != null && routePath != null &&
                url != null && url.startsWith("https://", ignoreCase = true)
            ) {
                return CreatedLink(url = url, slug = slug, routePath = routePath, linkId = linkId)
            }
        } catch (_: JSONException) {
            // Fall through.
        }
        return null
    }

    /** Seconds from a `Retry-After` header; `null` when it is missing or not a number of seconds. */
    private fun retryAfterSeconds(response: StrybokHttpResponse): Long? {
        val value = response.header("Retry-After")?.trim().orEmpty()
        if (value.isEmpty() || !value.all { it in '0'..'9' }) return null
        return value.toLongOrNull()
    }

    private fun preflightFailure(field: String, message: String): StrybokValidationException =
        StrybokValidationException(message, listOf(StrybokValidationIssue(field, message)))

    /** The service's `error.message` and `error.details.issues`, as far as they are present. */
    private fun validationFailure(status: Int, body: String): StrybokValidationException {
        var message: String? = null
        val issues = ArrayList<StrybokValidationIssue>()
        try {
            val error = JSONObject(body).opt("error") as? JSONObject
            message = (error?.opt("message") as? String)?.takeIf { it.isNotBlank() }
            val list = (error?.opt("details") as? JSONObject)?.opt("issues") as? JSONArray
            for (index in 0 until (list?.length() ?: 0)) {
                val issue = list?.opt(index) as? JSONObject ?: continue
                val text = issue.opt("message") as? String ?: continue
                issues += StrybokValidationIssue(issue.opt("path") as? String ?: "", text)
            }
        } catch (_: JSONException) {
            // No readable explanation; the status alone has to do.
        }
        return StrybokValidationException(
            message ?: "The service rejected the request (HTTP $status)",
            Collections.unmodifiableList(issues),
        )
    }

    private fun toMap(json: JSONObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        for (key in json.keys()) map[key] = toPlain(json.opt(key))
        return Collections.unmodifiableMap(map)
    }

    private fun toPlain(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> toMap(value)
        is JSONArray -> Collections.unmodifiableList(List(value.length()) { toPlain(value.opt(it)) })
        else -> value
    }

    /**
     * Best effort and never awaited by delivery. This is a self-reported metric
     * (an app can be modified), separate from server-side fallback visits.
     */
    private fun reportAppOpen(link: ResolvedDeepLink) {
        scope.launch {
            try {
                val body = JSONObject()
                    .put("eventId", UUID.randomUUID().toString())
                    .put("linkId", link.linkId)
                    .put("type", "app_open")
                    .put("platform", platform)
                    .put("sdkVersion", Strybok.SDK_VERSION.take(32))
                    .putOpt("campaign", campaignJson(link.campaign))
                    .toString()
                execute(
                    StrybokHttpRequest(
                        method = "POST",
                        url = "${config.apiBaseUrl}/v1/public/events",
                        headers = mapOf("Content-Type" to "application/json"),
                        body = body,
                        timeoutMillis = config.timeoutMillis,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Analytics must never affect the app.
            }
        }
    }

    private fun checkNotStopped() {
        if (stopped) throw StrybokStoppedException()
    }

    internal companion object {
        const val CLIENT_KEY_HEADER = "X-Strybok-Key"
        const val ATTESTATION_HEADER = "X-Strybok-Attestation"
        const val KEY_REFUSED =
            "The client key was refused: it is wrong or was replaced, or link creation is switched off"
        private const val ATTESTATION_INVALID = "ATTESTATION_INVALID"
        private const val ATTESTATION_REQUIRED = "ATTESTATION_REQUIRED"
        private const val MAX_ROUTE_PATH_LENGTH = 512
        const val DEFERRED_NEEDS_CONTEXT =
            "Deferred links need a Context: create the instance with Strybok(context, config)"
    }
}
