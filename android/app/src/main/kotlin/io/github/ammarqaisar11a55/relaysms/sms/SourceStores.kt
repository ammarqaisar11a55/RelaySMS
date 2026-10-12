package io.github.ammarqaisar11a55.relaysms.sms

import io.github.ammarqaisar11a55.relaysms.core.database.AppDatabase
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxEntity
import io.github.ammarqaisar11a55.relaysms.core.database.SeenSenderEntity
import io.github.ammarqaisar11a55.relaysms.core.database.SenderFilterEntity
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppPreferences
import io.github.ammarqaisar11a55.relaysms.core.security.FieldCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

data class SenderFilter(val pattern: String, val display: String)

/** Room/DataStore-backed implementation of the source phone's storage seams. */
class SourceStores(
    private val db: AppDatabase,
    private val prefs: AppPreferences,
    private val cipher: () -> FieldCipher,
    private val isPaired: () -> Boolean,
) : SourceConfigReader, FilterReader, OutboxWriter, SeenSenderRecorder, OutcomeRecorder {

    override suspend fun read(): SourceConfig = prefs.current().let { SourceConfig(it.role, it.forwardingEnabled, it.allowlistOnly, isPaired()) }

    override suspend fun normalizedPatterns(): List<String> = db.senderFilterDao().patterns()

    override suspend fun pendingCount(): Int = db.outboxDao().pendingCount()

    override suspend fun insertIfAbsent(item: OutboxEntity): Boolean = db.outboxDao().insertIfAbsent(item) != -1L

    override suspend fun record(sender: String, nowMillis: Long) {
        val normalized = SenderMatcher.normalize(sender) ?: return
        val key = sha256Hex(normalized)
        val dao = db.seenSenderDao()
        val existing = dao.find(key)
        dao.upsert(
            SeenSenderEntity(
                senderKey = key,
                senderEnc = existing?.senderEnc ?: cipher().encrypt(sender.toByteArray(Charsets.UTF_8), seenContext(key)),
                lastSeenAt = nowMillis,
                count = (existing?.count ?: 0) + 1,
            ),
        )
        dao.trim(MAX_SEEN_SENDERS)
    }

    override suspend fun record(outcome: SmsOutcome, nowMillis: Long) = prefs.recordSmsEvent(
        queued = outcome == SmsOutcome.QUEUED,
        filtered = outcome == SmsOutcome.FILTERED,
        failed = outcome == SmsOutcome.MALFORMED || outcome == SmsOutcome.STORAGE_ERROR,
        queueFull = outcome == SmsOutcome.QUEUE_FULL,
        nowMillis = nowMillis,
    ).let { }

    val filters: Flow<List<SenderFilter>> = db.senderFilterDao().observeAll().map { rows -> rows.map { SenderFilter(it.pattern, it.display) } }

    /** Recently seen senders that are not on the allowlist yet, newest first. */
    val suggestions: Flow<List<String>> = combine(db.seenSenderDao().observeRecent(MAX_SEEN_SENDERS), db.senderFilterDao().observeAll()) { seen, filters ->
        val listed = filters.mapTo(HashSet()) { it.pattern }
        seen.mapNotNull { row ->
            val sender = runCatching { String(cipher().decrypt(row.senderEnc, seenContext(row.senderKey)), Charsets.UTF_8) }.getOrNull()
            sender?.takeIf { SenderMatcher.normalize(it) !in listed }
        }
    }

    /** @return false when [raw] is not a usable sender. */
    suspend fun addFilter(raw: String, nowMillis: Long): Boolean {
        val pattern = SenderMatcher.normalize(raw) ?: return false
        db.senderFilterDao().upsert(SenderFilterEntity(pattern = pattern, display = raw.trim(), createdAt = nowMillis))
        return true
    }

    suspend fun removeFilter(pattern: String) = db.senderFilterDao().delete(pattern)

    private fun seenContext(key: String) = "seen_sender:$key"

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_SEEN_SENDERS = 40
    }
}
