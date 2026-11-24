package com.strybok.sdk

/**
 * A link resolved by the Strybok API.
 *
 * [routePath] and [payload] are plain data chosen by whoever created the link.
 * Treat them like any other untrusted input: map them onto routes your app
 * already knows, never execute them.
 */
public class ResolvedDeepLink(
    /** The full URL that opened the app, exactly as received (query and fragment included). */
    public val url: String,
    public val linkId: String,
    public val appId: String,
    /** In-app route, always starting with a single `/` (for example `/invite/abc123`). */
    public val routePath: String,
    /**
     * Decoded JSON object attached to the link; empty when none was set. Values are
     * `String`, `Boolean`, `Int`/`Long`/`Double` (or another `Number`), `List`, `Map` or `null`.
     */
    public val payload: Map<String, Any?>,
) {
    override fun equals(other: Any?): Boolean =
        other is ResolvedDeepLink &&
            url == other.url &&
            linkId == other.linkId &&
            appId == other.appId &&
            routePath == other.routePath &&
            payload == other.payload

    override fun hashCode(): Int =
        arrayOf(url, linkId, appId, routePath, payload).contentHashCode()

    override fun toString(): String = "ResolvedDeepLink($routePath, linkId: $linkId)"
}
