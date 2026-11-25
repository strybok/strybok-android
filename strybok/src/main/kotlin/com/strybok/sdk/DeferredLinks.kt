package com.strybok.sdk

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Where the deferred-link check of this installation stands; see [Strybok.deferredLinkStatus].
 *
 * A check that finds nothing is the normal case — most people who install an app never tapped a
 * link — so none of these values is an error and none is ever reported to
 * [StrybokConfig.onError]. The status is meant for developers and QA.
 */
public enum class DeferredLinkStatus(
    /** Whether this installation will never run the check again. */
    public val isFinal: Boolean,
) {
    /** [StrybokConfig.deferredLinks] is off; nothing is read, sent or stored. */
    DISABLED(false),

    /** The check is running in this launch (or waits for `start`). */
    CHECKING(false),

    /** This launch could not finish the check (store or network not reachable); a later launch tries again. */
    PENDING(false),

    /** The check was finished in an earlier launch, or the app was updated since it was installed. */
    NOT_FIRST_LAUNCH(true),

    /** No source is configured, or none can ever answer on this device. */
    UNAVAILABLE(true),

    /** The store returned a referrer without a click id of ours: the usual result of an ordinary install. */
    NO_REFERRER(true),

    /** The service refused the id: unknown, older than 24 hours, already used, or not this application's. */
    NOT_FOUND(true),

    /** A normal link of this app arrived first, so the deferred link was dropped. */
    SUPERSEDED(true),

    /** The link was handed to the delivery pipeline. */
    DELIVERED(true),

    /** Three launches could not finish the check, or the answer of the service was unusable. */
    FAILED(true),
}

/** Receives every change of [Strybok.deferredLinkStatus]; for callers without coroutines. */
public fun interface StrybokDeferredLinkStatusListener {
    public fun onDeferredLinkStatus(status: DeferredLinkStatus)
}

/**
 * The answer of an [InstallReferrerSource]: exactly one of a referrer string,
 * [UNAVAILABLE_FOR_NOW] or [UNAVAILABLE].
 */
public class InstallReferrerResult private constructor(
    private val kind: Int,
    /**
     * The raw install referrer exactly as the store returned it (it may be empty); `null` for
     * the two "unavailable" answers.
     */
    public val referrer: String?,
) {
    /** The store could not be asked; a later launch may succeed. */
    public val isUnavailableForNow: Boolean get() = kind == FOR_NOW

    /** This store is not on the device, is too old, or its library is not in the app. */
    public val isUnavailable: Boolean get() = kind == NEVER

    /** Never contains the referrer: it must not end up in a log. */
    override fun toString(): String = when (kind) {
        FOR_NOW -> "InstallReferrerResult(unavailable for now)"
        NEVER -> "InstallReferrerResult(unavailable)"
        else -> "InstallReferrerResult(referrer)"
    }

    public companion object {
        private const val REFERRER = 0
        private const val FOR_NOW = 1
        private const val NEVER = 2

        /** The store could not be asked; a later launch may succeed. */
        @JvmField
        public val UNAVAILABLE_FOR_NOW: InstallReferrerResult = InstallReferrerResult(FOR_NOW, null)

        /** This store is not on the device, is too old, or its library is not in the app. */
        @JvmField
        public val UNAVAILABLE: InstallReferrerResult = InstallReferrerResult(NEVER, null)

        /** The raw install referrer of the store the app was installed from, unchanged. */
        @JvmStatic
        public fun referrer(value: String): InstallReferrerResult = InstallReferrerResult(REFERRER, value)
    }
}

/**
 * Asks one app store for the install referrer of this app. Shipped implementations:
 * [PlayInstallReferrerSource], [GalaxyStoreInstallReferrerSource] and
 * [AppGalleryInstallReferrerSource]; write your own for another store or for tests.
 *
 * [getInstallReferrer] is a blocking call. The SDK invokes it on a background thread, never on
 * the main thread and never while the SDK starts. An implementation
 *
 * - must not throw (the SDK treats an exception as [InstallReferrerResult.UNAVAILABLE_FOR_NOW]);
 * - must answer within 10 seconds: after that the SDK interrupts the thread, abandons the call
 *   and treats it as "unavailable for now";
 * - must release whatever connection it opened, also when it is interrupted.
 */
public fun interface InstallReferrerSource {
    public fun getInstallReferrer(): InstallReferrerResult
}

// ---------------------------------------------------------------------------------------------

/** The key our value has inside a store's install referrer string. */
private const val REFERRER_KEY = "strybok_click"
private val CLICK_ID = Regex("^dc_[0-9A-Za-z]{32}$")

/**
 * The click id inside a store's install referrer (SDK contract, "Finding the click id in a
 * referrer"), or `null`. Everything else in the string is ignored: not parsed further, not
 * stored, not sent, not logged. Never throws.
 */
internal fun findClickId(referrer: String): String? {
    val direct = clickIdPart(referrer)
    if (direct != null) return direct.takeIf(CLICK_ID::matches)
    // A store may hand the value back still percent-encoded: decode the whole string, once.
    if (!referrer.contains("$REFERRER_KEY%3D", ignoreCase = true)) return null
    return clickIdPart(percentDecode(referrer))?.takeIf(CLICK_ID::matches)
}

/** The value of the first `strybok_click` pair, or `null` when no part has exactly that key. */
private fun clickIdPart(referrer: String): String? {
    for (part in referrer.split('&')) {
        val equals = part.indexOf('=')
        val key = if (equals < 0) part else part.substring(0, equals)
        if (key == REFERRER_KEY) return if (equals < 0) "" else part.substring(equals + 1)
    }
    return null
}

/** Decodes `%XX` escapes as UTF-8. A malformed escape is kept as it is; `+` is not a space. */
private fun percentDecode(value: String): String {
    val bytes = java.io.ByteArrayOutputStream(value.length)
    val raw = value.toByteArray(Charsets.UTF_8)
    var index = 0
    while (index < raw.size) {
        val byte = raw[index]
        if (byte == '%'.code.toByte() && index + 2 < raw.size) {
            val high = Character.digit(raw[index + 1].toInt().toChar(), 16)
            val low = Character.digit(raw[index + 2].toInt().toChar(), 16)
            if (high >= 0 && low >= 0) {
                bytes.write(high * 16 + low)
                index += 3
                continue
            }
        }
        bytes.write(byte.toInt())
        index += 1
    }
    return bytes.toString("UTF-8")
}

/**
 * What the check needs from the device: the two stored values, the package name and whether the
 * app was updated. An interface so the whole flow runs in JVM tests.
 *
 * The write methods return `false` when the value could not be stored; the check then stops
 * rather than risk repeating itself or delivering twice.
 */
internal interface DeferredLinkPlatform {
    val packageName: String

    /** `PackageInfo.firstInstallTime != lastUpdateTime`. */
    fun wasUpdatedSinceInstall(): Boolean

    fun attempts(): Int

    fun storeAttempts(value: Int): Boolean

    fun isDone(): Boolean

    fun storeDone(): Boolean

    /** `true` for the first caller in this process, `false` for every later one. */
    fun claimProcess(): Boolean
}

/** How the engine answered when it was offered the deferred link. */
internal enum class DeferredDelivery { DELIVERED, SUPERSEDED, STOPPED }

/**
 * The deferred-link check of the SDK contract ("Deferred links (optional, Android)"): asks the
 * install referrer sources for a click id, exchanges it for the link and hands the link to the
 * normal delivery pipeline — at most once per installation.
 *
 * Nothing here reports an error, throws to the app or logs the referrer or the click id.
 */
internal class DeferredLinkChecker(
    private val config: StrybokConfig,
    private val platform: DeferredLinkPlatform,
    private val scope: CoroutineScope,
    /** Sends the redeem request with the usual retries; `null` when no answer below 500 came. */
    private val redeem: suspend (body: String) -> StrybokHttpResponse?,
    /** Whether a link of this app was accepted since the process started. */
    private val normalLinkAccepted: () -> Boolean,
    /** Delivers unless a normal link was accepted or the SDK was stopped; atomic with both. */
    private val deliver: (ResolvedDeepLink) -> DeferredDelivery,
    private val parse: (url: String, body: String) -> ResolvedDeepLink?,
    private val sourceTimeoutMillis: Long,
    private val setStatus: (DeferredLinkStatus) -> Unit,
) {
    /** Runs the whole flow once. Never throws, except for cancellation. */
    suspend fun run() {
        val result = try {
            check()
        } catch (error: CancellationException) {
            throw error
        } catch (_: StrybokStoppedException) {
            return // Stopped mid-flight; `done` is only stored when the answer had arrived.
        } catch (_: Exception) {
            // Storage that throws, for example. Nothing was delivered; a later launch tries again.
            DeferredLinkStatus.PENDING
        }
        if (result != null) setStatus(result)
    }

    /** The status the check ended with; `null` when the SDK was stopped before it could end. */
    private suspend fun check(): DeferredLinkStatus? {
        if (!platform.claimProcess()) {
            // Another instance in this process runs (or ran) the check; one check per process.
            return if (platform.isDone()) DeferredLinkStatus.NOT_FIRST_LAUNCH else DeferredLinkStatus.PENDING
        }
        if (platform.isDone()) return DeferredLinkStatus.NOT_FIRST_LAUNCH
        if (platform.wasUpdatedSinceInstall()) {
            // An update to a version with this feature must not ask about an old referrer.
            platform.storeDone()
            return DeferredLinkStatus.NOT_FIRST_LAUNCH
        }
        val attempts = platform.attempts()
        if (attempts >= MAX_ATTEMPTS) {
            platform.storeDone()
            return DeferredLinkStatus.FAILED
        }
        // Before anything else happens: a launch that dies from here on has used an attempt.
        if (!platform.storeAttempts(attempts + 1)) return DeferredLinkStatus.PENDING

        var clickId: String? = null
        var unavailableForNow = false
        var gotReferrer = false
        for (source in config.installReferrerSources) {
            val answer = ask(source)
            val referrer = answer.referrer
            if (referrer != null) {
                gotReferrer = true
                clickId = findClickId(referrer)
                if (clickId != null) break
            } else if (!answer.isUnavailable) {
                unavailableForNow = true
            }
        }
        if (clickId == null) {
            if (unavailableForNow) return DeferredLinkStatus.PENDING
            platform.storeDone()
            return if (gotReferrer) DeferredLinkStatus.NO_REFERRER else DeferredLinkStatus.UNAVAILABLE
        }

        if (normalLinkAccepted()) {
            platform.storeDone()
            return DeferredLinkStatus.SUPERSEDED // One launch, one navigation; no request.
        }
        val body = JSONObject()
            .put("platform", "android")
            .put("clickId", clickId)
            .put("packageName", platform.packageName)
            .put("sdkVersion", Strybok.SDK_VERSION.take(32))
            .toString()
        val response = redeem(body) ?: return DeferredLinkStatus.PENDING
        return when (val code = response.statusCode) {
            200 -> {
                val link = deferredLinkOf(response.body)
                if (link == null) {
                    platform.storeDone()
                    return DeferredLinkStatus.FAILED
                }
                // Before delivery: a crash after the app received the link cannot deliver it again.
                if (!platform.storeDone()) return DeferredLinkStatus.PENDING
                when (deliver(link)) {
                    DeferredDelivery.DELIVERED -> DeferredLinkStatus.DELIVERED
                    DeferredDelivery.SUPERSEDED -> DeferredLinkStatus.SUPERSEDED
                    DeferredDelivery.STOPPED -> null
                }
            }
            429 -> DeferredLinkStatus.PENDING // Not retried in this launch.
            else -> if (code in 400..499) {
                platform.storeDone()
                DeferredLinkStatus.NOT_FOUND
            } else {
                DeferredLinkStatus.PENDING // Not an answer of the service at all.
            }
        }
    }

    /** A usable answer: `deferred` is `true`, `url` is a link of this app, the rest as for resolving. */
    private fun deferredLinkOf(body: String): ResolvedDeepLink? {
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }
        if (json.opt("deferred") != true) return null
        val url = json.opt("url") as? String ?: return null
        if (parseStrybokLink(url, config.allowedHosts) == null) return null
        return parse(url, body)
    }

    /**
     * One source, on a background thread, for at most [sourceTimeoutMillis]. A source that takes
     * longer is interrupted and abandoned; one that throws counts as "unavailable for now".
     */
    private suspend fun ask(source: InstallReferrerSource): InstallReferrerResult {
        val call = scope.async(Dispatchers.IO) { runInterruptible { source.getInstallReferrer() } }
        return try {
            withTimeoutOrNull(sourceTimeoutMillis) { call.await() } ?: InstallReferrerResult.UNAVAILABLE_FOR_NOW
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive() // We were cancelled: propagate.
            if (error is CancellationException) throw StrybokStoppedException() // stop() cancelled the call.
            InstallReferrerResult.UNAVAILABLE_FOR_NOW
        } finally {
            call.cancel()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
