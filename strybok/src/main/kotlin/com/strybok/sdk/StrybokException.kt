package com.strybok.sdk

/**
 * Base class of every error reported by the SDK.
 *
 * The hierarchy is sealed, so a Kotlin `when` over it is exhaustive.
 */
public sealed class StrybokException(
    message: String,
    /** The link that was being handled, exactly as it was received, when known. */
    public val url: String?,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The URL is not a Strybok link of this app: not `https`, a host outside
 * `allowedHosts`, credentials or a custom port, or a path that is not a single slug.
 * Thrown by [Strybok.resolve] before any network call.
 */
public class UnsupportedLinkException(message: String, url: String?) : StrybokException(message, url)

/** The service does not know this link, or it is disabled or expired (HTTP 404). */
public class LinkNotFoundException(url: String?) :
    StrybokException("No active link matches this URL", url)

/** The request could not be completed (offline, DNS, connection reset) after all retries. */
public class StrybokNetworkException(message: String, url: String?, cause: Throwable?) :
    StrybokException(message, url, cause)

/** The service did not answer in time, after all retries. */
public class StrybokTimeoutException(url: String?) :
    StrybokException("The resolve request timed out", url)

/** The service answered with an unexpected status code or a malformed body. */
public class StrybokServerException(
    message: String,
    url: String?,
    /** HTTP status of the answer; `200` when the body was malformed. */
    public val statusCode: Int,
) : StrybokException(message, url)
