package io.github.ammarqaisar11a55.relaysms.sms

/**
 * Normalises and compares SMS sender addresses.
 *
 * Senders are either phone numbers ("+923001234567", "0300 1234567"), short codes ("8558") or
 * alphanumeric sender IDs chosen by the sending organisation ("HBL", "Telenor"). Alphanumeric IDs
 * are compared case-insensitively. Phone numbers are compared on their last [NATIONAL_DIGITS]
 * digits so that international and national spellings of one number match.
 */
object SenderMatcher {
    private const val NATIONAL_DIGITS = 10
    const val MAX_PATTERN_CHARS = 64

    private fun isPhoneLike(raw: String): Boolean =
        raw.isNotEmpty() && raw.all { it.isDigit() || it in "+-() ." } && raw.any { it.isDigit() }

    /** @return canonical key for a sender or user-entered filter, or null if unusable. */
    fun normalize(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > MAX_PATTERN_CHARS) return null
        return if (isPhoneLike(t)) {
            val digits = t.filter { it.isDigit() }
            if (digits.length >= NATIONAL_DIGITS) "n:" + digits.takeLast(NATIONAL_DIGITS) else "n:$digits"
        } else {
            "a:" + t.lowercase().filterNot { it.isWhitespace() }
        }
    }

    fun matches(sender: String, normalizedPatterns: Collection<String>): Boolean {
        val key = normalize(sender) ?: return false
        return key in normalizedPatterns
    }
}

enum class FilterDecision { FORWARD, FILTERED }

object ForwardingPolicy {
    /**
     * With [allowlistOnly] a sender must be on the list. Without it every sender is forwarded.
     * An empty allowlist therefore forwards nothing.
     */
    fun decide(sender: String, allowlistOnly: Boolean, normalizedPatterns: Collection<String>): FilterDecision =
        if (!allowlistOnly || SenderMatcher.matches(sender, normalizedPatterns)) FilterDecision.FORWARD else FilterDecision.FILTERED
}
