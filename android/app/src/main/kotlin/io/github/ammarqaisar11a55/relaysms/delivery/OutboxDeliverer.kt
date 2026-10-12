package io.github.ammarqaisar11a55.relaysms.delivery

import io.github.ammarqaisar11a55.relaysms.core.database.OutboxDao
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxEntity
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxState
import io.github.ammarqaisar11a55.relaysms.core.security.FieldCipher
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSmsProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Result of handing one queued item to the relay. */
enum class SendResult {
    /** The relay durably accepted the item (or already had it). */
    ACCEPTED,

    /** Temporary problem: network, server unavailable, rate limited. Try again later. */
    RETRY_LATER,

    /** No paired companion or no server configured yet. Keep the item, do not count an attempt. */
    NOT_CONFIGURED,

    /** The relay refused the item permanently (too large, pairing revoked). */
    REJECTED,
}

/** Sends one decrypted outbox payload. Implementations encrypt it for the recipient first. */
fun interface OutboxTransport {
    suspend fun send(id: String, kind: String, plaintext: ByteArray): SendResult
}

enum class DeliveryRun { IDLE, RETRY, WAITING_FOR_SETUP }

/**
 * Drains the outbox. Safe to run repeatedly and concurrently with new inserts: every transition
 * is a single-row update and uploads are idempotent on the item id.
 */
class OutboxDeliverer(
    private val dao: OutboxDao,
    private val cipher: () -> FieldCipher,
    private val transport: () -> OutboxTransport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** Runs are serialised; a second caller waits and then picks up anything queued meanwhile. */
    suspend fun runOnce(): DeliveryRun = mutex.withLock { drain() }

    private suspend fun drain(): DeliveryRun {
        val t = now()
        dao.expirePendingOlderThan(cutoff = t - PENDING_TTL_MS, now = t)
        dao.deleteTombstonesOlderThan(t - TOMBSTONE_TTL_MS)
        while (true) {
            val batch = dao.nextPending(BATCH)
            if (batch.isEmpty()) return DeliveryRun.IDLE
            for (item in batch) {
                when (sendOne(item)) {
                    SendResult.ACCEPTED -> dao.finish(item.id, OutboxState.DELIVERED, now(), null)
                    SendResult.REJECTED -> dao.finish(item.id, OutboxState.FAILED, now(), "rejected")
                    SendResult.RETRY_LATER -> {
                        dao.recordAttempt(item.id, now(), "retry")
                        return DeliveryRun.RETRY
                    }
                    SendResult.NOT_CONFIGURED -> return DeliveryRun.WAITING_FOR_SETUP
                    null -> dao.finish(item.id, OutboxState.FAILED, now(), "undecryptable")
                }
            }
        }
    }

    /** @return null when the stored payload cannot be decrypted (local key was lost). */
    private suspend fun sendOne(item: OutboxEntity): SendResult? {
        val payload = item.payload ?: return null
        val plaintext = try {
            cipher().decrypt(payload, IncomingSmsProcessor.outboxContext(item.id))
        } catch (e: Exception) {
            return null
        }
        return try {
            transport().send(item.id, item.kind, plaintext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.RETRY_LATER
        }
    }

    companion object {
        const val BATCH = 20
        /** Undelivered items are dropped after this long; an OTP that old is useless. */
        const val PENDING_TTL_MS = 72L * 60 * 60 * 1000
        /** Content-free tombstones are kept this long to reject duplicate events. */
        const val TOMBSTONE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
