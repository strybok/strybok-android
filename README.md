# strybok (Android SDK)

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

Full guide, manifest setup and troubleshooting:
[`docs/android-sdk.md`](./docs/android-sdk.md).
Behaviour shared by all SDKs: [`docs/sdk-contract.md`](./docs/sdk-contract.md).

- Not published to Maven Central yet: clone `https://github.com/strybok/strybok-android` and use
  it as a Gradle composite build (see Install).
- Covered by JVM unit tests only. Not yet verified on a physical device or emulator.
- Deferred deep linking is not supported.
- `minSdk` 21. Only dependency: `kotlinx-coroutines-core`.

## Install

Clone the repository next to your app (or add it as a git submodule), then in the app's
`settings.gradle.kts`:

```kotlin
includeBuild("../strybok-android") // path to your clone
```

and in `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.strybok:strybok:0.1.0")
}
```

Gradle substitutes the coordinates with the included build; nothing is downloaded.

## Layout

| Path                                              | Content                                                   |
| ------------------------------------------------- | --------------------------------------------------------- |
| `strybok/src/main/kotlin/.../Strybok.kt`          | Public entry point and the thin `Intent` glue.            |
| `strybok/src/main/kotlin/.../StrybokEngine.kt`    | All behaviour, plain Kotlin without `android.*` types.    |
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
