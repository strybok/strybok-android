package com.strybok.sdk

import java.util.Locale

/**
 * Host (lower-case), path and campaign query of a URL that passed [parseStrybokLink].
 *
 * [campaignQuery] is empty or `?utm_…=…`: the only part of the URL's query that is ever sent.
 */
internal class ParsedLink(val host: String, val path: String, val campaignQuery: String = "") {
    /** The URL given to resolve: nothing of the tapped URL but host, slug and campaign query. */
    val lookup: String get() = "https://$host$path$campaignQuery"
}

private val SLUG_PATH = Regex("/[A-Za-z0-9_-]{1,64}")
private const val HTTPS_PREFIX = "https://"

/**
 * Returns the host and slug path of [url] when it is a Strybok link of this app,
 * `null` otherwise.
 *
 * Parsing is done by hand on purpose: it has no Android dependency, never throws,
 * and does not reject a link because of unusual characters in the query or
 * fragment. Everything that is used later is validated strictly: host and path
 * here, and of the query only the campaign parameters ([campaignQuery]).
 */
internal fun parseStrybokLink(url: String, allowedHosts: Set<String>): ParsedLink? {
    if (!url.regionMatches(0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length, ignoreCase = true)) return null

    var end = url.length
    val fragment = url.indexOf('#')
    if (fragment >= 0) end = fragment
    val queryEnd = end
    val query = url.indexOf('?')
    if (query in 0 until end) end = query

    val rest = url.substring(HTTPS_PREFIX.length, end)
    val slash = rest.indexOf('/')
    if (slash < 0) return null
    val authority = rest.substring(0, slash)
    val path = rest.substring(slash)

    if ('@' in authority) return null // user info
    var host = authority
    val colon = authority.lastIndexOf(':')
    if (colon >= 0) {
        if (authority.substring(colon + 1) != "443") return null
        host = authority.substring(0, colon)
    }
    host = host.lowercase(Locale.ROOT)
    if (host !in allowedHosts) return null
    if (!SLUG_PATH.matches(path)) return null
    val rawQuery = if (end < queryEnd) url.substring(end + 1, queryEnd) else ""
    return ParsedLink(host, path, campaignQuery(rawQuery))
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
