## 0.5.0

- Links without an API call, optional and off by default: the new `StrybokConfig.parameterLinks`
  (`ParameterLinks(payloadKeys)`; `Builder.parameterLinks` for Java) delivers path links such as
  `https://<host>/product/42?id=7` and Firebase long links `https://<host>/?link=…` that the
  owner switched on for the application. The keys come from `parameterLinks` of the integration
  guide (`GET /v1/apps/:appId/integration`, shown on the dashboard's setup screen). With it:
  - a route path and a long link are links of the app too, besides a single slug; an empty path
    is the root; reserved paths such as `/favicon.ico` or `/.well-known/…` never are;
  - resolving sends the path as received, the first value of each configured key (1–256
    characters without control characters, re-encoded) and the `utm_*` values; for a long link
    the deep link's scheme, host and path with its values of the keys, plus `apn` and `ibi`.
    Nothing else of the query and never the fragment;
  - the delivered link has the route and payload the service read, never the tapped path, and
    `app_open` carries the `linkId` the service returned;
  - any address of the hosts that is a valid route is resolved, so `onError` gets a
    `LinkNotFoundException` for one that is no link.
- An invalid list of keys (more than 20, a key twice, a key that is not 1–40 characters of
  `A–Z a–z 0–9 _ . -`, starts with `utm_` in any case, or is `st`, `sd`, `si` or `strybok_click`)
  makes `StrybokConfig` throw an `IllegalArgumentException`, like an invalid `apiBaseUrl`.
- The SDK keeps reading the URL string as received: the path is sent as it is, never decoded or
  normalised. Values are decoded with a strict UTF-8 decoder of the SDK's own, so every Android
  version reads them the same way.
- Unchanged without the option: the same links are accepted and every request is the one 0.4.0
  sent. Deferred links keep the single-slug rule with the option as well: the URL of a redeem
  answer must have one slug segment.
- `StrybokConfig` has a new last constructor parameter: recompile code built against 0.4.0.
- Covered by JVM unit tests only, against the shared test vectors of the SDK contract (77 tapped
  URLs, 19 configurations) and a fake transport. Never run on a device, an emulator or against
  the production API.

## 0.4.0

- Campaign values. `ResolvedDeepLink.campaign` (`LinkCampaign`: `source`, `medium`, `campaign`,
  `term`, `content`, each `String?`; `isEmpty`; `LinkCampaign.EMPTY`) holds the link's default
  campaign values overridden by the `utm_*` parameters of the tapped URL, as the service returned
  them. It is never `null`. The values are untrusted labels chosen by whoever shared the link.
- The resolve request now carries the five parameters `utm_source`, `utm_medium`, `utm_campaign`,
  `utm_term` and `utm_content` of the tapped URL — exact lower-case names, the first occurrence
  of each, percent-decoded, and only values of 1–64 characters out of `A–Z a–z 0–9 . _ ~ -`, in
  a fixed order. Every other query parameter and the fragment are still never sent, and a URL
  without valid campaign parameters produces exactly the request of earlier versions.
- The `app_open` event reports `campaign` exactly as resolve returned it and leaves it out when
  there is none; it is never built from the URL.
- Additive for callers: `ResolvedDeepLink` keeps its five-argument constructor (the new argument
  has a default and a Java overload). Which URLs are accepted, duplicate detection (the whole
  URL string as received) and everything else are unchanged.
- Deferred links, optional and off by default: restore a link that was tapped before the app was
  installed from a store. With `StrybokConfig.deferredLinks = true`, a list of
  `installReferrerSources` and an instance created with the new `Strybok(context, config)`, the
  SDK asks the stores for their install referrer after the first `start`, exchanges the click id
  found in it (`POST /v1/public/deferred-links/redeem`: the id, the package name and the SDK
  version, nothing else) and delivers the link through the normal pipeline with the new
  `ResolvedDeepLink.isDeferred` set. `enableDeferredLinks()` switches it on later, for example
  after a consent screen.
- The check never runs on the start path: `start`, the launch link, `getInitialLink` and
  `createLink` do not wait for it, and `getInitialLink` never returns a deferred link. A normal
  link of the app that arrives first — also while the request is in flight — wins and the
  deferred link is dropped. No `app_open` is sent for it, nothing is reported to `onError`, and
  neither the referrer nor the click id is logged or stored.
- At most once per installation: the SDK keeps `attempts` and `done` in the app's no-backup
  storage, counts an attempt before a source is asked, gives up after three launches, stores
  `done` before the link is delivered, and skips the check for an app that was updated since it
  was installed (`firstInstallTime != lastUpdateTime`).
- `deferredLinkStatus` (`DeferredLinkStatus`: `DISABLED`, `CHECKING`, `PENDING`,
  `NOT_FIRST_LAUNCH`, `UNAVAILABLE`, `NO_REFERRER`, `NOT_FOUND`, `SUPERSEDED`, `DELIVERED`,
  `FAILED`; `isFinal`) is readable at any time; `deferredLinkStatuses` is a `StateFlow` and
  `addDeferredLinkStatusListener` the callback form.
- **No new dependency.** `InstallReferrerSource` is a one-method interface with the answers
  `InstallReferrerResult.referrer(…)`, `UNAVAILABLE_FOR_NOW` and `UNAVAILABLE`. Shipped
  sources: `PlayInstallReferrerSource` (compiled against
  `com.android.installreferrer:installreferrer` with `compileOnly`),
  `GalaxyStoreInstallReferrerSource` (compiled against
  `store.galaxy.samsung.installreferrer:samsung_galaxystore_install_referrer` with
  `compileOnly`) and `AppGalleryInstallReferrerSource` (a content provider query, no library).
  An app adds the library of each store it uses; a source whose library is missing answers
  "unavailable". The consumer R8 rules cover the missing classes.
- Not verified: nothing of deferred links has run on a device or against a real store. The
  Galaxy Store and AppGallery sources are unverified altogether, including whether those stores
  hand the value to the app; the manifest entries AppGallery may need are documented, not added.

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
