package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.database.MessageDirection
import io.github.ammarqaisar11a55.relaysms.core.database.MessageOrigin
import io.github.ammarqaisar11a55.relaysms.core.database.MessageStatus
import io.github.ammarqaisar11a55.relaysms.inbox.InboxFilter
import io.github.ammarqaisar11a55.relaysms.inbox.InboxMessage
import io.github.ammarqaisar11a55.relaysms.inbox.InboxQuery
import io.github.ammarqaisar11a55.relaysms.inbox.InboxSort
import io.github.ammarqaisar11a55.relaysms.otp.OtpDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/** Coarse upper bounds that would catch an accidental quadratic algorithm, not benchmarks. */
class PerformanceTest {
    private val history = List(20_000) { i ->
        InboxMessage(
            "id$i", if (i % 3 == 0) "TESTBANK" else "+92300${1000000 + i}", "Synthetic message number $i with code ${100000 + i}",
            i.toLong(), i.toLong(), MessageDirection.INCOMING, MessageOrigin.RELAY, MessageStatus.RECEIVED, i % 2 == 0, i % 3 == 0, 1, "k",
        )
    }

    @Test fun `searching and sorting a large history stays fast`() {
        InboxQuery.apply(history, "warm up", InboxFilter.ALL, InboxSort.NEWEST_FIRST)
        var found = 0
        val ms = measureTimeMillis {
            repeat(5) {
                found = InboxQuery.apply(history, "number 1999", InboxFilter.ALL, InboxSort.NEWEST_FIRST).size
                InboxQuery.apply(history, "", InboxFilter.CODES, InboxSort.OLDEST_FIRST)
                InboxQuery.apply(history, "testbank", InboxFilter.UNREAD, InboxSort.NEWEST_FIRST)
            }
        }
        assertEquals(11, found)
        assertTrue("15 queries over 20,000 messages took $ms ms", ms < 4_000)
    }

    @Test fun `code detection is linear in message length`() {
        val messages = List(2_000) { "Dear customer, your OTP for transaction of PKR ${1000 + it} is ${100000 + it}. Do not share it. Ref ${900000000 + it}." }
        messages.take(50).forEach(OtpDetector::detect)
        val ms = measureTimeMillis { messages.forEach { assertEquals((100000 + messages.indexOf(it)).toString().length, OtpDetector.detect(it).best!!.code.length) } }
        assertTrue("2,000 detections took $ms ms", ms < 4_000)
        // Hostile input: a very long run of digits and separators must not blow up.
        val hostile = "OTP " + "123 456 ".repeat(5_000)
        assertTrue(measureTimeMillis { OtpDetector.detect(hostile) } < 2_000)
    }
}
