package com.strybok.sdk

import java.security.MessageDigest
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

/**
 * The optional attestation of `createLink` ("Attestation (optional)" in the SDK contract):
 *
 *   challenge -> Play Integrity token bound to it -> session token -> createLink
 *
 * One instance per [StrybokEngine], created by the first `createLink` call that needs it. It
 * never runs on its own and nothing here is written to disk, to logs or into an error.
 *
 * Thread-safe: state is guarded by [lock]; nothing blocking runs while it is held.
 */
internal class AttestationSession(
    private val apiBaseUrl: String,
    private val clientKey: String,
    private val timeoutMillis: Long,
    private val provider: StrybokIntegrityTokenProvider?,
    private val scope: CoroutineScope,
    /** Monotonic clock, milliseconds. */
    private val clockMillis: () -> Long,
    /** Wall clock, milliseconds since the epoch; compared with the service's `expiresAt`. */
    private val wallClockMillis: () -> Long,
    /** One HTTP call; `null` when there was no answer in time. See [StrybokEngine]. */
    private val execute: suspend (StrybokHttpRequest) -> StrybokHttpResponse?,
) {
    private class Cached(val token: String, val useUntilClock: Long, val useUntilWall: Long)

    private val lock = Any()

    // --- guarded by lock -----------------------------------------------------
    private var cached: Cached? = null
    private var inFlight: Deferred<String?>? = null

    /** When the last attempt ended with "cannot attest"; `null` when there was none. */
    private var unavailableAt: Long? = null
    // -------------------------------------------------------------------------

    /**
     * A session token for the `X-Strybok-Attestation` header, or `null` when this device cannot
     * attest right now (the request is then sent without the header).
     *
     * Concurrent callers share one attestation. After a failed attempt the next one is not
     * started for [RETRY_PAUSE_MILLIS], so a device that cannot attest does not pay for it on
     * every share.
     *
     * @throws StrybokUnauthorizedException when the service refuses the client key.
     */
    suspend fun token(): String? {
        val attempt = synchronized(lock) {
            val now = clockMillis()
            cached?.let {
                if (now < it.useUntilClock && wallClockMillis() < it.useUntilWall) return it.token
                cached = null
            }
            unavailableAt?.let { if (now - it < RETRY_PAUSE_MILLIS) return null }
            // In the SDK's own scope: a caller that gives up does not take the others down.
            inFlight ?: scope.async { attestAndRemember() }.also { inFlight = it }
        }
        try {
            return attempt.await()
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive() // The caller itself was cancelled.
            throw StrybokStoppedException() // stop() cancelled the attestation.
        }
    }

    /** Forgets [token] after the service refused it; a newer token is left alone. */
    fun discard(token: String) {
        synchronized(lock) { if (cached?.token == token) cached = null }
    }

    private suspend fun attestAndRemember(): String? {
        val grant = try {
            attest()
        } catch (error: Throwable) {
            synchronized(lock) { inFlight = null }
            throw error
        }
        synchronized(lock) {
            inFlight = null
            unavailableAt = if (grant == null) clockMillis() else null
            if (grant != null) {
                val useUntilWall = grant.expiresAtMillis - EXPIRY_MARGIN_MILLIS
                val lifetime = useUntilWall - wallClockMillis()
                // A device whose clock is far off gets a token it may use for this call only.
                cached = if (lifetime > 0) Cached(grant.token, clockMillis() + lifetime, useUntilWall) else null
            }
        }
        return grant?.token
    }

    private class Grant(val token: String, val expiresAtMillis: Long)

    /** One attempt; `null` means "cannot attest now". Never retried: every attempt spends a challenge. */
    private suspend fun attest(): Grant? {
        // No provider: nothing to ask, so no request is made either.
        val provider = provider ?: return null
        val challenge = post("/v1/public/attest/challenge", null)?.let { stringField(it, "challenge") }
            ?: return null
        val integrityToken = try {
            withTimeoutOrNull(PROOF_TIMEOUT_MILLIS) {
                runInterruptible(Dispatchers.IO) { provider.requestIntegrityToken(requestHashOf(challenge)) }
            }
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive() // stop() or the caller: propagate.
            null // Thrown by the provider itself, e.g. a cancelled Google task.
        } catch (_: Exception) {
            null // No Play services, no Play Store, quota, network, … — all "cannot attest".
        } catch (_: LinkageError) {
            null // The provider needs a library the app does not ship.
        }
        if (integrityToken.isNullOrEmpty()) return null
        val body = JSONObject().put("integrityToken", integrityToken).put("challenge", challenge).toString()
        val answer = post("/v1/public/attest/android", body) ?: return null
        val token = stringField(answer, "token") ?: return null
        val expiresAt = stringField(answer, "expiresAt")?.let(::parseIsoInstantMillis) ?: return null
        // The token goes into an HTTP header; anything else than printable ASCII is not ours.
        if (!token.all { it in '!'..'~' }) return null
        return Grant(token, expiresAt)
    }

    /**
     * One request to an attestation endpoint. Returns the body of a 200 answer and `null` for
     * everything that means "cannot attest now" (no answer, a network failure, any other
     * status).
     *
     * @throws StrybokUnauthorizedException for a 401: the client key is wrong or switched off.
     */
    private suspend fun post(path: String, body: String?): String? {
        val headers = LinkedHashMap<String, String>()
        if (body != null) headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        headers[StrybokEngine.CLIENT_KEY_HEADER] = clientKey
        val request = StrybokHttpRequest("POST", apiBaseUrl + path, headers, body, timeoutMillis)
        val response = try {
            execute(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: StrybokStoppedException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return null
        return when (response.statusCode) {
            200 -> response.body
            401 -> throw StrybokUnauthorizedException(StrybokEngine.KEY_REFUSED)
            else -> null
        }
    }

    private fun stringField(body: String, name: String): String? = try {
        (JSONObject(body).opt(name) as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_FIELD_LENGTH }
    } catch (_: JSONException) {
        null
    }

    internal companion object {
        /** A token is not used during the last minute before the service's `expiresAt`. */
        const val EXPIRY_MARGIN_MILLIS = 60_000L

        /** Pause after an attempt that ended with "cannot attest". */
        const val RETRY_PAUSE_MILLIS = 60_000L

        /** Upper bound for the provider, which talks to Google Play. */
        const val PROOF_TIMEOUT_MILLIS = 30_000L

        private const val MAX_FIELD_LENGTH = 4096

        private const val BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /**
         * `requestHash` of a Play Integrity standard request: unpadded base64url of the SHA-256
         * of the challenge's UTF-8 bytes.
         */
        fun requestHashOf(challenge: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(challenge.toByteArray(Charsets.UTF_8)))

        /** Unpadded base64url. `java.util.Base64` needs API 26 and `android.util.Base64` a device. */
        fun base64Url(bytes: ByteArray): String {
            val out = StringBuilder((bytes.size * 4 + 2) / 3)
            var index = 0
            while (index < bytes.size) {
                val left = bytes.size - index
                val b0 = bytes[index].toInt() and 0xff
                val b1 = if (left > 1) bytes[index + 1].toInt() and 0xff else 0
                val b2 = if (left > 2) bytes[index + 2].toInt() and 0xff else 0
                out.append(BASE64URL[b0 shr 2])
                out.append(BASE64URL[(b0 and 0x03 shl 4) or (b1 shr 4)])
                if (left > 1) out.append(BASE64URL[(b1 and 0x0f shl 2) or (b2 shr 6)])
                if (left > 2) out.append(BASE64URL[b2 and 0x3f])
                index += 3
            }
            return out.toString()
        }

        private val ISO_INSTANT = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z""")

        /** `2026-11-24T10:00:00.000Z` as milliseconds since the epoch; `null` for anything else. */
        fun parseIsoInstantMillis(text: String): Long? {
            val match = ISO_INSTANT.matchEntire(text) ?: return null
            val (year, month, day, hour, minute, second) = match.destructured
            val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            calendar.isLenient = false
            calendar.clear()
            calendar.set(year.toInt(), month.toInt() - 1, day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
            val millis = match.groupValues[7].padEnd(3, '0').take(3).toInt()
            return try {
                calendar.timeInMillis + millis
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
