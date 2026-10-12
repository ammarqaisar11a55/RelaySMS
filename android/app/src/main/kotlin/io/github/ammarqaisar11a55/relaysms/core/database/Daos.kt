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
