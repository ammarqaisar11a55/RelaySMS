package io.github.ammarqaisar11a55.relaysms.feature.inbox

import io.github.ammarqaisar11a55.relaysms.pairing.LinkState
import io.github.ammarqaisar11a55.relaysms.pairing.LinkStatus
import io.github.ammarqaisar11a55.relaysms.transport.SocketState
import io.github.ammarqaisar11a55.relaysms.transport.SyncOutcome
import io.github.ammarqaisar11a55.relaysms.transport.SyncStatus
import io.github.ammarqaisar11a55.relaysms.ui.components.Tone
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

enum class ConnectionKind { NOT_PAIRED, REVOKED, NO_NETWORK, SYNCING, CONNECTED, RECENTLY_SYNCED, CONNECTING, OFFLINE }

data class ConnectionSummary(val kind: ConnectionKind, val label: String, val detail: String, val tone: Tone)

/**
 * Turns the three independent facts the app knows (is there a pairing, is the live socket open,
 * when did a sync last succeed) into one honest status line. "Connected" is shown only while an
 * authenticated connection to the relay is actually open.
 */
object ConnectionSummaries {
    private const val RECENT_MS = 2 * 60 * 1000L

    fun of(link: LinkStatus, paired: Boolean, socket: SocketState, sync: SyncStatus, nowMillis: Long, online: Boolean = true): ConnectionSummary {
        val lastSync = if (sync.lastCompleteAtMillis > 0) "Last synced ${Times.short(sync.lastCompleteAtMillis, nowMillis)}" else "Not synced yet in this session"
        return when {
            !paired && link.state == LinkState.REVOKED ->
                ConnectionSummary(ConnectionKind.REVOKED, "Pairing removed", "Pair again to receive messages", Tone.ERROR)
            !paired -> ConnectionSummary(ConnectionKind.NOT_PAIRED, "Not paired", "Pair with your source phone to start", Tone.WARN)
            // The phone itself has no internet: say so, rather than blaming the relay.
            !online -> ConnectionSummary(ConnectionKind.NO_NETWORK, "No internet connection", "$lastSync · messages will arrive when this phone is back online", Tone.WARN)
            sync.running -> ConnectionSummary(ConnectionKind.SYNCING, "Synchronizing…", lastSync, Tone.NEUTRAL)
            socket == SocketState.CONNECTED -> ConnectionSummary(ConnectionKind.CONNECTED, "Connected", lastSync, Tone.OK)
            sync.lastOutcome == SyncOutcome.COMPLETE && nowMillis - sync.lastCompleteAtMillis < RECENT_MS ->
                ConnectionSummary(ConnectionKind.RECENTLY_SYNCED, "Synced", lastSync, Tone.OK)
            socket == SocketState.CONNECTING -> ConnectionSummary(ConnectionKind.CONNECTING, "Connecting…", lastSync, Tone.NEUTRAL)
            else -> ConnectionSummary(ConnectionKind.OFFLINE, "Relay not reachable", "$lastSync · will retry automatically", Tone.WARN)
        }
    }
}

object Times {
    /** Time today, weekday within the last week, otherwise the date. */
    fun short(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val date = Date(millis)
        return when {
            sameDay -> DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
            nowMillis - millis in 0 until 6 * 24 * 3_600_000L -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(date)
            else -> DateFormat.getDateInstance(DateFormat.SHORT).format(date)
        }
    }

    fun full(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(millis))
}
