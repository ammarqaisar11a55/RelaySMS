package io.github.ammarqaisar11a55.relaysms.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** The relay answered with an error. [code] is the machine-readable code from the body. */
    data class Error(val status: Int, val code: String) : ApiResult<Nothing>

    /** No usable answer: offline, DNS, TLS failure, timeout, malformed response. */
    data object Unreachable : ApiResult<Nothing>
}

@Serializable data class RegisterRequest(val role: String, val hpkePublicKey: String, val signingPublicKey: String, val registrationCode: String? = null)
@Serializable data class RegisterResponse(val deviceId: String, val token: String)
@Serializable data class PublicDevice(val deviceId: String, val role: String, val hpkePublicKey: String, val signingPublicKey: String)
@Serializable data class PeerInfo(
    val deviceId: String,
    val role: String,
    val hpkePublicKey: String,
    val signingPublicKey: String,
    val linkedAt: String,
    val lastSeenAt: String,
    val online: Boolean,
    val awaitingPeer: Int = 0,
)
@Serializable data class MeResponse(val deviceId: String, val role: String, val serverTime: String = "", val peer: PeerInfo? = null)
/** Public facts about a relay. Older relays have no such endpoint and are treated as WebSocket relays. */
@Serializable data class RelayInfo(val apiVersion: Int = 1, val realtime: String = "websocket", val pollIntervalSeconds: Int = 10)
@Serializable data class TokenResponse(val token: String)
@Serializable data class RevokeResponse(val revoked: Boolean)
@Serializable data class CreatePairingResponse(val pairingId: String, val secret: String, val expiresAt: String)
@Serializable data class RedeemRequest(val secret: String, val proof: String)
@Serializable data class RedeemResponse(val pairingId: String, val expiresAt: String, val companion: PublicDevice)
@Serializable data class PairingView(
    val pairingId: String,
    val status: String,
    val expiresAt: String,
    val companion: PublicDevice? = null,
    val source: PublicDevice? = null,
    val sourceProof: String? = null,
    val sourceConfirmed: Boolean = false,
    val companionConfirmed: Boolean = false,
)
@Serializable data class StatusResponse(val status: String)
@Serializable data class SubmitMessageRequest(val messageId: String, val ciphertext: String, val signature: String)
@Serializable data class PendingMessage(val messageId: String, val senderId: String, val ciphertext: String, val signature: String, val createdAt: String)
@Serializable data class PendingResponse(val messages: List<PendingMessage>, val more: Boolean = false)
@Serializable data class AckRequest(val messageIds: List<String>)
@Serializable data class AckResponse(val acknowledged: Int)
@Serializable private data class ErrorBody(val error: ErrorDetail? = null)
@Serializable private data class ErrorDetail(val code: String = "unknown")

/** Typed client for the relay's HTTP API (docs/API.md). Never logs requests or responses. */
class RelayApi(private val client: OkHttpClient, baseUrl: String, private val token: String?) {
    private val base = baseUrl.trimEnd('/') + "/v1"

    suspend fun register(body: RegisterRequest) = post("/devices", RegisterRequest.serializer(), body, RegisterResponse.serializer(), auth = false)
    suspend fun info() = get("/info", RelayInfo.serializer())
    suspend fun me() = get("/devices/me", MeResponse.serializer())
    suspend fun rotateToken() = postEmpty("/devices/me/token", TokenResponse.serializer())
    suspend fun deleteDevice(): ApiResult<Unit> = execute<Unit>(request("/devices/me").delete().build(), null)
    suspend fun revokeLink() = execute(request("/link").delete().build(), RevokeResponse.serializer())

    suspend fun createPairing() = postEmpty("/pairings", CreatePairingResponse.serializer())
    suspend fun redeemPairing(id: String, body: RedeemRequest) = post("/pairings/$id/redeem", RedeemRequest.serializer(), body, RedeemResponse.serializer())
    suspend fun getPairing(id: String) = get("/pairings/$id", PairingView.serializer())
    suspend fun confirmPairing(id: String) = postEmpty("/pairings/$id/confirm", StatusResponse.serializer())
    suspend fun cancelPairing(id: String) = postEmpty("/pairings/$id/cancel", StatusResponse.serializer())

    suspend fun submitMessage(body: SubmitMessageRequest) = post("/messages", SubmitMessageRequest.serializer(), body, StatusResponse.serializer())
    suspend fun pendingMessages(limit: Int = 50) = get("/messages/pending?limit=$limit", PendingResponse.serializer())
    suspend fun acknowledge(ids: List<String>) = post("/messages/ack", AckRequest.serializer(), AckRequest(ids), AckResponse.serializer())

    private fun request(path: String, auth: Boolean = true): Request.Builder {
        val b = Request.Builder().url(base + path).header("Accept", "application/json")
        if (auth && token != null) b.header("Authorization", "Bearer $token")
        return b
    }

    private suspend fun <T> get(path: String, out: KSerializer<T>) = execute(request(path).get().build(), out)

    private suspend fun <B, T> post(path: String, inSer: KSerializer<B>, body: B, out: KSerializer<T>, auth: Boolean = true): ApiResult<T> =
        execute(request(path, auth).post(JSON.encodeToString(inSer, body).toRequestBody(JSON_MEDIA)).build(), out)

    private suspend fun <T> postEmpty(path: String, out: KSerializer<T>): ApiResult<T> =
        execute(request(path).post("{}".toRequestBody(JSON_MEDIA)).build(), out)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> execute(request: Request, out: KSerializer<T>?): ApiResult<T> {
        val (status, text) = try {
            client.newCall(request).awaitBody()
        } catch (e: IOException) {
            return ApiResult.Unreachable
        }
        if (status !in 200..299) {
            val code = runCatching { JSON.decodeFromString(ErrorBody.serializer(), text).error?.code }.getOrNull() ?: "http_$status"
            return ApiResult.Error(status, code)
        }
        if (out == null || status == 204) return ApiResult.Ok(Unit as T)
        return try {
            ApiResult.Ok(JSON.decodeFromString(out, text))
        } catch (e: Exception) {
            ApiResult.Unreachable
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Shared client: system trust store, normal certificate validation, bounded timeouts. */
        fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    }
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Ok -> ApiResult.Ok(transform(value))
    is ApiResult.Error -> this
    ApiResult.Unreachable -> ApiResult.Unreachable
}

/**
 * Runs the call and reads the whole response body on OkHttp's own thread, so that callers on the
 * main dispatcher never touch the network. Bodies are small JSON documents.
 */
internal suspend fun Call.awaitBody(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { it.code to it.body?.string().orEmpty() }
            } catch (e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
                return
            }
            if (cont.isActive) cont.resume(result)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
