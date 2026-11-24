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

/**
 * Configuration of [Strybok]. Kotlin callers use named arguments, Java callers
 * the [Builder].
 *
 * @throws IllegalArgumentException when a value is out of range.
 */
public class StrybokConfig(
    /** Base URL of the API, e.g. `https://api.strybok.com`. A path prefix is preserved. */
    apiBaseUrl: String,
    /** Link hostnames of this app. Links on any other host are ignored. */
    allowedHosts: Set<String>,
    /** Time allowed for one resolve attempt. */
    public val timeoutMillis: Long = 5_000,
    /**
     * Extra attempts after a timeout, network error or 5xx. Resolving is an
     * idempotent GET, so retrying is safe. `404` is never retried.
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
) {
    public val apiBaseUrl: String = apiBaseUrl.trim().trimEnd('/')

    /** Lower-cased copy of the hostnames passed in. */
    public val allowedHosts: Set<String> =
        allowedHosts.mapTo(LinkedHashSet()) { it.trim().lowercase(Locale.ROOT) }

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

        public fun timeoutMillis(value: Long): Builder = apply { timeoutMillis = value }

        public fun maxRetries(value: Int): Builder = apply { maxRetries = value }

        public fun retryDelayMillis(value: Long): Builder = apply { retryDelayMillis = value }

        public fun dedupeWindowMillis(value: Long): Builder = apply { dedupeWindowMillis = value }

        public fun reportAppOpens(value: Boolean): Builder = apply { reportAppOpens = value }

        public fun onError(value: StrybokErrorListener?): Builder = apply { onError = value }

        public fun transport(value: StrybokTransport?): Builder = apply { transport = value }

        public fun callbackExecutor(value: Executor?): Builder = apply { callbackExecutor = value }

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
        )
    }
}
