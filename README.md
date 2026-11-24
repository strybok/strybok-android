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

Full guide, manifest setup and troubleshooting:
[`docs/android-sdk.md`](./docs/android-sdk.md).
Behaviour shared by all SDKs: [`docs/sdk-contract.md`](./docs/sdk-contract.md).

- **Early release (0.2.x)**: covered by JVM unit tests only. Not yet verified on a physical device or emulator.
- Deferred deep linking is not supported.
- `minSdk` 21. Only dependency: `kotlinx-coroutines-core`.

## Install

In `app/build.gradle.kts` (the library is on Maven Central):

```kotlin
dependencies {
    implementation("com.strybok:strybok-android:0.2.0")
}
```

## Layout

| Path                                              | Content                                                   |
| ------------------------------------------------- | --------------------------------------------------------- |
| `strybok/src/main/kotlin/.../Strybok.kt`          | Public entry point and the thin `Intent` glue.            |
| `strybok/src/main/kotlin/.../StrybokEngine.kt`    | All behaviour, plain Kotlin without `android.*` types.    |
| `strybok/src/main/kotlin/.../CreateLinkBody.kt`   | JSON body of `createLink`.                                |
| `strybok/src/main/kotlin/.../LinkParser.kt`       | "Is this URL ours" rules.                                 |
| `strybok/src/main/kotlin/.../StrybokTransport.kt` | HTTP transport interface and `HttpURLConnection` default. |
| `strybok/src/test`                                | JVM unit tests (port of the Flutter SDK test list).       |

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
