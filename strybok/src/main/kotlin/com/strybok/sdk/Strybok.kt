package com.strybok.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
 * Restoring a link after the app was installed from a store ("deferred links") is optional
 * and off by default: see [StrybokConfig.deferredLinks], which needs the constructor that
 * takes a [Context].
 *
 * All methods are thread-safe.
 */
public class Strybok internal constructor(
    /** The configuration this instance was created with. */
    public val config: StrybokConfig,
    clockMillis: (() -> Long)?,
    wallClockMillis: (() -> Long)? = null,
    deferredPlatform: DeferredLinkPlatform? = null,
    sourceTimeoutMillis: Long = 10_000,
) {
    /**
     * An instance without deferred links.
     *
     * @throws IllegalArgumentException when [StrybokConfig.deferredLinks] is on: that needs
     *   the constructor with a [Context].
     */
    public constructor(config: StrybokConfig) : this(config, null, null)

    /**
     * An instance that can restore a link after installation (see
     * [StrybokConfig.deferredLinks] and [enableDeferredLinks]). The context is used for the
     * app's package name, its install and update times and two small values in the app's
     * private storage, and only once the check runs; nothing is touched here.
     *
     * @param context any context; the application context is kept.
     */
    public constructor(context: Context, config: StrybokConfig) :
        this(config, null, null, AndroidDeferredLinkPlatform(context))

    private val engine = StrybokEngine(
        config = config,
        callbackExecutor = config.callbackExecutor ?: MainThreadExecutor(),
        clockMillis = clockMillis ?: { System.nanoTime() / 1_000_000 },
        platform = PLATFORM,
        wallClockMillis = wallClockMillis ?: System::currentTimeMillis,
        deferredPlatform = deferredPlatform,
        sourceTimeoutMillis = sourceTimeoutMillis,
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
     * With [StrybokConfig.attestation] switched on, the first call (and about one call an hour
     * after that) first proves to the service that it comes from a genuine build of the app,
     * using [StrybokConfig.integrityTokenProvider]. A device that cannot produce the proof
     * still sends the request; the service then decides, and answers with
     * [StrybokAttestationRequiredException] when the owner only accepts attested apps.
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
     *   [StrybokRateLimitedException], [StrybokAttestationRequiredException] /
     *   [StrybokAttestationFailedException] when the owner asks for attestation, and
     *   [StrybokNetworkException] / [StrybokTimeoutException] / [StrybokServerException] after
     *   all retries.
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

    // --- Deferred links ---------------------------------------------------------------

    /**
     * Switches deferred links on after the instance was created — for example once the person
     * agreed on a consent screen — and runs the check once, after `start`. Does nothing when
     * [StrybokConfig.deferredLinks] was already on, when it was called before, or after [stop].
     *
     * The check never delays `start`, the launch link, [getInitialLink] or [createLink], and
     * reports nothing to [StrybokConfig.onError]; watch [deferredLinkStatus] instead.
     *
     * @throws IllegalStateException when the instance was created without a [Context].
     */
    public fun enableDeferredLinks(): Unit = engine.enableDeferredLinks()

    /**
     * Where the deferred-link check stands: readable at any time and observable. For
     * developers and QA; apps do not need it to receive the link. Collectors see the current
     * value first; values that follow each other quickly may be skipped.
     */
    public val deferredLinkStatuses: StateFlow<DeferredLinkStatus> get() = engine.deferredLinkStatus

    /** The current value of [deferredLinkStatuses]. */
    public val deferredLinkStatus: DeferredLinkStatus get() = engine.deferredLinkStatus.value

    /**
     * Callback alternative to [deferredLinkStatuses]. [listener] gets the current status right
     * away and later ones as they come, on the callback executor (the main thread by default).
     */
    public fun addDeferredLinkStatusListener(listener: StrybokDeferredLinkStatusListener): Unit =
        engine.addDeferredLinkStatusListener(listener)

    /** Removes a listener added with [addDeferredLinkStatusListener]. */
    public fun removeDeferredLinkStatusListener(listener: StrybokDeferredLinkStatusListener): Unit =
        engine.removeDeferredLinkStatusListener(listener)

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
        public const val SDK_VERSION: String = "0.4.0"

        internal const val PLATFORM: String = "android"
    }
}
