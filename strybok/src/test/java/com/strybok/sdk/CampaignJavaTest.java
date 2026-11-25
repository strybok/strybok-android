package com.strybok.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

/** Campaign values as a Java caller sees them: getters and the constructors without defaults. */
public class CampaignJavaTest {
    @Test
    public void aLinkBuiltWithoutCampaignHasAnEmptyOne() {
        ResolvedDeepLink link =
            new ResolvedDeepLink("https://my-app.strybok.link/a", "link_1", "app_1", "/x", Collections.emptyMap());
        assertSame(LinkCampaign.EMPTY, link.getCampaign());
        assertTrue(link.getCampaign().isEmpty());
        assertNull(link.getCampaign().getSource());
    }

    @Test
    public void campaignValuesAreReadWithGetters() {
        LinkCampaign campaign = new LinkCampaign("instagram", "social", "spring", null, null);
        ResolvedDeepLink link = new ResolvedDeepLink(
            "https://my-app.strybok.link/a", "link_1", "app_1", "/x", Collections.emptyMap(), campaign);
        assertEquals("instagram", link.getCampaign().getSource());
        assertEquals("social", link.getCampaign().getMedium());
        assertEquals("spring", link.getCampaign().getCampaign());
        assertNull(link.getCampaign().getTerm());
        assertNull(link.getCampaign().getContent());
        assertEquals(new LinkCampaign("instagram", "social", "spring"), campaign);
        assertTrue(new LinkCampaign().isEmpty());
    }
}
