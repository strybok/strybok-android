package com.strybok.sdk

/**
 * Produces the Google Play Integrity token that proves a [Strybok.createLink] request comes from
 * a genuine build of the app on a genuine device. Only used when [StrybokConfig.attestation] is
 * switched on.
 *
 * The SDK itself does not depend on Google's libraries, so apps for markets without Google Play
 * can use it unchanged. An app that wants the extra protection adds
 * `com.google.android.play:integrity` and passes a [PlayIntegrityTokenProvider]; an app that
 * already talks to Play Integrity can implement this interface on top of its own code.
 *
 * [requestIntegrityToken] is a blocking call. The SDK invokes it on a background thread, only
 * from inside `createLink`, and interrupts that thread when the token is no longer needed.
 */
public fun interface StrybokIntegrityTokenProvider {
    /**
     * Makes a Play Integrity **standard** request bound to [requestHash] and returns the token
     * exactly as Google returned it.
     *
     * Return `null` (or throw) when this device or build cannot produce one: no Google Play
     * services, no Play Store, a network failure, Google's quota. The SDK then sends the
     * create-link request without a proof and reports the service's answer; it never fails on
     * its own because of this.
     *
     * @param requestHash the value for `StandardIntegrityTokenRequest.setRequestHash`: unpadded
     *   base64url of the SHA-256 of the service's one-time challenge, 43 characters.
     */
    @Throws(Exception::class)
    public fun requestIntegrityToken(requestHash: String): String?
}
