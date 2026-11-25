package com.strybok.sdk

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two stored values, in memory. One instance outlives several "launches" of a test. */
class FakePlatform : DeferredLinkPlatform {
    override val packageName: String = "com.example.app"

    @Volatile var updated = false
    @Volatile var claims = true
    @Volatile var attemptsWriteFails = false
    @Volatile var doneWriteFails = false

    /** Runs right after `done` was stored: the place where a test "kills the process". */
    @Volatile var afterDoneStored: (() -> Unit)? = null

    val storedAttempts = AtomicInteger(0)
    val storedDone = AtomicBoolean(false)

    /** Every write, in order. */
    val writes = CopyOnWriteArrayList<String>()

    override fun wasUpdatedSinceInstall(): Boolean = updated

    override fun attempts(): Int = storedAttempts.get()

    override fun storeAttempts(value: Int): Boolean {
        if (attemptsWriteFails) return false
        storedAttempts.set(value)
        writes += "attempts=$value"
        return true
    }

    override fun isDone(): Boolean = storedDone.get()

    override fun storeDone(): Boolean {
        if (doneWriteFails) return false
        storedDone.set(true)
        writes += "done"
        afterDoneStored?.invoke()
        return true
    }

    override fun claimProcess(): Boolean = claims
}

/** Answers with [answers] in turn (the last one repeats) and records how often it was asked. */
class FakeSource(private vararg val answers: InstallReferrerResult) : InstallReferrerSource {
    val calls = AtomicInteger()

    @Volatile var onAsked: (() -> Unit)? = null

    override fun getInstallReferrer(): InstallReferrerResult {
        val call = calls.getAndIncrement()
        onAsked?.invoke()
        return answers[call.coerceAtMost(answers.size - 1)]
    }
}

fun referrer(value: String = "strybok_click=$CLICK_ID"): InstallReferrerResult = InstallReferrerResult.referrer(value)

/**
 * The deferred-link flow of the SDK contract ("Deferred links (optional, Android)"), against a
 * fake store, fake sources and a fake transport.
 */
class DeferredLinksTest {
    private val instances = ArrayList<Strybok>()
    private val errors = CopyOnWriteArrayList<StrybokException>()

    @After
    fun tearDown() {
        instances.forEach { it.stop() }
        assertEquals("the check reports nothing to onError", emptyList<StrybokException>(), errors.toList())
    }

    /** One launch of the app: a new instance over the same stored state. */
    private fun launch(
        api: StrybokTransport,
        platform: FakePlatform?,
        vararg sources: InstallReferrerSource,
        deferredLinks: Boolean = true,
        maxRetries: Int = 2,
        timeoutMillis: Long = 3_000,
        sourceTimeoutMillis: Long = 3_000,
        executor: Executor = directExecutor,
    ): Strybok = Strybok(
        StrybokConfig(
            apiBaseUrl = API,
            allowedHosts = setOf(HOST),
            timeoutMillis = timeoutMillis,
            maxRetries = maxRetries,
            retryDelayMillis = 1,
            onError = { errors += it },
            transport = api,
            callbackExecutor = executor,
            clientKey = CLIENT_KEY,
            deferredLinks = deferredLinks,
            installReferrerSources = sources.toList(),
        ),
        null,
        null,
        platform,
        sourceTimeoutMillis,
    ).also { instances += it }

    private fun Strybok.listen(): MutableList<ResolvedDeepLink> {
        val received = CopyOnWriteArrayList<ResolvedDeepLink>()
        addLinkListener { received += it }
        return received
    }

    private fun Strybok.awaitStatus(expected: DeferredLinkStatus) {
        eventually("status $expected (is $deferredLinkStatus)") { deferredLinkStatus == expected }
    }

    /** Starts without a launch link and waits for the check to end with [expected]. */
    private fun Strybok.runCheck(expected: DeferredLinkStatus): Strybok = apply {
        startWithUrl(null)
        awaitStatus(expected)
    }

    // --- off by default ------------------------------------------------------------

    @Test
    fun `is off by default and then reads, sends and stores nothing`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform()
        val source = FakeSource(referrer())
        val defaults = StrybokConfig(apiBaseUrl = API, allowedHosts = setOf(HOST))
        assertFalse(defaults.deferredLinks)
        assertTrue(defaults.installReferrerSources.isEmpty())

        val strybok = launch(api, platform, source, deferredLinks = false)
        val received = strybok.listen()
        strybok.startWithUrl(null)
        strybok.awaitStarted()
        settle()

        assertEquals(DeferredLinkStatus.DISABLED, strybok.deferredLinkStatus)
        assertEquals(0, source.calls.get())
        assertTrue(api.redeems.isEmpty())
        assertTrue(platform.writes.isEmpty())
        assertTrue(received.isEmpty())
    }

    @Test
    fun `needs a context when it is switched on`() {
        val config = StrybokConfig(apiBaseUrl = API, allowedHosts = setOf(HOST), deferredLinks = true)
        failsWith<IllegalArgumentException> { Strybok(config) }

        val plain = Strybok(StrybokConfig(apiBaseUrl = API, allowedHosts = setOf(HOST))).also { instances += it }
        failsWith<IllegalStateException> { plain.enableDeferredLinks() }
        assertEquals(DeferredLinkStatus.DISABLED, plain.deferredLinkStatus)
    }

    // --- the happy path -------------------------------------------------------------

    @Test
    fun `delivers the link of the click id found in the referrer`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform()
        val source = FakeSource(referrer("utm_source=google-play&strybok_click=$CLICK_ID&utm_medium=organic"))
        val strybok = launch(api, platform, source)
        val received = strybok.listen()
        assertEquals(DeferredLinkStatus.CHECKING, strybok.deferredLinkStatus)

        strybok.runCheck(DeferredLinkStatus.DELIVERED)
        eventually("the link") { received.size == 1 }

        val request = api.redeems.single()
        assertEquals("POST", request.method)
        assertEquals("$API/v1/public/deferred-links/redeem", request.url)
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("application/json", request.headers["Accept"])
        val body = request.json
        assertEquals(setOf("platform", "clickId", "packageName", "sdkVersion"), body.keys().asSequence().toSet())
        assertEquals("android", body.getString("platform"))
        assertEquals(CLICK_ID, body.getString("clickId"))
        assertEquals("com.example.app", body.getString("packageName"))
        assertEquals(Strybok.SDK_VERSION, body.getString("sdkVersion"))

        val link = received.single()
        assertTrue(link.isDeferred)
        assertEquals("https://$HOST/promo42", link.url)
        assertEquals("link_7", link.linkId)
        assertEquals("/promo/42", link.routePath)
        assertEquals(mapOf("code" to "SPRING"), link.payload)
        assertEquals(LinkCampaign(source = "instagram"), link.campaign)

        assertEquals(listOf("attempts=1", "done"), platform.writes.toList())
        assertTrue(DeferredLinkStatus.DELIVERED.isFinal)
        settle()
        assertTrue("no app_open for a deferred link", api.events.isEmpty())
        assertTrue(api.resolves.isEmpty())
        assertEquals(1, source.calls.get())
    }

    @Test
    fun `a normal link is not deferred`() = runBlocking<Unit> {
        val strybok = launch(FakeApi(), FakePlatform(), deferredLinks = false)
        assertFalse(strybok.resolve(INVITE).isDeferred)
    }

    @Test
    fun `queues the link until a listener attaches and never returns it from getInitialLink`() = runBlocking<Unit> {
        val api = FakeApi()
        val strybok = launch(api, FakePlatform(), FakeSource(referrer()))
        strybok.runCheck(DeferredLinkStatus.DELIVERED)

        assertNull(strybok.getInitialLink())
        val received = strybok.listen()
        eventually("the queued link") { received.size == 1 }
        assertTrue(received.single().isDeferred)
        settle()
        assertEquals(1, received.size)
    }

    @Test
    fun `start, the launch link and createLink do not wait for a source that hangs`() = runBlocking<Unit> {
        val api = FakeApi()
        val release = CountDownLatch(1)
        val asked = CountDownLatch(1)
        val source = InstallReferrerSource {
            asked.countDown()
            release.await()
            referrer("nothing=here")
        }
        // The launch URL is not ours, so nothing supersedes the check.
        val strybok = launch(api, FakePlatform(), source, sourceTimeoutMillis = 30_000)
        strybok.startWithUrl("https://elsewhere.example.com/x")
        withTimeout(2_000) {
            strybok.awaitStarted()
            assertNull(strybok.getInitialLink())
            assertEquals("https://$HOST/Ab3dE9x", strybok.createLink("/product/42").url)
        }
        assertTrue(asked.await(2, TimeUnit.SECONDS))
        assertEquals(DeferredLinkStatus.CHECKING, strybok.deferredLinkStatus)

        release.countDown()
        strybok.awaitStatus(DeferredLinkStatus.NO_REFERRER)
    }

    @Test
    fun `the check runs only after the launch link was handled`() = runBlocking<Unit> {
        val resolving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = FakeApi {
            resolving.countDown()
            release.await()
            ok()
        }
        val source = FakeSource(referrer())
        val strybok = launch(api, FakePlatform(), source)
        settle()
        assertEquals("not before start", 0, source.calls.get())

        strybok.startWithUrl(INVITE)
        assertTrue(resolving.await(2, TimeUnit.SECONDS))
        settle()
        assertEquals("not while the launch link resolves", 0, source.calls.get())
        assertEquals(DeferredLinkStatus.CHECKING, strybok.deferredLinkStatus)

        release.countDown()
        strybok.awaitStatus(DeferredLinkStatus.SUPERSEDED)
        assertEquals(1, source.calls.get())
    }

    // --- state, attempts and restarts ---------------------------------------------------

    @Test
    fun `counts the attempt before the first source is asked`() = runBlocking<Unit> {
        val platform = FakePlatform()
        val seen = AtomicInteger(-1)
        val source = FakeSource(referrer())
        source.onAsked = { seen.set(platform.attempts()) }

        launch(FakeApi(), platform, source).runCheck(DeferredLinkStatus.DELIVERED)

        assertEquals(1, seen.get())
        assertEquals("attempts=1", platform.writes.first())
    }

    @Test
    fun `three launches that cannot finish, then failed`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform()
        val source = FakeSource(InstallReferrerResult.UNAVAILABLE_FOR_NOW)

        repeat(3) { launchNumber ->
            val strybok = launch(api, platform, source).runCheck(DeferredLinkStatus.PENDING)
            assertEquals(launchNumber + 1, platform.attempts())
            assertFalse(platform.isDone())
            assertFalse(DeferredLinkStatus.PENDING.isFinal)
            strybok.stop()
        }
        launch(api, platform, source).runCheck(DeferredLinkStatus.FAILED).stop()

        assertEquals("the fourth launch asks nobody", 3, source.calls.get())
        assertTrue(platform.isDone())
        assertEquals(listOf("attempts=1", "attempts=2", "attempts=3", "done"), platform.writes.toList())

        launch(api, platform, source).runCheck(DeferredLinkStatus.NOT_FIRST_LAUNCH)
        assertEquals(3, source.calls.get())
        assertTrue(api.redeems.isEmpty())
    }

    @Test
    fun `a later launch does nothing once done is stored`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform()
        val source = FakeSource(referrer())
        launch(api, platform, source).runCheck(DeferredLinkStatus.DELIVERED).stop()

        val second = launch(api, platform, source)
        val received = second.listen()
        second.runCheck(DeferredLinkStatus.NOT_FIRST_LAUNCH)
        settle()

        assertEquals(1, source.calls.get())
        assertEquals(1, api.redeems.size)
        assertTrue(received.isEmpty())
        assertEquals(listOf("attempts=1", "done"), platform.writes.toList())
    }

    @Test
    fun `an updated app is not asked about an old referrer`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform().apply { updated = true }
        val source = FakeSource(referrer())

        launch(api, platform, source).runCheck(DeferredLinkStatus.NOT_FIRST_LAUNCH)

        assertEquals(0, source.calls.get())
        assertTrue(api.redeems.isEmpty())
        assertEquals(listOf("done"), platform.writes.toList())
    }

    @Test
    fun `a launch killed while a source is asked has used an attempt`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform()
        val asked = CountDownLatch(1)
        val hanging = InstallReferrerSource {
            asked.countDown()
            hang()
        }
        val first = launch(api, platform, hanging, sourceTimeoutMillis = 30_000)
        first.startWithUrl(null)
        assertTrue(asked.await(2, TimeUnit.SECONDS))
        first.stop() // The process dies here.

        assertEquals(1, platform.attempts())
        assertFalse(platform.isDone())
        assertEquals(DeferredLinkStatus.CHECKING, first.deferredLinkStatus)

        val second = launch(api, platform, FakeSource(referrer()))
        val received = second.listen()
        second.runCheck(DeferredLinkStatus.DELIVERED)
        eventually("the link") { received.size == 1 }
        assertEquals(listOf("attempts=1", "attempts=2", "done"), platform.writes.toList())
    }

    @Test
    fun `a launch killed right after done was stored never delivers`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform()
        val first = launch(api, platform, FakeSource(referrer()))
        val received = first.listen()
        platform.afterDoneStored = { first.stop() } // Killed between the write and the delivery.
        first.startWithUrl(null)
        eventually("done") { platform.isDone() }
        settle()
        assertTrue(received.isEmpty())

        platform.afterDoneStored = null
        val second = launch(api, platform, FakeSource(referrer()))
        val again = second.listen()
        second.runCheck(DeferredLinkStatus.NOT_FIRST_LAUNCH)
        settle()
        assertTrue("lost, never delivered twice", again.isEmpty())
        assertEquals(1, api.redeems.size)
    }

    @Test
    fun `a launch killed before the answer arrived asks again and delivers once`() = runBlocking<Unit> {
        val platform = FakePlatform()
        lateinit var first: Strybok
        val api = FakeApi()
        api.onRedeem = {
            first.stop() // The answer is on its way when the process dies.
            redeemed()
        }
        first = launch(api, platform, FakeSource(referrer()))
        val lost = first.listen()
        first.startWithUrl(null)
        eventually("the request") { api.redeems.size == 1 }
        settle()
        assertFalse("done is not stored before the answer", platform.isDone())
        assertTrue(lost.isEmpty())

        api.onRedeem = null // The service repeats its answer for two minutes.
        val second = launch(api, platform, FakeSource(referrer()))
        val received = second.listen()
        second.runCheck(DeferredLinkStatus.DELIVERED)
        eventually("the link") { received.size == 1 }
        assertEquals(api.redeems[0].body, api.redeems[1].body)
        assertEquals(listOf("attempts=1", "attempts=2", "done"), platform.writes.toList())
    }

    @Test
    fun `stops without asking when the attempt cannot be stored`() = runBlocking<Unit> {
        val platform = FakePlatform().apply { attemptsWriteFails = true }
        val source = FakeSource(referrer())
        launch(FakeApi(), platform, source).runCheck(DeferredLinkStatus.PENDING)
        assertEquals(0, source.calls.get())
    }

    @Test
    fun `does not deliver when done cannot be stored`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform().apply { doneWriteFails = true }
        val strybok = launch(api, platform, FakeSource(referrer()))
        val received = strybok.listen()
        strybok.runCheck(DeferredLinkStatus.PENDING)
        settle()
        assertTrue(received.isEmpty())
        assertEquals(1, api.redeems.size)
    }

    @Test
    fun `only one instance in a process runs the check`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform().apply { claims = false }
        val source = FakeSource(referrer())
        launch(api, platform, source).runCheck(DeferredLinkStatus.PENDING)
        platform.storedDone.set(true)
        launch(api, platform, source).runCheck(DeferredLinkStatus.NOT_FIRST_LAUNCH)
        assertEquals(0, source.calls.get())
        assertTrue(api.redeems.isEmpty())
        assertTrue(platform.writes.isEmpty())
    }

    // --- sources ---------------------------------------------------------------------

    @Test
    fun `no sources at all is unavailable`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform()
        launch(api, platform).runCheck(DeferredLinkStatus.UNAVAILABLE)
        assertTrue(platform.isDone())
        assertTrue(api.redeems.isEmpty())
    }

    @Test
    fun `sources that can never answer are unavailable`() = runBlocking<Unit> {
        val platform = FakePlatform()
        val first = FakeSource(InstallReferrerResult.UNAVAILABLE)
        val second = FakeSource(InstallReferrerResult.UNAVAILABLE)
        launch(FakeApi(), platform, first, second).runCheck(DeferredLinkStatus.UNAVAILABLE)
        assertEquals(1, first.calls.get())
        assertEquals(1, second.calls.get())
        assertTrue(platform.isDone())
        assertTrue(DeferredLinkStatus.UNAVAILABLE.isFinal)
    }

    @Test
    fun `a referrer without a click id is the ordinary install`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform()
        val sources = arrayOf(
            FakeSource(InstallReferrerResult.UNAVAILABLE),
            FakeSource(referrer("utm_source=google-play&utm_medium=organic")),
            FakeSource(referrer("")),
        )
        launch(api, platform, *sources).runCheck(DeferredLinkStatus.NO_REFERRER)
        assertEquals(listOf(1, 1, 1), sources.map { it.calls.get() })
        assertTrue(platform.isDone())
        assertTrue(api.redeems.isEmpty())
    }

    @Test
    fun `one source that could not be asked keeps the check pending`() = runBlocking<Unit> {
        val platform = FakePlatform()
        launch(
            FakeApi(), platform,
            FakeSource(referrer("utm_source=google-play")),
            FakeSource(InstallReferrerResult.UNAVAILABLE_FOR_NOW),
            FakeSource(InstallReferrerResult.UNAVAILABLE),
        ).runCheck(DeferredLinkStatus.PENDING)
        assertFalse(platform.isDone())
        assertEquals(listOf("attempts=1"), platform.writes.toList())
    }

    @Test
    fun `asks the sources in order and stops at the first id`() = runBlocking<Unit> {
        val api = FakeApi()
        val order = CopyOnWriteArrayList<String>()
        val play = FakeSource(InstallReferrerResult.UNAVAILABLE).apply { onAsked = { order += "play" } }
        val galaxy = FakeSource(InstallReferrerResult.UNAVAILABLE_FOR_NOW).apply { onAsked = { order += "galaxy" } }
        val appGallery = FakeSource(referrer()).apply { onAsked = { order += "appgallery" } }
        val never = FakeSource(referrer("strybok_click=dc_${"z".repeat(32)}")).apply { onAsked = { order += "never" } }

        launch(api, FakePlatform(), play, galaxy, appGallery, never).runCheck(DeferredLinkStatus.DELIVERED)

        assertEquals(listOf("play", "galaxy", "appgallery"), order.toList())
        assertEquals(CLICK_ID, api.redeems.single().json.getString("clickId"))
    }

    @Test
    fun `a source that throws counts as unavailable for now`() = runBlocking<Unit> {
        val platform = FakePlatform()
        val throwing = InstallReferrerSource { throw IllegalStateException("referrer=strybok_click=$CLICK_ID") }
        val missingClass = InstallReferrerSource { throw NoClassDefFoundError("com/vendor/Client") }
        launch(FakeApi(), platform, throwing, missingClass).runCheck(DeferredLinkStatus.PENDING)
        assertFalse(platform.isDone())
    }

    @Test
    fun `a source that takes too long is abandoned and interrupted`() = runBlocking<Unit> {
        val platform = FakePlatform()
        val interrupted = CountDownLatch(1)
        val slow = InstallReferrerSource {
            try {
                hang()
            } catch (error: InterruptedException) {
                interrupted.countDown()
                throw error
            }
        }
        val next = FakeSource(InstallReferrerResult.UNAVAILABLE)
        launch(FakeApi(), platform, slow, next, sourceTimeoutMillis = 50).runCheck(DeferredLinkStatus.PENDING)
        assertTrue(interrupted.await(2, TimeUnit.SECONDS))
        assertEquals("the next source is still asked", 1, next.calls.get())
        assertFalse(platform.isDone())
    }

    @Test
    fun `sources are asked off the calling thread`() = runBlocking<Unit> {
        val caller = Thread.currentThread()
        val thread = arrayOfNulls<Thread>(1)
        val source = InstallReferrerSource {
            thread[0] = Thread.currentThread()
            InstallReferrerResult.UNAVAILABLE
        }
        launch(FakeApi(), FakePlatform(), source).runCheck(DeferredLinkStatus.UNAVAILABLE)
        assertTrue(thread[0] != null && thread[0] !== caller)
    }

    // --- a normal link wins ------------------------------------------------------------

    @Test
    fun `a launch link of this app supersedes the deferred link without a request`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform()
        val source = FakeSource(referrer())
        val strybok = launch(api, platform, source)
        val received = strybok.listen()

        assertTrue(strybok.startWithUrl(INVITE))
        strybok.awaitStatus(DeferredLinkStatus.SUPERSEDED)
        settle()

        assertTrue(api.redeems.isEmpty())
        assertEquals(listOf(INVITE), received.map { it.url })
        assertFalse(received.single().isDeferred)
        assertTrue(platform.isDone())
        assertEquals(1, source.calls.get())
    }

    @Test
    fun `a link that arrives while the request is in flight supersedes the deferred link`() = runBlocking<Unit> {
        val platform = FakePlatform()
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = FakeApi()
        api.onRedeem = {
            inFlight.countDown()
            release.await()
            redeemed()
        }
        val strybok = launch(api, platform, FakeSource(referrer()))
        val received = strybok.listen()
        strybok.startWithUrl(null)
        assertTrue(inFlight.await(2, TimeUnit.SECONDS))

        assertTrue(strybok.handleUrl(INVITE))
        eventually("the normal link") { received.size == 1 }
        release.countDown()
        strybok.awaitStatus(DeferredLinkStatus.SUPERSEDED)
        settle()

        assertEquals(listOf(INVITE), received.map { it.url })
        assertTrue(platform.isDone())
        assertEquals(1, api.redeems.size)
        assertTrue(DeferredLinkStatus.SUPERSEDED.isFinal)
    }

    @Test
    fun `a normal link that fails to resolve still supersedes`() = runBlocking<Unit> {
        val api = FakeApi { StrybokHttpResponse(500, "") }
        val platform = FakePlatform()
        val failures = CopyOnWriteArrayList<StrybokException>()
        val strybok = Strybok(
            StrybokConfig(
                apiBaseUrl = API, allowedHosts = setOf(HOST), maxRetries = 0, transport = api,
                onError = { failures += it }, callbackExecutor = directExecutor, deferredLinks = true,
                installReferrerSources = listOf(FakeSource(referrer())),
            ),
            null, null, platform,
        ).also { instances += it }
        strybok.startWithUrl(INVITE)
        strybok.awaitStatus(DeferredLinkStatus.SUPERSEDED)
        assertTrue(api.redeems.isEmpty())
        assertEquals("only the normal link's own error", 1, failures.size)
    }

    @Test
    fun `a link of another app does not supersede`() = runBlocking<Unit> {
        val api = FakeApi()
        val strybok = launch(api, FakePlatform(), FakeSource(referrer()))
        val received = strybok.listen()
        assertFalse(strybok.startWithUrl("https://elsewhere.example.com/invite777"))
        strybok.awaitStatus(DeferredLinkStatus.DELIVERED)
        eventually("the link") { received.size == 1 }
        assertTrue(received.single().isDeferred)
    }

    // --- answers of the service ----------------------------------------------------------

    @Test
    fun `unusable 200 answers fail the check and deliver nothing`() = runBlocking<Unit> {
        val unusable = listOf(
            "not json",
            "[]",
            // A link on a host that is not ours.
            """{"linkId":"l","appId":"a","routePath":"/x","url":"https://evil.example.com/promo42","deferred":true}""",
            // Not a link at all.
            """{"linkId":"l","appId":"a","routePath":"/x","url":"https://$HOST/a/b","deferred":true}""",
            """{"linkId":"l","appId":"a","routePath":"/x","deferred":true}""",
            // Not marked as deferred.
            """{"linkId":"l","appId":"a","routePath":"/x","url":"https://$HOST/promo42"}""",
            """{"linkId":"l","appId":"a","routePath":"/x","url":"https://$HOST/promo42","deferred":"true"}""",
            // Not valid as for resolving.
            """{"linkId":"l","appId":"a","routePath":"//evil.example.com","url":"https://$HOST/promo42","deferred":true}""",
            """{"appId":"a","routePath":"/x","url":"https://$HOST/promo42","deferred":true}""",
        )
        for (body in unusable) {
            val api = FakeApi()
            api.onRedeem = { StrybokHttpResponse(200, body) }
            val platform = FakePlatform()
            val strybok = launch(api, platform, FakeSource(referrer()))
            val received = strybok.listen()
            strybok.runCheck(DeferredLinkStatus.FAILED)
            settle()
            assertTrue(body, received.isEmpty())
            assertTrue(body, platform.isDone())
            assertEquals(body, 1, api.redeems.size)
            strybok.stop()
        }
        assertTrue(DeferredLinkStatus.FAILED.isFinal)
    }

    @Test
    fun `unknown fields of the answer are ignored`() = runBlocking<Unit> {
        val api = FakeApi()
        api.onRedeem = {
            StrybokHttpResponse(
                200,
                """{"linkId":"l","appId":"a","routePath":"/x","url":"https://$HOST/promo42","deferred":true,"later":{"a":1}}""",
            )
        }
        val strybok = launch(api, FakePlatform(), FakeSource(referrer()))
        val received = strybok.listen()
        strybok.runCheck(DeferredLinkStatus.DELIVERED)
        eventually("the link") { received.size == 1 }
        assertTrue(received.single().payload.isEmpty())
        assertTrue(received.single().campaign.isEmpty)
    }

    @Test
    fun `a refused id is not found and is not asked for again`() = runBlocking<Unit> {
        for (status in listOf(404, 400, 401, 403, 410, 413, 422)) {
            val api = FakeApi()
            api.onRedeem = { StrybokHttpResponse(status, """{"error":{"code":"DEFERRED_LINK_NOT_FOUND"}}""") }
            val platform = FakePlatform()
            val strybok = launch(api, platform, FakeSource(referrer()))
            strybok.runCheck(DeferredLinkStatus.NOT_FOUND)
            assertTrue("$status", platform.isDone())
            assertEquals("$status is not retried", 1, api.redeems.size)
            strybok.stop()
        }
        assertTrue(DeferredLinkStatus.NOT_FOUND.isFinal)
    }

    @Test
    fun `429 is not retried in this launch and keeps the check pending`() = runBlocking<Unit> {
        val api = FakeApi()
        api.onRedeem = { StrybokHttpResponse(429, "", mapOf("Retry-After" to "1")) }
        val platform = FakePlatform()
        launch(api, platform, FakeSource(referrer())).runCheck(DeferredLinkStatus.PENDING)
        settle()
        assertEquals(1, api.redeems.size)
        assertFalse(platform.isDone())
    }

    @Test
    fun `5xx, network failures and timeouts are retried with the same body, then pending`() = runBlocking<Unit> {
        val failures = listOf<(StrybokHttpRequest) -> StrybokHttpResponse>(
            { StrybokHttpResponse(503, "") },
            { throw IOException("offline; clickId=$CLICK_ID") },
            { throw SocketTimeoutException("slow") },
        )
        for (failure in failures) {
            val api = FakeApi()
            api.onRedeem = failure
            val platform = FakePlatform()
            val strybok = launch(api, platform, FakeSource(referrer()), maxRetries = 2)
            strybok.runCheck(DeferredLinkStatus.PENDING)
            assertEquals(3, api.redeems.size)
            assertEquals(1, api.redeems.map { it.body }.toSet().size)
            assertFalse(platform.isDone())
            strybok.stop()
        }
    }

    @Test
    fun `no answer within the timeout is retried, then pending`() = runBlocking<Unit> {
        val api = FakeApi()
        api.onRedeem = { hang() }
        val platform = FakePlatform()
        launch(api, platform, FakeSource(referrer()), maxRetries = 1, timeoutMillis = 40)
            .runCheck(DeferredLinkStatus.PENDING)
        assertEquals(2, api.redeems.size)
        assertFalse(platform.isDone())
    }

    @Test
    fun `an answer that is neither success nor refusal keeps the check pending`() = runBlocking<Unit> {
        val api = FakeApi()
        api.onRedeem = { StrybokHttpResponse(302, "") }
        val platform = FakePlatform()
        launch(api, platform, FakeSource(referrer())).runCheck(DeferredLinkStatus.PENDING)
        assertFalse(platform.isDone())
    }

    @Test
    fun `a retry that succeeds delivers`() = runBlocking<Unit> {
        val api = FakeApi()
        val calls = AtomicInteger()
        api.onRedeem = { if (calls.incrementAndGet() == 1) StrybokHttpResponse(502, "") else redeemed() }
        val strybok = launch(api, FakePlatform(), FakeSource(referrer()))
        val received = strybok.listen()
        strybok.runCheck(DeferredLinkStatus.DELIVERED)
        eventually("the link") { received.size == 1 }
        assertEquals(api.redeems[0].body, api.redeems[1].body)
    }

    @Test
    fun `a pending launch is followed by a launch that delivers`() = runBlocking<Unit> {
        val api = FakeApi()
        api.onRedeem = { StrybokHttpResponse(503, "") }
        val platform = FakePlatform()
        launch(api, platform, FakeSource(referrer()), maxRetries = 0).runCheck(DeferredLinkStatus.PENDING).stop()

        api.onRedeem = null
        val second = launch(api, platform, FakeSource(referrer()))
        val received = second.listen()
        second.runCheck(DeferredLinkStatus.DELIVERED)
        eventually("the link") { received.size == 1 }
        assertEquals(listOf("attempts=1", "attempts=2", "done"), platform.writes.toList())
    }

    // --- switching on later, status, stop ----------------------------------------------------

    @Test
    fun `enableDeferredLinks after start runs the check once`() = runBlocking<Unit> {
        val api = FakeApi()
        val platform = FakePlatform()
        val source = FakeSource(referrer())
        val strybok = launch(api, platform, source, deferredLinks = false)
        val received = strybok.listen()
        strybok.startWithUrl(null)
        strybok.awaitStarted()
        settle()
        assertEquals(0, source.calls.get())

        strybok.enableDeferredLinks()
        strybok.enableDeferredLinks()
        strybok.awaitStatus(DeferredLinkStatus.DELIVERED)
        eventually("the link") { received.size == 1 }
        strybok.enableDeferredLinks()
        settle()

        assertEquals(1, source.calls.get())
        assertEquals(1, api.redeems.size)
        assertEquals(1, received.size)
    }

    @Test
    fun `enableDeferredLinks before start waits for start`() = runBlocking<Unit> {
        val source = FakeSource(referrer())
        val strybok = launch(FakeApi(), FakePlatform(), source, deferredLinks = false)
        strybok.enableDeferredLinks()
        assertEquals(DeferredLinkStatus.CHECKING, strybok.deferredLinkStatus)
        settle()
        assertEquals(0, source.calls.get())

        strybok.runCheck(DeferredLinkStatus.DELIVERED)
        assertEquals(1, source.calls.get())
    }

    @Test
    fun `calling start again does not run the check again`() = runBlocking<Unit> {
        val api = FakeApi()
        val source = FakeSource(referrer())
        val strybok = launch(api, FakePlatform(), source)
        strybok.runCheck(DeferredLinkStatus.DELIVERED)
        strybok.startWithUrl(null)
        settle()
        assertEquals(1, source.calls.get())
        assertEquals(1, api.redeems.size)
    }

    @Test
    fun `the status can be observed as a flow and with a listener`() = runBlocking<Unit> {
        val strybok = launch(FakeApi(), FakePlatform(), FakeSource(referrer()))
        val seen = CopyOnWriteArrayList<DeferredLinkStatus>()
        val removed = CopyOnWriteArrayList<DeferredLinkStatus>()
        val listener = StrybokDeferredLinkStatusListener { seen += it }
        val other = StrybokDeferredLinkStatusListener { removed += it }
        strybok.addDeferredLinkStatusListener(listener)
        strybok.addDeferredLinkStatusListener(listener)
        strybok.addDeferredLinkStatusListener(other)
        eventually("the current status") { seen.size == 1 && removed.size == 1 }
        strybok.removeDeferredLinkStatusListener(other)
        assertEquals(DeferredLinkStatus.CHECKING, strybok.deferredLinkStatuses.value)

        strybok.runCheck(DeferredLinkStatus.DELIVERED)
        eventually("the listener") { seen.lastOrNull() == DeferredLinkStatus.DELIVERED }
        assertEquals(listOf(DeferredLinkStatus.CHECKING, DeferredLinkStatus.DELIVERED), seen.toList())
        assertEquals(listOf(DeferredLinkStatus.CHECKING), removed.toList())
        assertEquals(DeferredLinkStatus.DELIVERED, strybok.deferredLinkStatuses.value)
    }

    @Test
    fun `nothing is delivered after stop`() = runBlocking<Unit> {
        val inFlight = CountDownLatch(1)
        val api = FakeApi()
        api.onRedeem = {
            inFlight.countDown()
            hang()
        }
        val platform = FakePlatform()
        val strybok = launch(api, platform, FakeSource(referrer()))
        val received = strybok.listen()
        strybok.startWithUrl(null)
        assertTrue(inFlight.await(2, TimeUnit.SECONDS))
        strybok.stop()
        settle()
        assertTrue(received.isEmpty())
        assertFalse(platform.isDone())
        strybok.enableDeferredLinks() // Harmless after stop.
    }

    // --- nothing sensitive leaves the check -----------------------------------------------------

    @Test
    fun `neither the referrer nor the click id shows up in what the SDK prints`() = runBlocking<Unit> {
        val secret = "strybok_click=$CLICK_ID&utm_source=private"
        assertFalse(InstallReferrerResult.referrer(secret).toString().contains("strybok"))
        assertFalse(InstallReferrerResult.referrer(secret).toString().contains(CLICK_ID))
        assertEquals(secret, InstallReferrerResult.referrer(secret).referrer)
        assertNull(InstallReferrerResult.UNAVAILABLE.referrer)
        assertTrue(InstallReferrerResult.UNAVAILABLE.isUnavailable)
        assertTrue(InstallReferrerResult.UNAVAILABLE_FOR_NOW.isUnavailableForNow)

        val api = FakeApi()
        val strybok = launch(api, FakePlatform(), FakeSource(referrer(secret)))
        strybok.runCheck(DeferredLinkStatus.DELIVERED)
        val request = api.redeems.single()
        assertFalse(request.toString().contains(CLICK_ID))
        assertFalse("only the id is sent, not the rest of the referrer", request.body!!.contains("private"))
        assertFalse(request.url.contains(CLICK_ID))
        assertFalse(request.headers.values.any { it.contains(CLICK_ID) })
        for (status in DeferredLinkStatus.values()) assertFalse(status.toString().contains("dc_"))
    }

    // --- finding the click id ------------------------------------------------------------------

    @Test
    fun `finds the click id in a referrer`() {
        assertEquals(CLICK_ID, findClickId("strybok_click=$CLICK_ID"))
        assertEquals(CLICK_ID, findClickId("utm_source=x&strybok_click=$CLICK_ID&utm_medium=y"))
        assertEquals(CLICK_ID, findClickId("gclid=abc=def&&strybok_click=$CLICK_ID&"))
        // The first occurrence counts.
        val other = "dc_" + "Z".repeat(32)
        assertEquals(CLICK_ID, findClickId("strybok_click=$CLICK_ID&strybok_click=$other"))
        assertNull(findClickId("strybok_click=bad&strybok_click=$CLICK_ID"))
    }

    @Test
    fun `a missing or malformed click id is no id`() {
        val malformed = listOf(
            "",
            "&",
            "=",
            "utm_source=google-play&utm_medium=organic",
            "strybok_click",
            "strybok_click=",
            "strybok_click=dc_",
            "strybok_click=${CLICK_ID.dropLast(1)}",
            "strybok_click=${CLICK_ID}0",
            "strybok_click=${CLICK_ID.dropLast(1)}-",
            "strybok_click=${CLICK_ID.dropLast(1)}%41",
            "strybok_click=DC_${CLICK_ID.drop(3)}",
            "strybok_click= $CLICK_ID",
            "Strybok_Click=$CLICK_ID",
            "xstrybok_click=$CLICK_ID",
            "strybok_click_2=$CLICK_ID",
            "other=strybok_click=$CLICK_ID",
            " strybok_click=$CLICK_ID",
            "strybok_click=$CLICK_ID;x=1",
        )
        for (referrer in malformed) assertNull(referrer, findClickId(referrer))
    }

    @Test
    fun `decodes a referrer that came back still encoded, once`() {
        assertEquals(CLICK_ID, findClickId("strybok_click%3D$CLICK_ID"))
        assertEquals(CLICK_ID, findClickId("strybok_click%3d$CLICK_ID%26utm_source%3Dgoogle"))
        assertEquals(CLICK_ID, findClickId("utm_source%3Dx%26strybok_click%3D$CLICK_ID"))
        // The key itself is compared exactly, also after decoding.
        assertNull(findClickId("STRYBOK_CLICK%3D$CLICK_ID"))
        // A malformed escape elsewhere does not stop the decoding.
        assertEquals(CLICK_ID, findClickId("a%3D%zz%26strybok_click%3D$CLICK_ID%26b%3D%"))
        // Encoded twice stays encoded after one round.
        assertNull(findClickId("strybok_click%253D$CLICK_ID"))
        // The key is there in plain text with a bad value: the encoded copy is not looked at.
        assertNull(findClickId("strybok_click=bad&x=strybok_click%3D$CLICK_ID"))
        // After decoding the key is part of another value.
        assertNull(findClickId("a=strybok_click%3D$CLICK_ID"))
        assertNull(findClickId("strybok_click%3Dbad"))
        // `+` is not a space here, and decoded bytes that are not UTF-8 do no harm.
        assertNull(findClickId("%ff%fe+strybok_click%3D$CLICK_ID"))
    }
}
