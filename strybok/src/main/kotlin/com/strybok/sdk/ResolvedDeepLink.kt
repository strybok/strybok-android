package com.strybok.sdk

/**
 * A link resolved by the Strybok API.
 *
 * [routePath], [payload] and [campaign] are plain data chosen by whoever created
 * or shared the link. Treat them like any other untrusted input: map them onto
 * routes your app already knows, never execute them, and use campaign values as
 * labels only.
 */
public class ResolvedDeepLink @JvmOverloads constructor(
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
    /**
     * Campaign values of this open: the link's defaults, overridden by the `utm_*` parameters
     * of the tapped URL. Never `null`; [LinkCampaign.isEmpty] when there are none.
     */
    public val campaign: LinkCampaign = LinkCampaign.EMPTY,
    /**
     * `true` when this link was not tapped just now but restored after the app was installed
     * from a store (see [StrybokConfig.deferredLinks]); `false` for every link that arrived the
     * normal way. A deferred link can arrive seconds after the first screen was shown and is
     * never returned by [Strybok.getInitialLink].
     */
    @get:JvmName("isDeferred")
    public val isDeferred: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is ResolvedDeepLink &&
            url == other.url &&
            linkId == other.linkId &&
            appId == other.appId &&
            routePath == other.routePath &&
            payload == other.payload &&
            campaign == other.campaign &&
            isDeferred == other.isDeferred

    override fun hashCode(): Int =
        arrayOf(url, linkId, appId, routePath, payload, campaign, isDeferred).contentHashCode()

    override fun toString(): String = "ResolvedDeepLink($routePath, linkId: $linkId)"
}
