package com.strybok.sdk

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Host (lower-case), path and query parts of a URL that passed [parseStrybokLink].
 *
 * [campaignQuery] is empty or `?utm_…=…`. [parameters] is what the `parameterLinks` option adds
 * before it — the payload pairs of a path link, or `link`, `apn` and `ibi` of a Firebase long
 * link — and empty without the option. Nothing else of the URL is ever sent.
 */
internal class ParsedLink(
    val host: String,
    val path: String,
    val campaignQuery: String = "",
    val parameters: String = "",
) {
    /** The URL given to resolve: nothing of the tapped URL but host, path and the parts above. */
    val lookup: String get() = when {
        parameters.isEmpty() -> "https://$host$path$campaignQuery"
        campaignQuery.isEmpty() -> "https://$host$path?$parameters"
        else -> "https://$host$path?$parameters&${campaignQuery.substring(1)}"
    }
}

private val SLUG_PATH = Regex("/[A-Za-z0-9_-]{1,64}")
private const val HTTPS_PREFIX = "https://"

/**
 * Returns the host, path and query parts that are sent for [url] when it is a Strybok link of
 * this app, `null` otherwise. Without [parameterLinks] (and for deferred links always) only a
 * single slug path is ours, exactly as in the versions before; with it a route path or a
 * Firebase long link is ours too (SDK contract, "Links without an API call").
 *
 * Parsing is done by hand on purpose: it has no Android dependency, never throws,
 * and does not reject a link because of unusual characters in the query or
 * fragment. The path is taken as received, never decoded or normalised (as
 * `android.net.Uri` and `java.net.URI` would). Everything that is used later is validated
 * strictly: host and path here, and of the query only the campaign parameters
 * ([campaignQuery]) and the payload keys of [parameterLinks].
 */
internal fun parseStrybokLink(
    url: String,
    allowedHosts: Set<String>,
    parameterLinks: ParameterLinks? = null,
): ParsedLink? {
    if (!url.regionMatches(0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length, ignoreCase = true)) return null

    var end = url.length
    val fragment = url.indexOf('#')
    if (fragment >= 0) end = fragment
    val queryEnd = end
    val query = url.indexOf('?')
    if (query in 0 until end) end = query

    val rest = url.substring(HTTPS_PREFIX.length, end)
    val slash = rest.indexOf('/')
    // An empty path is ours only with parameterLinks, which reads it as the root.
    if (slash < 0 && parameterLinks == null) return null
    val authority = if (slash < 0) rest else rest.substring(0, slash)
    val path = if (slash < 0) "" else rest.substring(slash)

    if ('@' in authority) return null // user info
    var host = authority
    val colon = authority.lastIndexOf(':')
    if (colon >= 0) {
        if (authority.substring(colon + 1) != "443") return null
        host = authority.substring(0, colon)
    }
    host = host.lowercase(Locale.ROOT)
    if (host !in allowedHosts) return null
    val rawQuery = if (end < queryEnd) url.substring(end + 1, queryEnd) else ""
    if (parameterLinks == null) {
        if (!SLUG_PATH.matches(path)) return null
        return ParsedLink(host, path, campaignQuery(rawQuery))
    }
    return parameterLink(host, path, rawQuery, parameterLinks.payloadKeys)
}

private const val MAX_ROUTE_PATH_LENGTH = 512
private const val MAX_PAYLOAD_VALUE_LENGTH = 256

/** Paths that are never links, whatever the owner allows: anything under these two … */
private val RESERVED_DIRECTORIES = arrayOf("/.well-known", "/_strybok")

/** … and exactly these files, which browsers and crawlers ask for on their own. */
private val RESERVED_FILES = setOf(
    "/favicon.ico",
    "/robots.txt",
    "/apple-touch-icon.png",
    "/apple-touch-icon-precomposed.png",
    "/sitemap.xml",
    "/ads.txt",
    "/app-ads.txt",
    "/security.txt",
    "/humans.txt",
    "/manifest.json",
    "/site.webmanifest",
    "/browserconfig.xml",
)

/**
 * The rules of "Which URLs are ours, with the option" for a URL whose scheme, host, user info
 * and port passed: a slug, an empty path read as `/`, a Firebase long link, or a route path.
 * `null` when the URL is not ours.
 */
private fun parameterLink(host: String, rawPath: String, query: String, payloadKeys: List<String>): ParsedLink? {
    val campaign = campaignQuery(query)
    var path = rawPath
    // 1. A slug is ours as without the option; 2. an empty path is the root.
    if (!SLUG_PATH.matches(path)) {
        if (path.isEmpty()) path = "/"
        // 3. A Firebase long link; `link` on any other path is an ordinary parameter.
        if (path == "/") {
            val link = firstOccurrence(query, "link")
            if (link != null) {
                val parameters = longLinkParameters(link.value, query, payloadKeys) ?: return null
                return ParsedLink(host, path, campaign, parameters)
            }
        }
        // 4. A route path.
        if (!isRoutePath(path)) return null
    }
    return ParsedLink(host, path, campaign, payloadPairs(query, payloadKeys).joinToString("&"))
}

/**
 * `link={deep}`, then `apn` and `ibi` when the URL has them; `null` when the URL is not ours.
 * Nothing else of a long link is sent: not `afl`, `ifl`, `ofl`, `st`, `sd`, `si`, `isi`.
 */
private fun longLinkParameters(link: String?, query: String, payloadKeys: List<String>): String? {
    val deep = link?.let { deepLink(it, payloadKeys) } ?: return null
    val parameters = StringBuilder("link=").append(encodeQueryValue(deep))
    for (key in arrayOf("apn", "ibi")) {
        val occurrence = firstOccurrence(query, key) ?: continue
        // Sent as they are, even empty: the server compares them with the app's identifiers.
        val value = occurrence.value ?: return null // Cannot be decoded: the URL is not ours.
        parameters.append('&').append(key).append('=').append(encodeQueryValue(value))
    }
    return parameters.toString()
}

/**
 * The deep link [value] (the decoded `link` parameter) rebuilt from its lower-cased scheme and
 * host, its path and the payload keys of its query — nothing else of it, and never its
 * fragment; `null` when it is not a deep link.
 */
private fun deepLink(value: String, payloadKeys: List<String>): String? {
    for (char in value) if (char <= ' ' || char == '\u007F' || char == '\\') return null
    val scheme = when {
        startsWithAscii(value, "https://") -> "https"
        startsWithAscii(value, "http://") -> "http"
        else -> return null
    }
    val hostStart = scheme.length + 3
    var index = hostStart
    while (index < value.length && isHostChar(value[index])) index++
    if (index == hostStart) return null
    // The host is followed by nothing, a path, a query or a fragment: never user info or a port.
    if (index < value.length && value[index] != '/' && value[index] != '?' && value[index] != '#') return null
    val host = value.substring(hostStart, index).lowercase(Locale.ROOT)
    var rest = value.substring(index)
    val hash = rest.indexOf('#')
    if (hash >= 0) rest = rest.substring(0, hash)
    val question = rest.indexOf('?')
    val path = if (question >= 0) rest.substring(0, question) else rest
    val pairs = payloadPairs(if (question >= 0) rest.substring(question + 1) else "", payloadKeys)
    val base = "$scheme://$host${path.ifEmpty { "/" }}"
    return if (pairs.isEmpty()) base else "$base?${pairs.joinToString("&")}"
}

/**
 * `key=value` pairs of the [payloadKeys], in their order: of each key its first occurrence in
 * [query], kept when its decoded value is 1–256 UTF-16 code units without control characters.
 * A first occurrence that cannot be decoded or breaks these rules drops the key; a later one
 * never counts.
 */
private fun payloadPairs(query: String, payloadKeys: List<String>): List<String> {
    if (payloadKeys.isEmpty() || query.isEmpty()) return emptyList()
    val first = HashMap<String, String?>()
    for (part in query.split('&')) {
        val equals = part.indexOf('=')
        val key = decodeQueryText(if (equals < 0) part else part.substring(0, equals)) ?: continue
        if (key in first || key !in payloadKeys) continue
        first[key] = if (equals < 0) "" else decodeQueryText(part.substring(equals + 1))
    }
    return payloadKeys.mapNotNull { key ->
        first[key]?.takeIf(::isPayloadValue)?.let { value -> "$key=${encodeQueryValue(value)}" }
    }
}

private fun isPayloadValue(value: String): Boolean =
    value.length in 1..MAX_PAYLOAD_VALUE_LENGTH && value.none { it <= '\u001F' || it in '\u007F'..'\u009F' }

/** The first occurrence of a key in a query; [value] is `null` when it cannot be decoded. */
private class Occurrence(val value: String?)

/**
 * The first occurrence of [key] in [query], whose key is compared after decoding: `null` when
 * there is none; its value is empty for a part without `=`.
 */
private fun firstOccurrence(query: String, key: String): Occurrence? {
    if (query.isEmpty()) return null
    for (part in query.split('&')) {
        val equals = part.indexOf('=')
        if (decodeQueryText(if (equals < 0) part else part.substring(0, equals)) != key) continue
        return Occurrence(if (equals < 0) "" else decodeQueryText(part.substring(equals + 1)))
    }
    return null
}

/**
 * A route path as received: 1–512 characters of the URL path characters, every `%` followed by
 * two hexadecimal digits, no `//`, no `.` or `..` segment (also written with `%2e`), and nothing
 * a browser asks for on its own. Never decoded or normalised.
 */
private fun isRoutePath(path: String): Boolean {
    if (path.length !in 1..MAX_ROUTE_PATH_LENGTH || path[0] != '/' || "//" in path) return false
    for (index in path.indices) {
        val char = path[index]
        if (char == '%') {
            if (index + 2 >= path.length || hexValue(path[index + 1]) < 0 || hexValue(path[index + 2]) < 0) {
                return false
            }
        } else if (!isPathChar(char)) {
            return false
        }
    }
    for (segment in path.split('/')) {
        val dots = segment.replace("%2e", ".").replace("%2E", ".")
        if (dots == "." || dots == "..") return false
    }
    if (RESERVED_DIRECTORIES.any { path == it || path.startsWith("$it/") }) return false
    return path !in RESERVED_FILES
}

/** `A–Z a–z 0–9 - . _ ~ ! $ & ' ( ) * + , ; = : @` and `/`. */
private fun isPathChar(char: Char): Boolean = isUnreserved(char) || char in "/:@!\$&'()*+,;="

/** `A–Z a–z 0–9 . - _`: the characters of a deep link's host. */
private fun isHostChar(char: Char): Boolean =
    char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '.' || char == '-' || char == '_'

/** Whether [text] starts with [lowerCasePrefix], with ASCII letters in any case (and no others). */
private fun startsWithAscii(text: String, lowerCasePrefix: String): Boolean {
    if (text.length < lowerCasePrefix.length) return false
    for (index in lowerCasePrefix.indices) {
        val char = text[index]
        val lower = if (char in 'A'..'Z') char + ('a' - 'A') else char
        if (lower != lowerCasePrefix[index]) return false
    }
    return true
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * [value] as its UTF-8 bytes, each byte outside `A–Z a–z 0–9 - . _ ~` written as `%XX` with
 * upper-case digits: a space is `%20`, `+` is `%2B`. [value] is always well-formed here: it comes
 * from [decodeQueryText].
 */
internal fun encodeQueryValue(value: String): String {
    val out = StringBuilder(value.length)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val unsigned = byte.toInt() and 0xFF
        if (unsigned < 0x80 && isUnreserved(unsigned.toChar())) {
            out.append(unsigned.toChar())
        } else {
            out.append('%').append(HEX_DIGITS[unsigned shr 4]).append(HEX_DIGITS[unsigned and 0x0F])
        }
    }
    return out.toString()
}

/**
 * Percent-decodes [raw] (a key or a value of a query) as UTF-8, with `+` as a space. `null` when
 * it cannot be decoded: a `%` without two hexadecimal digits, a lone surrogate, or bytes that
 * are not UTF-8. Nothing is replaced: what cannot be read is not sent. The UTF-8 rules are
 * written out here so that every Android version reads a value the same way.
 */
internal fun decodeQueryText(raw: String): String? {
    if (raw.none { it == '%' || it >= '\u0080' }) return raw.replace('+', ' ')
    val bytes = ByteArrayOutputStream(raw.length)
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        when {
            char == '%' -> {
                if (index + 2 >= raw.length) return null
                val high = hexValue(raw[index + 1])
                val low = hexValue(raw[index + 2])
                if (high < 0 || low < 0) return null
                bytes.write(high * 16 + low)
                index += 3
            }
            char == '+' -> {
                bytes.write(0x20)
                index += 1
            }
            char < '\u0080' -> {
                bytes.write(char.code)
                index += 1
            }
            char.isHighSurrogate() && index + 1 < raw.length && raw[index + 1].isLowSurrogate() -> {
                writeUtf8(bytes, Character.toCodePoint(char, raw[index + 1]))
                index += 2
            }
            char.isSurrogate() -> return null
            else -> {
                writeUtf8(bytes, char.code)
                index += 1
            }
        }
    }
    return utf8OrNull(bytes.toByteArray())
}

/** Writes [codePoint] (U+0080 or above, not a surrogate) as UTF-8. */
private fun writeUtf8(out: ByteArrayOutputStream, codePoint: Int) {
    when {
        codePoint < 0x800 -> {
            out.write(0xC0 or (codePoint shr 6))
        }
        codePoint < 0x10000 -> {
            out.write(0xE0 or (codePoint shr 12))
            out.write(0x80 or ((codePoint shr 6) and 0x3F))
        }
        else -> {
            out.write(0xF0 or (codePoint shr 18))
            out.write(0x80 or ((codePoint shr 12) and 0x3F))
            out.write(0x80 or ((codePoint shr 6) and 0x3F))
        }
    }
    out.write(0x80 or (codePoint and 0x3F))
}

/**
 * [bytes] decoded as UTF-8, or `null` when they are not UTF-8: a truncated or overlong sequence,
 * an encoded surrogate or a value above U+10FFFF. A byte-order mark is kept as U+FEFF.
 */
private fun utf8OrNull(bytes: ByteArray): String? {
    val out = StringBuilder(bytes.size)
    var index = 0
    while (index < bytes.size) {
        val first = bytes[index].toInt() and 0xFF
        if (first < 0x80) {
            out.append(first.toChar())
            index += 1
            continue
        }
        // The bounds of the second byte rule out overlong forms, surrogates and values above U+10FFFF.
        var lower = 0x80
        var upper = 0xBF
        val length: Int
        var codePoint: Int
        when (first) {
            in 0xC2..0xDF -> {
                length = 2
                codePoint = first and 0x1F
            }
            in 0xE0..0xEF -> {
                length = 3
                codePoint = first and 0x0F
                if (first == 0xE0) lower = 0xA0
                if (first == 0xED) upper = 0x9F
            }
            in 0xF0..0xF4 -> {
                length = 4
                codePoint = first and 0x07
                if (first == 0xF0) lower = 0x90
                if (first == 0xF4) upper = 0x8F
            }
            else -> return null
        }
        if (index + length > bytes.size) return null
        for (offset in 1 until length) {
            val next = bytes[index + offset].toInt() and 0xFF
            if (next < lower || next > upper) return null
            lower = 0x80
            upper = 0xBF
            codePoint = (codePoint shl 6) or (next and 0x3F)
        }
        out.appendCodePoint(codePoint)
        index += length
    }
    return out.toString()
}

private val CAMPAIGN_QUERY_KEYS = arrayOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content")
private const val MAX_CAMPAIGN_VALUE_LENGTH = 64

/**
 * The campaign query of the lookup URL (SDK contract, "Campaign query"): `""`, or `?` followed
 * by those of the five `utm_*` parameters of [rawQuery] (a query string without `?` and
 * without the fragment) whose first occurrence has a valid value, in a fixed order.
 *
 * Every other parameter is dropped, it may hold private data. Never throws.
 */
internal fun campaignQuery(rawQuery: String): String {
    if (rawQuery.isEmpty()) return ""
    val values = arrayOfNulls<String>(CAMPAIGN_QUERY_KEYS.size)
    val seen = BooleanArray(CAMPAIGN_QUERY_KEYS.size)
    for (part in rawQuery.split('&')) {
        val equals = part.indexOf('=')
        val key = decodeUnreserved(if (equals < 0) part else part.substring(0, equals)) ?: continue
        val index = CAMPAIGN_QUERY_KEYS.indexOf(key)
        if (index < 0 || seen[index]) continue
        seen[index] = true // The first occurrence counts, even when its value is dropped.
        val value = if (equals < 0) null else decodeUnreserved(part.substring(equals + 1))
        if (value != null && value.length in 1..MAX_CAMPAIGN_VALUE_LENGTH) values[index] = value
    }
    val query = StringBuilder()
    for (index in CAMPAIGN_QUERY_KEYS.indices) {
        val value = values[index] ?: continue
        query.append(if (query.isEmpty()) '?' else '&').append(CAMPAIGN_QUERY_KEYS[index]).append('=').append(value)
    }
    return query.toString()
}

/**
 * Percent-decodes [raw] (`+` is a space) and returns the result only when it consists of
 * `A–Z a–z 0–9 . _ ~ -`; `null` otherwise.
 *
 * Campaign keys and valid values are made of these characters only, so anything else — a space,
 * a malformed escape, any byte of a multi-byte UTF-8 sequence (valid or not) — can only belong
 * to a key that is not ours or to a value that is dropped. No UTF-8 decoding is needed for that.
 */
private fun decodeUnreserved(raw: String): String? {
    val decoded = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        var char = raw[index]
        if (char == '%') {
            if (index + 2 >= raw.length) return null
            val high = hexValue(raw[index + 1])
            val low = hexValue(raw[index + 2])
            if (high < 0 || low < 0) return null
            char = (high * 16 + low).toChar()
            index += 3
        } else {
            index += 1
        }
        if (!isUnreserved(char)) return null
        decoded.append(char)
    }
    return decoded.toString()
}

private fun hexValue(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
}

private fun isUnreserved(char: Char): Boolean =
    char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '.' || char == '_' || char == '~' || char == '-'

/**
 * Picks the link URL out of the parts of an Android intent, or `null` when the
 * intent does not carry a freshly tapped link.
 *
 * An activity brought back from the recents screen is started with its original
 * intent again; [launchedFromHistoryFlag] marks that case, and the old link must
 * not be handled a second time.
 */
internal fun linkUrlFromIntentParts(
    action: String?,
    dataString: String?,
    flags: Int,
    viewAction: String,
    launchedFromHistoryFlag: Int,
): String? = when {
    action != viewAction -> null
    flags and launchedFromHistoryFlag != 0 -> null
    else -> dataString
}
