# Android SDK integration (Kotlin / Java)

The `com.strybok.sdk` library receives verified HTTPS links (Android App Links) from your
Activity, asks the Strybok API which in-app route a link stands for, and hands your app each
link exactly once.

> **Status:** covered by JVM unit tests, and a sample app using it compiles (Kotlin and Java,
> including an R8 release build). Not yet verified on a physical device or emulator (see the
> [manual test plan](./manual-test-plan.md)) and therefore not published to Maven Central.
>
> Deferred deep linking is **not supported**: a link tapped before the app is installed is not
> restored after installation.

Building a Flutter app? Use the [Flutter SDK](https://github.com/strybok/strybok-flutter) instead. The behaviour shared by all
SDKs is described in the [SDK contract](./sdk-contract.md).

## 1. Prerequisites

1. Create an application in Strybok with your Android package name and certificate fingerprints.
2. Read its setup data: `GET /v1/apps/:appId/integration` returns the managed hostname
   (for example `my-app.strybok.link`) and ready-to-copy snippets.
3. Complete the [Android App Links setup](./android.md) so that
   `https://my-app.strybok.link/.well-known/assetlinks.json` lists your app. Without it Android
   never delivers links to the app, and the SDK has nothing to receive.

Requirements: `minSdk` 21, Kotlin coroutines (`kotlinx-coroutines-core` comes with the library).

## 2. Add the library

The library is not published to Maven Central yet. Clone its repository next to your app (or add
it as a git submodule) and include it as a Gradle composite build:

```sh
git clone https://github.com/strybok/strybok-android
```

`settings.gradle.kts` of your app:

```kotlin
includeBuild("../strybok-android") // path to your clone
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.strybok:strybok:0.1.0")
}
```

Gradle substitutes the coordinates with the project from the included build; nothing is
downloaded. The library builds with Gradle 9.1 and Android Gradle plugin 9.0.1 (built-in Kotlin).
This was checked with an app on the same plugin version; other combinations are untested.

Alternatively build the AAR once (`./gradlew assembleRelease` in the SDK directory) and add
`strybok/build/outputs/aar/strybok-release.aar` as a file dependency. An AAR carries no
dependency information, so add
`implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")` (or newer) yourself.

No ProGuard/R8 rules are needed.

## 3. Declare the link in the manifest

`app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />

<application ...>
    <activity
        android:name=".MainActivity"
        android:exported="true"
        android:launchMode="singleTop">

        <!-- your existing MAIN/LAUNCHER filter stays as it is -->

        <intent-filter android:autoVerify="true">
            <action android:name="android.intent.action.VIEW" />
            <category android:name="android.intent.category.DEFAULT" />
            <category android:name="android.intent.category.BROWSABLE" />
            <data android:scheme="https" android:host="my-app.strybok.link" />
        </intent-filter>
    </activity>
</application>
```

Declare the whole host (no path filter); the slug is resolved at runtime. `singleTop` (or
`singleTask`) makes Android hand a link to the running Activity through `onNewIntent` instead of
stacking a second copy of it.

## 4. Use it

Create **one instance for the whole process**, for example in your `Application`:

```kotlin
class App : Application() {
    lateinit var strybok: Strybok
        private set

    override fun onCreate() {
        super.onCreate()
        strybok = Strybok(
            StrybokConfig(
                apiBaseUrl = "https://api.strybok.com",
                allowedHosts = setOf("my-app.strybok.link"),
                onError = { error -> Log.w("Strybok", "Link failed", error) },
            ),
        )
    }
}
```

Android has no global stream of incoming URLs: the system gives links to an Activity as intents.
Forward them from `onCreate` and `onNewIntent`, and collect `links`:

```kotlin
class MainActivity : AppCompatActivity() {
    private val strybok get() = (application as App).strybok

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        strybok.start(intent, savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                strybok.links.collect { link ->
                    // link.routePath == "/invite/abc123"
                    // link.payload["invitationId"] == "abc123"
                    openRoute(link)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        strybok.handleIntent(intent)
    }
}
```

One collector covers every case:

| Situation                                 | What happens                                                                                          |
| ----------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| **Cold start** (app launched by the link) | `start()` resolves the launch link. It is kept until something collects `links`, then delivered once. |
| **Background** (app resumed by the link)  | `onNewIntent` → `handleIntent` → delivered on `links`.                                                |
| **Foreground**                            | `onNewIntent` → `handleIntent` → delivered on `links`.                                                |

A link that finishes resolving while nothing is collecting (the collector above is cancelled while
the Activity is stopped) is queued and delivered to the next collector. Nothing is lost, nothing
is delivered twice.

### Configuration changes and re-created activities

Android re-creates an Activity after a rotation, and after the process was killed in the
background, **with the intent it was originally launched with**. The SDK must not navigate to
that old link again:

- Always pass `savedInstanceState`: `strybok.start(intent, savedInstanceState)`. When it is not
  `null` the Activity is being re-created and the intent is ignored.
- Intents re-delivered from the recents screen (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`) are
  ignored by `start` and `handleIntent`.
- Keep the `Strybok` instance in the `Application` (or another process-wide holder), not in the
  Activity. A link that is still resolving during a rotation then survives it and is delivered to
  the new Activity's collector.
- Calling `start` again is harmless: the first call defines the launch link, later calls behave
  like `handleIntent`. This is what makes a second `onCreate` work when the user left with Back
  and opened the app through another link.
- The identical URL arriving twice within `dedupeWindowMillis` (2 s by default) is dropped. The
  same link tapped again later is a new delivery.

Prefer to read the launch link yourself, for example to pick the start destination?
`strybok.getInitialLink()` suspends until the launch link is resolved (or has failed), returns it
at most once and removes it from `links`, so combining both never navigates twice.

### Jetpack Compose with Navigation

```kotlin
private val inviteRoute = Regex("^/invite/([A-Za-z0-9_-]{1,64})$")

@Composable
fun AppNavHost(strybok: Strybok, navController: NavHostController = rememberNavController()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(strybok, navController, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            strybok.links.collect { link ->
                // Map the untrusted route onto destinations the app knows.
                val invite = inviteRoute.matchEntire(link.routePath)
                if (invite != null) {
                    navController.navigate("invite/${invite.groupValues[1]}") { launchSingleTop = true }
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen() }
        composable("invite/{code}") { entry ->
            InviteScreen(code = entry.arguments?.getString("code").orEmpty())
        }
    }
}

class MainActivity : ComponentActivity() {
    private val strybok get() = (application as App).strybok

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        strybok.start(intent, savedInstanceState)
        setContent { AppNavHost(strybok) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        strybok.handleIntent(intent)
    }
}
```

Do not also declare a `navDeepLink { uriPattern = "https://my-app.strybok.link/..." }` for the
same host: Navigation would handle the intent as well, and the screen would open twice.

This Compose snippet is illustrative; it was not compiled as part of the SDK build.

### Java

```java
public class MainActivity extends AppCompatActivity {
    private final StrybokLinkListener listener = this::openRoute;

    private Strybok strybok() {
        return ((App) getApplication()).getStrybok();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        strybok().start(getIntent(), savedInstanceState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        strybok().handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        strybok().addLinkListener(listener); // queued links arrive right away
    }

    @Override
    protected void onStop() {
        strybok().removeLinkListener(listener);
        super.onStop();
    }

    private void openRoute(ResolvedDeepLink link) {
        if (link.getRoutePath().startsWith("/invite/")) { /* navigate */ }
    }
}
```

Create the instance with the builder:

```java
Strybok strybok = new Strybok(
    new StrybokConfig.Builder("https://api.strybok.com", Collections.singleton("my-app.strybok.link"))
        .onError(error -> Log.w("Strybok", "Link failed", error))
        .build());
```

Listeners and callbacks run on the main thread unless you set `callbackExecutor`.

### API

| Member                                   | Purpose                                                                                                                                                 |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `Strybok(config)`                        | Create the instance; does no work until `start`.                                                                                                        |
| `start(intent, savedInstanceState)`      | Call from `onCreate`. Returns `true` when the intent carries one of your links.                                                                         |
| `start(intent)`                          | Same without the re-creation guard; only for intents known to be fresh.                                                                                 |
| `handleIntent(intent)`                   | Call from `onNewIntent`. Returns `true` when the intent carries one of your links.                                                                      |
| `startWithUrl(url)` / `handleUrl(url)`   | The same with plain strings (tests, custom sources such as a push notification).                                                                        |
| `links`                                  | `Flow<ResolvedDeepLink>` (`url`, `linkId`, `appId`, `routePath`, `payload`). Completes on `stop`.                                                       |
| `addLinkListener` / `removeLinkListener` | Listener alternative to `links`.                                                                                                                        |
| `getInitialLink()`                       | Suspends; the launch link, if not delivered yet. Also `getInitialLink(callback)`.                                                                       |
| `awaitStarted()`                         | Suspends until the launch link was resolved or has failed.                                                                                              |
| `resolve(url)`                           | Suspends; resolve a URL explicitly (QR scanner). Does not emit on `links` or report an app open. Also `resolve(uri: Uri)` and `resolve(url, callback)`. |
| `isStrybokUrl(url)`                      | Whether a URL is one of this app's links.                                                                                                               |
| `stop()`                                 | Stop for good; nothing is delivered afterwards. Not needed for a process-wide instance.                                                                 |
| `Strybok.SDK_VERSION`                    | `"0.1.0"`.                                                                                                                                              |

`StrybokConfig` options: `timeoutMillis` (5000 per attempt), `maxRetries` (2; only timeouts,
network errors and 5xx are retried — resolving is an idempotent GET), `retryDelayMillis` (400,
doubles per retry), `dedupeWindowMillis` (2000), `reportAppOpens` (`true`), `onError`, plus
`transport` and `callbackExecutor` for tests and custom HTTP stacks.

### Errors

`resolve` throws, and `onError` receives, a `StrybokException`. The hierarchy is sealed, so a
`when` is exhaustive; every error has `url`, the link being handled.

| Type                                                  | Meaning                                                                                                                                        |
| ----------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `UnsupportedLinkException`                            | Not `https`, host not in `allowedHosts`, or not a single-slug path. Thrown by `resolve` before any network call. Ignored silently for intents. |
| `LinkNotFoundException`                               | Unknown, disabled or expired link (404).                                                                                                       |
| `StrybokTimeoutException` / `StrybokNetworkException` | No answer after all retries (e.g. offline). The app keeps working; the link is simply not opened. `cause` holds the transport error.           |
| `StrybokServerException`                              | Unexpected status or malformed response; see `statusCode`.                                                                                     |

```kotlin
when (error) {
    is LinkNotFoundException -> showMessage("This link is no longer valid")
    is StrybokTimeoutException, is StrybokNetworkException -> showMessage("You seem to be offline")
    is StrybokServerException, is UnsupportedLinkException -> Unit
}
```

After `stop()`, `resolve` throws `IllegalStateException`; `start`, `handleIntent` and
`handleUrl` do nothing and return `false`.

## 5. Treat routes as untrusted input

`routePath` and `payload` are data from the network, chosen by whoever created the link. Match
the route against routes your app knows before navigating, and never execute payload contents.
`payload` is a read-only `Map<String, Any?>` of plain JSON values (`String`, `Boolean`, numbers,
`List`, `Map`, `null`).

## 6. Links that arrive before sign-in

If a link needs an authenticated user, remember the route when it arrives and open it after
login instead of dropping it. The SDK delivers each link once; holding on to it across your
sign-in flow is up to the app.

## 7. Analytics and privacy

After a link is delivered (or queued) the SDK sends one best-effort `app_open` event
(`POST /v1/public/events`) containing a random event id, the link id, the platform (`android`)
and the SDK version — no device identifiers. It never delays navigation and all failures are
ignored. Disable it with `reportAppOpens = false`.

Only the link's host and slug are sent to `resolve`; query parameters and fragments stay on the
device.

## 8. Testing your integration

`startWithUrl` / `handleUrl` take plain strings and `StrybokConfig.transport` replaces the
network, so the flow can be tested on the JVM without a device. Set `callbackExecutor` in JVM
tests: the default one posts to the Android main thread.

```kotlin
@Test
fun opensInvite() = runBlocking {
    val strybok = Strybok(
        StrybokConfig(
            apiBaseUrl = "https://api.strybok.com",
            allowedHosts = setOf("my-app.strybok.link"),
            transport = { request ->
                if (request.method == "GET") {
                    StrybokHttpResponse(200, """{"linkId":"l1","appId":"a1","routePath":"/invite/abc123"}""")
                } else {
                    StrybokHttpResponse(202, "") // app_open event
                }
            },
            callbackExecutor = { it.run() },
        ),
    )
    strybok.startWithUrl("https://my-app.strybok.link/invite777")
    assertEquals("/invite/abc123", strybok.getInitialLink()?.routePath)
    strybok.stop()
}
```

`org.json` is only a stub in local unit tests; add `testImplementation("org.json:json:20250517")`
to tests that run SDK code on the JVM.

On a device or emulator, simulate a tap:

```sh
adb shell am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE \
  -d "https://my-app.strybok.link/<slug>"
```

Check cold start (app force-stopped first), background, foreground, rotation right after
opening a link, and reopening the app from the recents screen (the link must not open again).

The SDK's own tests: `./gradlew test` in the SDK directory.

## Troubleshooting

| Symptom                                                | Check                                                                                                                                                                                                                          |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Link opens the browser/store instead of the app        | The App Link is not verified: see the [Android setup](./android.md) (`adb shell pm get-app-links <package>`). The SDK is not involved yet at this point.                                                                       |
| App opens but nothing happens                          | `start`/`handleIntent` returned `false`? Then the host is missing from `allowedHosts` or the intent is not a `VIEW` intent. Otherwise look at `onError`; is the API reachable from the device? Is anything collecting `links`? |
| Works on cold start, not while the app is running      | `onNewIntent` does not call `handleIntent`, or the Activity is not `singleTop`/`singleTask` and a second instance was created.                                                                                                 |
| Screen opens again after rotation or from recents      | `start(intent)` is used without `savedInstanceState`, or the app also navigates from `intent.data` itself or through a Navigation `navDeepLink` for the same host.                                                             |
| Screen opens twice                                     | Two collectors/listeners are attached at the same time (each one receives every link), or both `getInitialLink()` and your own `intent.data` handling navigate.                                                                |
| `onError` gets `StrybokNetworkException` in debug      | No `INTERNET` permission, or `apiBaseUrl` is `http://` and cleartext traffic is blocked by the network security config.                                                                                                        |
| `RuntimeException: Method ... not mocked` in tests     | Set `callbackExecutor` and add the `org.json` test dependency (see Testing).                                                                                                                                                   |
| Works from Notes/Messages, not from Instagram/Telegram | In-app browsers often do not hand links to apps. Users get the fallback page with a manual button.                                                                                                                             |
