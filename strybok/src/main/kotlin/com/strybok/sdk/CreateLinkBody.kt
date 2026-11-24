package com.strybok.sdk

import java.math.BigDecimal
import java.math.BigInteger

/** A payload value that JSON cannot express. The message never quotes payload content. */
internal class NotJsonException(message: String) : Exception(message)

private const val MAX_DEPTH = 64

/**
 * Body of `POST /v1/public/links`. Arguments that were not given are left out (the service
 * rejects unknown fields and SDKs do not send `null`). Written by hand so the text is the
 * same on every Android version and in JVM tests, whatever `org.json` is on the classpath.
 *
 * @throws NotJsonException when [payload] holds something that is not plain JSON data.
 */
internal fun createLinkBody(
    routePath: String,
    payload: Map<String, Any?>?,
    title: String?,
    description: String?,
    imageUrl: String?,
): String {
    val out = StringBuilder(128)
    out.append("{\"routePath\":")
    writeString(out, routePath)
    if (payload != null) {
        out.append(",\"payload\":")
        writeValue(out, payload, 0)
    }
    for ((name, value) in arrayOf("title" to title, "description" to description, "imageUrl" to imageUrl)) {
        if (value == null) continue
        out.append(",\"").append(name).append("\":")
        writeString(out, value)
    }
    return out.append('}').toString()
}

private fun writeValue(out: StringBuilder, value: Any?, depth: Int) {
    if (depth > MAX_DEPTH) throw NotJsonException("payload is nested too deeply")
    when (value) {
        null -> out.append("null")
        is String -> writeString(out, value)
        is Boolean -> out.append(value.toString())
        is Int, is Long, is Short, is Byte, is BigInteger, is BigDecimal -> out.append(value.toString())
        is Double -> {
            if (value.isNaN() || value.isInfinite()) throw NotJsonException("payload contains a number that is not finite")
            out.append(value.toString())
        }
        is Float -> {
            if (value.isNaN() || value.isInfinite()) throw NotJsonException("payload contains a number that is not finite")
            out.append(value.toString())
        }
        is Map<*, *> -> {
            out.append('{')
            var first = true
            for ((key, item) in value) {
                if (key !is String) throw NotJsonException("payload contains a map whose keys are not strings")
                if (!first) out.append(',')
                first = false
                writeString(out, key)
                out.append(':')
                writeValue(out, item, depth + 1)
            }
            out.append('}')
        }
        is Iterable<*> -> writeArray(out, value.iterator(), depth)
        is Array<*> -> writeArray(out, value.iterator(), depth)
        else -> throw NotJsonException(
            "payload contains a value that is not JSON data (${value.javaClass.name}); " +
                "use strings, booleans, numbers, null, lists and maps with string keys",
        )
    }
}

private fun writeArray(out: StringBuilder, items: Iterator<*>, depth: Int) {
    out.append('[')
    var first = true
    for (item in items) {
        if (!first) out.append(',')
        first = false
        writeValue(out, item, depth + 1)
    }
    out.append(']')
}

private fun writeString(out: StringBuilder, value: String) {
    out.append('"')
    for (char in value) {
        when {
            char == '"' -> out.append("\\\"")
            char == '\\' -> out.append("\\\\")
            char == '\n' -> out.append("\\n")
            char == '\r' -> out.append("\\r")
            char == '\t' -> out.append("\\t")
            char < ' ' -> out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else -> out.append(char)
        }
    }
    out.append('"')
}
