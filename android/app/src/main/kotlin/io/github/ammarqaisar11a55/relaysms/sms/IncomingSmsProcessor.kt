package io.github.ammarqaisar11a55.relaysms.sms

import io.github.ammarqaisar11a55.relaysms.core.database.OutboxEntity
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxKind
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxState
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.security.FieldCipher
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What happened to one incoming SMS event. Safe to log and to count. */
enum class SmsOutcome { NOT_SOURCE, NOT_PAIRED, FORWARDING_OFF, MALFORMED, FILTERED, DUPLICATE, QUEUE_FULL, STORAGE_ERROR, QUEUED }

data class SourceConfig(val role: DeviceRole?, val forwardingEnabled: Boolean, val allowlistOnly: Boolean, val paired: Boolean = true)

/** Storage and scheduling seams, so the decision logic is testable without Android. */
interface SourceConfigReader { suspend fun read(): SourceConfig }
interface FilterReader { suspend fun normalizedPatterns(): List<String> }
interface OutboxWriter {
    suspend fun pendingCount(): Int
    /** @return false when an item with this id already exists. */
    suspend fun insertIfAbsent(item: OutboxEntity): Boolean
}
interface SeenSenderRecorder { suspend fun record(sender: String, nowMillis: Long) }
interface OutcomeRecorder { suspend fun record(outcome: SmsOutcome, nowMillis: Long) }
fun interface DeliveryScheduler { fun schedule() }

/**
 * Decides what to do with one incoming SMS and, if it is to be forwarded, stores it encrypted in
 * the outbox and schedules delivery. Performs no network I/O.
 */
class IncomingSmsProcessor(
    private val config: SourceConfigReader,
    private val filters: FilterReader,
    private val outbox: OutboxWriter,
    private val seenSenders: SeenSenderRecorder,
    private val outcomes: OutcomeRecorder,
    private val cipher: FieldCipher,
    private val scheduler: DeliveryScheduler,
    private val maxPending: Int = MAX_PENDING,
) {
    suspend fun process(parts: List<SmsPart>, subscriptionId: Int, nowMillis: Long): SmsOutcome {
        val outcome = try {
            decide(parts, subscriptionId, nowMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SmsOutcome.STORAGE_ERROR
        }
        try {
            if (outcome != SmsOutcome.NOT_SOURCE) outcomes.record(outcome, nowMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Counters are best effort and must never affect forwarding.
        }
        if (outcome == SmsOutcome.QUEUED) scheduler.schedule()
        return outcome
    }

    private suspend fun decide(parts: List<SmsPart>, subscriptionId: Int, nowMillis: Long): SmsOutcome {
        val cfg = config.read()
        if (cfg.role != DeviceRole.SOURCE) return SmsOutcome.NOT_SOURCE
        val sms = SmsAssembler.assemble(parts, subscriptionId) ?: return SmsOutcome.MALFORMED
        // Suggestions are useful while the user is still choosing filters, so senders are noted
        // even when forwarding is off. Only the sender is kept, encrypted, never the body.
        runCatching { seenSenders.record(sms.sender, nowMillis) }
        // Without a verified companion there is nobody to encrypt for; nothing is retained.
        if (!cfg.paired) return SmsOutcome.NOT_PAIRED
        if (!cfg.forwardingEnabled) return SmsOutcome.FORWARDING_OFF
        if (ForwardingPolicy.decide(sms.sender, cfg.allowlistOnly, filters.normalizedPatterns()) == FilterDecision.FILTERED) {
            return SmsOutcome.FILTERED
        }
        if (outbox.pendingCount() >= maxPending) return SmsOutcome.QUEUE_FULL
        val id = EventId.of(sms)
        val payload = cipher.encrypt(Json.encodeToString(sms).toByteArray(Charsets.UTF_8), outboxContext(id))
        val inserted = outbox.insertIfAbsent(
            OutboxEntity(id = id, kind = OutboxKind.SMS, payload = payload, state = OutboxState.PENDING, createdAt = nowMillis, updatedAt = nowMillis),
        )
        return if (inserted) SmsOutcome.QUEUED else SmsOutcome.DUPLICATE
    }

    companion object {
        const val MAX_PENDING = 500
        fun outboxContext(id: String) = "outbox:$id"
    }
}
