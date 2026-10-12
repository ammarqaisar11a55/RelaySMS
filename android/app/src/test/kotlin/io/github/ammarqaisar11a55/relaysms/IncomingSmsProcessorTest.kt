package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.database.OutboxEntity
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.sms.FilterReader
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSmsProcessor
import io.github.ammarqaisar11a55.relaysms.sms.OutboxWriter
import io.github.ammarqaisar11a55.relaysms.sms.OutcomeRecorder
import io.github.ammarqaisar11a55.relaysms.sms.SeenSenderRecorder
import io.github.ammarqaisar11a55.relaysms.sms.SenderMatcher
import io.github.ammarqaisar11a55.relaysms.sms.SmsOutcome
import io.github.ammarqaisar11a55.relaysms.sms.SmsPart
import io.github.ammarqaisar11a55.relaysms.sms.SourceConfig
import io.github.ammarqaisar11a55.relaysms.sms.SourceConfigReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class IncomingSmsProcessorTest {
    private val cipher = testFieldCipher()
    private var config = SourceConfig(DeviceRole.SOURCE, forwardingEnabled = true, allowlistOnly = true)
    private val patterns = mutableListOf(SenderMatcher.normalize("TESTBANK")!!)
    private val rows = LinkedHashMap<String, OutboxEntity>()
    private val outcomes = mutableListOf<SmsOutcome>()
    private val seen = mutableListOf<String>()
    private var scheduled = 0
    private var failInsert = false

    private fun processor(maxPending: Int = 3) = IncomingSmsProcessor(
        config = object : SourceConfigReader { override suspend fun read() = config },
        filters = object : FilterReader { override suspend fun normalizedPatterns() = patterns.toList() },
        outbox = object : OutboxWriter {
            override suspend fun pendingCount() = rows.size
            override suspend fun insertIfAbsent(item: OutboxEntity): Boolean {
                if (failInsert) throw IOException("disk full")
                if (rows.containsKey(item.id)) return false
                rows[item.id] = item
                return true
            }
        },
        seenSenders = object : SeenSenderRecorder { override suspend fun record(sender: String, nowMillis: Long) { seen += sender } },
        outcomes = object : OutcomeRecorder { override suspend fun record(outcome: SmsOutcome, nowMillis: Long) { outcomes += outcome } },
        cipher = cipher,
        scheduler = { scheduled++ },
        maxPending = maxPending,
    )

    private fun sms(sender: String = "TESTBANK", body: String = "Synthetic code 000000", ts: Long = 1000) = listOf(SmsPart(sender, body, ts))

    @Test fun `accepted message is stored encrypted and delivery is scheduled`() = runBlocking {
        assertEquals(SmsOutcome.QUEUED, processor().process(sms(), -1, 2000))
        assertEquals(1, scheduled)
        val row = rows.values.single()
        val stored = row.payload!!
        assertFalse("payload must not contain plaintext", String(stored, Charsets.ISO_8859_1).contains("000000"))
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("TESTBANK"))
        val decoded = Json.decodeFromString<IncomingSms>(String(cipher.decrypt(stored, IncomingSmsProcessor.outboxContext(row.id)), Charsets.UTF_8))
        assertEquals(IncomingSms("TESTBANK", "Synthetic code 000000", 1000, 1, -1), decoded)
    }

    @Test fun `nothing is stored when forwarding is off`() = runBlocking {
        config = config.copy(forwardingEnabled = false)
        assertEquals(SmsOutcome.FORWARDING_OFF, processor().process(sms(), -1, 2000))
        assertTrue(rows.isEmpty())
        assertEquals(0, scheduled)
        assertEquals(listOf("TESTBANK"), seen)
    }

    @Test fun `pausing forwarding takes effect on the next message`() = runBlocking {
        val p = processor()
        assertEquals(SmsOutcome.QUEUED, p.process(sms(ts = 1), -1, 2000))
        config = config.copy(forwardingEnabled = false)
        assertEquals(SmsOutcome.FORWARDING_OFF, p.process(sms(ts = 2), -1, 2000))
        assertEquals(1, rows.size)
    }

    @Test fun `unapproved sender is filtered in allowlist mode and filter edits apply immediately`() = runBlocking {
        val p = processor()
        assertEquals(SmsOutcome.FILTERED, p.process(sms(sender = "OTHER"), -1, 2000))
        assertTrue(rows.isEmpty())
        patterns += SenderMatcher.normalize("OTHER")!!
        assertEquals(SmsOutcome.QUEUED, p.process(sms(sender = "OTHER"), -1, 2000))
        patterns.clear()
        assertEquals(SmsOutcome.FILTERED, p.process(sms(sender = "TESTBANK", ts = 9), -1, 2000))
        config = config.copy(allowlistOnly = false)
        assertEquals(SmsOutcome.QUEUED, p.process(sms(sender = "ANYONE"), -1, 2000))
    }

    @Test fun `duplicate broadcast does not create a second entry`() = runBlocking {
        val p = processor()
        assertEquals(SmsOutcome.QUEUED, p.process(sms(), -1, 2000))
        assertEquals(SmsOutcome.DUPLICATE, p.process(sms(), -1, 2500))
        assertEquals(1, rows.size)
        assertEquals(1, scheduled)
    }

    @Test fun `full queue refuses new messages and reports it`() = runBlocking {
        val p = processor(maxPending = 2)
        assertEquals(SmsOutcome.QUEUED, p.process(sms(ts = 1), -1, 2000))
        assertEquals(SmsOutcome.QUEUED, p.process(sms(ts = 2), -1, 2000))
        assertEquals(SmsOutcome.QUEUE_FULL, p.process(sms(ts = 3), -1, 2000))
        assertEquals(2, rows.size)
        assertEquals(SmsOutcome.QUEUE_FULL, outcomes.last())
    }

    @Test fun `malformed event and storage failure are reported without crashing`() = runBlocking {
        val p = processor()
        assertEquals(SmsOutcome.MALFORMED, p.process(emptyList(), -1, 2000))
        assertEquals(SmsOutcome.MALFORMED, p.process(listOf(SmsPart(null, null, 0)), -1, 2000))
        failInsert = true
        assertEquals(SmsOutcome.STORAGE_ERROR, p.process(sms(), -1, 2000))
        assertEquals(0, scheduled)
        assertEquals(listOf(SmsOutcome.MALFORMED, SmsOutcome.MALFORMED, SmsOutcome.STORAGE_ERROR), outcomes)
    }

    @Test fun `nothing is retained while no companion is paired`() = runBlocking {
        config = config.copy(paired = false)
        assertEquals(SmsOutcome.NOT_PAIRED, processor().process(sms(), -1, 2000))
        assertTrue(rows.isEmpty())
        assertEquals(0, scheduled)
    }

    @Test fun `companion and unconfigured installs ignore sms entirely`() = runBlocking {
        config = config.copy(role = DeviceRole.COMPANION)
        assertEquals(SmsOutcome.NOT_SOURCE, processor().process(sms(), -1, 2000))
        config = config.copy(role = null)
        assertEquals(SmsOutcome.NOT_SOURCE, processor().process(sms(), -1, 2000))
        assertTrue(rows.isEmpty())
        assertTrue(outcomes.isEmpty())
        assertTrue(seen.isEmpty())
    }
}
