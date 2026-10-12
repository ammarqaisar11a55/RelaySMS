package io.github.ammarqaisar11a55.relaysms.otp

/** A substring of a message that is probably a one-time code. [start] and [end] index the original text. */
data class OtpCandidate(val code: String, val start: Int, val end: Int, val score: Int)

data class OtpResult(val candidates: List<OtpCandidate>) {
    /** The most likely code, or null when the message does not look like it carries one. */
    val best: OtpCandidate? get() = candidates.firstOrNull()

    /** True when more than one code is about equally likely; the UI then offers all of them. */
    val ambiguous: Boolean get() = candidates.size > 1 && candidates[0].score - candidates[1].score <= 2

    companion object {
        val NONE = OtpResult(emptyList())
    }
}

/**
 * Finds likely verification codes in a message, entirely on this device.
 *
 * A number is treated as a code only when the message also talks about codes ("OTP",
 * "verification", "PIN" ...) and the number's surroundings do not mark it as something else: an
 * amount, an account or card number, a reference, a phone number, a date or a duration. This is a
 * heuristic. It is tuned to prefer missing a code over presenting a wrong number as one, because
 * the user can always read and copy from the full message.
 */
object OtpDetector {
    private const val MAX_SCAN_CHARS = 2_000
    private const val THRESHOLD = 4

    private val STRONG = Regex(
        "\\b(otp|one[- ]?time|passcode|pass ?code|password|verification|verify|verifying|verified|authenti\\w*|" +
            "security code|secure code|login code|log-?in code|access code|confirmation code|activation code|2fa|tac)\\b" +
            "|او ٹی پی|کوڈ|رمز|پاس ?ورڈ|توثیق",
    )
    private val WEAK = Regex("\\b(codes?|pins?)\\b")

    /** 4-8 digits, optionally written in two groups, or a short letter prefix as in "G-123456". */
    private val NUMERIC = Regex("(?<![\\w.,/+-])(?:[A-Za-z]{1,3}-)?(\\d{3}[ -]\\d{3}|\\d{4}[ -]\\d{4}|\\d{4,8})(?![\\w/]|[.,]\\d)")

    /** Mixed letters and digits, e.g. "K7M2QX". Only considered right after a code word. */
    private val ALPHANUMERIC = Regex("\\b(?=[A-Z0-9]*\\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{5,8}\\b")

    private val LEAD_IN = Regex("(\\bis|\\bare|:|=|\\buse|\\benter|\\botp|\\bcode|\\bpin|\\bpassword|کوڈ|رمز)\\W{0,3}$")
    private val MONEY_BEFORE = Regex("(\\brs\\.?|\\bpkr|\\busd|\\binr|\\baed|\\$|\\bamount|\\bamt|\\bbalance|\\bbal|\\bdebited|\\bcredited|\\bpaid|\\bfee|\\bcharges?)\\W{0,3}(of\\W{0,2})?$")
    private val UNIT_AFTER = Regex("^\\W{0,2}(pkr|rs\\b|usd|/-|rupees|sec\\b|secs\\b|seconds|min\\b|mins\\b|minutes|hours|hrs|days|mb\\b|gb\\b|sms\\b|points)")
    private val IDENTIFIER_BEFORE = Regex(
        "(a/c|acct|account|card|ending|ends with|iban|ref|reference|txn|trx|tid|transaction|invoice|order|ticket|" +
            "helpline|call|dial|contact|whatsapp|x{2,}|\\*{2,})\\W{0,3}(no\\.?|number|#|id)?\\W{0,3}$",
    )

    fun detect(message: String): OtpResult {
        val text = normalizeDigits(message.take(MAX_SCAN_CHARS))
        val lower = text.lowercase()
        val strong = STRONG.findAll(lower).map { it.range }.toList()
        val weak = WEAK.findAll(lower).map { it.range }.toList()
        if (strong.isEmpty() && weak.isEmpty()) return OtpResult.NONE

        val found = ArrayList<OtpCandidate>()
        for (m in NUMERIC.findAll(text)) {
            val group = m.groups[1] ?: continue
            val digits = group.value.filter(Char::isDigit)
            var score = keywordScore(group.range, strong, weak)
            val before = lower.substring((group.range.first - 28).coerceAtLeast(0), group.range.first)
            val after = lower.substring(group.range.last + 1, (group.range.last + 16).coerceAtMost(lower.length))
            if (LEAD_IN.containsMatchIn(before)) score += 2
            if (digits.length == 6) score += 1
            if (digits.length == 4) score -= 1
            if (MONEY_BEFORE.containsMatchIn(before) || UNIT_AFTER.containsMatchIn(after)) score -= 6
            if (IDENTIFIER_BEFORE.containsMatchIn(before)) score -= 6
            // Dates and times: 2026-10-12, 12:30, 10/12.
            if (after.length >= 2 && after[0] in "-/:" && after[1].isDigit()) score -= 6
            if (score >= THRESHOLD) found += OtpCandidate(digits, group.range.first, group.range.last + 1, score)
        }
        for (m in ALPHANUMERIC.findAll(text)) {
            if (found.any { m.range.first < it.end && it.start <= m.range.last }) continue
            val before = lower.substring((m.range.first - 28).coerceAtLeast(0), m.range.first)
            if (!LEAD_IN.containsMatchIn(before)) continue
            val score = keywordScore(m.range, strong, weak) + 1
            if (score >= THRESHOLD) found += OtpCandidate(m.value, m.range.first, m.range.last + 1, score)
        }
        return OtpResult(found.sortedWith(compareByDescending<OtpCandidate> { it.score }.thenBy { it.start }).take(4))
    }

    fun looksLikeOtp(message: String): Boolean = detect(message).best != null

    /** Points for the closest code word: before the number counts more than after it. */
    private fun keywordScore(candidate: IntRange, strong: List<IntRange>, weak: List<IntRange>): Int {
        fun near(ranges: List<IntRange>, beforeWithin: Int, afterWithin: Int): Pair<Boolean, Boolean> {
            var before = false
            var after = false
            for (r in ranges) {
                if (r.last < candidate.first && candidate.first - r.last <= beforeWithin) before = true
                if (r.first > candidate.last && r.first - candidate.last <= afterWithin) after = true
            }
            return before to after
        }
        val (strongBefore, strongAfter) = near(strong, 60, 32)
        val (weakBefore, weakAfter) = near(weak, 40, 24)
        return when {
            strongBefore -> 4
            strongAfter || weakBefore -> 3
            weakAfter -> 2
            else -> 0
        }
    }

    /** Maps Arabic-Indic and Eastern Arabic-Indic digits to ASCII so Urdu and Arabic messages work. */
    private fun normalizeDigits(text: String): String {
        if (text.none { it in '٠'..'٩' || it in '۰'..'۹' }) return text
        return buildString(text.length) {
            for (c in text) {
                append(
                    when (c) {
                        in '٠'..'٩' -> '0' + (c - '٠')
                        in '۰'..'۹' -> '0' + (c - '۰')
                        else -> c
                    },
                )
            }
        }
    }
}
