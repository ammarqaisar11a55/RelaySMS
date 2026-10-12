package io.github.ammarqaisar11a55.relaysms.transport

import io.github.ammarqaisar11a55.relaysms.core.network.ServerUrl
import io.github.ammarqaisar11a55.relaysms.pairing.IdentityStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

enum class SocketState { STOPPED, CONNECTING, CONNECTED, WAITING_TO_RETRY }

/** Exponential backoff with jitter, used for reconnecting. */
class Backoff(private val baseMs: Long = 1_000, private val maxMs: Long = 60_000, private val random: Random = Random.Default) {
    private var attempt = 0

    fun reset() {
        attempt = 0
    }

    /** Next delay: base * 2^attempt, capped, with +/-25% jitter so devices do not reconnect in step. */
    fun next(): Long {
        val exp = (baseMs shl attempt.coerceAtMost(16)).coerceIn(baseMs, maxMs)
        if (attempt < 16) attempt++
        val jitter = (exp * 0.25).toLong()
        return (exp - jitter + random.nextLong(2 * jitter + 1)).coerceAtLeast(baseMs / 2)
    }
}

/**
 * Authenticated WebSocket to the relay. It carries only content-free hints ("messages are
 * waiting"); the data is always fetched over HTTPS. Reconnects with backoff while started.
 * [state] is CONNECTED only while a handshake authenticated by the relay is open.
 */
class RelaySocket(
    httpClient: OkHttpClient,
    private val store: IdentityStore,
    private val scope: CoroutineScope,
    private val onEvent: suspend (type: String) -> Unit,
    private val onRejected: suspend () -> Unit,
) {
    private val client = httpClient.newBuilder().pingInterval(25, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val _state = MutableStateFlow(SocketState.STOPPED)
    val state: StateFlow<SocketState> = _state.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _state.value = SocketState.STOPPED
    }

    private suspend fun runLoop() {
        val backoff = Backoff()
        while (scope.isActive) {
            val identity = store.identity.first { it != null }!!
            _state.value = SocketState.CONNECTING
            val closed = CompletableDeferred<Int>()
            val request = Request.Builder()
                .url(ServerUrl.toWebSocketUrl(identity.serverUrl, "/v1/ws"))
                .header("Authorization", "Bearer ${identity.token}")
                .build()
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    backoff.reset()
                    _state.value = SocketState.CONNECTED
                    scope.launch { onEvent(EVENT_CONNECTED) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val type = runCatching { JSON.decodeFromString(Event.serializer(), text).type }.getOrNull() ?: return
                    scope.launch { onEvent(type) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(code)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.complete(if (response?.code == 401) CLOSE_REVOKED else -1)
                }
            })
            val code = try {
                closed.await()
            } finally {
                socket.cancel()
            }
            _state.value = SocketState.WAITING_TO_RETRY
            if (code == CLOSE_REVOKED) onRejected()
            delay(backoff.next())
        }
    }

    @Serializable private data class Event(val type: String)

    companion object {
        const val EVENT_CONNECTED = "connected"
        const val EVENT_MESSAGES = "messages_available"
        const val EVENT_LINK = "link_changed"
        const val EVENT_DELIVERY = "delivery_updated"
        private const val CLOSE_REVOKED = 4401
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
