## 0.3.0

- Optional attestation of `createLink` ("Extra protection"), off by default: with
  `StrybokConfig.attestation = true` and an `integrityTokenProvider`, the SDK proves to the
  service that a request comes from a genuine build of the app on a genuine device, using the
  Google Play Integrity standard API. The proof is made lazily inside `createLink` (challenge →
  integrity token → session token in the `X-Strybok-Attestation` header), never at start-up; the
  token is kept in memory until 60 seconds before it expires, concurrent calls share one
  attestation, and a refused token is replaced once.
- **No new dependency.** `StrybokIntegrityTokenProvider` is a one-method interface;
  `PlayIntegrityTokenProvider(context, cloudProjectNumber)` is the ready-made implementation and
  is compiled against `com.google.android.play:integrity` with `compileOnly`. Apps that want
  attestation add that library themselves; without it the provider reports "cannot attest". The
  published POM and module metadata are unchanged. The AAR now ships a consumer R8 rule
  (`-dontwarn` for the two Google packages) so apps without the library still build.
- A device that cannot attest (no Google Play services, a sideloaded build, no provider) sends
  the request without a proof and gets the service's answer; the SDK tries again after 60
  seconds at the earliest.
- New errors, thrown by `createLink` only: `StrybokAttestationRequiredException` (the owner only
  accepts attested apps; also reported when attestation is off) and
  `StrybokAttestationFailedException` (`reason`). They extend the sealed `StrybokException`, so
  an exhaustive `when` over it needs branches for them (or an `else`). Both were
  `StrybokUnauthorizedException` in 0.2.0.
- `StrybokConfig` has two new last constructor parameters (`Builder.attestation`,
  `Builder.integrityTokenProvider`): recompile code built against 0.2.0.
- Not verified against Google's real Play Integrity service or on a device: covered by JVM unit
  tests with a fake token provider and a fake service only.

## 0.2.0

- `createLink(routePath, payload, title, description, imageUrl)` asks the service for a short
  link to a screen of the app (the "Share" button) and returns a `CreatedLink` (`url`, `slug`,
  `routePath`, `linkId`). A suspend function, plus callback overloads with
  `StrybokCreateLinkCallback` for Java. It runs only when called, never as part of `start`, and
  is retried like `resolve` (creating is idempotent; 4xx answers, including 429, are not
  retried).
- New option `StrybokConfig.clientKey` (`Builder.clientKey`): the application's publishable
  `pk_…` key from the dashboard. Only `createLink` needs it.
- New errors, thrown by `createLink` only: `StrybokUnauthorizedException`,
  `RouteNotAllowedException` (`routePath`), `StrybokRateLimitedException` (`retryAfterSeconds`)
  and `StrybokValidationException` (`issues`). They extend the sealed `StrybokException`, so an
  exhaustive `when` over it needs branches for them (or an `else`).
- `StrybokHttpResponse` has `headers` (optional third constructor argument) and `header(name)`;
  the default transport fills them. A custom transport only has to pass headers on if the app
  wants `retryAfterSeconds`.
- `StrybokConfig` has a new last constructor parameter: recompile code built against 0.1.0.

## 0.1.0

- Initial version: `Strybok` with `start` / `handleIntent` (and `startWithUrl` / `handleUrl`),
  `links` flow, link listeners, `getInitialLink`, `resolve`, `stop`; host allowlist;
  duplicate-delivery protection; timeout and retry; sealed `StrybokException` hierarchy;
  best-effort `app_open` events; replaceable `StrybokTransport`.
