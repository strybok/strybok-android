package com.strybok.sdk

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Campaign values (SDK contract, "Campaign query"; decision 0018). */
class CampaignTest {
    private val instances = ArrayList<Strybok>()

    @After
    fun tearDown() {
        instances.forEach { it.stop() }
    }

    private fun sdk(api: StrybokTransport, clock: (() -> Long)? = null): Strybok = Strybok(
        StrybokConfig(
            apiBaseUrl = API,
            allowedHosts = setOf(HOST),
            retryDelayMillis = 1,
            transport = api,
            callbackExecutor = directExecutor,
        ),
        clock,
    ).also { instances += it }

    private fun lookup(url: String): String = parseStrybokLink(url, setOf(HOST))!!.lookup

    private fun resolved(campaign: String?): StrybokHttpResponse = StrybokHttpResponse(
        200,
        """{"linkId":"link_1","appId":"app_1","routePath":"/x"${campaign?.let { ""","campaign":$it""" } ?: ""}}""",
    )

    // --- lookup ----------------------------------------------------------------

    @Test
    fun `the example of the contract`() {
        assertEquals(
            "https://myapp.strybok.link/invite777?utm_source=instagram",
            parseStrybokLink(
                "https://MyApp.strybok.link/invite777?utm_source=instagram&fbclid=abc&utm_medium=two%20words&utm_source=x#top",
                setOf("myapp.strybok.link"),
            )!!.lookup,
        )
    }

    @Test
    fun `without campaign parameters the lookup is what earlier versions sent`() {
        for (url in listOf(
            INVITE,
            "$INVITE?",
            "$INVITE#utm_source=fragment",
            "$INVITE?#utm_source=fragment",
            "$INVITE?token=secret&fbclid=abc&gclid=1&utm_id=7#f",
            "$INVITE?a=1#?utm_source=fragment",
            "$INVITE?utm_source=",
            "$INVITE?utm_source",
            "$INVITE?&&=&=x&",
        )) {
            assertEquals(url, INVITE, lookup(url))
        }
    }

    @Test
    fun `keeps the five keys in the fixed order and nothing else`() {
        assertEquals(
            "$INVITE?utm_source=s&utm_medium=m&utm_campaign=c&utm_term=t&utm_content=n",
            lookup("$INVITE?utm_content=n&x=1&utm_term=t&utm_campaign=c&token=secret&utm_medium=m&utm_source=s#utm_source=f"),
        )
        assertEquals("$INVITE?utm_medium=m&utm_content=n", lookup("$INVITE?utm_content=n&&utm_medium=m&"))
        assertEquals("$INVITE?utm_source=a", lookup("$INVITE?utm_source=a#b"))
    }

    @Test
    fun `key names are exact and lower case`() {
        for (query in listOf(
            "UTM_SOURCE=a",
            "Utm_Source=a",
            "utm_sourcex=a",
            "xutm_source=a",
            "utm_id=a",
            "utm_source%20=a",
            "+utm_source=a",
            "utm_source[]=a",
        )) {
            assertEquals(query, INVITE, lookup("$INVITE?$query"))
        }
    }

    @Test
    fun `the first occurrence of a key counts, even when its value is dropped`() {
        assertEquals("$INVITE?utm_source=first", lookup("$INVITE?utm_source=first&utm_source=second"))
        assertEquals(INVITE, lookup("$INVITE?utm_source=not%20valid&utm_source=second"))
        assertEquals(INVITE, lookup("$INVITE?utm_source=&utm_source=second"))
        assertEquals(INVITE, lookup("$INVITE?utm_source&utm_source=second"))
        assertEquals("$INVITE?utm_medium=m", lookup("$INVITE?utm_source=a%2Fb&utm_medium=m&utm_source=second"))
    }

    @Test
    fun `values outside the allowed characters or length are dropped`() {
        for (value in listOf(
            "two%20words",
            "two+words",
            "two words",
            "a@b.c",
            "a%40b.c",
            "a/b",
            "a%2Fb",
            "a=b",
            "a%26utm_medium=b",
            "a%3Db",
            "(other)",
            "a,b",
            "a:b",
            "a*b",
            "a!b",
            "a'b",
            "a\"b",
            "<script>",
            "кампанія",
            "%D0%BA%D0%B0",
            "caf%C3%A9",
            "a%00b",
            "a%0Ab",
            "a".repeat(65),
        )) {
            assertEquals(value, INVITE, lookup("$INVITE?utm_source=$value"))
        }
        val longest = "a".repeat(64)
        assertEquals("$INVITE?utm_source=$longest", lookup("$INVITE?utm_source=$longest"))
        assertEquals(
            "$INVITE?utm_source=AZaz09._~-",
            lookup("$INVITE?utm_source=AZaz09._~-"),
        )
    }

    @Test
    fun `percent-encoded keys and values are decoded`() {
        assertEquals("$INVITE?utm_source=insta-gram", lookup("$INVITE?utm_source=%69nsta%2Dgram"))
        assertEquals("$INVITE?utm_source=a._~b", lookup("$INVITE?utm_source=a%2e%5f%7Eb"))
        assertEquals("$INVITE?utm_source=x", lookup("$INVITE?utm%5Fsource=x"))
        assertEquals("$INVITE?utm_medium=x", lookup("$INVITE?%75tm_medium=x"))
        // 64 characters after decoding.
        val encoded = "%61".repeat(64)
        assertEquals("$INVITE?utm_term=${"a".repeat(64)}", lookup("$INVITE?utm_term=$encoded"))
        assertEquals(INVITE, lookup("$INVITE?utm_term=$encoded%61"))
    }

    @Test
    fun `malformed escapes and invalid UTF-8 drop the value and never throw`() {
        for (value in listOf(
            "%",
            "a%",
            "a%2",
            "%2",
            "%zz",
            "%G0",
            "%0G",
            "a%-1",
            "%+1",
            "%%",
            "%u0041",
            "%C3%28",
            "%FF",
            "%C0%AE",
            "%E2%82",
            "%ED%A0%80",
            "\uD800",
            "a\uDC00b",
        )) {
            assertEquals(value, INVITE, lookup("$INVITE?utm_source=$value"))
            assertEquals(value, "$INVITE?utm_medium=ok", lookup("$INVITE?utm_source=$value&utm_medium=ok"))
            assertEquals(value, "$INVITE?utm_medium=ok", lookup("$INVITE?$value=x&utm_medium=ok"))
        }
    }

    @Test
    fun `a query changes neither which URLs are ours nor the host and path that are used`() {
        val hosts = setOf(HOST)
        val query = "?utm_source=a&utm_medium=b"
        for (url in listOf(
            "http://$HOST/invite777",
            "https://evil.example.com/invite777",
            "https://user:pw@$HOST/invite777",
            "https://user@$HOST/invite777",
            "https://$HOST:8443/invite777",
            "https://$HOST/a/b",
            "https://$HOST/invite777/",
            "https://$HOST/",
            "https://$HOST",
            "https://$HOST/" + "a".repeat(65),
        )) {
            assertNull(url, parseStrybokLink(url, hosts))
            assertNull(url, parseStrybokLink(url + query, hosts))
        }
        // Nothing in a query can stand in for the authority or the path.
        for (url in listOf(
            "https://evil.example.com/x?utm_source=https://$HOST/invite777",
            "https://evil.example.com?utm_source=a@$HOST/invite777",
            "https://$HOST?utm_source=/invite777",
            "https://$HOST/?utm_source=a",
        )) {
            assertNull(url, parseStrybokLink(url, hosts))
        }
        val parsed = parseStrybokLink("HTTPS://My-App.STRYBOK.link:443/Invite777?utm_source=a&token=s#f", hosts)!!
        assertEquals(HOST, parsed.host)
        assertEquals("/Invite777", parsed.path)
        assertEquals("?utm_source=a", parsed.campaignQuery)
        assertEquals("https://$HOST/Invite777?utm_source=a", parsed.lookup)
    }

    // --- resolve ---------------------------------------------------------------

    @Test
    fun `resolve sends the campaign query and nothing else of the query`() = runBlocking<Unit> {
        val api = FakeApi()
        val url = "https://My-App.Strybok.Link/invite777?token=secret&utm_medium=social&utm_source=instagram&email=a%40b.c#private"
        val link = sdk(api).resolve(url)

        assertEquals(url, link.url)
        val request = api.resolves.single()
        assertEquals(
            "$API/v1/public/resolve?url=https%3A%2F%2F$HOST%2Finvite777%3Futm_source%3Dinstagram%26utm_medium%3Dsocial",
            request.url,
        )
        assertEquals("https://$HOST/invite777?utm_source=instagram&utm_medium=social", request.lookup)
        for (private in listOf("secret", "token", "email", "private", "b.c")) {
            assertFalse(private, request.url.contains(private))
        }
    }

    @Test
    fun `a URL without campaign parameters is requested exactly as before`() = runBlocking<Unit> {
        val api = FakeApi()
        val instance = sdk(api)
        instance.resolve(INVITE)
        instance.resolve("$INVITE?token=secret&utm_source=two%20words#f")
        val expected = "$API/v1/public/resolve?url=https%3A%2F%2F$HOST%2Finvite777"
        assertEquals(listOf(expected, expected), api.resolves.map { it.url })
    }

    @Test
    fun `campaign of the resolved link holds the five string fields the server sent`() = runBlocking<Unit> {
        val api = FakeApi {
            resolved("""{"source":"instagram","medium":"social","campaign":"spring","term":"shoes","content":"banner","extra":"x"}""")
        }
        val campaign = sdk(api).resolve(INVITE).campaign
        assertEquals(LinkCampaign("instagram", "social", "spring", "shoes", "banner"), campaign)
        assertEquals("instagram", campaign.source)
        assertEquals("social", campaign.medium)
        assertEquals("spring", campaign.campaign)
        assertEquals("shoes", campaign.term)
        assertEquals("banner", campaign.content)
        assertFalse(campaign.isEmpty)
    }

    @Test
    fun `campaign is empty, never null, when missing or not an object`() = runBlocking<Unit> {
        for (campaign in listOf(null, "null", "[]", "[\"a\"]", "\"instagram\"", "7", "true", "{}", """{"other":"x"}""")) {
            val link = sdk(FakeApi { resolved(campaign) }).resolve(INVITE)
            assertTrue(campaign, link.campaign.isEmpty)
            assertEquals(campaign, LinkCampaign.EMPTY, link.campaign)
            assertEquals("/x", link.routePath)
        }
    }

    @Test
    fun `campaign fields that are not strings are ignored, not errors`() = runBlocking<Unit> {
        val api = FakeApi {
            resolved("""{"source":7,"medium":null,"campaign":{"a":1},"term":["x"],"content":"banner","utm_source":"x"}""")
        }
        assertEquals(LinkCampaign(content = "banner"), sdk(api).resolve(INVITE).campaign)
    }

    @Test
    fun `links compare by their campaign too`() {
        val plain = ResolvedDeepLink(INVITE, "l", "a", "/x", emptyMap())
        assertSame(LinkCampaign.EMPTY, plain.campaign)
        assertEquals(plain, ResolvedDeepLink(INVITE, "l", "a", "/x", emptyMap(), LinkCampaign()))
        assertEquals(plain.hashCode(), ResolvedDeepLink(INVITE, "l", "a", "/x", emptyMap(), LinkCampaign()).hashCode())
        assertNotEquals(plain, ResolvedDeepLink(INVITE, "l", "a", "/x", emptyMap(), LinkCampaign(source = "s")))
        assertNotEquals(LinkCampaign(source = "s"), LinkCampaign(medium = "s"))
        assertEquals(
            "LinkCampaign(source: s, medium: null, campaign: null, term: null, content: null)",
            LinkCampaign(source = "s").toString(),
        )
    }

    // --- app_open --------------------------------------------------------------

    @Test
    fun `app_open reports the campaign exactly as resolve returned it`() = runBlocking<Unit> {
        // The server answers with other values than the URL has: the answer is what is reported.
        val api = FakeApi { resolved("""{"source":"newsletter","content":"footer","ignored":"x","term":5}""") }
        val instance = sdk(api)
        val received = CopyOnWriteArrayList<ResolvedDeepLink>()
        instance.addLinkListener { received += it }
        instance.startWithUrl(null)
        instance.handleUrl("$INVITE?utm_source=instagram&utm_medium=social")
        eventually("event") { api.events.size == 1 }

        val event = api.events.single().json
        assertEquals(
            setOf("eventId", "linkId", "type", "platform", "sdkVersion", "campaign"),
            event.keys().asSequence().toSet(),
        )
        val campaign = event.getJSONObject("campaign")
        assertEquals(setOf("source", "content"), campaign.keys().asSequence().toSet())
        assertEquals("newsletter", campaign.getString("source"))
        assertEquals("footer", campaign.getString("content"))
        assertEquals(LinkCampaign(source = "newsletter", content = "footer"), received.single().campaign)
    }

    @Test
    fun `app_open has no campaign when resolve returned none, whatever the URL says`() = runBlocking<Unit> {
        for (campaign in listOf(null, "{}", "null", "[]", """{"source":1}""")) {
            val api = FakeApi { resolved(campaign) }
            val instance = sdk(api)
            instance.addLinkListener { }
            instance.startWithUrl(null)
            instance.handleUrl("$INVITE?utm_source=instagram")
            eventually("event") { api.events.size == 1 }
            val event = api.events.single().json
            assertEquals(
                campaign,
                setOf("eventId", "linkId", "type", "platform", "sdkVersion"),
                event.keys().asSequence().toSet(),
            )
        }
    }

    // --- dedupe ----------------------------------------------------------------

    @Test
    fun `the same slug with different campaign parameters is two links`() = runBlocking<Unit> {
        val now = AtomicLong(1_000_000)
        val api = FakeApi()
        val instance = sdk(api, clock = now::get)
        val received = CopyOnWriteArrayList<ResolvedDeepLink>()
        instance.addLinkListener { received += it }
        instance.startWithUrl(null)

        assertTrue(instance.handleUrl("$INVITE?utm_source=a"))
        assertFalse("same URL string", instance.handleUrl("$INVITE?utm_source=a"))
        assertTrue(instance.handleUrl("$INVITE?utm_source=b"))
        assertTrue(instance.handleUrl(INVITE))
        // Compared as received: a parameter that is never sent still makes another URL.
        assertTrue(instance.handleUrl("$INVITE?fbclid=1"))
        assertFalse(instance.handleUrl("$INVITE?fbclid=1"))
        eventually("deliveries") { received.size == 4 }
        settle()
        assertEquals(4, received.size)
        assertEquals(
            setOf("$INVITE?utm_source=a", "$INVITE?utm_source=b", INVITE, "$INVITE?fbclid=1"),
            received.map { it.url }.toSet(),
        )
        assertEquals(
            listOf(INVITE, INVITE, "$INVITE?utm_source=a", "$INVITE?utm_source=b"),
            api.resolves.map { it.lookup }.sorted(),
        )
    }
}
