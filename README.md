# strybok (Android SDK)

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fcom%2Fstrybok%2Fstrybok-android%2Fmaven-metadata.xml&label=maven%20central)](https://central.sonatype.com/artifact/com.strybok/strybok-android) [![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](./LICENSE)

Receives Android App Links, resolves them through the Strybok API to an in-app route and
payload, and delivers each link exactly once — on cold start, from the background and in the
foreground. Kotlin, coroutines/`Flow`, with listener and callback variants for Java.

```kotlin
val strybok = Strybok(
    StrybokConfig(
        apiBaseUrl = "https://api.strybok.com",
        allowedHosts = setOf("my-app.strybok.link"),
    ),
)

// Activity
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    strybok.start(intent, savedInstanceState)
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            strybok.links.collect { link -> navigate(link.routePath) }
        }
    }
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    strybok.handleIntent(intent)
}
```

Create a link to share (needs the application's publishable key, `clientKey = "pk_…"`, and the
"Links from the app" feature enabled in the dashboard):

```kotlin
val link = strybok.createLink("/product/42", payload = mapOf("productId" to 42), title = "Red shoes")
share(link.url)
```

Campaign values (`utm_source`, `utm_medium`, `utm_campaign`, `utm_term`, `utm_content`) of the
open come with the link:

```kotlin
strybok.links.collect { link -> link.campaign.source?.let { analytics.label("source", it) } }
```

Campaign values are the link's defaults (set in the dashboard) overridden by the `utm_*`
parameters of the tapped URL. They are labels chosen by whoever shared the link: use them for
your analytics, never to decide anything. Details:
[integration guide](./docs/android-sdk.md#campaign-values).

### Extra protection (optional)

The publishable key can be copied out of an app. The owner of the application can therefore ask
Strybok to accept link requests only — or preferably — from a genuine build of the app on a
genuine device, proved with Google **Play Integrity**. It is **off by default**, and the SDK
does not depend on Google's libraries: the app adds `com.google.android.play:integrity` itself.

```kotlin
// build.gradle.kts of the app
implementation("com.google.android.play:integrity:1.4.0") // or newer; not pulled in by the SDK
```

```kotlin
val strybok = Strybok(
    StrybokConfig(
        apiBaseUrl = "https://api.strybok.com",
        allowedHosts = setOf("my-app.strybok.link"),
        clientKey = "pk_…",
        attestation = true,
        integrityTokenProvider = PlayIntegrityTokenProvider(applicationContext, cloudProjectNumber = 123456789012L),
    ),
)
```

Before that: link a Google Cloud project with the Play Integrity API to the app in the Play
Console, upload a service-account key in the Strybok dashboard and choose the mode there
(**Preferred** first). The steps are in the
[integration guide](./docs/android-sdk.md#extra-protection) and in
[Keeping link creation from your app safe](./docs/link-creation-security.md).

`createLink` is called as before; the proof is made lazily inside it, about once an hour, never
at start-up. Two more errors can come out: `StrybokAttestationRequiredException` (the owner
requires a proof and this device or build could not give one) and
`StrybokAttestationFailedException` (a fresh proof was refused twice). A device that cannot
attest still sends the request, and the service decides.

Limits you should know:

- It needs Google Play services, the Play Store and a build **installed from Google Play**. It
  does not work on devices without Google services (most devices in mainland China, Huawei
  without GMS) or for sideloaded builds. For a flavour without Google libraries, leave the
  dependency out and pass `integrityTokenProvider = null`.
- Google allows 10,000 integrity requests a day per app by default.
- **It has never run against Google's real service or on a physical device** — only against
  fakes in unit tests. Treat the **Required** mode as experimental.

### Deferred links (optional)

A person without the app taps a link, installs the app from the store page the link sent them
to and opens it: the SDK then delivers that link about a second after the first start. Off by
default.

1. Dashboard → your application → **Restore the link after install (Android only)**.
2. Add the library of each store you publish in (the SDK does not bring them along):

   | Source                             | Store                | Add to the app                                                                    | Standing                                     |
   | ---------------------------------- | -------------------- | --------------------------------------------------------------------------------- | -------------------------------------------- |
   | `PlayInstallReferrerSource`        | Google Play          | `com.android.installreferrer:installreferrer:2.2`                                 | From Google's documentation; not on a device |
   | `GalaxyStoreInstallReferrerSource` | Samsung Galaxy Store | `store.galaxy.samsung.installreferrer:samsung_galaxystore_install_referrer:4.0.0` | **Unverified**                               |
   | `AppGalleryInstallReferrerSource`  | Huawei AppGallery    | No library; possibly manifest entries, see the guide                              | **Unverified**                               |

3. Create the instance with a `Context` and switch it on:

```kotlin
val strybok = Strybok(
    applicationContext,
    StrybokConfig(
        apiBaseUrl = "https://api.strybok.com",
        allowedHosts = setOf("my-app.strybok.link"),
        deferredLinks = true,
        installReferrerSources = listOf(PlayInstallReferrerSource(applicationContext)),
    ),
)
```

The link arrives on `links` like any other, with `isDeferred == true`; it is never the launch
link and `getInitialLink()` never returns it. If your app asks for consent before reading the
install referrer, leave `deferredLinks` off and call `strybok.enableDeferredLinks()` afterwards.
`strybok.deferredLinkStatus` tells developers and QA what the check did; nothing is reported to
`onError`.

The limits, plainly:

- It works only when the app is installed **from the store page the link sent the person to**
  (not after a search, not from the store's website, not from an APK), **within 24 hours**, once.
- **Huawei AppGallery and Samsung Galaxy Store were never verified**, not even that those stores
  pass the value on.
- **None of it has run on a device or against a real store** — only against fakes in unit tests.
- The SDK reads the store's install referrer at most three times per installation and sends only
  the random code found in it, the package name and the SDK version.

Full guide: [`docs/android-sdk.md`](./docs/android-sdk.md#10-deferred-links-optional).

Full guide, manifest setup and troubleshooting:
[`docs/android-sdk.md`](./docs/android-sdk.md).
Behaviour shared by all SDKs: [`docs/sdk-contract.md`](./docs/sdk-contract.md).

- **Early release (0.4.x)**: covered by JVM unit tests only. Not yet verified on a physical device or emulator.
- Restoring a link after installation ("deferred links") is optional, off by default and has not
  run on a device; see above.
- `minSdk` 21. Only dependency: `kotlinx-coroutines-core`. Google's Play Integrity library and
  the stores' install referrer libraries are not dependencies; only apps that switch attestation
  or deferred links on add them.

## Install

In `app/build.gradle.kts` (the library is on Maven Central):

```kotlin
dependencies {
    implementation("com.strybok:strybok-android:0.4.0")
}
```

## Layout

| Path                                                        | Content                                                                     |
| ----------------------------------------------------------- | --------------------------------------------------------------------------- |
| `strybok/src/main/kotlin/.../Strybok.kt`                    | Public entry point and the thin `Intent` glue.                              |
| `strybok/src/main/kotlin/.../StrybokEngine.kt`              | All behaviour, plain Kotlin without `android.*` types.                      |
| `strybok/src/main/kotlin/.../CreateLinkBody.kt`             | JSON body of `createLink`.                                                  |
| `strybok/src/main/kotlin/.../Attestation.kt`                | Optional attestation of `createLink`: challenge, token cache.               |
| `strybok/src/main/kotlin/.../PlayIntegrityTokenProvider.kt` | Optional Play Integrity provider (`compileOnly` library).                   |
| `strybok/src/main/kotlin/.../LinkParser.kt`                 | "Is this URL ours" rules and the campaign query filter.                     |
| `strybok/src/main/kotlin/.../DeferredLinks.kt`              | Optional deferred links: the check, its status, the sources' interface.     |
| `strybok/src/main/kotlin/.../InstallReferrerSources.kt`     | Google Play, Galaxy Store (`compileOnly` libraries) and AppGallery sources. |
| `strybok/src/main/kotlin/.../DeferredLinkStorage.kt`        | The two stored values of the check, in no-backup storage.                   |
| `strybok/src/main/kotlin/.../StrybokTransport.kt`           | HTTP transport interface and `HttpURLConnection` default.                   |
| `strybok/src/test`                                          | JVM unit tests (port of the Flutter SDK test list).                         |

## Build and test

Needs JDK 17 or newer and an Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`)
with platform 36.

```sh
./gradlew test             # JVM unit tests
./gradlew assembleRelease  # strybok/build/outputs/aar/strybok-release.aar
./gradlew lint
```

## Licence

Apache 2.0, see [LICENSE](./LICENSE).
