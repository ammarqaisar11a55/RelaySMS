package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.sms.EventId
import io.github.ammarqaisar11a55.relaysms.sms.FilterDecision
import io.github.ammarqaisar11a55.relaysms.sms.ForwardingPolicy
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import io.github.ammarqaisar11a55.relaysms.sms.SenderMatcher
import io.github.ammarqaisar11a55.relaysms.sms.SmsAssembler
import io.github.ammarqaisar11a55.relaysms.sms.SmsPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsLogicTest {
    @Test fun `single part message is assembled`() {
        val sms = SmsAssembler.assemble(listOf(SmsPart("TESTBANK", "Your code is 000000", 1_700_000_000_000)), subscriptionId = 2)
        assertEquals(IncomingSms("TESTBANK", "Your code is 000000", 1_700_000_000_000, 1, 2), sms)
    }

    @Test fun `multipart message is joined in order with boundaries preserved`() {
        val parts = listOf(
            SmsPart("+923001234567", "Part one ends with a space ", 1000),
            SmsPart("+923001234567", "part two", 1001),
            SmsPart("+923001234567", ", part three.", 1002),
        )
        val sms = SmsAssembler.assemble(parts)!!
        assertEquals("Part one ends with a space part two, part three.", sms.body)
        assertEquals(3, sms.partCount)
        assertEquals(1000, sms.receivedAtMillis)
    }

    @Test fun `unusable broadcasts are rejected`() {
        assertNull(SmsAssembler.assemble(emptyList()))
        assertNull(SmsAssembler.assemble(listOf(SmsPart(null, "x", 1))))
        assertNull(SmsAssembler.assemble(listOf(SmsPart("  ", "x", 1))))
        assertNull(SmsAssembler.assemble(listOf(SmsPart("A", null, 1))))
        assertNull(SmsAssembler.assemble(listOf(SmsPart("A", "", 1))))
        assertNull(SmsAssembler.assemble(listOf(SmsPart("A", "x", 0))))
        assertNull(SmsAssembler.assemble(listOf(SmsPart("A", "x", 1), SmsPart("B", "y", 1))))
        assertNull(SmsAssembler.assemble(listOf(SmsPart("A", "x".repeat(SmsAssembler.MAX_BODY_CHARS + 1), 1))))
        assertNull(SmsAssembler.assemble(listOf(SmsPart("A".repeat(65), "x", 1))))
    }

    @Test fun `event id is stable for the same message and differs otherwise`() {
        val a = IncomingSms("TESTBANK", "Code 000000", 5, 1)
        assertEquals(EventId.of(a), EventId.of(a.copy()))
        assertEquals(36, EventId.of(a).length)
        assertNotEquals(EventId.of(a), EventId.of(a.copy(body = "Code 000001")))
        assertNotEquals(EventId.of(a), EventId.of(a.copy(receivedAtMillis = 6)))
        assertNotEquals(EventId.of(a), EventId.of(a.copy(sender = "TESTBANK2")))
        // Field boundaries are unambiguous: moving a character between fields changes the id.
        assertNotEquals(EventId.of(IncomingSms("AB", "C", 5, 1)), EventId.of(IncomingSms("A", "BC", 5, 1)))
    }

    @Test fun `phone numbers match across national and international spellings`() {
        val list = listOf(SenderMatcher.normalize("0300 1234567")!!)
        assertTrue(SenderMatcher.matches("+923001234567", list))
        assertTrue(SenderMatcher.matches("03001234567", list))
        assertTrue(SenderMatcher.matches("0092-300-1234567", list))
        assertTrue(!SenderMatcher.matches("+923001234568", list))
    }

    @Test fun `short codes match exactly`() {
        val list = listOf(SenderMatcher.normalize("8558")!!)
        assertTrue(SenderMatcher.matches("8558", list))
        assertTrue(!SenderMatcher.matches("18558", list))
        assertTrue(!SenderMatcher.matches("855", list))
    }

    @Test fun `alphanumeric sender ids match case-insensitively and never match numbers`() {
        val list = listOf(SenderMatcher.normalize("Test Bank")!!)
        assertTrue(SenderMatcher.matches("TESTBANK", list))
        assertTrue(SenderMatcher.matches("test bank", list))
        assertTrue(!SenderMatcher.matches("TESTBANK2", list))
        assertNotNull(SenderMatcher.normalize("Telenor"))
        assertNull(SenderMatcher.normalize("   "))
        assertNull(SenderMatcher.normalize("x".repeat(65)))
        assertNotEquals(SenderMatcher.normalize("8558"), SenderMatcher.normalize("a8558"))
    }

    @Test fun `allowlist mode forwards only listed senders`() {
        val list = listOf(SenderMatcher.normalize("TESTBANK")!!)
        assertEquals(FilterDecision.FORWARD, ForwardingPolicy.decide("TestBank", true, list))
        assertEquals(FilterDecision.FILTERED, ForwardingPolicy.decide("OTHER", true, list))
        assertEquals(FilterDecision.FILTERED, ForwardingPolicy.decide("TESTBANK", true, emptyList()))
        assertEquals(FilterDecision.FORWARD, ForwardingPolicy.decide("OTHER", false, emptyList()))
    }
}
