package com.strybok.sdk

import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Links without an API call (SDK contract, "Links without an API call"; decision 0035): every
 * case of the shared test vectors, through the launch link, a later link and `resolve`, and the
 * behaviour around them.
 */
class ParameterLinksTest {
    private val instances = ArrayList<Strybok>()

    @After
    fun tearDown() {
        instances.forEach { it.stop() }
    }

    /** A byte-identical copy of the shared file of the SDK contract. */
    private val vectors = JSONObject(
        ParameterLinksTest::class.java.getResource("/parameter-links-vectors.json")!!.readText(Charsets.UTF_8),
    )

    private val hosts: Set<String> = strings(vectors.getJSONArray("allowedHosts")).toSet()

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)

    private fun optionOf(json: Any?): ParameterLinks? =
        (json as? JSONObject)?.let { ParameterLinks(strings(it.getJSONArray("payloadKeys"))) }

    /** The resolve request for [lookup], with `url` encoded the way the SDK always encoded it. */
    private fun resolveUrlFor(lookup: String): String =
        "$API/v1/public/resolve?url=${URLEncoder.encode(lookup, "UTF-8")}"

    private fun sdk(
        api: StrybokTransport,
        parameterLinks: ParameterLinks? = KEYS,
        errors: MutableList<StrybokException>? = null,
        clock: (() -> Long)? = null,
    ): Strybok = Strybok(
        StrybokConfig(
            apiBaseUrl = API,
            allowedHosts = hosts,
            retryDelayMillis = 1,
            onError = errors?.let { list -> StrybokErrorListener { list += it } },
            transport = api,
            callbackExecutor = directExecutor,
            parameterLinks = parameterLinks,
        ),
        clock,
    ).also { instances += it }

    private fun Strybok.listen(): MutableList<ResolvedDeepLink> {
        val received = CopyOnWriteArrayList<ResolvedDeepLink>()
        addLinkListener { received += it }
        return received
    }

    private fun answer(
        linkId: String = "plk_paths",
        routePath: String = "/from-the-server",
        payload: String = """{"id":"server"}""",
        campaign: String? = null,
    ): StrybokHttpResponse = StrybokHttpResponse(
        200,
        """{"linkId":"$linkId","appId":"app_1","routePath":"$routePath","payload":$payload""" +
            (campaign?.let { ""","campaign":$it""" } ?: "") + "}",
    )

    // --- the shared vectors -------------------------------------------------------------

    @Test
    fun `every lookup of the shared vectors`() = runBlocking<Unit> {
        val lookups = vectors.getJSONArray("lookups")
        val notOurs = ArrayList<FakeApi>()
        for (index in 0 until lookups.length()) {
            val vector = lookups.getJSONObject(index)
            val name = vector.getString("name")
            val url = vector.getString("url")
            val expected = vector.opt("lookup") as? String
            val option = optionOf(vector.opt("parameterLinks"))
            assertEquals(name, expected, parseStrybokLink(url, hosts, option)?.lookup)

            val errors = CopyOnWriteArrayList<StrybokException>()
            // The link that launched the app.
            val launchApi = FakeApi { answer() }
            val launch = sdk(launchApi, option, errors)
            assertEquals(name, expected != null, launch.startWithUrl(url))
            launch.awaitStarted()
            // A link that arrives while the app runs.
            val runningApi = FakeApi { answer() }
            val running = sdk(runningApi, option, errors)
            running.startWithUrl(null)
            assertEquals(name, expected != null, running.handleUrl(url))
            // The explicit call.
            val manualApi = FakeApi { answer() }
            val manual = sdk(manualApi, option, errors)
            assertEquals(name, expected != null, manual.isStrybokUrl(url))

            if (expected == null) {
                assertEquals(name, url, failsWith<UnsupportedLinkException> { manual.resolve(url) }.url)
                assertNull(name, launch.getInitialLink())
                notOurs += listOf(launchApi, runningApi, manualApi)
            } else {
                assertEquals(name, url, manual.resolve(url).url)
                assertEquals(name, url, launch.getInitialLink()?.url)
                eventually("$name: the later link") { runningApi.resolves.size == 1 }
                for (api in listOf(launchApi, runningApi, manualApi)) {
                    val request = api.resolves.single()
                    assertEquals(name, resolveUrlFor(expected), request.url)
                    assertEquals(name, expected, request.lookup)
                    assertEquals(name, "GET", request.method)
                    assertEquals(name, mapOf("Accept" to "application/json"), request.headers)
                    assertNull(name, request.body)
                }
            }
            assertTrue(name, errors.isEmpty())
        }
        settle()
        assertTrue("a URL that is not ours sends no request", notOurs.all { it.resolves.isEmpty() })
        assertTrue(lookups.length() > 0)
    }

    @Test
    fun `every configuration of the shared vectors`() {
        val configurations = vectors.getJSONArray("configurations")
        for (index in 0 until configurations.length()) {
            val vector = configurations.getJSONObject(index)
            val name = vector.getString("name")
            val option = optionOf(vector.opt("parameterLinks"))
            val build = { StrybokConfig(API, hosts, parameterLinks = option) }
            val buildForJava = { StrybokConfig.Builder(API, hosts).parameterLinks(option).build() }
            if (vector.getBoolean("valid")) {
                assertEquals(name, option, build().parameterLinks)
                assertEquals(name, option, buildForJava().parameterLinks)
            } else {
                failsWith<IllegalArgumentException> { build() }
                failsWith<IllegalArgumentException> { buildForJava() }
            }
        }
        assertTrue(configurations.length() > 0)
    }

    // --- the option -----------------------------------------------------------------------

    @Test
    fun `is off by default`() {
        val config = StrybokConfig(API, hosts)
        assertNull(config.parameterLinks)
        val instance = Strybok(config).also { instances += it }
        assertFalse(instance.isStrybokUrl("https://$PARAMETER_HOST/product/42"))
        assertTrue(instance.isStrybokUrl("https://$PARAMETER_HOST/promo"))
    }

    @Test
    fun `an invalid option is an IllegalArgumentException that names the key`() {
        val error = failsWith<IllegalArgumentException> {
            StrybokConfig(API, hosts, parameterLinks = ParameterLinks(listOf("id", "utm_medium")))
        }
        assertTrue(error.message!!, error.message!!.startsWith("parameterLinks: "))
        assertTrue(error.message!!, "utm_medium" in error.message!!)
        @Suppress("UNCHECKED_CAST")
        val fromJava = listOf("id", null) as List<String>
        failsWith<IllegalArgumentException> { StrybokConfig(API, hosts, parameterLinks = ParameterLinks(fromJava)) }
    }

    @Test
    fun `ParameterLinks copies the keys and is a value`() {
        val keys = mutableListOf("id")
        val option = ParameterLinks(keys)
        keys += "lang"
        assertEquals(listOf("id"), option.payloadKeys)
        failsWith<UnsupportedOperationException> { (option.payloadKeys as MutableList<String>).add("x") }
        assertEquals(ParameterLinks(listOf("id")), option)
        assertEquals(ParameterLinks(listOf("id")).hashCode(), option.hashCode())
        assertNotEquals(ParameterLinks(listOf("lang", "id")), KEYS)
        assertEquals("ParameterLinks(payloadKeys: [id, lang])", KEYS.toString())
    }

    // --- delivery -------------------------------------------------------------------------

    @Test
    fun `the route and payload come from the answer, never from the tapped URL`() = runBlocking<Unit> {
        val api = FakeApi {
            answer(routePath = "/product/42", payload = """{"id":"7"}""", campaign = """{"source":"mail"}""")
        }
        val instance = sdk(api)
        val received = instance.listen()
        instance.startWithUrl(null)
        val tapped = "https://$PARAMETER_HOST/Product/42/?id=8&lang=uk&secret=x#top"
        assertTrue(instance.handleUrl(tapped))
        eventually("the link") { received.size == 1 }

        assertEquals("https://$PARAMETER_HOST/Product/42/?id=8&lang=uk", api.resolves.single().lookup)
        val link = received.single()
        assertEquals("/product/42", link.routePath)
        assertEquals(mapOf("id" to "7"), link.payload)
        assertEquals("plk_paths", link.linkId)
        assertEquals(LinkCampaign(source = "mail"), link.campaign)
        assertEquals(tapped, link.url)
        assertFalse(link.isDeferred)
    }

    @Test
    fun `a long link is delivered with the route and payload the server read`() = runBlocking<Unit> {
        val api = FakeApi { answer(routePath = "/p", payload = "{}") }
        val instance = sdk(api)
        val tapped = "https://go.example.com/?link=https%3A%2F%2Fexample.com%2Fother%3Fid%3D1&apn=com.example.app"
        assertTrue(instance.startWithUrl(tapped))
        val link = instance.getInitialLink()!!
        assertEquals("/p", link.routePath)
        assertTrue(link.payload.isEmpty())
        assertEquals(tapped, link.url)
    }

    @Test
    fun `app_open carries the linkId the server returned`() = runBlocking<Unit> {
        val api = FakeApi { answer(linkId = "plk_firebase", campaign = """{"source":"mail"}""") }
        val instance = sdk(api)
        instance.listen()
        instance.startWithUrl(null)
        instance.handleUrl("https://go.example.com/?link=https%3A%2F%2Fexample.com%2Fp&utm_source=newsletter")
        eventually("the event") { api.events.size == 1 }

        val event = api.events.single().json
        assertEquals(
            setOf("eventId", "linkId", "type", "platform", "sdkVersion", "campaign"),
            event.keys().asSequence().toSet(),
        )
        assertEquals("plk_firebase", event.getString("linkId"))
        assertEquals("app_open", event.getString("type"))
        assertEquals("0.5.0", event.getString("sdkVersion"))
        assertEquals("mail", event.getJSONObject("campaign").getString("source"))
    }

    @Test
    fun `an address that is no link is a LinkNotFoundException for onError`() = runBlocking<Unit> {
        val api = FakeApi { StrybokHttpResponse(404, """{"error":{"code":"LINK_NOT_FOUND"}}""") }
        val errors = CopyOnWriteArrayList<StrybokException>()
        val instance = sdk(api, errors = errors)
        instance.startWithUrl(null)
        assertTrue(instance.handleUrl("https://$PARAMETER_HOST/typo/1"))
        eventually("the error") { errors.size == 1 }
        assertTrue(errors.single() is LinkNotFoundException)
        assertEquals("https://$PARAMETER_HOST/typo/1", errors.single().url)
        failsWith<LinkNotFoundException> { instance.resolve("https://$PARAMETER_HOST/typo/1") }
    }

    @Test
    fun `duplicates are the same string as received, as before`() = runBlocking<Unit> {
        val now = AtomicLong(1_000_000)
        val api = FakeApi { answer() }
        val instance = sdk(api, clock = now::get)
        val received = instance.listen()
        instance.startWithUrl(null)

        assertTrue(instance.handleUrl("https://$PARAMETER_HOST/product/42?id=7"))
        assertFalse(instance.handleUrl("https://$PARAMETER_HOST/product/42?id=7"))
        // The same lookup from another string is another link.
        assertTrue(instance.handleUrl("https://$PARAMETER_HOST/product/42?id=7&x=1"))
        eventually("both links") { received.size == 2 }
        settle()
        assertEquals(2, received.size)
        assertEquals(1, api.resolves.map { it.url }.toSet().size)
    }

    @Test
    fun `a slug that is also a reserved directory name stays a slug`() = runBlocking<Unit> {
        val api = FakeApi { answer() }
        sdk(api).resolve("https://$PARAMETER_HOST/_strybok?id=7")
        assertEquals("https://$PARAMETER_HOST/_strybok?id=7", api.resolves.single().lookup)
    }

    @Test
    fun `without the option path links and long links are not ours and a slug sends what it sent`() =
        runBlocking<Unit> {
            val api = FakeApi { answer() }
            val errors = CopyOnWriteArrayList<StrybokException>()
            val instance = sdk(api, parameterLinks = null, errors = errors)
            instance.startWithUrl(null)
            for (url in listOf(
                "https://$PARAMETER_HOST/product/42?id=7",
                "https://go.example.com/?link=https%3A%2F%2Fexample.com%2Fp",
                "https://$PARAMETER_HOST?id=7",
                "https://$PARAMETER_HOST/",
            )) {
                assertFalse(url, instance.handleUrl(url))
                assertFalse(url, instance.isStrybokUrl(url))
                failsWith<UnsupportedLinkException> { instance.resolve(url) }
            }
            instance.resolve("https://$PARAMETER_HOST/promo?id=7&utm_source=a#f")
            assertEquals(
                "$API/v1/public/resolve?url=https%3A%2F%2F$PARAMETER_HOST%2Fpromo%3Futm_source%3Da",
                api.resolves.single().url,
            )
            assertTrue(errors.isEmpty())
        }

    // --- deferred links keep the slug rule -------------------------------------------------

    private fun deferredLaunch(api: FakeApi, platform: FakePlatform): Strybok = Strybok(
        StrybokConfig(
            apiBaseUrl = API,
            allowedHosts = hosts,
            retryDelayMillis = 1,
            transport = api,
            callbackExecutor = directExecutor,
            deferredLinks = true,
            installReferrerSources = listOf(FakeSource(referrer())),
            parameterLinks = KEYS,
        ),
        null,
        null,
        platform,
    ).also { instances += it }

    @Test
    fun `a deferred-link answer whose URL is not one slug segment is refused, with the option`() {
        for (url in listOf(
            "https://$PARAMETER_HOST/product/42",
            "https://$PARAMETER_HOST/",
            "https://$PARAMETER_HOST",
            "https://go.example.com/?link=https%3A%2F%2Fexample.com%2Fp",
        )) {
            val api = FakeApi().apply { onRedeem = { redeemed(url = url) } }
            val platform = FakePlatform()
            val instance = deferredLaunch(api, platform)
            val received = instance.listen()
            instance.startWithUrl(null)
            eventually("$url: failed") { instance.deferredLinkStatus == DeferredLinkStatus.FAILED }
            settle()
            assertTrue(url, received.isEmpty())
            assertEquals(url, 1, api.redeems.size)
            assertTrue(url, platform.isDone())
            instance.stop()
        }
    }

    @Test
    fun `a deferred-link answer with a slug is delivered, with the option`() {
        val api = FakeApi().apply { onRedeem = { redeemed(url = "https://$PARAMETER_HOST/promo42?id=7") } }
        val instance = deferredLaunch(api, FakePlatform())
        val received = instance.listen()
        instance.startWithUrl(null)
        eventually("delivered") { instance.deferredLinkStatus == DeferredLinkStatus.DELIVERED }
        eventually("the link") { received.size == 1 }
        assertTrue(received.single().isDeferred)
        assertEquals("/promo/42", received.single().routePath)
        assertTrue(api.resolves.isEmpty())
    }

    @Test
    fun `a path link that opened the app supersedes the deferred link`() = runBlocking<Unit> {
        val api = FakeApi { answer() }
        val instance = deferredLaunch(api, FakePlatform())
        assertTrue(instance.startWithUrl("https://$PARAMETER_HOST/product/42?id=7"))
        eventually("superseded") { instance.deferredLinkStatus == DeferredLinkStatus.SUPERSEDED }
        assertTrue(api.redeems.isEmpty())
        assertEquals(1, api.resolves.size)
    }

    // --- decoding and encoding --------------------------------------------------------------

    @Test
    fun `values are decoded strictly as UTF-8 and re-encoded byte by byte`() {
        assertEquals("a b+c", decodeQueryText("a+b%2Bc"))
        assertEquals("київ", decodeQueryText("%D0%BA%D0%B8%D1%97%D0%B2"))
        assertEquals("кий", decodeQueryText("кий"))
        assertEquals("😀", decodeQueryText("%F0%9F%98%80"))
        assertEquals("😀", decodeQueryText("😀"))
        assertEquals("﻿x", decodeQueryText("%EF%BB%BFx"))
        for (raw in listOf("%", "%4", "%zz", "%E0%A4", "%C0%AE", "%ED%A0%80", "%F4%90%80%80", "%FF", "a\uD800b")) {
            assertNull(raw, decodeQueryText(raw))
        }
        assertEquals("AZaz09-._~", encodeQueryValue("AZaz09-._~"))
        assertEquals("%20%2B%26%3D%2F%3F%23%25%27%28%29%21%2A", encodeQueryValue(" +&=/?#%'()!*"))
        assertEquals("%D0%BA%F0%9F%98%80%EF%BB%BF", encodeQueryValue("к😀﻿"))
    }

    @Test
    fun `raw characters, a byte-order mark and an apn without a value in requests`() = runBlocking<Unit> {
        for ((tapped, lookup) in mapOf(
            "https://$PARAMETER_HOST/city?id=київ" to "https://$PARAMETER_HOST/city?id=%D0%BA%D0%B8%D1%97%D0%B2",
            "https://$PARAMETER_HOST/p?id=%EF%BB%BF" to "https://$PARAMETER_HOST/p?id=%EF%BB%BF",
            "https://go.example.com/?link=https%3A%2F%2Fexample.com%2Fp&apn" to
                "https://go.example.com/?link=https%3A%2F%2Fexample.com%2Fp&apn=",
        )) {
            val api = FakeApi { answer() }
            sdk(api).resolve(tapped)
            assertEquals(tapped, lookup, api.resolves.single().lookup)
            assertEquals(tapped, resolveUrlFor(lookup), api.resolves.single().url)
        }
    }

    private companion object {
        const val PARAMETER_HOST = "myapp.strybok.link"
        val KEYS = ParameterLinks(listOf("id", "lang"))
    }
}
