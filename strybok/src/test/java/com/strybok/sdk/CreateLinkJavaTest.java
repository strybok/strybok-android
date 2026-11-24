package com.strybok.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** `createLink` as a Java caller sees it: builder, callback overloads and error getters. */
public class CreateLinkJavaTest {
    private static final String KEY = "pk_0123456789abcdefghijABCDEFGHIJ01";

    private static final class Result implements StrybokCreateLinkCallback {
        final CountDownLatch done = new CountDownLatch(1);
        volatile CreatedLink link;
        volatile StrybokException error;

        @Override
        public void onCreated(CreatedLink link) {
            this.link = link;
            done.countDown();
        }

        @Override
        public void onError(StrybokException error) {
            this.error = error;
            done.countDown();
        }

        Result await() throws InterruptedException {
            assertTrue("no answer", done.await(5, TimeUnit.SECONDS));
            return this;
        }
    }

    private static Strybok sdk(StrybokTransport transport) {
        return new Strybok(
            new StrybokConfig.Builder("https://api.strybok.com", Collections.singleton("my-app.strybok.link"))
                .clientKey(KEY)
                .retryDelayMillis(1)
                .transport(transport)
                .callbackExecutor(Runnable::run)
                .build());
    }

    @Test
    public void createsALinkWithACallback() throws Exception {
        List<StrybokHttpRequest> requests = new CopyOnWriteArrayList<>();
        Strybok strybok = sdk(request -> {
            requests.add(request);
            return new StrybokHttpResponse(
                201,
                "{\"linkId\":\"link_9\",\"url\":\"https://my-app.strybok.link/Ab3dE9x\",\"slug\":\"Ab3dE9x\",\"routePath\":\"/product/42\"}");
        });
        try {
            assertEquals(KEY, strybok.getConfig().getClientKey());

            Result routeOnly = new Result();
            strybok.createLink("/product/42", routeOnly);
            assertEquals("https://my-app.strybok.link/Ab3dE9x", routeOnly.await().link.getUrl());
            assertEquals("Ab3dE9x", routeOnly.link.getSlug());
            assertEquals("/product/42", routeOnly.link.getRoutePath());
            assertEquals("link_9", routeOnly.link.getLinkId());
            assertEquals("{\"routePath\":\"/product/42\"}", requests.get(0).getBody());
            assertEquals(KEY, requests.get(0).getHeaders().get("X-Strybok-Key"));

            Map<String, Object> payload = new HashMap<>();
            payload.put("productId", 42);
            Result full = new Result();
            strybok.createLink("/product/42", payload, "Red shoes", null, null, full);
            assertNull(full.await().error);
            assertEquals(
                "{\"routePath\":\"/product/42\",\"payload\":{\"productId\":42},\"title\":\"Red shoes\"}",
                requests.get(1).getBody());
        } finally {
            strybok.stop();
        }
    }

    @Test
    public void reportsTypedErrorsWithTheirDetails() throws Exception {
        Strybok limited = sdk(request ->
            new StrybokHttpResponse(429, "", Collections.singletonMap("Retry-After", "60")));
        Strybok refused = sdk(request -> new StrybokHttpResponse(403, ""));
        Strybok invalid = sdk(request -> new StrybokHttpResponse(
            422,
            "{\"error\":{\"message\":\"The request is invalid\",\"details\":{\"issues\":[{\"path\":\"title\",\"message\":\"Too big\"}]}}}"));
        try {
            Result first = new Result();
            limited.createLink("/product/42", first);
            StrybokException rateLimited = first.await().error;
            assertTrue(rateLimited instanceof StrybokRateLimitedException);
            assertEquals(Long.valueOf(60), ((StrybokRateLimitedException) rateLimited).getRetryAfterSeconds());

            Result second = new Result();
            refused.createLink("/admin", second);
            assertEquals("/admin", ((RouteNotAllowedException) second.await().error).getRoutePath());

            Result third = new Result();
            invalid.createLink("/product/42", third);
            StrybokValidationException validation = (StrybokValidationException) third.await().error;
            assertEquals("The request is invalid", validation.getMessage());
            assertEquals("title", validation.getIssues().get(0).getPath());
            assertEquals("Too big", validation.getIssues().get(0).getMessage());

            Result fourth = new Result();
            invalid.createLink("no-slash", fourth);
            assertTrue(fourth.await().error instanceof StrybokValidationException);
            assertNull(fourth.error.getUrl());
        } finally {
            limited.stop();
            refused.stop();
            invalid.stop();
        }
    }
}
