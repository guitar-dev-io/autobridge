package dev.autobridge.support

/**
 * The ways to tip the developer ("ค่าขนม"), shared by the phone's support card and the car's
 * support screen so both always offer the same tabs in the same order.
 *
 * A link channel with a blank URL is simply not offered: set [GITHUB_SPONSORS_URL] or
 * [KO_FI_URL] and its tab appears on both surfaces. PromptPay has no URL - it is the bundled QR
 * image - and it is never offered on the `safe` (Play) build, whose payment policy forbids
 * off-store payment prompts.
 *
 * Pure Kotlin so the rules are unit-tested without a device.
 */
object SupportChannels {

    const val GITHUB_SPONSORS_URL = "https://github.com/sponsors/guitar-dev-io"
    const val KO_FI_URL = "https://ko-fi.com/guitardev"

    /** Tab order; the first one offered is the tab the card opens on (GitHub Sponsors). */
    enum class Kind { GITHUB_SPONSORS, KO_FI, PROMPTPAY }

    /** One tab. [url] is null for PromptPay, which is shown as its bundled QR instead. */
    data class Channel(val kind: Kind, val label: String, val url: String?) {
        /** The URL as printed under the QR: no scheme, no trailing slash. */
        val displayUrl: String? get() = url?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')
    }

    private val defaultUrls = mapOf(
        Kind.GITHUB_SPONSORS to GITHUB_SPONSORS_URL,
        Kind.KO_FI to KO_FI_URL,
    )

    /** The channels to offer, in tab order. Never empty while [GITHUB_SPONSORS_URL] or [KO_FI_URL] is set. */
    fun available(safeBuild: Boolean, urls: Map<Kind, String> = defaultUrls): List<Channel> = buildList {
        Kind.entries.forEach { kind ->
            when (kind) {
                Kind.PROMPTPAY -> if (!safeBuild) add(Channel(kind, label(kind), null))
                else -> urls[kind]?.takeIf { it.isNotBlank() }?.let { add(Channel(kind, label(kind), it.trim())) }
            }
        }
    }

    /** Brand names, so they are not translated. */
    private fun label(kind: Kind): String = when (kind) {
        Kind.GITHUB_SPONSORS -> "GitHub Sponsors"
        Kind.KO_FI -> "Ko-fi"
        Kind.PROMPTPAY -> "PromptPay"
    }
}
