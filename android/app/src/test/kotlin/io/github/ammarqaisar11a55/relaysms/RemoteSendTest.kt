package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.sms.send.RemoteSendPolicy
import io.github.ammarqaisar11a55.relaysms.sms.send.ResultReporter
import io.github.ammarqaisar11a55.relaysms.sms.send.SendFailure
import io.github.ammarqaisar11a55.relaysms.sms.send.SendIds
import io.github.ammarqaisar11a55.relaysms.sms.send.SendRequest
import io.github.ammarqaisar11a55.relaysms.sms.send.SendRequestHandler
import io.github.ammarqaisar11a55.relaysms.sms.send.SendRequestLog
import io.github.ammarqaisar11a55.relaysms.sms.send.SendResult
import io.github.ammarqaisar11a55.relaysms.sms.send.SendSettings
import io.github.ammarqaisar11a55.relaysms.sms.send.SendStatus
import io.github.ammarqaisar11a55.relaysms.sms.send.SmsSendOutcome
import io.github.ammarqaisar11a55.relaysms.sms.send.SmsSender
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSendTest {
    private val now = 10_000_000_000L
    private fun request(to: String = "+923001234567", text: String = "Synthetic outgoing text", created: Long = now - 1000, expires: Long = now + 3_600_000) =
        SendRequest(to, text, created, expires)
    private fun reject(r: SendRequest = request(), enabled: Boolean = true, permission: Boolean = true, sent: Int = 0, limit: Int = 20) =
        RemoteSendPolicy.reject(r, now, enabled, permission, sent, limit)

    @Test fun `a valid request from the paired phone is allowed`() {
        assertNull(reject())
        assertNull(reject(request(to = "8558")))
        assertNull(reject(request(to = "0300 123-4567")))
    }

    @Test fun `sending stays off until the source phone's owner turns it on and grants permission`() {
        assertEquals(SendFailure.DISABLED, reject(enabled = false))
        assertEquals(SendFailure.NO_PERMISSION, reject(permission = false))
        // "Off" wins over everything else: nothing about the request matters.
        assertEquals(SendFailure.DISABLED, reject(request(to = "junk"), enabled = false, permission = false))
    }

    @Test fun `stale future-dated and over-long-lived requests are refused`() {
        assertEquals(SendFailure.EXPIRED, reject(request(expires = now - 1)))
        assertEquals(SendFailure.EXPIRED, reject(request(created = now - RemoteSendPolicy.MAX_AGE_MS - 1)))
        assertEquals(SendFailure.EXPIRED, reject(request(created = now + RemoteSendPolicy.CLOCK_SKEW_MS + 1, expires = now + 10_000_000)))
        assertEquals(SendFailure.EXPIRED, reject(request(created = now, expires = now + 24 * 3_600_000L)))
        assertNull(reject(request(created = now + 60_000, expires = now + 120_000)))
    }

    @Test fun `recipients and message text are validated`() {
        for (bad in listOf("", "  ", "12", "abc", "+92+300", "0300123456789012345", "tel:0300", "0300;rm", "TESTBANK")) {
            assertEquals("recipient '$bad'", SendFailure.INVALID_RECIPIENT, reject(request(to = bad)))
        }
        assertEquals("+923001234567", RemoteSendPolicy.normalizeRecipient(" +92 (300) 123-4567 "))
        assertEquals("03001234567", RemoteSendPolicy.normalizeRecipient("0300.1234567"))
        assertEquals(SendFailure.INVALID_MESSAGE, reject(request(text = "   ")))
        assertEquals(SendFailure.INVALID_MESSAGE, reject(request(text = "x".repeat(RemoteSendPolicy.MAX_TEXT_CHARS + 1))))
        assertNull(reject(request(text = "x".repeat(RemoteSendPolicy.MAX_TEXT_CHARS))))
    }

    @Test fun `the hourly limit is enforced`() {
        assertNull(reject(sent = 19, limit = 20))
        assertEquals(SendFailure.RATE_LIMITED, reject(sent = 20, limit = 20))
        assertEquals(SendFailure.RATE_LIMITED, reject(sent = 5, limit = 5))
    }

    @Test fun `result ids are stable per outcome`() {
        assertEquals(SendIds.resultId("a", SendStatus.SENT), SendIds.resultId("a", SendStatus.SENT))
        assertNotEquals(SendIds.resultId("a", SendStatus.SENT), SendIds.resultId("a", SendStatus.DELIVERED))
        assertNotEquals(SendIds.resultId("a", SendStatus.SENT), SendIds.resultId("b", SendStatus.SENT))
        assertTrue(Regex("^[0-9a-f-]{36}$").matches(SendIds.resultId("a", SendStatus.SENT)))
    }

    // ---- handler ----

    private val log = FakeSendLog()
    private val sender = FakeSmsSender()
    private val results = mutableListOf<SendResult>()
    private var enabled = true
    private var limit = 20
    private var clock = now
    private val handler = SendRequestHandler(
        object : SendSettings { override suspend fun enabled() = enabled; override suspend fun perHourLimit() = limit },
        sender, log, ResultReporter { results += it }, { clock },
    )

    @Test fun `an accepted request is sent once and its outcomes are reported separately`() = runBlocking {
        handler.handle("r1", request())
        assertEquals(listOf(Triple("r1", "+923001234567", "Synthetic outgoing text")), sender.sent)
        assertTrue("nothing is reported before the network answers", results.isEmpty())
        handler.onOutcome("r1", SmsSendOutcome.Sent)
        assertEquals(listOf(SendStatus.SENT), results.map { it.status })
        handler.onOutcome("r1", SmsSendOutcome.Delivered)
        assertEquals(listOf(SendStatus.SENT, SendStatus.DELIVERED), results.map { it.status })
        // Late or repeated callbacks change nothing.
        handler.onOutcome("r1", SmsSendOutcome.Sent)
        handler.onOutcome("r1", SmsSendOutcome.Failed(SendFailure.CARRIER_REJECTED))
        assertEquals(2, results.size)
    }

    @Test fun `a replayed request is never sent twice but its answer is repeated`() = runBlocking {
        handler.handle("r1", request())
        handler.handle("r1", request())
        handler.handle("r1", request(text = "different text, same id"))
        assertEquals(1, sender.sent.size)
        assertTrue(results.isEmpty())
        handler.onOutcome("r1", SmsSendOutcome.Sent)
        handler.handle("r1", request())
        assertEquals(1, sender.sent.size)
        assertEquals(listOf(SendStatus.SENT, SendStatus.SENT), results.map { it.status })
    }

    @Test fun `refusals and carrier failures are reported with a reason`() = runBlocking {
        enabled = false
        handler.handle("off", request())
        enabled = true
        sender.permission = false
        handler.handle("perm", request())
        sender.permission = true
        handler.handle("old", request(expires = clock - 1))
        handler.handle("bad", request(to = "nope"))
        sender.immediateFailure = SendFailure.NO_SERVICE
        handler.handle("nosvc", request())
        sender.immediateFailure = null
        handler.handle("carrier", request())
        handler.onOutcome("carrier", SmsSendOutcome.Failed(SendFailure.CARRIER_REJECTED))
        assertEquals(
            listOf("off" to SendFailure.DISABLED, "perm" to SendFailure.NO_PERMISSION, "old" to SendFailure.EXPIRED,
                "bad" to SendFailure.INVALID_RECIPIENT, "nosvc" to SendFailure.NO_SERVICE, "carrier" to SendFailure.CARRIER_REJECTED),
            results.map { it.requestId to it.reason },
        )
        assertTrue(results.all { it.status == SendStatus.FAILED })
        assertEquals("only the request the carrier saw reached the SMS stack", listOf("carrier"), sender.sent.map { it.first })
        // A refused request cannot be retried under the same id after the owner enables sending.
        handler.handle("off", request())
        assertEquals(1, sender.sent.size)
    }

    @Test fun `the hourly limit counts what was sent and frees up after an hour`() = runBlocking {
        limit = 2
        handler.handle("a", request()); handler.handle("b", request()); handler.handle("c", request())
        assertEquals(listOf("a", "b"), sender.sent.map { it.first })
        assertEquals(SendFailure.RATE_LIMITED, results.single().reason)
        clock += 3_600_001
        handler.handle("d", request(created = clock - 10, expires = clock + 60_000))
        assertEquals(listOf("a", "b", "d"), sender.sent.map { it.first })
    }

    @Test fun `every failure code has a sentence for the user`() {
        val codes = listOf(SendFailure.DISABLED, SendFailure.NO_PERMISSION, SendFailure.EXPIRED, SendFailure.INVALID_RECIPIENT,
            SendFailure.INVALID_MESSAGE, SendFailure.RATE_LIMITED, SendFailure.NO_SERVICE, SendFailure.RADIO_OFF, SendFailure.NO_SIM,
            SendFailure.CARRIER_REJECTED, SendFailure.NO_ANSWER)
        assertEquals(codes.size, codes.map(RemoteSendPolicy::describe).toSet().size)
        assertEquals("The message could not be sent.", RemoteSendPolicy.describe("something new"))
    }
}
