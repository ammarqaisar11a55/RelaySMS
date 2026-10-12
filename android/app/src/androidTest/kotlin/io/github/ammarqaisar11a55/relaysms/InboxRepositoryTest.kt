package io.github.ammarqaisar11a55.relaysms

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import io.github.ammarqaisar11a55.relaysms.core.database.AppDatabase
import io.github.ammarqaisar11a55.relaysms.core.security.TinkFieldCipher
import io.github.ammarqaisar11a55.relaysms.inbox.InboxRepository
import io.github.ammarqaisar11a55.relaysms.sms.IncomingSms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: InboxRepository
    private var now = 5_000L
    private val sms = IncomingSms("TESTBANK", "Synthetic code 112233", 1_000, 1)

    private fun newCipher(): TinkFieldCipher {
        AeadConfig.register()
        return TinkFieldCipher(KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(Aead::class.java))
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = InboxRepository(db.messageDao(), newCipher(), { now })
    }

    @After fun tearDown() = db.close()

    @Test fun storedMessageKeepsSenderBodyAndTimestampAndStartsUnread(): Unit = runBlocking {
        assertTrue(repo.storeIncoming("m1", sms))
        val m = repo.messages.first().single()
        assertEquals("TESTBANK", m.address)
        assertEquals("Synthetic code 112233", m.body)
        assertEquals(1_000L, m.receivedAtMillis)
        assertEquals(5_000L, m.storedAtMillis)
        assertFalse(m.isRead)
        assertEquals(1, repo.unreadCount.first())
        repo.markRead("m1")
        assertTrue(repo.observe("m1").first()!!.isRead)
        assertEquals(0, repo.unreadCount.first())
    }

    @Test fun duplicateDeliveryCreatesNoSecondEntry(): Unit = runBlocking {
        assertTrue(repo.storeIncoming("m1", sms))
        assertFalse(repo.storeIncoming("m1", sms))
        assertFalse(repo.storeIncoming("m1", sms.copy(body = "different content, same id")))
        assertEquals(1, repo.count())
        assertEquals("Synthetic code 112233", repo.messages.first().single().body)
    }

    @Test fun messagesAreSortedNewestFirstByOriginalTime(): Unit = runBlocking {
        repo.storeIncoming("old", sms.copy(receivedAtMillis = 10))
        repo.storeIncoming("new", sms.copy(receivedAtMillis = 30))
        repo.storeIncoming("mid", sms.copy(receivedAtMillis = 20))
        assertEquals(listOf("new", "mid", "old"), repo.messages.first().map { it.id })
    }

    @Test fun contentIsEncryptedInTheDatabaseFile(): Unit = runBlocking {
        repo.storeIncoming("m1", sms)
        db.openHelper.readableDatabase.query("SELECT * FROM messages").use { c ->
            assertTrue(c.moveToFirst())
            for (i in 0 until c.columnCount) {
                val text = when (c.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_BLOB -> String(c.getBlob(i), Charsets.ISO_8859_1)
                    android.database.Cursor.FIELD_TYPE_STRING -> c.getString(i)
                    else -> ""
                }
                assertFalse("column ${c.getColumnName(i)} leaks the sender", text.contains("TESTBANK", ignoreCase = true))
                assertFalse("column ${c.getColumnName(i)} leaks the body", text.contains("112233"))
            }
        }
    }

    @Test fun deletedMessageIsErasedAndCannotBeDeliveredAgain(): Unit = runBlocking {
        repo.storeIncoming("m1", sms)
        now = 9_000
        repo.delete("m1")
        assertTrue(repo.messages.first().isEmpty())
        assertNull(repo.observe("m1").first())
        val row = db.messageDao().find("m1")!!
        assertNull(row.addressEnc)
        assertNull(row.bodyEnc)
        assertEquals(9_000L, row.deletedAt)
        assertFalse("a re-delivered copy must not reappear", repo.storeIncoming("m1", sms))
        assertEquals(0, repo.count())
    }

    @Test fun deleteAllErasesEverything(): Unit = runBlocking {
        repo.storeIncoming("a", sms); repo.storeIncoming("b", sms.copy(receivedAtMillis = 2_000))
        repo.deleteAll()
        assertEquals(0, repo.count())
        assertFalse(repo.storeIncoming("a", sms))
    }

    @Test fun invalidMessagesAreRefusedAndRowsUnreadableAfterKeyLossAreSkipped(): Unit = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.storeIncoming("x", sms.copy(sender = " ")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.storeIncoming("x", sms.copy(body = "")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.storeIncoming("x", sms.copy(receivedAtMillis = 0)) } }
        repo.storeIncoming("m1", sms)
        // Same database opened with a different key, as after losing the Keystore key.
        val afterKeyLoss = InboxRepository(db.messageDao(), newCipher(), { now })
        assertTrue(afterKeyLoss.messages.first().isEmpty())
    }
}
