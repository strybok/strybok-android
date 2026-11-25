package com.strybok.sdk

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the Flutter SDK's test suite (github.com/strybok/strybok-flutter), plus the cases that
 * only exist on Android (listeners, repeated `start`, lifecycle-bound collectors,
 * concurrency). Everything runs on the JVM against a fake transport.
 */
class StrybokTest {
    private val instances = ArrayList<Strybok>()
    private val collectors = CoroutineScope(Dispatchers.Default)

    @After
    fun tearDown() {
        instances.forEach { it.stop() }
        collectors.cancel()
    }

    private fun sdk(
        api: StrybokTransport,
        errors: MutableList<StrybokException>? = null,
        clock: (() -> Long)? = null,
        maxRetries: Int = 2,
        timeoutMillis: Long = 3_000,
        reportAppOpens: Boolean = true,
        executor: Executor = directExecutor,
        apiBaseUrl: String = API,
    ): Strybok = Strybok(
        StrybokConfig(
            apiBaseUrl = apiBaseUrl,
            allowedHosts = setOf(HOST),
            timeoutMillis = timeoutMillis,
            maxRetries = maxRetries,
            retryDelayMillis = 1,
            reportAppOpens = reportAppOpens,
            onError = errors?.let { list -> StrybokErrorListener { list += it } },
            transport = api,
            callbackExecutor = executor,
        ),
        clock,
    ).also { instances += it }

    private fun Strybok.listen(): MutableList<ResolvedDeepLink> {
        val received = CopyOnWriteArrayList<ResolvedDeepLink>()
        addLinkListener { received += it }
        return received
    }

    /** Collects [Strybok.links] in the background and returns once the collector is attached. */
    private fun Strybok.collect(received: MutableList<ResolvedDeepLink>): Job {
        val before = subscriberCount
        val job = collectors.launch { links.collect { received += it } }
        eventually("collector to attach") { subscriberCount > before || isStopped }
        return job
    }

    // --- resolve ---------------------------------------------------------------

    @Test
    fun `resolve refuses foreign links without any network call`() = runBlocking<Unit> {
        val api = FakeApi()
        val error = failsWith<UnsupportedLinkException> {
            sdk(api).resolve("https://evil.example.com/invite777")
        }
        assertEquals("https://evil.example.com/invite777", error.url)
        assertTrue(api.resolves.isEmpty())
    }

    @Test
    fun `resolve returns route and payload and sends only host and slug to the API`() = runBlocking<Unit> {
        val api = FakeApi()
        val url = "https://My-App.Strybok.Link/invite777?token=secret#f"
        val link = sdk(api).resolve(url)

        assertEquals("/invite/abc123", link.routePath)
        assertEquals(mapOf("invitationId" to "abc123"), link.payload)
        assertEquals("link_1", link.linkId)
        assertEquals("app_1", link.appId)
        assertEquals(url, link.url)

        val request = api.resolves.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/public/resolve", request.path)
        assertEquals("application/json", request.headers["Accept"])
        assertEquals("$API/v1/public/resolve?url=https%3A%2F%2F$HOST%2Finvite777", request.url)
        assertEquals("https://$HOST/invite777", request.lookup)
        assertFalse(request.url.contains("secret"))
        assertNull(request.body)
        settle()
        assertTrue("explicit resolution is not an app open", api.events.isEmpty())
    }

    @Test
    fun `keeps a path prefix of the API base URL`() = runBlocking<Unit> {
        val api = FakeApi()
        sdk(api, apiBaseUrl = "https://example.com/strybok/").resolve(INVITE)
        assertEquals("/strybok/v1/public/resolve", api.resolves.single().path)
    }

    @Test
    fun `404 becomes LinkNotFoundException and is not retried`() = runBlocking<Unit> {
        val api = FakeApi { StrybokHttpResponse(404, """{"error":{"code":"LINK_NOT_FOUND"}}""") }
        val error = failsWith<LinkNotFoundException> { sdk(api).resolve(INVITE) }
        assertEquals(INVITE, error.url)
        assertEquals(1, api.resolves.size)
    }

    @Test
    fun `other client errors are not retried`() = runBlocking<Unit> {
        val api = FakeApi { StrybokHttpResponse(422, "{}") }
        val error = failsWith<StrybokServerException> { sdk(api).resolve(INVITE) }
        assertEquals(422, error.statusCode)
        assertEquals(1, api.resolves.size)
    }

    @Test
    fun `retries network errors and then succeeds`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val api = FakeApi {
            if (calls.incrementAndGet() < 3) throw IOException("connection reset")
            ok()
        }
        assertEquals("/invite/abc123", sdk(api).resolve(INVITE).routePath)
        assertEquals(3, calls.get())
    }

    @Test
    fun `gives up with a network exception after maxRetries`() = runBlocking<Unit> {
        val api = FakeApi { throw IOException("offline") }
        val error = failsWith<StrybokNetworkException> { sdk(api, maxRetries = 1).resolve(INVITE) }
        // Coroutines may hand over a copy of the exception (stack-trace recovery), so compare by value.
        assertTrue(error.cause is IOException)
        assertEquals("offline", error.cause?.message)
        assertEquals(INVITE, error.url)
        assertEquals(2, api.resolves.size)
    }

    @Test
    fun `unchecked transport failures count as network errors`() = runBlocking<Unit> {
        val api = FakeApi { throw IllegalArgumentException("bad proxy") }
        failsWith<StrybokNetworkException> { sdk(api, maxRetries = 0).resolve(INVITE) }
    }

    @Test
    fun `retries 5xx responses`() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val api = FakeApi { if (calls.incrementAndGet() == 1) StrybokHttpResponse(503, "") else ok() }
        sdk(api).resolve(INVITE)
        assertEquals(2, calls.get())
    }

    @Test
    fun `reports the last 5xx after maxRetries`() = runBlocking<Unit> {
        val api = FakeApi { StrybokHttpResponse(502, "") }
        val error = failsWith<StrybokServerException> { sdk(api).resolve(INVITE) }
        assertEquals(502, error.statusCode)
        assertEquals(3, api.resolves.size)
    }

    @Test
    fun `times out with a typed exception`() = runBlocking<Unit> {
        val api = FakeApi { hang() }
        val error = failsWith<StrybokTimeoutException> {
            sdk(api, maxRetries = 1, timeoutMillis = 80).resolve(INVITE)
        }
        assertEquals(INVITE, error.url)
        assertEquals(2, api.resolves.size)
        assertEquals(80L, api.resolves.first().timeoutMillis)
    }

    @Test
    fun `a socket timeout of the transport is a timeout too`() = runBlocking<Unit> {
        val api = FakeApi { throw SocketTimeoutException("read timed out") }
        failsWith<StrybokTimeoutException> { sdk(api, maxRetries = 1).resolve(INVITE) }
        assertEquals(2, api.resolves.size)
    }

    @Test
    fun `rejects malformed or unsafe responses`() = runBlocking<Unit> {
        for (body in listOf(
            "not json",
            "",
            "[]",
            "null",
            """{"linkId":"l","appId":"a"}""",
            """{"linkId":"l","appId":"a","routePath":"https://evil.example.com"}""",
            """{"linkId":"l","appId":"a","routePath":"//evil.example.com"}""",
            """{"linkId":"l","appId":"a","routePath":""}""",
            """{"linkId":1,"appId":"a","routePath":"/x"}""",
            """{"linkId":"l","appId":null,"routePath":"/x"}""",
        )) {
            val api = FakeApi { StrybokHttpResponse(200, body) }
            val error = try {
                sdk(api).resolve(INVITE)
                throw AssertionError("Accepted: $body")
            } catch (error: StrybokServerException) {
                error
            }
            assertEquals(body, 200, error.statusCode)
            assertEquals(body, 1, api.resolves.size)
        }
    }

    @Test
    fun `payload is an empty map when missing or not an object and plain data otherwise`() = runBlocking<Unit> {
        fun body(payload: String?) =
            """{"linkId":"l","appId":"a","routePath":"/x"${payload?.let { ""","payload":$it""" } ?: ""}}"""

        for (payload in listOf(null, "null", "[1,2]", "\"text\"", "7")) {
            val api = FakeApi { StrybokHttpResponse(200, body(payload)) }
            assertEquals(payload, emptyMap<String, Any?>(), sdk(api).resolve(INVITE).payload)
        }

        val api = FakeApi {
            StrybokHttpResponse(200, body("""{"s":"v","n":3,"b":true,"z":null,"list":[1,{"k":"v"},null],"o":{"in":"ner"}}"""))
        }
        val payload = sdk(api).resolve(INVITE).payload
        assertEquals("v", payload["s"])
        assertEquals(3, (payload["n"] as Number).toInt())
        assertEquals(true, payload["b"])
        assertTrue(payload.containsKey("z"))
        assertNull(payload["z"])
        val list = payload["list"] as List<*>
        assertEquals(1, (list[0] as Number).toInt())
        assertEquals(mapOf("k" to "v"), list[1])
        assertNull(list[2])
        assertEquals(mapOf("in" to "ner"), payload["o"])
    }

    @Test
    fun `callback resolve reports the link or the error`() {
        val results = CopyOnWriteArrayList<Any>()
        val callback = object : StrybokResolveCallback {
            override fun onResolved(link: ResolvedDeepLink) {
                results += link
            }

            override fun onError(error: StrybokException) {
                results += error
            }
        }
        val instance = sdk(FakeApi())
        instance.resolve(INVITE, callback)
        instance.resolve("https://evil.example.com/x", callback)
        eventually("both callbacks") { results.size == 2 }
        assertEquals(1, results.count { it is ResolvedDeepLink })
        assertEquals(1, results.count { it is UnsupportedLinkException })
    }

    @Test
    fun `the test recipe from the integration guide works`() = runBlocking<Unit> {
        val strybok = Strybok(
            StrybokConfig(
                apiBaseUrl = "https://api.strybok.com",
                allowedHosts = setOf("my-app.strybok.link"),
                transport = { request ->
                    if (request.method == "GET") {
                        StrybokHttpResponse(200, """{"linkId":"l1","appId":"a1","routePath":"/invite/abc123"}""")
                    } else {
                        StrybokHttpResponse(202, "") // app_open event
                    }
                },
                callbackExecutor = { it.run() },
            ),
        ).also { instances += it }
        strybok.startWithUrl("https://my-app.strybok.link/invite777")
        assertEquals("/invite/abc123", strybok.getInitialLink()?.routePath)
        strybok.stop()
    }

    // --- cold start ------------------------------------------------------------

    @Test
    fun `delivers the launch link once, even if the listener attaches late`() = runBlocking<Unit> {
        val api = FakeApi()
        val instance = sdk(api)
        assertTrue(instance.startWithUrl(INVITE))
        instance.awaitStarted()

        val received = instance.listen()
        eventually("launch link") { received.size == 1 }
        settle()

        assertEquals(listOf("/invite/abc123"), received.map { it.routePath })
        assertEquals(INVITE, received.single().url)
        assertEquals(1, api.resolves.size)
        assertTrue("a second listener gets nothing", instance.listen().isEmpty())
    }

    @Test
    fun `a late Flow collector receives the queued launch link once`() = runBlocking<Unit> {
        val api = FakeApi()
        val instance = sdk(api)
        instance.startWithUrl(INVITE)
        instance.awaitStarted()

        val first = CopyOnWriteArrayList<ResolvedDeepLink>()
        val job = instance.collect(first)
        eventually("launch link") { first.size == 1 }
        job.cancelAndJoin()

        // A collector that starts later (for example after a rotation) gets nothing again.
        val second = CopyOnWriteArrayList<ResolvedDeepLink>()
        instance.collect(second)
        settle()
        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
        assertEquals(1, api.resolves.size)
    }

    @Test
    fun `de-duplicates a launch link that is delivered again as a new intent`() = runBlocking<Unit> {
        val api = FakeApi()
        val instance = sdk(api)
        val received = instance.listen()
        assertTrue(instance.startWithUrl(INVITE))
        assertFalse(instance.handleUrl(INVITE))
        instance.awaitStarted()
        eventually("event") { api.events.size == 1 }
        settle()

        assertEquals(1, received.size)
        assertEquals(1, api.resolves.size)
        assertEquals(1, api.events.size)
    }

    @Test
    fun `getInitialLink consumes the launch link so listeners do not repeat it`() = runBlocking<Unit> {
        val instance = sdk(FakeApi())
        instance.startWithUrl(INVITE)
        assertEquals("/invite/abc123", instance.getInitialLink()?.routePath)
        assertNull(instance.getInitialLink())

        val received = instance.listen()
        val collected = CopyOnWriteArrayList<ResolvedDeepLink>()
        instance.collect(collected)
        settle()
        assertTrue(received.isEmpty())
        assertTrue(collected.isEmpty())
    }

    @Test
    fun `getInitialLink returns null once a listener already received it`() = runBlocking<Unit> {
        val instance = sdk(FakeApi())
        val received = instance.listen()
        instance.startWithUrl(INVITE)
        instance.awaitStarted()
        eventually("launch link") { received.size == 1 }
        assertNull(instance.getInitialLink())
    }

    @Test
    fun `getInitialLink waits for a slow launch link and has a callback variant`() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val instance = sdk(FakeApi { release.await(); ok() })
        instance.startWithUrl(INVITE)

        val results = CopyOnWriteArrayList<ResolvedDeepLink?>()
        instance.getInitialLink { results += it }
        settle()
        assertTrue("must wait for the resolve call", results.isEmpty())

        release.countDown()
        eventually("callback") { results.size == 1 }
        assertEquals("/invite/abc123", results.single()?.routePath)
        assertNull(instance.getInitialLink())
    }

    @Test
    fun `getInitialLink is null before start and never returns a later link`() = runBlocking<Unit> {
        val instance = sdk(FakeApi())
        assertNull(withTimeout(2_000) { instance.getInitialLink() })

        instance.startWithUrl(null)
        assertTrue(instance.handleUrl(INVITE))
        settle()
        assertNull(instance.getInitialLink())
        // The later link is still queued for the first listener.
        val received = instance.listen()
        eventually("queued link") { received.size == 1 }
    }

    @Test
    fun `no launch link, foreign launch link and repeated start are harmless`() = runBlocking<Unit> {
        val api = FakeApi()
        val errors = CopyOnWriteArrayList<StrybokException>()
        val instance = sdk(api, errors)
        assertFalse(instance.startWithUrl("https://other.example.com/x"))
        assertFalse(instance.startWithUrl(null))
        assertFalse(instance.startWithUrl("https://other.example.com/x"))
        instance.awaitStarted()
        assertNull(instance.getInitialLink())
        settle()
        assertTrue(api.resolves.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `a second start with a new link is handled like a later link`() = runBlocking<Unit> {
        // A process-wide instance outlives its Activity: the user leaves with Back and
        // opens the app again through another link, so onCreate calls start once more.
        var now = 0L
        val api = FakeApi { request -> ok(route = "/p${request.lookup.substringAfterLast('/')}") }
        val instance = sdk(api, clock = { now })
        val received = instance.listen()
        assertTrue(instance.startWithUrl("https://$HOST/first"))
        instance.awaitStarted()
        now += 60_000
        assertTrue(instance.startWithUrl("https://$HOST/second"))
        // The same activity re-created without the saved-state guard within the window.
        assertFalse(instance.startWithUrl("https://$HOST/second"))

        eventually("both links") { received.size == 2 }
        settle()
        assertEquals(listOf("/pfirst", "/psecond"), received.map { it.routePath })
        assertNull("only the first start defines the launch link", instance.getInitialLink())
        assertEquals(2, api.resolves.size)
    }

    @Test
    fun `a failing launch link is reported through onError and start completes`() = runBlocking<Unit> {
        val errors = CopyOnWriteArrayList<StrybokException>()
        val instance = sdk(FakeApi { StrybokHttpResponse(404, "") }, errors)
        instance.startWithUrl(INVITE)
        instance.awaitStarted()
        assertTrue(errors.single() is LinkNotFoundException)
        assertEquals(INVITE, errors.single().url)
        assertNull(instance.getInitialLink())
    }

    // --- running app -----------------------------------------------------------

    @Test
    fun `delivers each foreground link exactly once`() = runBlocking<Unit> {
        val api = FakeApi { request ->
            val slug = request.lookup.substringAfterLast('/')
            ok(route = "/p/$slug", linkId = "link_$slug")
        }
        val instance = sdk(api)
        val received = instance.listen()
        instance.startWithUrl(null)

        assertTrue(instance.handleUrl("https://$HOST/first"))
        assertTrue(instance.handleUrl("https://$HOST/second"))
        assertFalse(instance.handleUrl("https://evil.example.com/third"))
        assertFalse(instance.handleUrl(null))
        eventually("both links") { received.size == 2 }
        settle()

        assertEquals(setOf("/p/first", "/p/second"), received.map { it.routePath }.toSet())
        assertEquals(2, received.size)
        assertEquals(2, api.resolves.size)
    }

    @Test
    fun `drops an immediate duplicate but honours the same link tapped again later`() = runBlocking<Unit> {
        val now = AtomicLong(1_000_000)
        val api = FakeApi()
        val instance = sdk(api, clock = now::get)
        val received = instance.listen()
        instance.startWithUrl(null)

        assertTrue(instance.handleUrl(INVITE))
        now.addAndGet(1_999)
        assertFalse(instance.handleUrl(INVITE))
        eventually("first delivery") { received.size == 1 }
        settle()
        assertEquals(1, received.size)

        // App paused, user taps the same link again a minute later.
        now.addAndGet(60_000)
        assertTrue(instance.handleUrl(INVITE))
        eventually("second delivery") { received.size == 2 }
        assertEquals(2, api.resolves.size)
    }

    @Test
    fun `multiple listeners and collectors each see a link once`() = runBlocking<Unit> {
        val instance = sdk(FakeApi())
        val a = instance.listen()
        val b = instance.listen()
        val c = CopyOnWriteArrayList<ResolvedDeepLink>()
        instance.collect(c)
        instance.startWithUrl(null)
        instance.handleUrl(INVITE)
        eventually("all three") { a.size == 1 && b.size == 1 && c.size == 1 }
        settle()
        assertEquals(listOf(1, 1, 1), listOf(a.size, b.size, c.size))
    }

    @Test
    fun `adding the same listener twice does not duplicate deliveries`() = runBlocking<Unit> {
        val received = CopyOnWriteArrayList<ResolvedDeepLink>()
        val listener = StrybokLinkListener { received += it }
        val instance = sdk(FakeApi())
        instance.addLinkListener(listener)
        instance.addLinkListener(listener)
        instance.handleUrl(INVITE)
        eventually("delivery") { received.size == 1 }
        settle()
        assertEquals(1, received.size)

        instance.removeLinkListener(listener)
        instance.removeLinkListener(listener)
        instance.handleUrl("https://$HOST/other")
        settle()
        assertEquals("a removed listener gets nothing", 1, received.size)
    }

    @Test
    fun `errors go to onError and later links keep working`() = runBlocking<Unit> {
        val fail = AtomicBoolean(true)
        val errors = CopyOnWriteArrayList<StrybokException>()
        val instance = sdk(
            FakeApi { if (fail.get()) throw IOException("offline") else ok() },
            errors,
            maxRetries = 0,
        )
        val received = instance.listen()
        instance.startWithUrl(null)

        instance.handleUrl("https://$HOST/offline1")
        eventually("error") { errors.size == 1 }
        assertTrue(errors.single() is StrybokNetworkException)
        assertEquals("https://$HOST/offline1", errors.single().url)
        assertTrue(received.isEmpty())

        fail.set(false)
        instance.handleUrl("https://$HOST/online1")
        eventually("delivery") { received.size == 1 }
        assertEquals(1, errors.size)
    }

    @Test
    fun `a link resolved while the collector is gone waits for the next collector`() = runBlocking<Unit> {
        // repeatOnLifecycle(STARTED): the collector is cancelled in onStop and restarted in onStart.
        val instance = sdk(FakeApi())
        val first = CopyOnWriteArrayList<ResolvedDeepLink>()
        instance.collect(first).cancelAndJoin()
        eventually("collector to detach") { instance.subscriberCount == 0 }

        instance.handleUrl(INVITE)
        settle()
        assertTrue(first.isEmpty())

        val second = CopyOnWriteArrayList<ResolvedDeepLink>()
        instance.collect(second)
        eventually("queued link") { second.size == 1 }
    }

    @Test
    fun `links a cancelled collector did not consume go to the next one`() = runBlocking<Unit> {
        val api = FakeApi { request -> ok(route = "/p/${request.lookup.substringAfterLast('/')}") }
        val instance = sdk(api)
        val busy = CompletableDeferred<Unit>()
        val seen = CopyOnWriteArrayList<String>()
        val before = instance.subscriberCount
        val job = collectors.launch {
            instance.links.collect {
                seen += it.routePath
                busy.await() // Still handling the first link when it is cancelled.
            }
        }
        eventually("collector to attach") { instance.subscriberCount > before }

        instance.handleUrl("https://$HOST/one")
        eventually("first link") { seen.size == 1 }
        instance.handleUrl("https://$HOST/two")
        eventually("second resolve") { api.resolves.size == 2 }
        settle()
        job.cancelAndJoin()

        val next = instance.listen()
        eventually("leftover link") { next.size == 1 }
        settle()
        assertEquals(listOf("/p/one"), seen.toList())
        assertEquals(listOf("/p/two"), next.map { it.routePath })
    }

    @Test
    fun `a listener removed before its callback ran does not lose the link`() = runBlocking<Unit> {
        val executor = ManualExecutor()
        val instance = sdk(FakeApi(), executor = executor)
        val gone = CopyOnWriteArrayList<ResolvedDeepLink>()
        val listener = StrybokLinkListener { gone += it }
        instance.addLinkListener(listener)
        instance.handleUrl(INVITE)
        eventually("callback to be posted") { executor.queue.size == 1 }

        instance.removeLinkListener(listener) // e.g. onStop ran before the posted callback
        executor.runAll()
        assertTrue(gone.isEmpty())

        val next = CopyOnWriteArrayList<ResolvedDeepLink>()
        instance.addLinkListener { next += it }
        eventually("callback to be posted") { executor.queue.isNotEmpty() }
        executor.runAll()
        assertEquals(1, next.size)
    }

    @Test
    fun `concurrent links from many threads are each delivered exactly once`() = runBlocking<Unit> {
        val threads = 8
        val perThread = 25
        val api = FakeApi { request ->
            val slug = request.lookup.substringAfterLast('/')
            ok(route = "/p/$slug", linkId = "link_$slug")
        }
        val instance = sdk(api, timeoutMillis = 10_000)
        val a = instance.listen()
        val b = CopyOnWriteArrayList<ResolvedDeepLink>()
        instance.collect(b)
        instance.startWithUrl(null)

        val go = CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            thread {
                go.await()
                repeat(perThread) { i -> instance.handleUrl("https://$HOST/t${t}_$i") }
            }
        }
        go.countDown()
        workers.forEach { it.join() }

        val total = threads * perThread
        eventually("all deliveries") { a.size == total && b.size == total && api.events.size == total }
        settle()
        for (received in listOf(a, b)) {
            assertEquals(total, received.size)
            assertEquals(total, received.map { it.routePath }.toSet().size)
        }
        assertEquals(total, api.resolves.size)
        assertEquals(total, api.events.size)
    }

    // --- app_open events -------------------------------------------------------

    @Test
    fun `sends one minimal best-effort event per delivered link`() = runBlocking<Unit> {
        val api = FakeApi()
        val instance = sdk(api, apiBaseUrl = "https://example.com/strybok/")
        instance.listen()
        instance.startWithUrl(null)
        instance.handleUrl(INVITE)
        eventually("event") { api.events.size == 1 }
        settle()

        val request = api.events.single()
        assertEquals("POST", request.method)
        assertEquals("https://example.com/strybok/v1/public/events", request.url)
        assertEquals("application/json", request.headers["Content-Type"])
        val event = request.json
        assertEquals(
            setOf("eventId", "linkId", "type", "platform", "sdkVersion"),
            event.keys().asSequence().toSet(),
        )
        assertEquals("app_open", event.getString("type"))
        assertEquals("link_1", event.getString("linkId"))
        assertEquals("android", event.getString("platform"))
        assertEquals("0.4.0", event.getString("sdkVersion"))
        assertEquals("0.4.0", Strybok.SDK_VERSION)
        assertTrue(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                .matches(event.getString("eventId")),
        )
    }

    @Test
    fun `a queued launch link is reported as an app open too`() = runBlocking<Unit> {
        val api = FakeApi()
        val instance = sdk(api)
        instance.startWithUrl(INVITE)
        instance.awaitStarted()
        eventually("event") { api.events.size == 1 }
    }

    @Test
    fun `a slow or failing events endpoint never delays or breaks delivery`() = runBlocking<Unit> {
        val behaviours = listOf<() -> StrybokHttpResponse>(
            { hang() },
            { throw IOException("down") },
            { StrybokHttpResponse(500, "") },
        )
        for (onEvent in behaviours) {
            val api = FakeApi().also { it.onEvent = onEvent }
            val errors = CopyOnWriteArrayList<StrybokException>()
            val instance = sdk(api, errors)
            val received = instance.listen()
            instance.startWithUrl(null)
            instance.handleUrl(INVITE)
            eventually("delivery") { received.size == 1 }
            eventually("event attempt") { api.events.size == 1 }
            settle()
            assertEquals(1, received.size)
            assertEquals("events are never retried", 1, api.events.size)
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun `app_open events can be turned off`() = runBlocking<Unit> {
        val api = FakeApi()
        val instance = sdk(api, reportAppOpens = false)
        instance.startWithUrl(INVITE)
        instance.awaitStarted()
        val received = instance.listen()
        eventually("launch link") { received.size == 1 }
        settle()
        assertTrue(api.events.isEmpty())
    }

    // --- stop ------------------------------------------------------------------

    @Test
    fun `stop ends delivery, completes the flow and rejects further use`() = runBlocking<Unit> {
        val api = FakeApi()
        val instance = sdk(api)
        val received = instance.listen()
        val collected = CopyOnWriteArrayList<ResolvedDeepLink>()
        val collector = instance.collect(collected)
        instance.startWithUrl(null)

        instance.stop()
        instance.stop() // idempotent
        assertTrue(instance.isStopped)
        withTimeout(2_000) { collector.join() } // the flow completed
        assertEquals(0, instance.subscriberCount)

        assertFalse(instance.handleUrl(INVITE))
        assertFalse(instance.startWithUrl(INVITE))
        settle()
        assertTrue(received.isEmpty())
        assertTrue(collected.isEmpty())
        assertTrue(api.resolves.isEmpty())
        failsWith<IllegalStateException> { instance.resolve(INVITE) }
        assertNull(instance.getInitialLink())
        assertTrue("collecting after stop completes at once", withTimeout(2_000) { instance.links.toList() }.isEmpty())
        instance.addLinkListener { received += it }
        assertEquals(0, instance.subscriberCount)
    }

    @Test
    fun `stop discards links that were queued but not delivered`() = runBlocking<Unit> {
        val instance = sdk(FakeApi())
        instance.startWithUrl(INVITE)
        instance.awaitStarted()
        instance.stop()
        assertNull(instance.getInitialLink())
        assertTrue(instance.listen().isEmpty())
    }

    @Test
    fun `a link still resolving when stopped is not delivered`() = runBlocking<Unit> {
        // The transport ignores the interrupt and answers after stop().
        val release = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val api = FakeApi {
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // keep waiting
                }
            }
            answered.countDown()
            ok()
        }
        val errors = CopyOnWriteArrayList<StrybokException>()
        val instance = sdk(api, errors, timeoutMillis = 5_000)
        val received = instance.listen()
        instance.startWithUrl(null)
        instance.handleUrl(INVITE)
        eventually("request to start") { api.resolves.size == 1 }

        instance.stop()
        release.countDown()
        answered.await()
        settle()
        assertTrue(received.isEmpty())
        assertTrue(errors.isEmpty())
        assertTrue(api.events.isEmpty())
    }

    @Test
    fun `an explicit resolve in flight fails with IllegalStateException when stopped`() = runBlocking<Unit> {
        val api = FakeApi { hang() }
        val instance = sdk(api, timeoutMillis = 5_000)
        val stopper = thread {
            eventually("request to start") { api.resolves.size == 1 }
            instance.stop()
        }
        failsWith<IllegalStateException> { instance.resolve(INVITE) }
        stopper.join()
    }
}
