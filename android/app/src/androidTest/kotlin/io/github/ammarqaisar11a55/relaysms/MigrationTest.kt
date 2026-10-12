package io.github.ammarqaisar11a55.relaysms

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ammarqaisar11a55.relaysms.core.database.AppDatabase
import io.github.ammarqaisar11a55.relaysms.core.database.Migrations
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrades a database created with an old schema and checks that existing data survives. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun version1DataSurvivesUpgradeToLatest(): Unit = runBlocking {
        helper.createDatabase(name, 1).apply {
            insert("outbox", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
                put("id", "evt-1"); put("kind", "sms"); put("payload", byteArrayOf(1, 2, 3)); put("state", "pending")
                put("created_at", 100L); put("updated_at", 100L); put("attempts", 2)
            })
            insert("sender_filters", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
                put("pattern", "a:testbank"); put("display", "TESTBANK"); put("created_at", 50L)
            })
            insert("seen_senders", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
                put("sender_key", "k"); put("sender_enc", byteArrayOf(9)); put("last_seen_at", 60L); put("count", 4)
            })
            close()
        }
        // Validates the migrated schema against the exported schema of each version on the way.
        helper.runMigrationsAndValidate(name, 2, true, *Migrations.ALL)

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL).build()
        try {
            val item = db.outboxDao().nextPending(10).single()
            assertEquals("evt-1", item.id)
            assertEquals(2, item.attempts)
            assertArrayEquals(byteArrayOf(1, 2, 3), item.payload)
            assertEquals(listOf("a:testbank"), db.senderFilterDao().patterns())
            assertEquals(4, db.seenSenderDao().find("k")!!.count)
            // The table added by the migration works.
            assertEquals(0, db.messageDao().count())
            assertEquals(0, db.messageDao().observeUnreadCount().first())
        } finally {
            db.close()
            ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(name)
        }
    }
}
