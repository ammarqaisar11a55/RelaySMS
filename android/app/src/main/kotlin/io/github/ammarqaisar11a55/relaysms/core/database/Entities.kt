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

object MessageDirection {
    const val INCOMING = 0
    const val OUTGOING = 1
}

object MessageOrigin {
    /** Forwarded from the paired source phone through the relay. */
    const val RELAY = "relay"

    /** Received or sent by this phone's own SIM while RelaySMS is the default SMS app. */
    const val SIM = "sim"
}

object MessageStatus {
    const val RECEIVED = "received"
    const val PENDING = "pending"
    const val SENT = "sent"
    const val DELIVERED = "delivered"
    const val FAILED = "failed"

    /** Failed outgoing messages carry the reason code: "failed:<code>". */
    fun failed(reason: String?): String = if (reason.isNullOrEmpty()) FAILED else "$FAILED:$reason"
    fun isFailed(status: String): Boolean = status == FAILED || status.startsWith("$FAILED:")
    fun failureReason(status: String): String? = status.substringAfter("$FAILED:", "").ifEmpty { null }
}

/**
 * One message in RelaySMS's own inbox. [addressEnc] and [bodyEnc] are encrypted with the
 * device-local key and are null once the message is deleted; the remaining row is a content-free
 * tombstone that stops a re-delivered copy from reappearing.
 */
@Entity(
    tableName = "messages",
    indices = [Index("received_at"), Index("address_key", "received_at"), Index("deleted_at")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "address_enc", typeAffinity = ColumnInfo.BLOB) val addressEnc: ByteArray?,
    @ColumnInfo(name = "body_enc", typeAffinity = ColumnInfo.BLOB) val bodyEnc: ByteArray?,
    /** SHA-256 of the normalised address: groups a conversation without revealing the address. */
    @ColumnInfo(name = "address_key") val addressKey: String,
    /** When the SMS reached the SIM (incoming) or was composed (outgoing). */
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "stored_at") val storedAt: Long,
    val direction: Int,
    val origin: String,
    val status: String,
    @ColumnInfo(name = "is_read") val isRead: Boolean,
    @ColumnInfo(name = "has_otp") val hasOtp: Boolean,
    @ColumnInfo(name = "part_count") val partCount: Int,
    @ColumnInfo(name = "subscription_id") val subscriptionId: Int,
    /** Row id in the system SMS provider when mirrored there, else null. */
    @ColumnInfo(name = "provider_id") val providerId: Long?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
) {
    override fun equals(other: Any?): Boolean = other is MessageEntity && other.id == id && other.isRead == isRead &&
        other.status == status && other.deletedAt == deletedAt && other.providerId == providerId
    override fun hashCode(): Int = id.hashCode()
}

/**
 * Source phone: one row per remote send request ever received, so that a replayed request is
 * never sent twice. Holds no recipient and no message text.
 */
@Entity(tableName = "send_requests", indices = [Index("received_at")])
data class SendRequestEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    val status: String,
    val reason: String?,
)
