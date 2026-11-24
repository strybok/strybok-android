package com.strybok.sdk

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `createLink`, row by row of the "Creating links" section of the SDK contract
 * (docs/sdk-contract.md). Everything but the last test runs against a fake transport.
 */
class CreateLinkTest {
    private val instances = ArrayList<Strybok>()

    @After
    fun tearDown() {
        instances.forEach { it.stop() }
    }

    private fun sdk(
        api: StrybokTransport,
        clientKey: String? = CLIENT_KEY,
        errors: MutableList<StrybokException>? = null,
        maxRetries: Int = 2,
        timeoutMillis: Long = 3_000,
        retryDelayMillis: Long = 1,
        apiBaseUrl: String = API,
    ): Strybok = Strybok(
        StrybokConfig(
            apiBaseUrl = apiBaseUrl,
            allowedHosts = setOf(HOST),
            timeoutMillis = timeoutMillis,
            maxRetries = maxRetries,
            retryDelayMillis = retryDelayMillis,
            onError = errors?.let { list -> StrybokErrorListener { list += it } },
            transport = api,
            callbackExecutor = directExecutor,
            clientKey = clientKey,
        ),
    ).also { instances += it }

    private fun creating(onCreate: (StrybokHttpRequest) -> StrybokHttpResponse): FakeApi =
        FakeApi().apply { this.onCreate = onCreate }

    // --- request ---------------------------------------------------------------

    @Test
    fun `201 returns the created link and sends the key in its own header`() = runBlocking<Unit> {
        val api = FakeApi()
        val link = sdk(api).createLink("/product/42")

        assertEquals("https://$HOST/Ab3dE9x", link.url)
        assertEquals("Ab3dE9x", link.slug)
        assertEquals("/product/42", link.routePath)
        assertEquals("link_9", link.linkId)
        assertEquals(CreatedLink("https://$HOST/Ab3dE9x", "Ab3dE9x", "/product/42", "link_9"), link)

        val request = api.creates.single()
        assertEquals("POST", request.method)
        assertEquals("$API/v1/public/links", request.url)
        assertEquals(
            mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "X-Strybok-Key" to CLIENT_KEY,
            ),
            request.headers,
        )
        assertFalse(request.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertEquals(3_000L, request.timeoutMillis)
        assertTrue(api.resolves.isEmpty())
        settle()
        assertTrue("creating a link is not an app open", api.events.isEmpty())
    }

    @Test
    fun `200 means an identical link already existed and is a success too`() = runBlocking<Unit> {
        val api = creating { created(status = 200, slug = "Old1234") }
        val link = sdk(api).createLink("/product/42")
        assertEquals("https://$HOST/Old1234", link.url)
        assertEquals("Old1234", link.slug)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun `arguments that were not given are not sent`() = runBlocking<Unit> {
        val api = FakeApi()
        sdk(api).createLink("/product/42")
        assertEquals("""{"routePath":"/product/42"}""", api.creates.single().body)

        sdk(api).createLink("/product/42", title = "Red shoes")
        assertEquals("""{"routePath":"/product/42","title":"Red shoes"}""", api.creates.last().body)

        sdk(api).createLink("/product/42", payload = emptyMap(), imageUrl = "https://cdn.example.com/a.png")
        assertEquals(
            """{"routePath":"/product/42","payload":{},"imageUrl":"https://cdn.example.com/a.png"}""",
            api.creates.last().body,
        )
        assertFalse(api.creates.any { "null" in it.body!! })
    }

    @Test
    fun `sends every given field as JSON`() = runBlocking<Unit> {
        val api = FakeApi()
        sdk(api).createLink(
            routePath = "/product/42?ref=share",
            payload = linkedMapOf(
                "productId" to 42,
                "big" to 9_007_199_254_740_993L,
                "price" to 9.5,
                "sale" to true,
                "note" to null,
                "tags" to listOf("a", 1, false, null),
                "sizes" to arrayOf("s", "m"),
                "nested" to mapOf("quote" to "say \"hi\"\\\n\t\u0001", "unicode" to "café ☕"),
            ),
            title = "Red shoes",
            description = "Look at these",
            imageUrl = "https://cdn.example.com/shoes.png",
        )
        val request = api.creates.single()
        assertEquals(
            """{"routePath":"/product/42?ref=share","payload":{"productId":42,"big":9007199254740993,""" +
                """"price":9.5,"sale":true,"note":null,"tags":["a",1,false,null],"sizes":["s","m"],""" +
                """"nested":{"quote":"say \"hi\"\\\n\t\u0001","unicode":"café ☕"}},""" +
                """"title":"Red shoes","description":"Look at these","imageUrl":"https://cdn.example.com/shoes.png"}""",
            request.body,
        )
        // And it is JSON a real parser reads back.
        val json = request.json
        assertEquals(setOf("routePath", "payload", "title", "description", "imageUrl"), json.keys().asSequence().toSet())
        val nested = json.getJSONObject("payload").getJSONObject("nested")
        assertEquals("say \"hi\"\\\n\t\u0001", nested.getString("quote"))
        assertEquals("café ☕", nested.getString("unicode"))
        assertTrue(json.getJSONObject("payload").isNull("note"))
    }

    @Test
    fun `keeps a path prefix of the API base URL`() = runBlocking<Unit> {
        val api = FakeApi()
        sdk(api, apiBaseUrl = "https://example.com/strybok/").createLink("/a")
        assertEquals("https://example.com/strybok/v1/public/links", api.creates.single().url)
    }

    // --- before any network call -------------------------------------------------

    @Test
    fun `without a client key it fails as unauthorized without any network call`() = runBlocking<Unit> {
        val api = FakeApi()
        for (key in listOf(null, "", "   ")) {
            val error = failsWith<StrybokUnauthorizedException> { sdk(api, clientKey = key).createLink("/product/42") }
            assertNull(error.url)
        }
        // The missing key is reported first, whatever else is wrong.
        failsWith<StrybokUnauthorizedException> { sdk(api, clientKey = null).createLink("no-slash") }
        assertTrue(api.creates.isEmpty())
    }

    @Test
    fun `a key that cannot be a header value is refused without any network call`() = runBlocking<Unit> {
        val api = FakeApi()
        for (key in listOf("pk_abc\r\nX-Evil: 1", "pk_abc def", "pk_ключ")) {
            val error = failsWith<StrybokUnauthorizedException> { sdk(api, clientKey = key).createLink("/a") }
            assertFalse(key in error.toString())
        }
        assertTrue(api.creates.isEmpty())
    }

    @Test
    fun `the key is trimmed and available from the configuration and the builder`() = runBlocking<Unit> {
        val api = FakeApi()
        val strybok = sdk(api, clientKey = "  $CLIENT_KEY\n")
        assertEquals(CLIENT_KEY, strybok.config.clientKey)
        strybok.createLink("/a")
        assertEquals(CLIENT_KEY, api.creates.single().headers["X-Strybok-Key"])

        val built = StrybokConfig.Builder(API, setOf(HOST)).clientKey(CLIENT_KEY).build()
        assertEquals(CLIENT_KEY, built.clientKey)
        assertNull(StrybokConfig.Builder(API, setOf(HOST)).build().clientKey)
        assertNull(StrybokConfig(API, setOf(HOST)).clientKey)
    }

    @Test
    fun `an invalid route fails validation without any network call`() = runBlocking<Unit> {
        val api = FakeApi()
        val strybok = sdk(api)
        for (route in listOf("", "product/42", "//evil.example.com/x", "https://example.com/x", "/" + "a".repeat(512))) {
            val error = failsWith<StrybokValidationException> { strybok.createLink(route) }
            assertEquals("routePath", error.issues.single().path)
            assertNull(error.url)
        }
        assertTrue(api.creates.isEmpty())

        strybok.createLink("/" + "a".repeat(511)) // 512 characters are allowed.
        strybok.createLink("/")
        assertEquals(2, api.creates.size)
    }

    @Test
    fun `a payload that is not JSON data fails validation without any network call`() = runBlocking<Unit> {
        val api = FakeApi()
        val strybok = sdk(api)
        val cyclic = HashMap<String, Any?>().also { it["self"] = it }
        val payloads = listOf<Map<String, Any?>>(
            mapOf("when" to java.util.Date(0)),
            mapOf("ratio" to Double.NaN),
            mapOf("ratio" to Float.POSITIVE_INFINITY),
            mapOf("list" to listOf(mapOf(1 to "numeric key"))),
            mapOf("char" to 'c'),
            cyclic,
        )
        for (payload in payloads) {
            val error = failsWith<StrybokValidationException> { strybok.createLink("/a", payload) }
            assertEquals("payload", error.issues.single().path)
        }
        assertTrue(api.creates.isEmpty())
    }

    // --- response ----------------------------------------------------------------

    @Test
    fun `a success answer that cannot be read is a server error and is not retried`() = runBlocking<Unit> {
        val bodies = listOf(
            "",
            "<html>ok</html>",
            "[]",
            "\"https://$HOST/x\"",
            """{"url":"https://$HOST/x","slug":"x","routePath":"/a"}""", // no linkId
            """{"linkId":"l","slug":"x","routePath":"/a"}""", // no url
            """{"linkId":"l","url":"https://$HOST/x","routePath":"/a"}""", // no slug
            """{"linkId":"l","url":"https://$HOST/x","slug":"x"}""", // no routePath
            """{"linkId":7,"url":"https://$HOST/x","slug":"x","routePath":"/a"}""",
            """{"linkId":"l","url":"https://$HOST/x","slug":null,"routePath":"/a"}""",
            """{"linkId":"l","url":"http://$HOST/x","slug":"x","routePath":"/a"}""", // not HTTPS
            """{"linkId":"l","url":"javascript:alert(1)","slug":"x","routePath":"/a"}""",
            """{"linkId":"l","url":"//$HOST/x","slug":"x","routePath":"/a"}""",
        )
        for (status in listOf(200, 201)) {
            for (body in bodies) {
                val api = creating { StrybokHttpResponse(status, body) }
                val error = failsWith<StrybokServerException> { sdk(api).createLink("/a") }
                assertEquals(body, status, error.statusCode)
                assertEquals(body, 1, api.creates.size)
            }
        }
    }

    @Test
    fun `401 is unauthorized and is not retried`() = runBlocking<Unit> {
        val api = creating {
            StrybokHttpResponse(401, """{"error":{"code":"UNAUTHENTICATED","message":"Authentication is required","details":{}}}""")
        }
        val error = failsWith<StrybokUnauthorizedException> { sdk(api).createLink("/product/42") }
        assertNull(error.url)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun `403 is RouteNotAllowed with the route and is not retried`() = runBlocking<Unit> {
        val api = creating {
            StrybokHttpResponse(403, """{"error":{"code":"ROUTE_NOT_ALLOWED","message":"Not allowed","details":{}}}""")
        }
        val error = failsWith<RouteNotAllowedException> { sdk(api).createLink("/admin/secret") }
        assertEquals("/admin/secret", error.routePath)
        assertEquals(1, api.creates.size)
    }

    @Test
    fun `429 is RateLimited with the Retry-After seconds and is never retried`() = runBlocking<Unit> {
        val body = """{"error":{"code":"RATE_LIMITED","message":"Too many requests. Please retry in a minute.","details":{}}}"""
        val cases = listOf<Pair<Map<String, String>, Long?>>(
            mapOf("Retry-After" to "60") to 60L,
            mapOf("retry-after" to " 43200 ") to 43_200L, // header names are case-insensitive
            mapOf("RETRY-AFTER" to "0") to 0L,
            emptyMap<String, String>() to null,
            mapOf("Retry-After" to "Wed, 25 Nov 2026 00:00:00 GMT") to null,
            mapOf("Retry-After" to "-5") to null,
            mapOf("Retry-After" to "soon") to null,
            mapOf("Retry-After" to "99999999999999999999999") to null,
        )
        for ((headers, expected) in cases) {
            val api = creating { StrybokHttpResponse(429, body, headers) }
            val error = failsWith<StrybokRateLimitedException> { sdk(api).createLink("/product/42") }
            assertEquals("$headers", expected, error.retryAfterSeconds)
            assertEquals(1, api.creates.size)
        }
    }

    @Test
    fun `400, 413 and 422 are validation errors with the server's message and issues`() = runBlocking<Unit> {
        val body = """{"error":{"code":"VALIDATION_ERROR","message":"The request is invalid","details":{"issues":[""" +
            """{"path":"title","message":"Too big: expected string to have <=120 characters"},""" +
            """{"path":"imageUrl","message":"Must be a public HTTPS URL"},{"message":"no path"},{"path":"x"},"junk"]}}}"""
        for (status in listOf(400, 413, 422)) {
            val api = creating { StrybokHttpResponse(status, body) }
            val error = failsWith<StrybokValidationException> { sdk(api).createLink("/product/42") }
            assertEquals("The request is invalid", error.message)
            assertEquals(
                listOf(
                    StrybokValidationIssue("title", "Too big: expected string to have <=120 characters"),
                    StrybokValidationIssue("imageUrl", "Must be a public HTTPS URL"),
                    StrybokValidationIssue("", "no path"),
                ),
                error.issues,
            )
            assertEquals("title: Too big: expected string to have <=120 characters", error.issues.first().toString())
            assertEquals(1, api.creates.size)
        }
    }

    @Test
    fun `a validation answer without a readable body still is a validation error`() = runBlocking<Unit> {
        val bodies = listOf("", "Payload Too Large", "[]", """{"error":"nope"}""", """{"error":{"details":{"issues":{}}}}""")
        for (body in bodies) {
            val api = creating { StrybokHttpResponse(413, body) }
            val error = failsWith<StrybokValidationException> { sdk(api).createLink("/product/42") }
            assertEquals("The service rejected the request (HTTP 413)", error.message)
            assertTrue(error.issues.isEmpty())
        }
    }

    @Test
    fun `any other status below 500 is a server error with the status and is not retried`() = runBlocking<Unit> {
        for (status in listOf(404, 405, 409, 418, 204, 202, 302)) {
            val api = creating { StrybokHttpResponse(status, "{}") }
            val error = failsWith<StrybokServerException> { sdk(api).createLink("/product/42") }
            assertEquals(status, error.statusCode)
            assertNull(error.url)
            assertEquals(1, api.creates.size)
        }
    }

    // --- timeout and retries -------------------------------------------------------

    @Test
    fun `retries 5xx answers and then succeeds`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val api = creating { if (calls.incrementAndGet() < 3) StrybokHttpResponse(503, "") else created() }
        assertEquals("Ab3dE9x", sdk(api).createLink("/product/42").slug)
        assertEquals(3, api.creates.size)
    }

    @Test
    fun `reports the last 5xx after maxRetries`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val api = creating { StrybokHttpResponse(if (calls.incrementAndGet() < 3) 500 else 502, "") }
        val error = failsWith<StrybokServerException> { sdk(api).createLink("/product/42") }
        assertEquals(502, error.statusCode)
        assertEquals(3, api.creates.size)

        val once = creating { StrybokHttpResponse(500, "") }
        failsWith<StrybokServerException> { sdk(once, maxRetries = 0).createLink("/product/42") }
        assertEquals(1, once.creates.size)
    }

    @Test
    fun `retries transport failures and then succeeds`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val api = creating {
            if (calls.incrementAndGet() < 3) throw IOException("connection reset")
            created()
        }
        assertEquals("link_9", sdk(api).createLink("/product/42").linkId)
        assertEquals(3, api.creates.size)
    }

    @Test
    fun `gives up with a network exception after maxRetries`() = runBlocking<Unit> {
        val api = creating { throw IOException("offline") }
        val error = failsWith<StrybokNetworkException> { sdk(api, maxRetries = 1).createLink("/product/42") }
        assertEquals("offline", error.cause?.message)
        assertNull(error.url)
        assertEquals(2, api.creates.size)

        val unchecked = creating { throw IllegalArgumentException("bad proxy") }
        failsWith<StrybokNetworkException> { sdk(unchecked, maxRetries = 0).createLink("/product/42") }
    }

    @Test
    fun `times out with a typed exception after retrying`() = runBlocking<Unit> {
        val api = creating { hang() }
        val error = failsWith<StrybokTimeoutException> {
            sdk(api, maxRetries = 1, timeoutMillis = 80).createLink("/product/42")
        }
        assertNull(error.url)
        assertEquals("The create-link request timed out", error.message)
        assertEquals(2, api.creates.size)
        assertEquals(80L, api.creates.first().timeoutMillis)

        val socket = creating { throw SocketTimeoutException("read timed out") }
        failsWith<StrybokTimeoutException> { sdk(socket, maxRetries = 1).createLink("/product/42") }
        assertEquals(2, socket.creates.size)
    }

    @Test
    fun `a timeout followed by an answer succeeds`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val api = creating { if (calls.incrementAndGet() == 1) hang() else created(status = 200) }
        assertEquals("Ab3dE9x", sdk(api, timeoutMillis = 80).createLink("/product/42").slug)
        assertEquals(2, api.creates.size)
    }

    @Test
    fun `waits retryDelay before the first retry and twice as long before the next`() = runBlocking<Unit> {
        val times = CopyOnWriteArrayList<Long>()
        val api = creating {
            times += System.nanoTime()
            StrybokHttpResponse(503, "")
        }
        failsWith<StrybokServerException> { sdk(api, retryDelayMillis = 60).createLink("/product/42") }
        assertEquals(3, times.size)
        val first = (times[1] - times[0]) / 1_000_000
        val second = (times[2] - times[1]) / 1_000_000
        assertTrue("first pause was $first ms", first >= 55)
        assertTrue("second pause was $second ms", second >= 115)
    }

    @Test
    fun `every retry sends exactly the same request`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val api = creating {
            when (calls.incrementAndGet()) {
                1 -> throw IOException("connection reset")
                2 -> StrybokHttpResponse(503, "")
                else -> created()
            }
        }
        sdk(api).createLink(
            "/product/42",
            payload = mapOf("b" to 1, "a" to listOf(1.5, "x"), "c" to mapOf("z" to null)),
            title = "Red shoes",
            description = "Look at these",
            imageUrl = "https://cdn.example.com/shoes.png",
        )
        assertEquals(3, api.creates.size)
        val first = api.creates.first()
        for (retry in api.creates.drop(1)) {
            assertEquals(first.body, retry.body)
            assertEquals(first.headers, retry.headers)
            assertEquals(first.url, retry.url)
            assertEquals(first.method, retry.method)
        }
    }

    // --- errors --------------------------------------------------------------------

    @Test
    fun `errors never contain the client key or the payload`() = runBlocking<Unit> {
        val secret = "s3cr3t-payload-value"
        val payload = mapOf("token" to secret)
        val answers: List<(StrybokHttpRequest) -> StrybokHttpResponse> = listOf(
            { _ -> StrybokHttpResponse(200, "not json") },
            { _ -> StrybokHttpResponse(401, "") },
            { _ -> StrybokHttpResponse(403, "") },
            { _ -> StrybokHttpResponse(429, "", mapOf("Retry-After" to "60")) },
            { _ -> StrybokHttpResponse(422, """{"error":{"message":"The request is invalid","details":{"issues":[{"path":"payload","message":"Too big"}]}}}""") },
            { _ -> StrybokHttpResponse(404, "") },
            { _ -> StrybokHttpResponse(500, "") },
            { _ -> throw IOException("connection reset") },
            { _ -> throw SocketTimeoutException("read timed out") },
        )
        val errors = ArrayList<StrybokException>()
        for (answer in answers) {
            errors += failsWith<StrybokException> { sdk(creating(answer), maxRetries = 0).createLink("/product/42", payload) }
        }
        // Pre-flight failures.
        errors += failsWith<StrybokException> { sdk(FakeApi()).createLink("product/42", payload) }
        errors += failsWith<StrybokException> { sdk(FakeApi()).createLink("/a", mapOf("token" to secret, "bad" to Any())) }
        errors += failsWith<StrybokException> {
            sdk(FakeApi()).createLink("/a", mapOf(secret to mapOf(7 to secret)))
        }
        assertEquals(
            listOf(
                StrybokServerException::class, StrybokUnauthorizedException::class, RouteNotAllowedException::class,
                StrybokRateLimitedException::class, StrybokValidationException::class, StrybokServerException::class,
                StrybokServerException::class, StrybokNetworkException::class, StrybokTimeoutException::class,
                StrybokValidationException::class, StrybokValidationException::class, StrybokValidationException::class,
            ),
            errors.map { it::class },
        )
        for (error in errors) {
            val text = buildString {
                var current: Throwable? = error
                while (current != null) {
                    append(current.toString()).append('\n').append(current.message).append('\n')
                    current = current.cause
                }
                append(error.url)
                if (error is StrybokValidationException) append(error.issues)
            }
            assertFalse(text, CLIENT_KEY in text)
            assertFalse(text, secret in text)
        }
        assertEquals("POST $API/v1/public/links", StrybokHttpRequest("POST", "$API/v1/public/links", mapOf("X-Strybok-Key" to CLIENT_KEY), secret, 1).toString())
    }

    @Test
    fun `errors go to the caller and never to onError`() = runBlocking<Unit> {
        val errors = CopyOnWriteArrayList<StrybokException>()
        val api = creating { StrybokHttpResponse(403, "") }
        val strybok = sdk(api, errors = errors)
        failsWith<RouteNotAllowedException> { strybok.createLink("/admin") }
        failsWith<StrybokValidationException> { strybok.createLink("admin") }
        settle()
        assertTrue(errors.isEmpty())
    }

    // --- never on the start path -----------------------------------------------------

    @Test
    fun `works without start and without touching incoming links`() = runBlocking<Unit> {
        val api = FakeApi()
        val strybok = sdk(api)
        assertEquals("https://$HOST/Ab3dE9x", strybok.createLink("/product/42").url)
        settle()
        assertTrue(api.resolves.isEmpty())
        assertTrue(api.events.isEmpty())
        assertNull(strybok.getInitialLink())
    }

    @Test
    fun `start, incoming links and resolve never call the create endpoint`() = runBlocking<Unit> {
        val api = FakeApi()
        val strybok = sdk(api)
        val received = CopyOnWriteArrayList<ResolvedDeepLink>()
        strybok.addLinkListener { received += it }
        strybok.startWithUrl(INVITE)
        strybok.awaitStarted()
        strybok.handleUrl("https://$HOST/other")
        strybok.resolve(INVITE)
        eventually("both links") { received.size == 2 }
        eventually("both events") { api.events.size == 2 }
        assertTrue(api.creates.isEmpty())
        assertFalse(api.resolves.any { "X-Strybok-Key" in it.headers })
        assertFalse(api.events.any { "X-Strybok-Key" in it.headers })
    }

    @Test
    fun `neither waits for a launch link that is still resolving nor delays it`() = runBlocking<Unit> {
        val api = FakeApi { hang() }
        val strybok = sdk(api, maxRetries = 0, timeoutMillis = 2_000)
        strybok.startWithUrl(INVITE)
        eventually("the resolve request") { api.resolves.size == 1 }

        val startedAt = System.nanoTime()
        assertEquals("Ab3dE9x", strybok.createLink("/product/42").slug)
        assertTrue("createLink waited for the launch link", System.nanoTime() - startedAt < 1_500_000_000)

        // And the other way round: start completes while createLink hangs.
        val slow = FakeApi().apply { onCreate = { hang() } }
        val other = sdk(slow, maxRetries = 0, timeoutMillis = 2_000)
        val pending = CoroutineScope(Dispatchers.Default)
        val create = pending.launch { runCatching { other.createLink("/product/42") } }
        eventually("the create request") { slow.creates.size == 1 }
        other.startWithUrl(INVITE)
        assertEquals("/invite/abc123", other.getInitialLink()?.routePath)
        assertTrue(create.isActive)
        create.cancel()
    }

    // --- callbacks and lifecycle -------------------------------------------------------

    private class Recorder : StrybokCreateLinkCallback {
        val links = CopyOnWriteArrayList<CreatedLink>()
        val errors = CopyOnWriteArrayList<StrybokException>()

        override fun onCreated(link: CreatedLink) {
            links += link
        }

        override fun onError(error: StrybokException) {
            errors += error
        }
    }

    @Test
    fun `callback createLink reports the link or the error on the callback executor`() {
        val api = FakeApi()
        val strybok = sdk(api)
        val recorder = Recorder()

        strybok.createLink("/product/42", recorder)
        eventually("the link") { recorder.links.size == 1 }
        assertEquals("""{"routePath":"/product/42"}""", api.creates.single().body)

        strybok.createLink("/product/42", mapOf("productId" to 42), "Red shoes", null, null, recorder)
        eventually("the second link") { recorder.links.size == 2 }
        assertEquals("""{"routePath":"/product/42","payload":{"productId":42},"title":"Red shoes"}""", api.creates.last().body)

        strybok.createLink("no-slash", recorder) // pre-flight failures arrive through the callback too
        eventually("the validation error") { recorder.errors.size == 1 }
        assertTrue(recorder.errors.single() is StrybokValidationException)

        api.onCreate = { StrybokHttpResponse(429, "", mapOf("Retry-After" to "7")) }
        strybok.createLink("/product/42", recorder)
        eventually("the rate limit") { recorder.errors.size == 2 }
        assertEquals(7L, (recorder.errors.last() as StrybokRateLimitedException).retryAfterSeconds)
        assertEquals(2, recorder.links.size)
    }

    @Test
    fun `after stop it throws IllegalStateException and an unfinished call reports nothing`() = runBlocking<Unit> {
        val api = FakeApi().apply { onCreate = { hang() } }
        val strybok = sdk(api)
        val recorder = Recorder()
        strybok.createLink("/product/42", recorder)
        eventually("the create request") { api.creates.size == 1 }
        strybok.stop()
        settle()
        assertTrue(recorder.links.isEmpty())
        assertTrue(recorder.errors.isEmpty())

        failsWith<IllegalStateException> { strybok.createLink("/product/42") }
        failsWith<IllegalStateException> { strybok.createLink("/product/42", recorder) }
        assertEquals(1, api.creates.size)
    }

    // --- the real transport -------------------------------------------------------------

    @Test
    fun `the default transport posts the body and the key header and reads Retry-After`() = runBlocking<Unit> {
        class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String)

        val requests = CopyOnWriteArrayList<Recorded>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += Recorded(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                body,
            )
            val limited = requests.size > 1
            val answer = if (limited) {
                """{"error":{"code":"RATE_LIMITED","message":"Too many requests. Please retry in a minute.","details":{}}}"""
            } else {
                """{"linkId":"link_9","url":"https://$HOST/Ab3dE9x","slug":"Ab3dE9x","routePath":"/product/é"}"""
            }.toByteArray(Charsets.UTF_8)
            if (limited) exchange.responseHeaders.add("Retry-After", "60")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (limited) 429 else 201, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
            exchange.close()
        }
        server.start()
        try {
            val strybok = Strybok(
                StrybokConfig(
                    apiBaseUrl = "http://127.0.0.1:${server.address.port}/",
                    allowedHosts = setOf(HOST),
                    callbackExecutor = directExecutor,
                    clientKey = CLIENT_KEY,
                ),
            ).also { instances += it }

            val link = strybok.createLink("/product/é", mapOf("name" to "café"), title = "Red shoes")
            assertEquals("https://$HOST/Ab3dE9x", link.url)
            assertEquals("/product/é", link.routePath)

            val request = requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/public/links", request.path)
            assertEquals(CLIENT_KEY, request.headers["x-strybok-key"])
            assertEquals("application/json", request.headers["content-type"])
            assertEquals("application/json", request.headers["accept"])
            assertNull(request.headers["authorization"])
            assertEquals("""{"routePath":"/product/é","payload":{"name":"café"},"title":"Red shoes"}""", request.body)

            val error = failsWith<StrybokRateLimitedException> { strybok.createLink("/product/é") }
            assertEquals(60L, error.retryAfterSeconds)
            assertEquals(2, requests.size)
        } finally {
            server.stop(0)
        }
    }
}
