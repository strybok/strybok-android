package com.strybok.sdk

import java.util.Locale

/** Host (lower-case) and path of a URL that passed [parseStrybokLink]. */
internal class ParsedLink(val host: String, val path: String)

private val SLUG_PATH = Regex("/[A-Za-z0-9_-]{1,64}")
private const val HTTPS_PREFIX = "https://"

/**
 * Returns the host and slug path of [url] when it is a Strybok link of this app,
 * `null` otherwise.
 *
 * Parsing is done by hand on purpose: it has no Android dependency, never throws,
 * and does not reject a link because of unusual characters in the query or
 * fragment, which are ignored anyway. Everything that is used later (host and
 * path) is validated strictly.
 */
internal fun parseStrybokLink(url: String, allowedHosts: Set<String>): ParsedLink? {
    if (!url.regionMatches(0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length, ignoreCase = true)) return null

    var end = url.length
    val fragment = url.indexOf('#')
    if (fragment >= 0) end = fragment
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
    return ParsedLink(host, path)
}

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
