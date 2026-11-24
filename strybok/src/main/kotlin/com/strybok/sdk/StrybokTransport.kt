package com.strybok.sdk

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP request made by the SDK. */
public class StrybokHttpRequest(
    /** `GET` or `POST`. */
    public val method: String,
    /** Absolute URL, already encoded. */
    public val url: String,
    public val headers: Map<String, String>,
    /** UTF-8 request body, or `null` for requests without one. */
    public val body: String?,
    /** Time allowed for this request, in milliseconds. */
    public val timeoutMillis: Long,
) {
    override fun toString(): String = "$method $url"
}

/** The answer to a [StrybokHttpRequest]. */
public class StrybokHttpResponse(
    public val statusCode: Int,
    /** Response body decoded as UTF-8; empty when there is none. */
    public val body: String,
)

/**
 * Performs the HTTP calls of the SDK. Replace it through [StrybokConfig.transport]
 * to use your own HTTP stack or to fake the API in tests.
 *
 * [execute] is a blocking call. The SDK invokes it on a background thread and
 * interrupts that thread when the request is no longer needed. Any exception
 * counts as a network failure, a [java.net.SocketTimeoutException] as a timeout;
 * HTTP error statuses must be returned, not thrown.
 */
public fun interface StrybokTransport {
    @Throws(IOException::class)
    public fun execute(request: StrybokHttpRequest): StrybokHttpResponse
}

/** Default [StrybokTransport] on top of [HttpURLConnection]; no extra dependencies. */
public class HttpUrlConnectionTransport : StrybokTransport {
    @Throws(IOException::class)
    override fun execute(request: StrybokHttpRequest): StrybokHttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            val timeout = request.timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.requestMethod = request.method
            connection.useCaches = false
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            val body = request.body
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            return StrybokHttpResponse(status, stream?.use(::readCapped).orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BODY_BYTES) throw IOException("Response body is too large")
        }
        return out.toString("UTF-8")
    }

    private companion object {
        /** Resolve answers are small; anything bigger is not ours. */
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}
