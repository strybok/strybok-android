package com.strybok.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParserTest {
    // Created without a callback executor on purpose: the main-thread executor
    // is lazy, so nothing touches android.os.Looper here.
    private val sdk = Strybok(StrybokConfig(API, setOf("My-App.Strybok.Link")))

    @Test
    fun `accepts https links on an allowed host with a single slug`() {
        for (url in listOf(
            INVITE,
            "https://MY-APP.strybok.link/AbC_7-x",
            "HTTPS://$HOST/invite777",
            "https://$HOST/abc?utm=1#frag",
            "https://$HOST/abc#frag?not-a-query",
            "https://$HOST/abc?next=https://evil.example.com/a/b&x=a|b c",
            "https://$HOST:443/invite777",
            "https://$HOST/" + "a".repeat(64),
        )) {
            assertTrue(url, sdk.isStrybokUrl(url))
        }
    }

    @Test
    fun `rejects everything else`() {
        for (url in listOf(
            "http://$HOST/invite777",
            "myapp://$HOST/invite777",
            "https://evil.example.com/invite777",
            "https://$HOST.evil.example.com/invite777",
            "https://evil-$HOST/invite777",
            "https://user:pw@$HOST/invite777",
            "https://user@$HOST/invite777",
            "https://$HOST:8443/invite777",
            "https://$HOST:/invite777",
            "https://$HOST/",
            "https://$HOST",
            "https://$HOST?x=/invite777",
            "https://$HOST/a/b",
            "https://$HOST/invite777/",
            "https://$HOST/.well-known/assetlinks.json",
            "https://$HOST/a%2Fb",
            "https://$HOST/" + "a".repeat(65),
            "https://evil.example.com\\@$HOST/invite777",
            "https://evil.example.com/x?https://$HOST/invite777",
            "https://evil.example.com#@$HOST/invite777",
            " https://$HOST/invite777",
            "https://$HOST/invite777\n",
            "https:/$HOST/invite777",
            "$HOST/invite777",
            "not a url",
            "",
        )) {
            assertFalse(url, sdk.isStrybokUrl(url))
        }
        assertFalse(sdk.isStrybokUrl(null))
    }

    @Test
    fun `lookup parts are the lower-cased host and the slug only`() {
        val parsed = parseStrybokLink("HTTPS://My-App.STRYBOK.link:443/Invite777?token=s#f", setOf(HOST))!!
        assertEquals(HOST, parsed.host)
        assertEquals("/Invite777", parsed.path)
    }

    @Test
    fun `config lower-cases hosts, trims the base URL and validates values`() {
        val config = StrybokConfig("https://example.com/strybok/", setOf("My-App.Strybok.Link"))
        assertEquals("https://example.com/strybok", config.apiBaseUrl)
        assertEquals(setOf(HOST), config.allowedHosts)
        assertEquals(5_000L, config.timeoutMillis)
        assertEquals(2, config.maxRetries)
        assertEquals(400L, config.retryDelayMillis)
        assertEquals(2_000L, config.dedupeWindowMillis)
        assertTrue(config.reportAppOpens)

        failsWith<IllegalArgumentException> { StrybokConfig("api.strybok.com", setOf(HOST)) }
        failsWith<IllegalArgumentException> { StrybokConfig(API, emptySet()) }
        failsWith<IllegalArgumentException> { StrybokConfig(API, setOf(HOST), maxRetries = -1) }
        failsWith<IllegalArgumentException> { StrybokConfig(API, setOf(HOST), timeoutMillis = 0) }
    }

    @Test
    fun `builder produces the same configuration for Java callers`() {
        val transport = FakeApi()
        val config = StrybokConfig.Builder(API, setOf(HOST))
            .timeoutMillis(1_000)
            .maxRetries(0)
            .retryDelayMillis(10)
            .dedupeWindowMillis(500)
            .reportAppOpens(false)
            .transport(transport)
            .callbackExecutor(directExecutor)
            .build()
        assertEquals(1_000L, config.timeoutMillis)
        assertEquals(0, config.maxRetries)
        assertEquals(10L, config.retryDelayMillis)
        assertEquals(500L, config.dedupeWindowMillis)
        assertFalse(config.reportAppOpens)
        assertTrue(config.transport === transport)
        assertTrue(config.callbackExecutor === directExecutor)
    }

    @Test
    fun `only fresh VIEW intents carry a link`() {
        val view = "android.intent.action.VIEW"
        val history = 0x00100000
        fun url(action: String?, data: String?, flags: Int) =
            linkUrlFromIntentParts(action, data, flags, viewAction = view, launchedFromHistoryFlag = history)

        assertEquals(INVITE, url(view, INVITE, 0))
        assertEquals(INVITE, url(view, INVITE, 0x10000000)) // FLAG_ACTIVITY_NEW_TASK
        assertNull(url("android.intent.action.MAIN", INVITE, 0))
        assertNull(url(null, INVITE, 0))
        assertNull(url(view, null, 0))
        // Re-launched from the recents screen with the original intent.
        assertNull(url(view, INVITE, 0x10000000 or history))
    }
}
