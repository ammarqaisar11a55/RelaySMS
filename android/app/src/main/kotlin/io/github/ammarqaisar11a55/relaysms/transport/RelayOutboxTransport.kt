package io.github.ammarqaisar11a55.relaysms.transport

import io.github.ammarqaisar11a55.relaysms.core.network.ApiResult
import io.github.ammarqaisar11a55.relaysms.core.network.RelayApi
import io.github.ammarqaisar11a55.relaysms.core.security.DeviceCrypto
import io.github.ammarqaisar11a55.relaysms.delivery.OutboxTransport
import io.github.ammarqaisar11a55.relaysms.delivery.SendResult
import io.github.ammarqaisar11a55.relaysms.pairing.IdentityStore
import kotlinx.serialization.json.JsonObject

/**
 * Uploads one outbox item: wraps the stored body in an envelope, encrypts it for the paired
 * device and submits the ciphertext. The plaintext never leaves this function unencrypted.
 */
class RelayOutboxTransport(
    private val store: IdentityStore,
    private val crypto: () -> DeviceCrypto,
    private val api: (baseUrl: String, token: String?) -> RelayApi,
    /** Invoked when the relay says this device is no longer linked or no longer registered. */
    private val onAuthorizationLost: suspend () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : OutboxTransport {

    override suspend fun send(id: String, kind: String, plaintext: ByteArray): SendResult {
        val identity = store.identity.value ?: return SendResult.NOT_CONFIGURED
        val peer = store.peer.value ?: return SendResult.NOT_CONFIGURED
        val body = MessageCodec.parseBody(String(plaintext, Charsets.UTF_8)) as? JsonObject ?: return SendResult.REJECTED
        val request = try {
            MessageCodec.seal(crypto(), identity.deviceId, peer.identity, Envelope(MessageCodec.VERSION, kind, id, now(), body))
        } catch (e: Exception) {
            // Local keys unusable: retrying cannot help, and nothing unencrypted may be sent.
            return SendResult.REJECTED
        }
        return when (val r = api(identity.serverUrl, identity.token).submitMessage(request)) {
            is ApiResult.Ok -> SendResult.ACCEPTED
            ApiResult.Unreachable -> SendResult.RETRY_LATER
            is ApiResult.Error -> when {
                r.status == 401 || r.code == "not_linked" -> {
                    onAuthorizationLost()
                    SendResult.REJECTED
                }
                r.status == 429 || r.status >= 500 -> SendResult.RETRY_LATER
                else -> SendResult.REJECTED
            }
        }
    }
}
