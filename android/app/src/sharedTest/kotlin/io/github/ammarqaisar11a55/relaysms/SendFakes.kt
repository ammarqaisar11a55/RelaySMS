package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.sms.send.SendRequestHandler
import io.github.ammarqaisar11a55.relaysms.sms.send.SendRequestLog
import io.github.ammarqaisar11a55.relaysms.sms.send.SendStatus
import io.github.ammarqaisar11a55.relaysms.sms.send.SmsSender

/** In-memory stand-ins for the source phone's request log and SMS stack. */
class FakeSendLog : SendRequestLog {
    data class Row(var status: String, var reason: String?, val at: Long)
    val rows = LinkedHashMap<String, Row>()
    override suspend fun recordIfNew(requestId: String, nowMillis: Long): Boolean {
        if (rows.containsKey(requestId)) return false
        rows[requestId] = Row(SendRequestHandler.STATUS_RECEIVED, null, nowMillis)
        return true
    }
    override suspend fun setStatus(requestId: String, status: String, reason: String?) { rows[requestId]?.let { it.status = status; it.reason = reason } }
    override suspend fun find(requestId: String) = rows[requestId]?.let { it.status to it.reason }
    override suspend fun sentSince(sinceMillis: Long) = rows.values.count { it.at >= sinceMillis && it.status in setOf("sending", SendStatus.SENT, SendStatus.DELIVERED) }
}

class FakeSmsSender(var permission: Boolean = true, var immediateFailure: String? = null) : SmsSender {
    val sent = mutableListOf<Triple<String, String, String>>()
    override fun hasPermission() = permission
    override fun send(requestId: String, to: String, text: String): String? {
        immediateFailure?.let { return it }
        sent += Triple(requestId, to, text)
        return null
    }
}

