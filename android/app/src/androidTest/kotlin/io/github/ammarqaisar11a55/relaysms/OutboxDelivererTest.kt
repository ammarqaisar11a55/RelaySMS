package io.github.ammarqaisar11a55.relaysms

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import io.github.ammarqaisar11a55.relaysms.core.database.AppDatabase
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxEntity
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxKind
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxState
import io.github.ammarqaisar11a55.relaysms.core.security.FieldCipher
import io.github.ammarqaisar11a55.relaysms.core.security.TinkFieldCipher
import io.github.ammarqaisar11a55.relaysms.delivery.DeliveryRun
import io.github.ammarqaisar11a55.relaysms.delivery.OutboxDeliverer
import io.github.ammarqaisar11a55.relaysms.delivery.OutboxTransport
import io.github.ammarqaisar11a55.relaysms.delivery.SendResult
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSmsProcessor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutboxDelivererTest {
    private lateinit var db: AppDatabase
    private lateinit var cipher: FieldCipher
    private var now = 1_000_000_000L
    private val sent = mutableListOf<Pair<String, String>>()
    private var transport = OutboxTransport { id, _, plain -> sent += id to String(plain); SendResult.ACCEPTED }

    @Before fun setUp() {
        AeadConfig.register()
        cipher = TinkFieldCipher(KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(Aead::class.java))
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    private fun deliverer() = OutboxDeliverer(db.outboxDao(), { cipher }, { transport }, { now })

    private suspend fun enqueue(id: String, body: String, createdAt: Long = now) {
        db.outboxDao().insertIfAbsent(
            OutboxEntity(id, OutboxKind.SMS, cipher.encrypt(body.toByteArray(), IncomingSmsProcessor.outboxContext(id)), OutboxState.PENDING, createdAt, createdAt),
        )
    }

    private suspend fun count(state: String) = db.outboxDao().observeCount(state).first()

    @Test fun acceptedItemsBecomeContentFreeTombstonesInOrder() = runBlocking {
        enqueue("a", "first", now - 2)
        enqueue("b", "second", now - 1)
        assertEquals(DeliveryRun.IDLE, deliverer().runOnce())
        assertEquals(listOf("a" to "first", "b" to "second"), sent)
        assertEquals(0, db.outboxDao().pendingCount())
        assertEquals(2, count(OutboxState.DELIVERED))
        // Tombstone rejects a duplicate event and holds no payload.
        assertEquals(-1L, db.outboxDao().insertIfAbsent(OutboxEntity("a", OutboxKind.SMS, byteArrayOf(1), OutboxState.PENDING, now, now)))
    }

    @Test fun temporaryFailureKeepsTheItemAndAsksForRetry() = runBlocking {
        enqueue("a", "first")
        transport = OutboxTransport { _, _, _ -> SendResult.RETRY_LATER }
        assertEquals(DeliveryRun.RETRY, deliverer().runOnce())
        assertEquals(DeliveryRun.RETRY, deliverer().runOnce())
        val row = db.outboxDao().nextPending(10).single()
        assertEquals(2, row.attempts)
        transport = OutboxTransport { _, _, _ -> throw java.io.IOException("offline") }
        assertEquals(DeliveryRun.RETRY, deliverer().runOnce())
        transport = OutboxTransport { id, _, plain -> sent += id to String(plain); SendResult.ACCEPTED }
        assertEquals(DeliveryRun.IDLE, deliverer().runOnce())
        assertEquals(listOf("a" to "first"), sent)
    }

    @Test fun unconfiguredTransportLeavesQueueUntouched() = runBlocking {
        enqueue("a", "first")
        transport = OutboxTransport { _, _, _ -> SendResult.NOT_CONFIGURED }
        assertEquals(DeliveryRun.WAITING_FOR_SETUP, deliverer().runOnce())
        assertEquals(0, db.outboxDao().nextPending(10).single().attempts)
    }

    @Test fun staleUndeliveredItemsExpireAndOldTombstonesAreRemoved() = runBlocking {
        enqueue("old", "stale", createdAt = now - OutboxDeliverer.PENDING_TTL_MS - 1)
        enqueue("new", "fresh")
        assertEquals(DeliveryRun.IDLE, deliverer().runOnce())
        assertEquals(listOf("new" to "fresh"), sent)
        assertEquals(1, count(OutboxState.EXPIRED))
        now += OutboxDeliverer.TOMBSTONE_TTL_MS + 1
        deliverer().runOnce()
        assertEquals(0, count(OutboxState.EXPIRED) + count(OutboxState.DELIVERED))
    }

    @Test fun rejectedAndUndecryptableItemsFailWithoutBlockingTheQueue() = runBlocking {
        db.outboxDao().insertIfAbsent(OutboxEntity("bad", OutboxKind.SMS, byteArrayOf(1, 2, 3), OutboxState.PENDING, now - 5, now - 5))
        enqueue("rej", "nope", now - 4)
        enqueue("ok", "fine", now - 3)
        transport = OutboxTransport { id, _, plain -> if (id == "rej") SendResult.REJECTED else { sent += id to String(plain); SendResult.ACCEPTED } }
        assertEquals(DeliveryRun.IDLE, deliverer().runOnce())
        assertEquals(listOf("ok" to "fine"), sent)
        assertEquals(2, count(OutboxState.FAILED))
        assertNull(db.outboxDao().nextPending(10).firstOrNull())
    }
}
