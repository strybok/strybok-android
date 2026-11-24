package com.strybok.sdk

import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ATTESTATION_HEADER = "X-Strybok-Attestation"
private const val PAYLOAD_SECRET = "payload-secret-value"

/** 2026-11-24T10:00:00Z. */
private val T0: Long = Instant.parse("2026-11-24T10:00:00Z").toEpochMilli()
private const val HOUR = 3_600_000L

private fun error(status: Int, code: String, details: String = "{}"): StrybokHttpResponse =
    StrybokHttpResponse(status, """{"error":{"code":"$code","message":"Refused","details":$details}}""")

/** The service's attestation endpoints and `POST /v1/public/links`, as far as the SDK can tell. */
private class AttestApi(private val wallClock: () -> Long) : StrybokTransport {
    val all = CopyOnWriteArrayList<StrybokHttpRequest>()
    val challenges = CopyOnWriteArrayList<StrybokHttpRequest>()
    val exchanges = CopyOnWriteArrayList<StrybokHttpRequest>()
    val creates = CopyOnWriteArrayList<StrybokHttpRequest>()
    val resolves = CopyOnWriteArrayList<StrybokHttpRequest>()

    /** Challenges are 43 base64url characters, like the real ones. */
    fun challengeNo(n: Int): String = "challenge-$n".padEnd(43, 'A')

    fun tokenNo(n: Int): String = "sat1.session-token-$n.signature"

    fun grant(n: Int, expiresAt: Long = wallClock() + HOUR): StrybokHttpResponse =
        StrybokHttpResponse(200, """{"token":"${tokenNo(n)}","expiresAt":"${Instant.ofEpochMilli(expiresAt)}"}""")

    @Volatile
    var onChallenge: (Int) -> StrybokHttpResponse = { n ->
        StrybokHttpResponse(200, """{"challenge":"${challengeNo(n)}","expiresAt":"2026-11-24T10:05:00.000Z"}""")
    }

    @Volatile
    var onExchange: (Int) -> StrybokHttpResponse = { n -> grant(n) }

    @Volatile
    var onCreate: (StrybokHttpRequest) -> StrybokHttpResponse = { created() }

    override fun execute(request: StrybokHttpRequest): StrybokHttpResponse {
        all += request
        return when (request.path) {
            "/v1/public/attest/challenge" -> {
                challenges += request
                onChallenge(challenges.size)
            }
            "/v1/public/attest/android" -> {
                exchanges += request
                onExchange(exchanges.size)
            }
            "/v1/public/links" -> {
                creates += request
                onCreate(request)
            }
            "/v1/public/events" -> StrybokHttpResponse(202, "")
            else -> {
                resolves += request
                ok()
            }
        }
    }
}

private val StrybokHttpRequest.attestation: String? get() = headers[ATTESTATION_HEADER]

/** Counts requests and answers with a token that looks like Google's. */
private class FakeIntegrity(
    @Volatile var answer: (String) -> String? = { hash -> "integrity.token-for.$hash".padEnd(120, 'x') },
) : StrybokIntegrityTokenProvider {
    val hashes = CopyOnWriteArrayList<String>()

    override fun requestIntegrityToken(requestHash: String): String? {
        hashes += requestHash
        return answer(requestHash)
    }
}

/**
 * The optional attestation of `createLink`, row by row of "Attestation (optional)" in the SDK
 * contract (docs/sdk-contract.md). Google Play is replaced by [FakeIntegrity] and
 * the service by [AttestApi]: no real Play Integrity request is made here.
 */
class AttestationTest {
    private val instances = ArrayList<Strybok>()
    private val clock = AtomicLong(1_000)
    private val wall = AtomicLong(T0)
    private val api = AttestApi { wall.get() }
    private val integrity = FakeIntegrity()

    @After
    fun tearDown() {
        instances.forEach { it.stop() }
    }

    /** Moves both clocks, like time passing on a device. */
    private fun advance(millis: Long) {
        clock.addAndGet(millis)
        wall.addAndGet(millis)
    }

    private fun sdk(
        attestation: Boolean = true,
        provider: StrybokIntegrityTokenProvider? = integrity,
        maxRetries: Int = 2,
        timeoutMillis: Long = 3_000,
        errors: MutableList<StrybokException>? = null,
        api: StrybokTransport = this.api,
    ): Strybok = Strybok(
        StrybokConfig(
            apiBaseUrl = API,
            allowedHosts = setOf(HOST),
            timeoutMillis = timeoutMillis,
            maxRetries = maxRetries,
            retryDelayMillis = 1,
            onError = errors?.let { list -> StrybokErrorListener { list += it } },
            transport = api,
            callbackExecutor = directExecutor,
            clientKey = CLIENT_KEY,
            attestation = attestation,
            integrityTokenProvider = provider,
        ),
        clock::get,
        wall::get,
    ).also { instances += it }

    private fun expectedHash(challenge: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(challenge.toByteArray(Charsets.UTF_8)))

    // --- off by default ------------------------------------------------------------

    @Test
    fun `attestation is off by default and then nothing is attested or sent`() = runBlocking<Unit> {
        val config = StrybokConfig(API, setOf(HOST), transport = api, clientKey = CLIENT_KEY)
        assertFalse(config.attestation)
        assertNull(config.integrityTokenProvider)
        assertFalse(StrybokConfig.Builder(API, setOf(HOST)).build().attestation)

        // A provider alone does not switch it on.
        val strybok = sdk(attestation = false)
        strybok.createLink("/product/42")
        strybok.createLink("/product/43")

        assertEquals(2, api.creates.size)
        assertTrue(api.creates.none { ATTESTATION_HEADER in it.headers })
        assertTrue(api.challenges.isEmpty() && api.exchanges.isEmpty())
        assertTrue(integrity.hashes.isEmpty())
    }

    @Test
    fun `nothing is attested by start, incoming links or resolve`() = runBlocking<Unit> {
        val strybok = sdk()
        strybok.startWithUrl(INVITE)
        strybok.awaitStarted()
        assertEquals("/invite/abc123", strybok.links.first().routePath)
        strybok.handleUrl("https://$HOST/other")
        strybok.resolve(INVITE)
        settle()

        assertTrue(api.challenges.isEmpty() && api.exchanges.isEmpty() && api.creates.isEmpty())
        assertTrue(integrity.hashes.isEmpty())
    }

    // --- flow ----------------------------------------------------------------------

    @Test
    fun `the first createLink asks for a challenge, a proof and a token, then sends the token`() = runBlocking<Unit> {
        val link = sdk().createLink("/product/42", mapOf("productId" to 42))
        assertEquals("https://$HOST/Ab3dE9x", link.url)

        assertEquals(
            listOf("/v1/public/attest/challenge", "/v1/public/attest/android", "/v1/public/links"),
            api.all.map { it.path },
        )
        val challenge = api.challenges.single()
        assertEquals("POST", challenge.method)
        assertNull(challenge.body)
        assertEquals(mapOf("Accept" to "application/json", "X-Strybok-Key" to CLIENT_KEY), challenge.headers)
        assertEquals(3_000, challenge.timeoutMillis)

        // requestHash: unpadded base64url of SHA-256(challenge as UTF-8).
        assertEquals(listOf(expectedHash(api.challengeNo(1))), integrity.hashes)
        assertEquals(43, integrity.hashes.single().length)

        val exchange = api.exchanges.single()
        assertEquals("POST", exchange.method)
        assertEquals(
            mapOf("Content-Type" to "application/json", "Accept" to "application/json", "X-Strybok-Key" to CLIENT_KEY),
            exchange.headers,
        )
        assertEquals(setOf("integrityToken", "challenge"), exchange.json.keys().asSequence().toSet())
        assertEquals(api.challengeNo(1), exchange.json.getString("challenge"))
        assertEquals(integrity.answer(integrity.hashes.single()), exchange.json.getString("integrityToken"))

        val create = api.creates.single()
        assertEquals(api.tokenNo(1), create.attestation)
        assertEquals(CLIENT_KEY, create.headers["X-Strybok-Key"])
        assertEquals("""{"routePath":"/product/42","payload":{"productId":42}}""", create.body)
    }

    @Test
    fun `requestHash matches the example of the contract`() {
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", AttestationSession.requestHashOf("abc"))
        assertEquals("", AttestationSession.base64Url(ByteArray(0)))
        for (size in 1..40) {
            val bytes = ByteArray(size) { (it * 37 + size).toByte() }
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), AttestationSession.base64Url(bytes))
        }
    }

    @Test
    fun `expiresAt is read with and without fractional seconds`() {
        assertEquals(T0, AttestationSession.parseIsoInstantMillis("2026-11-24T10:00:00Z"))
        assertEquals(T0 + 123, AttestationSession.parseIsoInstantMillis("2026-11-24T10:00:00.123Z"))
        assertEquals(T0 + 120, AttestationSession.parseIsoInstantMillis("2026-11-24T10:00:00.12Z"))
        assertEquals(T0 + 123, AttestationSession.parseIsoInstantMillis("2026-11-24T10:00:00.123456Z"))
        for (bad in listOf("", "soon", "2026-11-24", "2026-11-24T10:00:00", "2026-13-24T10:00:00Z", "2026-11-24T10:00:00+01:00")) {
            assertNull(bad, AttestationSession.parseIsoInstantMillis(bad))
        }
    }

    // --- token cache ---------------------------------------------------------------

    @Test
    fun `later calls reuse the token without attesting again`() = runBlocking<Unit> {
        val strybok = sdk()
        repeat(3) { strybok.createLink("/product/$it") }
        advance(30 * 60_000)
        strybok.createLink("/product/later")

        assertEquals(1, api.challenges.size)
        assertEquals(1, api.exchanges.size)
        assertEquals(1, integrity.hashes.size)
        assertEquals(List(4) { api.tokenNo(1) }, api.creates.map { it.attestation })
    }

    @Test
    fun `the token is used until 60 seconds before it expires, then a new one is made`() = runBlocking<Unit> {
        val strybok = sdk()
        strybok.createLink("/product/1")

        advance(HOUR - 60_000 - 1)
        strybok.createLink("/product/2")
        assertEquals(1, api.exchanges.size)

        advance(1) // Exactly 60 seconds before expiresAt.
        strybok.createLink("/product/3")
        assertEquals(2, api.challenges.size)
        assertEquals(2, api.exchanges.size)
        assertEquals(listOf(api.tokenNo(1), api.tokenNo(1), api.tokenNo(2)), api.creates.map { it.attestation })
        assertEquals(expectedHash(api.challengeNo(2)), integrity.hashes.last())
    }

    @Test
    fun `a wall clock that jumps does not keep an expired token alive`() = runBlocking<Unit> {
        val strybok = sdk()
        strybok.createLink("/product/1")

        wall.addAndGet(-5 * HOUR) // Set back by the user: the monotonic clock still counts.
        clock.addAndGet(HOUR)
        strybok.createLink("/product/2")
        assertEquals(2, api.exchanges.size)

        wall.addAndGet(10 * HOUR) // Set forward: past the service's expiresAt.
        strybok.createLink("/product/3")
        assertEquals(3, api.exchanges.size)
    }

    @Test
    fun `a token that looks expired to this device is used for the call it was made for only`() = runBlocking<Unit> {
        api.onExchange = { n -> api.grant(n, expiresAt = wall.get() - HOUR) } // Device clock far ahead.
        val strybok = sdk()
        strybok.createLink("/product/1")
        strybok.createLink("/product/2")

        assertEquals(listOf(api.tokenNo(1), api.tokenNo(2)), api.creates.map { it.attestation })
    }

    @Test
    fun `concurrent calls share one attestation`() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        integrity.answer = { hash ->
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            "integrity.token-for.$hash".padEnd(120, 'x')
        }
        val strybok = sdk()
        val calls = List(8) { async(Dispatchers.Default) { strybok.createLink("/product/$it") } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        settle() // Let every call reach the attestation in flight.
        release.countDown()
        calls.awaitAll()

        assertEquals(1, api.challenges.size)
        assertEquals(1, api.exchanges.size)
        assertEquals(1, integrity.hashes.size)
        assertEquals(List(8) { api.tokenNo(1) }, api.creates.map { it.attestation })
    }

    @Test
    fun `each instance has its own token`() = runBlocking<Unit> {
        sdk().createLink("/product/1")
        sdk().createLink("/product/1")
        assertEquals(listOf(api.tokenNo(1), api.tokenNo(2)), api.creates.map { it.attestation })
    }

    // --- ATTESTATION_INVALID: attest again, once -------------------------------------

    private fun invalid(reason: String) = error(401, "ATTESTATION_INVALID", """{"reason":"$reason"}""")

    @Test
    fun `a refused token is replaced and the same request is sent again, once`() = runBlocking<Unit> {
        for (reason in listOf("expired", "invalid", "exhausted")) {
            val api = AttestApi { wall.get() }
            api.onCreate = { if (it.attestation == api.tokenNo(1)) invalid(reason) else created() }
            val strybok = sdk(api = api, maxRetries = 0)

            val link = strybok.createLink("/product/42", mapOf("productId" to 42), title = "Red shoes")
            assertEquals("link_9", link.linkId)

            // maxRetries is 0: attesting again does not count against it.
            assertEquals(reason, listOf(api.tokenNo(1), api.tokenNo(2)), api.creates.map { it.attestation })
            assertEquals(api.creates[0].body, api.creates[1].body)
            assertEquals(2, api.challenges.size)
            assertNotEquals(api.exchanges[0].json.getString("challenge"), api.exchanges[1].json.getString("challenge"))

            // The new token is the cached one from now on.
            strybok.createLink("/product/43")
            assertEquals(api.tokenNo(2), api.creates.last().attestation)
            assertEquals(2, api.exchanges.size)
        }
    }

    @Test
    fun `a fresh token that is refused as well fails with AttestationFailed`() = runBlocking<Unit> {
        api.onCreate = { invalid(if (it.attestation == api.tokenNo(1)) "expired" else "exhausted") }
        val strybok = sdk()

        val error = failsWith<StrybokAttestationFailedException> { strybok.createLink("/product/42") }
        assertEquals("exhausted", error.reason)
        assertNull(error.url)
        assertEquals(2, api.creates.size)
        assertEquals(2, api.exchanges.size)

        // The next call starts over rather than reusing a refused token.
        api.onCreate = { created() }
        strybok.createLink("/product/42")
        assertEquals(api.tokenNo(3), api.creates.last().attestation)
    }

    @Test
    fun `when attesting again is not possible the call fails with AttestationFailed`() = runBlocking<Unit> {
        api.onCreate = { invalid("expired") }
        integrity.answer = { hash -> if (integrity.hashes.size == 1) "integrity.$hash".padEnd(120, 'x') else null }

        val error = failsWith<StrybokAttestationFailedException> { sdk().createLink("/product/42") }
        assertEquals("expired", error.reason)
        assertEquals(1, api.creates.size) // Nothing is sent without a token after a refusal.
        assertEquals(2, api.challenges.size)
    }

    @Test
    fun `calls refused together attest again together`() = runBlocking<Unit> {
        val strybok = sdk()
        strybok.createLink("/product/0") // Token 1 is cached.
        val bothSent = CountDownLatch(2)
        api.onCreate = {
            if (it.attestation == api.tokenNo(1)) {
                bothSent.countDown()
                assertTrue(bothSent.await(5, TimeUnit.SECONDS))
                invalid("exhausted")
            } else {
                created()
            }
        }
        List(2) { async(Dispatchers.Default) { strybok.createLink("/product/${it + 1}") } }.awaitAll()

        assertEquals(2, api.exchanges.size) // One for token 1, one shared for token 2.
        assertEquals(listOf(api.tokenNo(2), api.tokenNo(2)), api.creates.takeLast(2).map { it.attestation })
    }

    @Test
    fun `the reason is only passed on when it is one of the service's words`() = runBlocking<Unit> {
        for ((details, expected) in listOf(
            """{"reason":"expired"}""" to "expired",
            """{}""" to null,
            """{"reason":42}""" to null,
            """{"reason":"${api.tokenNo(2)}"}""" to null,
            """{"reason":"Something long and free-form that is not a reason word"}""" to null,
        )) {
            val api = AttestApi { wall.get() }
            api.onCreate = { error(401, "ATTESTATION_INVALID", details) }
            val strybok = sdk(api = api)
            assertEquals(details, expected, failsWith<StrybokAttestationFailedException> { strybok.createLink("/a") }.reason)
        }
    }

    // --- cannot attest: send without the header, report the service's answer ---------

    private fun unavailableCases(): List<Pair<String, () -> StrybokIntegrityTokenProvider?>> = listOf(
        "no provider" to { null },
        "provider returns null" to { StrybokIntegrityTokenProvider { null } },
        "provider returns an empty token" to { StrybokIntegrityTokenProvider { "" } },
        "provider throws" to { StrybokIntegrityTokenProvider { throw IllegalStateException("PLAY_STORE_NOT_FOUND") } },
        "provider throws a checked exception" to { StrybokIntegrityTokenProvider { throw IOException("offline") } },
        "provider needs a missing library" to {
            StrybokIntegrityTokenProvider { throw NoClassDefFoundError("com/google/android/play/core/integrity/X") }
        },
        "challenge: 503 not configured" to { api.onChallenge = { error(503, "ATTESTATION_NOT_CONFIGURED") }; integrity },
        "challenge: 429" to { api.onChallenge = { error(429, "RATE_LIMITED") }; integrity },
        "challenge: 500" to { api.onChallenge = { StrybokHttpResponse(500, "") }; integrity },
        "challenge: 404" to { api.onChallenge = { StrybokHttpResponse(404, "") }; integrity },
        "challenge: network" to { api.onChallenge = { throw IOException("offline") }; integrity },
        "challenge: timeout" to { api.onChallenge = { throw SocketTimeoutException() }; integrity },
        "challenge: not JSON" to { api.onChallenge = { StrybokHttpResponse(200, "<html>") }; integrity },
        "challenge: no challenge" to { api.onChallenge = { StrybokHttpResponse(200, """{"challenge":7}""") }; integrity },
        "exchange: 403 refused" to {
            api.onExchange = { error(403, "ATTESTATION_FAILED", """{"reason":"app_not_recognized"}""") }; integrity
        },
        "exchange: 409 not configured" to {
            api.onExchange = { error(409, "ATTESTATION_NOT_CONFIGURED", """{"scope":"application"}""") }; integrity
        },
        "exchange: 429" to { api.onExchange = { error(429, "RATE_LIMITED") }; integrity },
        "exchange: 503 unavailable" to {
            api.onExchange = { error(503, "ATTESTATION_UNAVAILABLE", """{"reason":"quota"}""") }; integrity
        },
        "exchange: 422" to { api.onExchange = { error(422, "VALIDATION_ERROR") }; integrity },
        "exchange: network" to { api.onExchange = { throw IOException("offline") }; integrity },
        "exchange: timeout" to { api.onExchange = { throw SocketTimeoutException() }; integrity },
        "exchange: no token" to { api.onExchange = { StrybokHttpResponse(200, """{"expiresAt":"2026-11-24T11:00:00Z"}""") }; integrity },
        "exchange: token is not a string" to {
            api.onExchange = { StrybokHttpResponse(200, """{"token":1,"expiresAt":"2026-11-24T11:00:00Z"}""") }; integrity
        },
        "exchange: no expiresAt" to { api.onExchange = { StrybokHttpResponse(200, """{"token":"sat1.a.b"}""") }; integrity },
        "exchange: unreadable expiresAt" to {
            api.onExchange = { StrybokHttpResponse(200, """{"token":"sat1.a.b","expiresAt":"soon"}""") }; integrity
        },
        "exchange: token unfit for a header" to {
            api.onExchange = { StrybokHttpResponse(200, """{"token":"sat1 a\nb","expiresAt":"2026-11-24T11:00:00Z"}""") }; integrity
        },
    )

    @Test
    fun `a device that cannot attest sends the request without a token and gets the service's answer`() = runBlocking<Unit> {
        for ((name, arrange) in unavailableCases()) {
            api.onChallenge = AttestApi { wall.get() }.onChallenge
            api.onExchange = { n -> api.grant(n) }
            api.creates.clear()
            val strybok = sdk(provider = arrange(), maxRetries = 2)

            // Mode "off" or "preferred": served.
            api.onCreate = { created() }
            assertEquals(name, "link_9", strybok.createLink("/product/42").linkId)
            // Mode "required": refused, and that is what the app sees.
            api.onCreate = { error(401, "ATTESTATION_REQUIRED") }
            failsWith<StrybokAttestationRequiredException> { strybok.createLink("/product/42") }

            assertEquals(name, 2, api.creates.size)
            assertTrue(name, api.creates.none { ATTESTATION_HEADER in it.headers })
        }
    }

    @Test
    fun `attestation requests are never retried`() = runBlocking<Unit> {
        api.onChallenge = { StrybokHttpResponse(500, "") }
        sdk(maxRetries = 3).createLink("/product/42")
        assertEquals(1, api.challenges.size)

        val other = AttestApi { wall.get() }
        other.onExchange = { throw IOException("offline") }
        sdk(api = other, maxRetries = 3).createLink("/product/42")
        assertEquals(1, other.challenges.size)
        assertEquals(1, other.exchanges.size)
    }

    @Test
    fun `without a provider no attestation request is made at all`() = runBlocking<Unit> {
        sdk(provider = null).createLink("/product/42")
        assertEquals(listOf("/v1/public/links"), api.all.map { it.path })
    }

    @Test
    fun `after a failed attempt the next one waits 60 seconds`() = runBlocking<Unit> {
        integrity.answer = { null }
        val strybok = sdk()
        strybok.createLink("/product/1")
        assertEquals(1, api.challenges.size)

        advance(59_999)
        strybok.createLink("/product/2")
        assertEquals(1, api.challenges.size)
        assertEquals(1, integrity.hashes.size)

        advance(1)
        integrity.answer = { hash -> "integrity.$hash".padEnd(120, 'x') }
        strybok.createLink("/product/3")
        assertEquals(2, api.challenges.size)
        assertEquals(listOf(null, null, api.tokenNo(1)), api.creates.map { it.attestation })
    }

    @Test
    fun `a provider that never answers is given up on`() = runBlocking<Unit> {
        // The transport timeout does not apply to the provider; the SDK's own bound does. Here the
        // provider is interrupted by stop() instead of waiting 30 seconds for that bound.
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val strybok = sdk(
            provider = {
                entered.countDown()
                try {
                    hang()
                } catch (error: InterruptedException) {
                    interrupted.countDown()
                    throw error
                }
            },
        )
        val call = async(Dispatchers.Default) { runCatching { strybok.createLink("/product/42") } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        strybok.stop()

        assertTrue(withTimeout(5_000) { call.await() }.exceptionOrNull() is IllegalStateException)
        assertTrue(interrupted.await(5, TimeUnit.SECONDS))
        assertTrue(api.creates.isEmpty())
    }

    // --- answers of the attestation endpoints: 401 ------------------------------------

    @Test
    fun `a client key refused by an attestation endpoint fails with Unauthorized`() = runBlocking<Unit> {
        api.onChallenge = { error(401, "UNAUTHENTICATED") }
        val strybok = sdk()
        failsWith<StrybokUnauthorizedException> { strybok.createLink("/product/42") }
        assertTrue(api.creates.isEmpty())
        assertTrue(integrity.hashes.isEmpty())

        // Not "cannot attest": the next call asks again instead of pausing.
        api.onChallenge = AttestApi { wall.get() }.onChallenge
        api.onExchange = { error(401, "UNAUTHENTICATED") }
        failsWith<StrybokUnauthorizedException> { strybok.createLink("/product/42") }
        assertEquals(2, api.challenges.size)
        assertTrue(api.creates.isEmpty())
    }

    // --- createLink answers that concern attestation ----------------------------------

    @Test
    fun `401 ATTESTATION_REQUIRED is AttestationRequired, also with attestation switched off`() = runBlocking<Unit> {
        api.onCreate = { error(401, "ATTESTATION_REQUIRED") }
        val off = sdk(attestation = false)
        val error = failsWith<StrybokAttestationRequiredException> { off.createLink("/product/42") }
        assertNull(error.url)
        assertEquals(1, api.creates.size) // Not retried.
        assertTrue(api.challenges.isEmpty())
    }

    @Test
    fun `other 401 answers stay Unauthorized`() = runBlocking<Unit> {
        for (body in listOf(
            """{"error":{"code":"UNAUTHENTICATED","message":"Authentication is required"}}""",
            """{"error":{"code":"SOMETHING_NEW"}}""",
            """{"error":"nope"}""",
            "",
            "<html>",
        )) {
            api.onCreate = { StrybokHttpResponse(401, body) }
            failsWith<StrybokUnauthorizedException> { sdk().createLink("/product/42") }
        }
        // One attestation per instance, no second one because of the 401.
        assertEquals(5, api.exchanges.size)
        assertEquals(5, api.creates.size)
    }

    @Test
    fun `ATTESTATION_INVALID without a token to replace is AttestationFailed and is not retried`() = runBlocking<Unit> {
        api.onCreate = { error(401, "ATTESTATION_INVALID", """{"reason":"invalid"}""") }
        val error = failsWith<StrybokAttestationFailedException> { sdk(attestation = false).createLink("/product/42") }
        assertEquals("invalid", error.reason)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun `429 for the unattested allowance is RateLimited like every 429`() = runBlocking<Unit> {
        integrity.answer = { null }
        api.onCreate = {
            StrybokHttpResponse(
                429,
                """{"error":{"code":"RATE_LIMITED","message":"Daily limit","details":{"scope":"unattested"}}}""",
                mapOf("Retry-After" to "120"),
            )
        }
        val error = failsWith<StrybokRateLimitedException> { sdk().createLink("/product/42") }
        assertEquals(120L, error.retryAfterSeconds)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun `503 ATTESTATION_NOT_CONFIGURED is a server error and is retried like any 5xx`() = runBlocking<Unit> {
        api.onCreate = { error(503, "ATTESTATION_NOT_CONFIGURED", """{"scope":"service"}""") }
        val error = failsWith<StrybokServerException> { sdk(maxRetries = 2).createLink("/product/42") }
        assertEquals(503, error.statusCode)
        assertEquals(List(3) { api.tokenNo(1) }, api.creates.map { it.attestation })
        assertEquals(1, api.exchanges.size)
    }

    @Test
    fun `a 5xx is retried with the same token and body before and after attesting again`() = runBlocking<Unit> {
        val answers = AtomicInteger()
        api.onCreate = {
            when (answers.incrementAndGet()) {
                1, 3 -> StrybokHttpResponse(502, "")
                2 -> invalid("expired")
                else -> created()
            }
        }
        sdk(maxRetries = 1).createLink("/product/42")
        assertEquals(
            listOf(api.tokenNo(1), api.tokenNo(1), api.tokenNo(2), api.tokenNo(2)),
            api.creates.map { it.attestation },
        )
        assertEquals(1, api.creates.map { it.body }.distinct().size)
    }

    // --- errors, callbacks, incoming links ---------------------------------------------

    @Test
    fun `errors never contain the client key, a token, a proof, the challenge or the payload`() = runBlocking<Unit> {
        val errors = ArrayList<StrybokException>()
        val payload = mapOf("secret" to PAYLOAD_SECRET)
        suspend fun collect(strybok: Strybok) {
            try {
                strybok.createLink("/product/42", payload)
            } catch (error: StrybokException) {
                errors += error
            }
        }
        val leaky = """{"reason":"${api.tokenNo(1)}","token":"${api.tokenNo(1)}","key":"$CLIENT_KEY"}"""
        api.onCreate = { error(401, "ATTESTATION_INVALID", leaky) }
        collect(sdk())
        api.onCreate = { error(401, "ATTESTATION_REQUIRED", leaky) }
        collect(sdk())
        api.onCreate = { error(401, "UNAUTHENTICATED", leaky) }
        collect(sdk())
        api.onCreate = { error(503, "ATTESTATION_NOT_CONFIGURED", leaky) }
        collect(sdk(maxRetries = 0))
        api.onCreate = { created() }
        api.onExchange = { error(401, "UNAUTHENTICATED", leaky) }
        collect(sdk())

        assertEquals(
            listOf(
                StrybokAttestationFailedException::class, StrybokAttestationRequiredException::class,
                StrybokUnauthorizedException::class, StrybokServerException::class, StrybokUnauthorizedException::class,
            ),
            errors.map { it::class },
        )
        val secrets = listOf(CLIENT_KEY, PAYLOAD_SECRET, "sat1.", "session-token", "integrity.token", "challenge-")
        for (error in errors) {
            val text = listOf(
                error.message, error.toString(), error.url, error.cause?.toString(),
                (error as? StrybokAttestationFailedException)?.reason,
            ).joinToString(" ")
            for (secret in secrets) assertFalse("$text contains $secret", text.contains(secret))
        }
    }

    @Test
    fun `attestation errors go to the callback of createLink and never to onError`() = runBlocking<Unit> {
        val incoming = ArrayList<StrybokException>()
        api.onCreate = { error(401, "ATTESTATION_REQUIRED") }
        integrity.answer = { throw IllegalStateException("no Play services") }
        val strybok = sdk(errors = incoming)
        val results = CopyOnWriteArrayList<Any>()
        strybok.createLink(
            "/product/42",
            object : StrybokCreateLinkCallback {
                override fun onCreated(link: CreatedLink) {
                    results += link
                }

                override fun onError(error: StrybokException) {
                    results += error
                }
            },
        )
        eventually("the callback") { results.isNotEmpty() }
        assertTrue(results.single() is StrybokAttestationRequiredException)
        assertTrue(incoming.isEmpty())
    }

    @Test
    fun `incoming links are delivered whatever attestation does`() = runBlocking<Unit> {
        api.onChallenge = { throw IOException("offline") }
        api.onCreate = { error(401, "ATTESTATION_REQUIRED") }
        val incoming = ArrayList<StrybokException>()
        val strybok = sdk(provider = { throw OutOfMemoryErrorLike() }, errors = incoming)
        strybok.startWithUrl(INVITE)

        failsWith<StrybokAttestationRequiredException> { strybok.createLink("/product/42") }
        assertEquals("/invite/abc123", strybok.links.first().routePath)
        assertTrue(strybok.handleUrl("https://$HOST/other"))
        assertEquals("/invite/abc123", strybok.links.first().routePath)
        assertTrue(incoming.isEmpty())
    }

    /** A provider failing with a linkage problem, as when Google's library is not in the app. */
    private class OutOfMemoryErrorLike : NoClassDefFoundError("missing")

    // --- the ready-made provider --------------------------------------------------------

    @Test
    fun `the Play Integrity library is not a dependency of the SDK`() {
        // compileOnly: it is on the compile classpath of the SDK and nowhere else, so these tests
        // run like an app that did not add it.
        assertFalse(PlayIntegrityTokenProvider.isLibraryAvailable())
    }
}
