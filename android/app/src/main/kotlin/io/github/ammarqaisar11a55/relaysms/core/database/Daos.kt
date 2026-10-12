package io.github.ammarqaisar11a55.relaysms.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    /** @return row id, or -1 when an item with the same id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: OutboxEntity): Long

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'pending'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE state = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE state = :state")
    fun observeCount(state: String): Flow<Int>

    @Query("SELECT * FROM outbox WHERE state = 'pending' ORDER BY created_at ASC LIMIT :limit")
    suspend fun nextPending(limit: Int): List<OutboxEntity>

    @Query("UPDATE outbox SET state = :state, payload = NULL, updated_at = :now, last_error = :error WHERE id = :id")
    suspend fun finish(id: String, state: String, now: Long, error: String?)

    @Query("UPDATE outbox SET attempts = attempts + 1, updated_at = :now, last_error = :error WHERE id = :id")
    suspend fun recordAttempt(id: String, now: Long, error: String?)

    @Query("UPDATE outbox SET state = 'expired', payload = NULL, updated_at = :now, last_error = 'expired' WHERE state = 'pending' AND created_at < :cutoff")
    suspend fun expirePendingOlderThan(cutoff: Long, now: Long): Int

    @Query("DELETE FROM outbox WHERE state != 'pending' AND updated_at < :cutoff")
    suspend fun deleteTombstonesOlderThan(cutoff: Long): Int

    /** Drops everything still waiting, e.g. when the companion it was meant for is gone. */
    @Query("DELETE FROM outbox WHERE state = 'pending'")
    suspend fun deletePending(): Int

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}

@Dao
interface SenderFilterDao {
    @Query("SELECT * FROM sender_filters ORDER BY display COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<SenderFilterEntity>>

    @Query("SELECT pattern FROM sender_filters")
    suspend fun patterns(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(filter: SenderFilterEntity)

    @Query("DELETE FROM sender_filters WHERE pattern = :pattern")
    suspend fun delete(pattern: String)

    @Query("DELETE FROM sender_filters")
    suspend fun deleteAll()
}

@Dao
interface SeenSenderDao {
    @Query("SELECT * FROM seen_senders ORDER BY last_seen_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SeenSenderEntity>>

    @Query("SELECT * FROM seen_senders WHERE sender_key = :key")
    suspend fun find(key: String): SeenSenderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SeenSenderEntity)

    @Query("DELETE FROM seen_senders WHERE sender_key NOT IN (SELECT sender_key FROM seen_senders ORDER BY last_seen_at DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM seen_senders")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    /** @return row id, or -1 when a message (or its tombstone) with this id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE deleted_at IS NULL ORDER BY received_at DESC, id DESC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id AND deleted_at IS NULL")
    fun observe(id: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun find(id: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE deleted_at IS NULL")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE deleted_at IS NULL AND is_read = 0 AND direction = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("UPDATE messages SET is_read = :read WHERE id = :id")
    suspend fun setRead(id: String, read: Boolean)

    @Query("UPDATE messages SET is_read = 1 WHERE is_read = 0 AND deleted_at IS NULL")
    suspend fun markAllRead()

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("UPDATE messages SET provider_id = :providerId WHERE id = :id")
    suspend fun setProviderId(id: String, providerId: Long?)

    /** Erases the content and leaves a tombstone. */
    @Query("UPDATE messages SET address_enc = NULL, body_enc = NULL, deleted_at = :now WHERE id = :id AND deleted_at IS NULL")
    suspend fun tombstone(id: String, now: Long)

    @Query("UPDATE messages SET address_enc = NULL, body_enc = NULL, deleted_at = :now WHERE deleted_at IS NULL")
    suspend fun tombstoneAll(now: Long)

    @Query("UPDATE messages SET address_enc = NULL, body_enc = NULL, deleted_at = :now WHERE deleted_at IS NULL AND received_at < :cutoff")
    suspend fun tombstoneOlderThan(cutoff: Long, now: Long): Int

    @Query("SELECT id, provider_id FROM messages WHERE deleted_at IS NULL AND received_at < :cutoff AND provider_id IS NOT NULL")
    suspend fun providerIdsOlderThan(cutoff: Long): List<ProviderRef>

    @Query("SELECT id, provider_id FROM messages WHERE deleted_at IS NULL AND provider_id IS NOT NULL")
    suspend fun allProviderIds(): List<ProviderRef>

    @Query("SELECT * FROM messages WHERE deleted_at IS NULL AND provider_id IS NULL ORDER BY received_at ASC LIMIT :limit")
    suspend fun withoutProviderId(limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET provider_id = NULL")
    suspend fun clearProviderIds()

    @Query("DELETE FROM messages WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun purgeTombstonesOlderThan(cutoff: Long): Int
}

data class ProviderRef(val id: String, @androidx.room.ColumnInfo(name = "provider_id") val providerId: Long?)
