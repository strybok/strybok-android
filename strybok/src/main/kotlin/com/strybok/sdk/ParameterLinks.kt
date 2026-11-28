package com.strybok.sdk

import java.util.Collections
import java.util.Locale

/**
 * Links without an API call: path links such as `https://<host>/product/42?id=7` and Firebase
 * long links `https://<host>/?link=…`, which the owner switches on per application in the
 * dashboard. Pass it as [StrybokConfig.parameterLinks].
 *
 * Without it the SDK accepts only links whose path is a single slug and sends nothing of the
 * query but the `utm_*` values, exactly as before.
 */
public class ParameterLinks(
    /**
     * `parameterLinks.payloadKeys` of the integration guide (`GET /v1/apps/:appId/integration`,
     * shown on the dashboard's setup screen), in that order. An empty list is fine: path links
     * then carry no payload. While the guide gives `null`, do not set the option at all.
     */
    payloadKeys: List<String>,
) {
    /**
     * The query keys whose values are sent with a path link, and with the deep link of a
     * Firebase long link, in the owner's order: at most 20 keys of 1–40 characters of
     * `A–Z a–z 0–9 _ . -`, none twice, none starting with `utm_` in any case and none of `st`,
     * `sd`, `si` and `strybok_click`; [StrybokConfig] refuses anything else.
     *
     * The keys are compiled into the app: a key the owner adds later reaches users with the next
     * release. Do not list keys that carry personal data.
     */
    public val payloadKeys: List<String> = Collections.unmodifiableList(ArrayList(payloadKeys))

    override fun equals(other: Any?): Boolean = other is ParameterLinks && payloadKeys == other.payloadKeys

    override fun hashCode(): Int = payloadKeys.hashCode()

    override fun toString(): String = "ParameterLinks(payloadKeys: $payloadKeys)"
}

private const val MAX_PAYLOAD_KEYS = 20
private val PAYLOAD_KEY = Regex("[A-Za-z0-9_.-]{1,40}")

/** Query keys Strybok reads itself (previews and deferred links); `utm_*` too. */
private val OWN_KEYS = setOf("st", "sd", "si", "strybok_click")

/** Why [keys] is not a valid list of payload keys, or `null` when it is. */
internal fun payloadKeysProblem(keys: List<String>): String? {
    if (keys.size > MAX_PAYLOAD_KEYS) return "at most $MAX_PAYLOAD_KEYS payload keys are allowed"
    val seen = HashSet<String>()
    // Read as List<*>: a list from Java may hold a null.
    for (key in keys as List<*>) {
        if (key !is String || !PAYLOAD_KEY.matches(key)) {
            return "the payload key \"$key\" is not 1–40 characters of A–Z a–z 0–9 _ . -"
        }
        // Compared exactly: `ST` is an ordinary key. The character rule above makes the key ASCII.
        if (key.lowercase(Locale.ROOT).startsWith("utm_") || key in OWN_KEYS) {
            return "the payload key \"$key\" is read by Strybok itself"
        }
        if (!seen.add(key)) return "the payload key \"$key\" is listed twice"
    }
    return null
}
