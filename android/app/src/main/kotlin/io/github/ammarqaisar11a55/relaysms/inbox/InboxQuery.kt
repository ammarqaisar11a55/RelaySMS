package io.github.ammarqaisar11a55.relaysms.inbox

enum class InboxFilter { ALL, UNREAD, CODES }

enum class InboxSort { NEWEST_FIRST, OLDEST_FIRST }

/** Filtering, searching and sorting of the decrypted inbox. Runs in memory, on this device only. */
object InboxQuery {
    fun apply(messages: List<InboxMessage>, query: String, filter: InboxFilter, sort: InboxSort): List<InboxMessage> {
        val needle = query.trim().lowercase()
        val digits = needle.filter(Char::isDigit)
        val filtered = messages.asSequence()
            .filter {
                when (filter) {
                    InboxFilter.ALL -> true
                    InboxFilter.UNREAD -> !it.isRead
                    InboxFilter.CODES -> it.hasOtp
                }
            }
            .filter { needle.isEmpty() || matches(it, needle, digits) }
            .toList()
        val order = compareBy<InboxMessage>({ it.receivedAtMillis }, { it.id })
        return if (sort == InboxSort.NEWEST_FIRST) filtered.sortedWith(order.reversed()) else filtered.sortedWith(order)
    }

    private fun matches(m: InboxMessage, needle: String, digits: String): Boolean {
        if (m.address.lowercase().contains(needle) || m.body.lowercase().contains(needle)) return true
        // "0300 1234567" should find a sender stored as "+923001234567".
        return digits.length >= 4 && digits == needle.filterNot { it.isWhitespace() || it in "+-()" } &&
            m.address.filter(Char::isDigit).contains(digits.trimStart('0'))
    }
}
