package io.github.ammarqaisar11a55.relaysms

import io.github.ammarqaisar11a55.relaysms.core.database.MessageDirection
import io.github.ammarqaisar11a55.relaysms.core.database.MessageOrigin
import io.github.ammarqaisar11a55.relaysms.core.database.MessageStatus
import io.github.ammarqaisar11a55.relaysms.feature.inbox.ConnectionKind
import io.github.ammarqaisar11a55.relaysms.feature.inbox.ConnectionSummaries
import io.github.ammarqaisar11a55.relaysms.inbox.InboxFilter
import io.github.ammarqaisar11a55.relaysms.inbox.InboxMessage
import io.github.ammarqaisar11a55.relaysms.inbox.InboxQuery
import io.github.ammarqaisar11a55.relaysms.inbox.InboxSort
import io.github.ammarqaisar11a55.relaysms.pairing.LinkState
import io.github.ammarqaisar11a55.relaysms.pairing.LinkStatus
import io.github.ammarqaisar11a55.relaysms.transport.SocketState
import io.github.ammarqaisar11a55.relaysms.transport.SyncOutcome
import io.github.ammarqaisar11a55.relaysms.transport.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxQueryTest {
    private fun msg(id: String, address: String, body: String, at: Long, read: Boolean = false, otp: Boolean = false) =
        InboxMessage(id, address, body, at, at, MessageDirection.INCOMING, MessageOrigin.RELAY, MessageStatus.RECEIVED, read, otp, 1, "k")

    private val all = listOf(
        msg("a", "TESTBANK", "Your OTP is 123456", 30, otp = true),
        msg("b", "+923001234567", "See you at five", 20, read = true),
        msg("c", "Telenor", "Your package expires tomorrow", 10),
        msg("d", "TESTBANK", "PKR 500 debited", 40, read = true),
    )

    private fun ids(q: String = "", f: InboxFilter = InboxFilter.ALL, s: InboxSort = InboxSort.NEWEST_FIRST) = InboxQuery.apply(all, q, f, s).map { it.id }

    @Test fun `sorts by original time in both directions`() {
        assertEquals(listOf("d", "a", "b", "c"), ids())
        assertEquals(listOf("c", "b", "a", "d"), ids(s = InboxSort.OLDEST_FIRST))
    }

    @Test fun `filters by unread and by likely codes`() {
        assertEquals(listOf("a", "c"), ids(f = InboxFilter.UNREAD))
        assertEquals(listOf("a"), ids(f = InboxFilter.CODES))
    }

    @Test fun `searches sender and text case-insensitively`() {
        assertEquals(listOf("d", "a"), ids("testbank"))
        assertEquals(listOf("c"), ids("  EXPIRES "))
        // Digits match message text and also phone-number senders that contain them.
        assertEquals(listOf("a", "b"), ids("123456"))
        assertEquals(listOf("a"), ids("OTP is 123456"))
        assertEquals(emptyList<String>(), ids("nothing like this"))
        assertEquals(listOf("a"), ids("testbank", InboxFilter.UNREAD))
    }

    @Test fun `phone number search ignores spelling of the number`() {
        assertEquals(listOf("b"), ids("0300 1234567"))
        assertEquals(listOf("b"), ids("+92300"))
        assertEquals(listOf("b"), ids("1234567"))
    }

    @Test fun `stable order for identical timestamps`() {
        val same = listOf(msg("y", "A", "1", 5), msg("x", "A", "2", 5), msg("z", "A", "3", 5))
        assertEquals(listOf("z", "y", "x"), InboxQuery.apply(same, "", InboxFilter.ALL, InboxSort.NEWEST_FIRST).map { it.id })
        assertEquals(listOf("x", "y", "z"), InboxQuery.apply(same, "", InboxFilter.ALL, InboxSort.OLDEST_FIRST).map { it.id })
    }

    @Test fun `connection status never claims more than is known`() {
        val now = 10_000_000L
        fun kind(link: LinkState, paired: Boolean, socket: SocketState, sync: SyncStatus = SyncStatus()) =
            ConnectionSummaries.of(LinkStatus(link), paired, socket, sync, now).kind
        assertEquals(ConnectionKind.NOT_PAIRED, kind(LinkState.NOT_PAIRED, false, SocketState.CONNECTED))
        assertEquals(ConnectionKind.REVOKED, kind(LinkState.REVOKED, false, SocketState.CONNECTED))
        assertEquals(ConnectionKind.CONNECTED, kind(LinkState.PAIRED, true, SocketState.CONNECTED))
        assertEquals(ConnectionKind.CONNECTING, kind(LinkState.PAIRED, true, SocketState.CONNECTING))
        assertEquals(ConnectionKind.OFFLINE, kind(LinkState.PAIRED, true, SocketState.WAITING_TO_RETRY))
        assertEquals(ConnectionKind.OFFLINE, kind(LinkState.PAIRED_UNCONFIRMED, true, SocketState.STOPPED))
        // A sync that succeeded moments ago counts; an old one does not.
        val recent = SyncStatus(lastOutcome = SyncOutcome.COMPLETE, lastCompleteAtMillis = now - 30_000)
        val old = SyncStatus(lastOutcome = SyncOutcome.COMPLETE, lastCompleteAtMillis = now - 600_000)
        assertEquals(ConnectionKind.RECENTLY_SYNCED, kind(LinkState.PAIRED, true, SocketState.STOPPED, recent))
        assertEquals(ConnectionKind.OFFLINE, kind(LinkState.PAIRED, true, SocketState.STOPPED, old))
        assertEquals(ConnectionKind.OFFLINE, kind(LinkState.PAIRED, true, SocketState.STOPPED, recent.copy(lastOutcome = SyncOutcome.RETRY)))
        assertEquals(ConnectionKind.SYNCING, kind(LinkState.PAIRED, true, SocketState.STOPPED, SyncStatus(running = true)))
        // Poll mode is described as regular checking, never as "Connected".
        assertEquals(ConnectionKind.CHECKING_REGULARLY, kind(LinkState.PAIRED, true, SocketState.POLLING, recent))
        assertEquals(ConnectionKind.OFFLINE, kind(LinkState.PAIRED, true, SocketState.POLLING, SyncStatus(lastOutcome = SyncOutcome.RETRY)))
        // No internet on the phone outranks everything except "not paired".
        fun offline(link: LinkState, paired: Boolean, socket: SocketState) = ConnectionSummaries.of(LinkStatus(link), paired, socket, recent, now, online = false).kind
        assertEquals(ConnectionKind.NO_NETWORK, offline(LinkState.PAIRED, true, SocketState.CONNECTED))
        assertEquals(ConnectionKind.NO_NETWORK, offline(LinkState.PAIRED, true, SocketState.WAITING_TO_RETRY))
        assertEquals(ConnectionKind.NOT_PAIRED, offline(LinkState.NOT_PAIRED, false, SocketState.STOPPED))
    }
}
