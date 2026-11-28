package com.strybok.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import org.junit.Test;

/** The parameterLinks option as a Java caller sets it: the builder and the getters. */
public class ParameterLinksJavaTest {
    private static final String API = "https://api.strybok.com";
    private static final Set<String> HOSTS = Collections.singleton("my-app.strybok.link");

    @Test
    public void theBuilderTakesTheOption() {
        ParameterLinks option = new ParameterLinks(Arrays.asList("id", "lang"));
        StrybokConfig config = new StrybokConfig.Builder(API, HOSTS).parameterLinks(option).build();
        assertSame(option, config.getParameterLinks());
        assertEquals(Arrays.asList("id", "lang"), config.getParameterLinks().getPayloadKeys());

        Strybok strybok = new Strybok(config);
        try {
            assertTrue(strybok.isStrybokUrl("https://my-app.strybok.link/product/42?id=7"));
            assertFalse(strybok.isStrybokUrl("https://my-app.strybok.link/robots.txt"));
        } finally {
            strybok.stop();
        }
    }

    @Test
    public void theOptionIsOffByDefault() {
        assertNull(new StrybokConfig.Builder(API, HOSTS).build().getParameterLinks());
        assertNull(new StrybokConfig.Builder(API, HOSTS).parameterLinks(null).build().getParameterLinks());
    }

    @Test
    public void anInvalidOptionIsRefusedByBuild() {
        StrybokConfig.Builder builder = new StrybokConfig.Builder(API, HOSTS)
            .parameterLinks(new ParameterLinks(Arrays.asList("id", null)));
        try {
            builder.build();
            fail("a null key was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("parameterLinks: "));
        }
    }
}
