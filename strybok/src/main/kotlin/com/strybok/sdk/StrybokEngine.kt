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
    /** Monotonic clock in milliseconds, used for duplicate detection only. */
    private val clockMillis: () -> Long,
    private val platform: String,
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
    // -------------------------------------------------------------------------

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

    fun isStrybokUrl(url: String?): Boolean =
        url != null && parseStrybokLink(url, config.allowedHosts) != null

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
        accepted
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
        val parsed = parseStrybokLink(url, config.allowedHosts)
            ?: throw UnsupportedLinkException("Not a link for this app", url)
        // Query and fragment are not needed for the lookup and may hold private data.
        val lookup = "https://${parsed.host}${parsed.path}"
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
        val request = StrybokHttpRequest(
            method = "POST",
            url = "${config.apiBaseUrl}/v1/public/links",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                CLIENT_KEY_HEADER to clientKey,
            ),
            body = body,
            timeoutMillis = config.timeoutMillis,
        )
        return send(request, null, "The create-link request timed out") { response ->
            when (val status = response.statusCode) {
                200, 201 -> parseCreated(response.body)
                    ?: throw StrybokServerException("Malformed create-link response", null, status)
                401 -> throw StrybokUnauthorizedException(
                    "The client key was refused: it is wrong or was replaced, or link creation is switched off",
                )
                403 -> throw RouteNotAllowedException(routePath)
                429 -> throw StrybokRateLimitedException(retryAfterSeconds(response))
                400, 413, 422 -> throw validationFailure(status, response.body)
                else -> throw StrybokServerException("Unexpected status $status", null, status)
            }
        }
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
        }
        scope.cancel()
    }

    // -------------------------------------------------------------------------

    /** Whether [url] is ours and not a duplicate delivery of the previous tap. */
    private fun acceptLocked(url: String?): Boolean {
        if (url == null || parseStrybokLink(url, config.allowedHosts) == null) return false
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

    private fun parseResolved(url: String, body: String): ResolvedDeepLink {
        try {
            val json = JSONObject(body)
            val linkId = json.opt("linkId") as? String
            val appId = json.opt("appId") as? String
            val routePath = json.opt("routePath") as? String
            if (linkId != null && appId != null && routePath != null &&
                routePath.startsWith("/") && !routePath.startsWith("//")
            ) {
                val payload = (json.opt("payload") as? JSONObject)?.let(::toMap) ?: emptyMap()
                return ResolvedDeepLink(url, linkId, appId, routePath, payload)
            }
        } catch (_: JSONException) {
            // Fall through to the error below.
        }
        throw StrybokServerException("Malformed resolve response", url, 200)
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

    private companion object {
        const val CLIENT_KEY_HEADER = "X-Strybok-Key"
        const val MAX_ROUTE_PATH_LENGTH = 512
    }
}
