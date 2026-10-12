package io.github.ammarqaisar11a55.relaysms.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** State of a locally queued item awaiting upload to the relay. */
object OutboxState {
    const val PENDING = "pending"
    const val DELIVERED = "delivered"
    const val EXPIRED = "expired"
    const val FAILED = "failed"
}

object OutboxKind {
    const val SMS = "sms"
}

/**
 * Durable delivery queue. [payload] is encrypted with the device-local key and is cleared once the
 * item leaves the PENDING state; the remaining row is a content-free tombstone used to reject
 * duplicate events.
 */
@Entity(tableName = "outbox", indices = [Index("state", "created_at")])
data class OutboxEntity(
    @PrimaryKey val id: String,
    val kind: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val payload: ByteArray?,
    val state: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val attempts: Int = 0,
    /** Short machine-readable reason for the last failure. Never free text. */
    @ColumnInfo(name = "last_error") val lastError: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is OutboxEntity && other.id == id && other.state == state &&
        other.attempts == attempts && other.updatedAt == updatedAt
    override fun hashCode(): Int = id.hashCode()
}

/** A sender the user has approved for forwarding. [pattern] is stored normalised. */
@Entity(tableName = "sender_filters")
data class SenderFilterEntity(
    @PrimaryKey val pattern: String,
    @ColumnInfo(name = "display") val display: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Senders recently observed on the source phone, kept only to offer them as filter suggestions.
 * [senderKey] is a keyed-free SHA-256 of the normalised sender; [senderEnc] is encrypted locally.
 */
@Entity(tableName = "seen_senders")
data class SeenSenderEntity(
    @PrimaryKey @ColumnInfo(name = "sender_key") val senderKey: String,
    @ColumnInfo(name = "sender_enc", typeAffinity = ColumnInfo.BLOB) val senderEnc: ByteArray,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    val count: Int,
) {
    override fun equals(other: Any?): Boolean = other is SeenSenderEntity && other.senderKey == senderKey && other.count == count
    override fun hashCode(): Int = senderKey.hashCode()
}
