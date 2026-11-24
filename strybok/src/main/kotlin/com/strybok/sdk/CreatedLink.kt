package com.strybok.sdk

/** A link made by [Strybok.createLink]. */
public class CreatedLink(
    /** The short link to share, always `https://`. */
    public val url: String,
    public val slug: String,
    /** The in-app route the link opens, as stored by the service. */
    public val routePath: String,
    public val linkId: String,
) {
    override fun equals(other: Any?): Boolean =
        other is CreatedLink &&
            url == other.url &&
            slug == other.slug &&
            routePath == other.routePath &&
            linkId == other.linkId

    override fun hashCode(): Int = arrayOf(url, slug, routePath, linkId).contentHashCode()

    override fun toString(): String = "CreatedLink($url, linkId: $linkId)"
}
