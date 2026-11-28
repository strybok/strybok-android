package com.strybok.sdk

import java.util.Locale
import java.util.concurrent.Executor

/** Receives a link resolved by the SDK. */
public fun interface StrybokLinkListener {
    public fun onLink(link: ResolvedDeepLink)
}

/** Receives the error of a link of this app that could not be resolved. */
public fun interface StrybokErrorListener {
    public fun onError(error: StrybokException)
}

/** Result callback of [Strybok.getInitialLink] for callers without coroutines. */
public fun interface StrybokInitialLinkCallback {
    /** [link] is `null` when there is no launch link or it was already delivered. */
    public fun onResult(link: ResolvedDeepLink?)
}

/** Result callback of [Strybok.resolve] for callers without coroutines. */
public interface StrybokResolveCallback {
    public fun onResolved(link: ResolvedDeepLink)

    public fun onError(error: StrybokException)
}

/** Result callback of [Strybok.createLink] for callers without coroutines. */
public interface StrybokCreateLinkCallback {
    public fun onCreated(link: CreatedLink)

    public fun onError(error: StrybokException)
}

/**
 * Configuration of [Strybok]. Kotlin callers use named arguments, Java callers
 * the [Builder].
 *
 * @throws IllegalArgumentException when a value is out of range, or the payload keys of
 *   [parameterLinks] are not valid (see [ParameterLinks.payloadKeys]).
 */
public class StrybokConfig(
    /** Base URL of the API, e.g. `https://api.strybok.com`. A path prefix is preserved. */
    apiBaseUrl: String,
    /** Link hostnames of this app. Links on any other host are ignored. */
    allowedHosts: Set<String>,
    /** Time allowed for one attempt of a resolve or create-link request. */
    public val timeoutMillis: Long = 5_000,
    /**
     * Extra attempts after a timeout, network error or 5xx. Resolving is an
     * idempotent GET and creating a link is idempotent too, so retrying is safe.
     * 4xx answers are never retried.
     */
    public val maxRetries: Int = 2,
    /** Pause before the first retry; doubles for each further retry. */
    public val retryDelayMillis: Long = 400,
    /**
     * The identical URL received again within this window is treated as a
     * duplicate delivery of the same tap and dropped.
     */
    public val dedupeWindowMillis: Long = 2_000,
    /** Whether to send a best-effort `app_open` event after a link was resolved. */
    public val reportAppOpens: Boolean = true,
    /**
     * Receives errors for links of this app that could not be resolved. Links that
     * are simply not ours are ignored without an error.
     */
    public val onError: StrybokErrorListener? = null,
    /** HTTP transport; `null` uses [HttpUrlConnectionTransport]. */
    public val transport: StrybokTransport? = null,
    /**
     * Where listeners and callbacks (including [onError]) are invoked; `null` uses
     * the Android main thread. Flow collectors run in their own coroutine context.
     */
    public val callbackExecutor: Executor? = null,
    /**
     * Publishable key of the application (`pk_…`), shown in the dashboard under "Links from
     * the app". Only needed for [Strybok.createLink]. It is safe to ship inside the app: it
     * can only create links for the route patterns the owner listed. Never put a secret API
     * key (`lb_…`) here.
     */
    clientKey: String? = null,
    /**
     * Extra protection for [Strybok.createLink], off by default: prove to the service that the
     * request comes from a genuine build of the app on a genuine device (Google Play
     * Integrity). Needs [clientKey] and an [integrityTokenProvider]. The proof is made lazily
     * inside `createLink`, never when the SDK starts or a link arrives. See
     * `docs/link-creation-security.md` for what it does and does not protect.
     */
    public val attestation: Boolean = false,
    /**
     * Where the Play Integrity token comes from when [attestation] is on; usually a
     * [PlayIntegrityTokenProvider]. `null` — for example in a build for a market without
     * Google Play — means this app cannot attest: links are requested without a proof and the
     * service decides.
     */
    public val integrityTokenProvider: StrybokIntegrityTokenProvider? = null,
    /**
     * Restore a link that was tapped before the app was installed, off by default. On the first
     * launch the SDK asks [installReferrerSources] for the store's install referrer and, when
     * it carries a click id of the service, delivers that link like any other (with
     * [ResolvedDeepLink.isDeferred] set). Needs an instance created with
     * `Strybok(context, config)` and the feature switched on for the application in the
     * dashboard. With `false` nothing is read, sent or stored.
     *
     * Reading the referrer is access to information stored on the device. If your app asks for
     * consent first, leave this `false` and call [Strybok.enableDeferredLinks] afterwards.
     */
    public val deferredLinks: Boolean = false,
    /**
     * The stores to ask when deferred links are on, in this order — for example
     * `listOf(PlayInstallReferrerSource(context))`. Empty means the check ends at once with
     * [DeferredLinkStatus.UNAVAILABLE].
     */
    installReferrerSources: List<InstallReferrerSource> = emptyList(),
    /**
     * Links without an API call, off by default: deliver path links
     * (`https://<host>/product/42?id=7`) and Firebase long links (`https://<host>/?link=…`) too,
     * and send the values of these payload keys with them. Copy `parameterLinks` of the
     * integration guide (`GET /v1/apps/:appId/integration`, shown on the dashboard's setup
     * screen); while it is `null` there, leave this `null`.
     *
     * With it, any address of [allowedHosts] that is a valid route is resolved, so [onError]
     * gets a [LinkNotFoundException] for one that is no link. The route and payload of a
     * delivered link are those the service read, never the tapped path. Without it nothing
     * changes: only single-slug links, and of the query only the `utm_*` values. Deferred links
     * keep the single-slug rule either way.
     */
    public val parameterLinks: ParameterLinks? = null,
) {
    /** A copy of the sources passed in. */
    public val installReferrerSources: List<InstallReferrerSource> =
        java.util.Collections.unmodifiableList(ArrayList(installReferrerSources))

    public val apiBaseUrl: String = apiBaseUrl.trim().trimEnd('/')

    /** Lower-cased copy of the hostnames passed in. */
    public val allowedHosts: Set<String> =
        allowedHosts.mapTo(LinkedHashSet()) { it.trim().lowercase(Locale.ROOT) }

    /** The publishable key passed in, without surrounding whitespace; `null` when none was given. */
    public val clientKey: String? = clientKey?.trim()?.takeIf { it.isNotEmpty() }

    init {
        val lower = this.apiBaseUrl.lowercase(Locale.ROOT)
        require(lower.startsWith("https://") || lower.startsWith("http://")) {
            "apiBaseUrl must be an absolute http(s) URL"
        }
        require('?' !in this.apiBaseUrl && '#' !in this.apiBaseUrl) {
            "apiBaseUrl must not contain a query or fragment"
        }
        require(this.allowedHosts.isNotEmpty() && this.allowedHosts.none { it.isEmpty() }) {
            "allowedHosts must contain at least one hostname"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(maxRetries >= 0) { "maxRetries must not be negative" }
        require(retryDelayMillis >= 0) { "retryDelayMillis must not be negative" }
        require(dedupeWindowMillis >= 0) { "dedupeWindowMillis must not be negative" }
        val problem = parameterLinks?.let { payloadKeysProblem(it.payloadKeys) }
        require(problem == null) { "parameterLinks: $problem" }
    }

    /** Builder for Java callers; every setter is optional. */
    public class Builder(private val apiBaseUrl: String, private val allowedHosts: Set<String>) {
        private var timeoutMillis: Long = 5_000
        private var maxRetries: Int = 2
        private var retryDelayMillis: Long = 400
        private var dedupeWindowMillis: Long = 2_000
        private var reportAppOpens: Boolean = true
        private var onError: StrybokErrorListener? = null
        private var transport: StrybokTransport? = null
        private var callbackExecutor: Executor? = null
        private var clientKey: String? = null
        private var attestation: Boolean = false
        private var integrityTokenProvider: StrybokIntegrityTokenProvider? = null
        private var deferredLinks: Boolean = false
        private var installReferrerSources: List<InstallReferrerSource> = emptyList()
        private var parameterLinks: ParameterLinks? = null

        public fun timeoutMillis(value: Long): Builder = apply { timeoutMillis = value }

        public fun maxRetries(value: Int): Builder = apply { maxRetries = value }

        public fun retryDelayMillis(value: Long): Builder = apply { retryDelayMillis = value }

        public fun dedupeWindowMillis(value: Long): Builder = apply { dedupeWindowMillis = value }

        public fun reportAppOpens(value: Boolean): Builder = apply { reportAppOpens = value }

        public fun onError(value: StrybokErrorListener?): Builder = apply { onError = value }

        public fun transport(value: StrybokTransport?): Builder = apply { transport = value }

        public fun callbackExecutor(value: Executor?): Builder = apply { callbackExecutor = value }

        /** Publishable key (`pk_…`) for [Strybok.createLink]; never a secret API key. */
        public fun clientKey(value: String?): Builder = apply { clientKey = value }

        /** Extra protection for [Strybok.createLink] (Play Integrity); needs [integrityTokenProvider]. */
        public fun attestation(value: Boolean): Builder = apply { attestation = value }

        /** Source of Play Integrity tokens when [attestation] is on, e.g. a [PlayIntegrityTokenProvider]. */
        public fun integrityTokenProvider(value: StrybokIntegrityTokenProvider?): Builder =
            apply { integrityTokenProvider = value }

        /** Restore a link tapped before the app was installed; needs `new Strybok(context, config)`. */
        public fun deferredLinks(value: Boolean): Builder = apply { deferredLinks = value }

        /** The stores to ask for the install referrer, in order, e.g. a [PlayInstallReferrerSource]. */
        public fun installReferrerSources(value: List<InstallReferrerSource>): Builder =
            apply { installReferrerSources = value }

        /** Links without an API call (path links and Firebase long links); see [StrybokConfig.parameterLinks]. */
        public fun parameterLinks(value: ParameterLinks?): Builder = apply { parameterLinks = value }

        public fun build(): StrybokConfig = StrybokConfig(
            apiBaseUrl = apiBaseUrl,
            allowedHosts = allowedHosts,
            timeoutMillis = timeoutMillis,
            maxRetries = maxRetries,
            retryDelayMillis = retryDelayMillis,
            dedupeWindowMillis = dedupeWindowMillis,
            reportAppOpens = reportAppOpens,
            onError = onError,
            transport = transport,
            callbackExecutor = callbackExecutor,
            clientKey = clientKey,
            attestation = attestation,
            integrityTokenProvider = integrityTokenProvider,
            deferredLinks = deferredLinks,
            installReferrerSources = installReferrerSources,
            parameterLinks = parameterLinks,
        )
    }
}
