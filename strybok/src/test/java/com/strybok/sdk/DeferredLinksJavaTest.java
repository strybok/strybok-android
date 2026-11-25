package com.strybok.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Test;

/** Deferred links as a Java caller sees them: builder, source lambda, results, status. */
public class DeferredLinksJavaTest {
    @Test
    public void theBuilderTakesTheSwitchAndTheSources() {
        InstallReferrerSource source = () -> InstallReferrerResult.referrer("utm_source=google-play");
        InstallReferrerSource other = () -> InstallReferrerResult.UNAVAILABLE;
        StrybokConfig config =
            new StrybokConfig.Builder("https://api.strybok.com", Collections.singleton("my-app.strybok.link"))
                .deferredLinks(true)
                .installReferrerSources(Arrays.asList(source, other))
                .build();

        assertTrue(config.getDeferredLinks());
        assertEquals(Arrays.asList(source, other), config.getInstallReferrerSources());

        StrybokConfig defaults =
            new StrybokConfig.Builder("https://api.strybok.com", Collections.singleton("my-app.strybok.link")).build();
        assertFalse(defaults.getDeferredLinks());
        assertTrue(defaults.getInstallReferrerSources().isEmpty());
    }

    @Test
    public void theThreeAnswersOfASource() {
        InstallReferrerResult referrer = InstallReferrerResult.referrer("");
        assertEquals("", referrer.getReferrer());
        assertFalse(referrer.isUnavailable());
        assertFalse(referrer.isUnavailableForNow());
        assertNull(InstallReferrerResult.UNAVAILABLE_FOR_NOW.getReferrer());
        assertTrue(InstallReferrerResult.UNAVAILABLE_FOR_NOW.isUnavailableForNow());
        assertTrue(InstallReferrerResult.UNAVAILABLE.isUnavailable());
    }

    @Test
    public void theStatusIsReadWithAGetterAndAListener() {
        Strybok strybok = new Strybok(
            new StrybokConfig.Builder("https://api.strybok.com", Collections.singleton("my-app.strybok.link"))
                .callbackExecutor(Runnable::run)
                .build());
        try {
            assertEquals(DeferredLinkStatus.DISABLED, strybok.getDeferredLinkStatus());
            assertFalse(strybok.getDeferredLinkStatus().isFinal());
            assertTrue(DeferredLinkStatus.NOT_FOUND.isFinal());

            List<DeferredLinkStatus> seen = new CopyOnWriteArrayList<>();
            StrybokDeferredLinkStatusListener listener = seen::add;
            strybok.addDeferredLinkStatusListener(listener);
            strybok.removeDeferredLinkStatusListener(listener);
        } finally {
            strybok.stop();
        }
    }

    @Test
    public void aLinkSaysWhetherItIsDeferred() {
        ResolvedDeepLink normal =
            new ResolvedDeepLink("https://my-app.strybok.link/a", "link_1", "app_1", "/x", Collections.emptyMap());
        ResolvedDeepLink deferred = new ResolvedDeepLink(
            "https://my-app.strybok.link/a", "link_1", "app_1", "/x", Collections.emptyMap(), LinkCampaign.EMPTY, true);
        assertFalse(normal.isDeferred());
        assertTrue(deferred.isDeferred());
        assertFalse(normal.equals(deferred));
    }
}
