package com.strybok.sdk

import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.fail

const val HOST = "my-app.strybok.link"
const val API = "https://api.strybok.com"
const val INVITE = "https://$HOST/invite777"

fun ok(route: String = "/invite/abc123", linkId: String = "link_1"): StrybokHttpResponse =
    StrybokHttpResponse(
        200,
        """{"linkId":"$linkId","appId":"app_1","routePath":"$route","payload":{"invitationId":"abc123"}}""",
    )

const val CLIENT_KEY = "pk_0123456789abcdefghijABCDEFGHIJ01"

fun created(status: Int = 201, slug: String = "Ab3dE9x"): StrybokHttpResponse = StrybokHttpResponse(
    status,
    """{"linkId":"link_9","url":"https://$HOST/$slug","slug":"$slug","routePath":"/product/42"}""",
)

/** Records requests and answers resolve calls with [onResolve] and create-link calls with [onCreate]. */
class FakeApi(
    @Volatile var onResolve: ((StrybokHttpRequest) -> StrybokHttpResponse)? = null,
) : StrybokTransport {
    val resolves = CopyOnWriteArrayList<StrybokHttpRequest>()
    val events = CopyOnWriteArrayList<StrybokHttpRequest>()
    val creates = CopyOnWriteArrayList<StrybokHttpRequest>()

    @Volatile
    var onCreate: ((StrybokHttpRequest) -> StrybokHttpResponse)? = null

    @Volatile
    var onEvent: (() -> StrybokHttpResponse)? = null

    override fun execute(request: StrybokHttpRequest): StrybokHttpResponse {
        if (request.url.substringBefore('?').endsWith("/v1/public/events")) {
            events += request
            return onEvent?.invoke() ?: StrybokHttpResponse(202, "")
        }
        if (request.url.endsWith("/v1/public/links")) {
            creates += request
            return onCreate?.invoke(request) ?: created()
        }
        resolves += request
        return onResolve?.invoke(request) ?: ok()
    }
}

/** The `url` query parameter of a resolve request, decoded. */
val StrybokHttpRequest.lookup: String
    get() = URLDecoder.decode(url.substringAfter("?url="), "UTF-8")

val StrybokHttpRequest.path: String
    get() = "/" + url.substringAfter("://").substringBefore('?').substringAfter('/', "")

val StrybokHttpRequest.json: JSONObject
    get() = JSONObject(body!!)

/** Runs callbacks on the calling thread. */
val directExecutor = Executor { it.run() }

/** Collects callbacks until the test runs them. */
class ManualExecutor : Executor {
    val queue = CopyOnWriteArrayList<Runnable>()

    override fun execute(command: Runnable) {
        queue += command
    }

    fun runAll() {
        while (queue.isNotEmpty()) queue.removeAt(0).run()
    }
}

/** Never answers; ends only when the SDK interrupts the thread. */
fun hang(): Nothing {
    CountDownLatch(1).await()
    error("unreachable")
}

/** Waits until [condition] holds; fails the test after five seconds. */
fun eventually(what: String = "condition", condition: () -> Boolean) {
    val deadline = System.nanoTime() + 5_000_000_000
    while (!condition()) {
        if (System.nanoTime() > deadline) fail("Timed out waiting for $what")
        Thread.sleep(2)
    }
}

/** Gives background work time to (wrongly) produce something. */
fun settle() = Thread.sleep(150)

inline fun <reified T : Throwable> failsWith(block: () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError("Expected ${T::class.java.simpleName} but got $error", error)
    }
    throw AssertionError("Expected ${T::class.java.simpleName} but nothing was thrown")
}
