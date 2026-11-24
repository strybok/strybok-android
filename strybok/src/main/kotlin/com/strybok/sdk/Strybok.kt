package com.strybok.sdk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.Flow

/**
 * Entry point of the SDK.
 *
 * Android has no global stream of incoming URLs: the system hands links to an
 * Activity as intents, and the Activity forwards them here.
 *
 * ```kotlin
 * // Application scope: one instance for the whole process.
 * val strybok = Strybok(
 *     StrybokConfig(
 *         apiBaseUrl = "https://api.strybok.com",
 *         allowedHosts = setOf("my-app.strybok.link"),
 *     ),
 * )
 *
 * // Activity
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     strybok.start(intent, savedInstanceState)
 *     lifecycleScope.launch {
 *         repeatOnLifecycle(Lifecycle.State.STARTED) {
 *             strybok.links.collect { link -> navigate(link.routePath) }
 *         }
 *     }
 * }
 *
 * override fun onNewIntent(intent: Intent) {
 *     super.onNewIntent(intent)
 *     strybok.handleIntent(intent)
 * }
 * ```
 *
 * [createLink] makes a link to share; it needs [StrybokConfig.clientKey] and is independent
 * of everything above.
 *
 * All methods are thread-safe. Deferred deep linking (restoring a link after the
 * app was installed from a store) is not supported.
 */
public class Strybok internal constructor(
    /** The configuration this instance was created with. */
    public val config: StrybokConfig,
    clockMillis: (() -> Long)?,
) {
    public constructor(config: StrybokConfig) : this(config, null)

    private val engine = StrybokEngine(
        config = config,
        callbackExecutor = config.callbackExecutor ?: MainThreadExecutor(),
        clockMillis = clockMillis ?: { System.nanoTime() / 1_000_000 },
        platform = PLATFORM,
    )

    /**
     * Resolved links, each delivered exactly once.
     *
     * Links that are resolved while nothing collects this flow and no listener is
     * attached (for example the one that cold-started the app, or one that arrives
     * while the UI is stopped) are kept and delivered to the next collector or
     * listener. With several collectors/listeners attached at the same time each
     * of them sees every link once. The flow completes when [stop] is called.
     */
    public val links: Flow<ResolvedDeepLink> get() = engine.links

    // --- Intent glue -----------------------------------------------------------

    /**
     * Call from `Activity.onCreate` with the activity's intent and its
     * `savedInstanceState`.
     *
     * When [savedInstanceState] is not `null` the activity is being re-created
     * (configuration change, or restore after process death) with the intent it
     * was originally launched with; that link was handled before and is ignored.
     * Intents re-delivered from the recents screen are ignored as well.
     *
     * @return `true` when the intent carries a link of this app that is now being resolved.
     */
    public fun start(intent: Intent?, savedInstanceState: Bundle?): Boolean =
        startWithUrl(if (savedInstanceState == null) linkUrlOf(intent) else null)

    /**
     * Like [start] with a `null` saved state. Use it only where the intent is
     * known to be fresh; in `Activity.onCreate` prefer the two-argument overload,
     * otherwise a re-created activity handles its launch link again.
     */
    public fun start(intent: Intent?): Boolean = startWithUrl(linkUrlOf(intent))

    /**
     * Call from `Activity.onNewIntent` (and from `onCreate` of further activities
     * that receive links). Ignores intents that are not `ACTION_VIEW`, that come
     * from the recents screen, or whose URL is not a link of this app.
     *
     * @return `true` when the intent carries a link of this app that is now being resolved.
     */
    public fun handleIntent(intent: Intent?): Boolean = handleUrl(linkUrlOf(intent))

    // --- Plain API (no Android types; usable in JVM tests) -----------------------

    /**
     * Starts the SDK with the URL that launched the app, or `null` when there is
     * none. Returns immediately; resolution happens in the background, and
     * [getInitialLink] / [awaitStarted] wait for it.
     *
     * Calling it again is harmless: the first call defines the launch link, any
     * later call is treated like [handleUrl]. After [stop] it does nothing.
     *
     * @return `true` when [launchUrl] is a link of this app that is now being resolved.
     */
    public fun startWithUrl(launchUrl: String?): Boolean = engine.start(launchUrl)

    /**
     * Handles a URL received while the app is running. URLs that are not links of
     * this app are ignored silently, and so is the identical URL received again
     * within [StrybokConfig.dedupeWindowMillis]. After [stop] it does nothing.
     *
     * @return `true` when [url] is a link of this app that is now being resolved.
     */
    public fun handleUrl(url: String?): Boolean = engine.handleUrl(url)

    /** Suspends until the launch link passed to `start`, if any, has been resolved or has failed. */
    public suspend fun awaitStarted(): Unit = engine.awaitStarted()

    /**
     * The link that launched the app, if it has not been delivered through [links]
     * or a listener yet. Returns it at most once and removes it from the queue, so
     * using both APIs never navigates twice.
     *
     * Suspends until the launch link has been resolved or has failed. Returns
     * `null` when `start` has not been called, when there was no launch link, when
     * it failed (see [StrybokConfig.onError]) or after [stop].
     */
    public suspend fun getInitialLink(): ResolvedDeepLink? = engine.getInitialLink()

    /** Callback variant of [getInitialLink]; [callback] runs on the callback executor. */
    public fun getInitialLink(callback: StrybokInitialLinkCallback): Unit =
        engine.getInitialLink(callback)

    /** Whether [url] is a link of this app: `https`, an allowed host, default port, no credentials, a single slug. */
    public fun isStrybokUrl(url: String?): Boolean = engine.isStrybokUrl(url)

    /**
     * Resolves [url] through the API without touching [links] and without
     * reporting an app open (QR scanners, tests).
     *
     * @throws StrybokException [UnsupportedLinkException] before any network call
     *   when the URL is not ours, [LinkNotFoundException] for unknown, disabled or
     *   expired links, and network/timeout/server errors otherwise.
     * @throws IllegalStateException after [stop].
     */
    public suspend fun resolve(url: String): ResolvedDeepLink = engine.resolve(url)

    /** [resolve] for an `android.net.Uri`. */
    public suspend fun resolve(uri: Uri): ResolvedDeepLink = engine.resolve(uri.toString())

    /**
     * Callback variant of [resolve]; [callback] runs on the callback executor and
     * is not called when the instance is stopped while the request is in flight.
     *
     * @throws IllegalStateException after [stop].
     */
    public fun resolve(url: String, callback: StrybokResolveCallback): Unit =
        engine.resolve(url, callback)

    // --- Creating links -----------------------------------------------------------

    /**
     * Asks the service for a short link to a screen of this app — the "Share" button.
     *
     * Needs [StrybokConfig.clientKey] and the "Links from the app" feature enabled for the
     * application in the dashboard, with a route pattern that covers [routePath]. It runs
     * only when you call it, is independent of `start` and of incoming links, and does not
     * use `allowedHosts`.
     *
     * Creating is idempotent: the same content always returns the same active link, so
     * timeouts, network errors and 5xx answers are retried like for [resolve], and repeated
     * taps on "Share" do not pile up links. Errors are thrown to the caller and never reach
     * [StrybokConfig.onError].
     *
     * Do not put personal data into [routePath] or [payload]: whoever receives the link can
     * read them.
     *
     * @param routePath in-app route the link opens; starts with a single `/`, at most 512 characters.
     * @param payload optional JSON object attached to the link. Values may be `String`,
     *   `Boolean`, finite numbers, `null`, `List`/arrays and `Map`s with `String` keys.
     * @param title optional title of the link preview.
     * @param description optional description of the link preview.
     * @param imageUrl optional public HTTPS URL of the link preview image.
     * @throws StrybokException [StrybokUnauthorizedException] when no client key is configured
     *   (before any network call) or the service refuses it, [StrybokValidationException] when
     *   the request is rejected by the SDK or the service, [RouteNotAllowedException],
     *   [StrybokRateLimitedException], and [StrybokNetworkException] /
     *   [StrybokTimeoutException] / [StrybokServerException] after all retries.
     * @throws IllegalStateException after [stop].
     */
    public suspend fun createLink(
        routePath: String,
        payload: Map<String, Any?>? = null,
        title: String? = null,
        description: String? = null,
        imageUrl: String? = null,
    ): CreatedLink = engine.createLink(routePath, payload, title, description, imageUrl)

    /**
     * Callback variant of [createLink] for callers without coroutines; pass `null` for the
     * optional values you do not need. [callback] runs on the callback executor and is not
     * called when the instance is stopped while the request is in flight.
     *
     * @throws IllegalStateException after [stop].
     */
    public fun createLink(
        routePath: String,
        payload: Map<String, Any?>?,
        title: String?,
        description: String?,
        imageUrl: String?,
        callback: StrybokCreateLinkCallback,
    ): Unit = engine.createLink(routePath, payload, title, description, imageUrl, callback)

    /** Callback variant of [createLink] with only a route. */
    public fun createLink(routePath: String, callback: StrybokCreateLinkCallback): Unit =
        engine.createLink(routePath, null, null, null, null, callback)

    // --- Listeners and lifecycle ---------------------------------------------------

    /**
     * Adds a listener for resolved links; an alternative to collecting [links].
     * Queued links are delivered to it right away. The listener runs on the
     * callback executor (the main thread by default). Adding the same listener
     * twice has no effect.
     */
    public fun addLinkListener(listener: StrybokLinkListener): Unit = engine.addLinkListener(listener)

    /** Removes a listener added with [addLinkListener]. Links it has not consumed stay queued. */
    public fun removeLinkListener(listener: StrybokLinkListener): Unit =
        engine.removeLinkListener(listener)

    /**
     * Stops the SDK and releases its resources; the instance cannot be reused.
     * Nothing is delivered afterwards, including a link that was still resolving.
     * Safe to call more than once.
     */
    public fun stop(): Unit = engine.stop()

    /** Whether [stop] has been called. */
    public val isStopped: Boolean get() = engine.isStopped

    internal val subscriberCount: Int get() = engine.subscriberCount

    private fun linkUrlOf(intent: Intent?): String? = intent?.let {
        linkUrlFromIntentParts(
            action = it.action,
            dataString = it.dataString,
            flags = it.flags,
            viewAction = Intent.ACTION_VIEW,
            launchedFromHistoryFlag = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
        )
    }

    /** Posts to the main thread; created lazily so JVM tests with their own executor never touch Looper. */
    private class MainThreadExecutor : Executor {
        private val handler by lazy { Handler(Looper.getMainLooper()) }

        override fun execute(command: Runnable) {
            handler.post(command)
        }
    }

    public companion object {
        /** Version reported with analytics events. */
        public const val SDK_VERSION: String = "0.2.0"

        internal const val PLATFORM: String = "android"
    }
}
