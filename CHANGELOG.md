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
