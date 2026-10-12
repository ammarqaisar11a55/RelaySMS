package io.github.ammarqaisar11a55.relaysms.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppSettings
import io.github.ammarqaisar11a55.relaysms.pairing.LinkState
import io.github.ammarqaisar11a55.relaysms.pairing.LinkStatus
import io.github.ammarqaisar11a55.relaysms.pairing.PairingProtocol
import io.github.ammarqaisar11a55.relaysms.ui.components.ConfirmDialog
import io.github.ammarqaisar11a55.relaysms.ui.components.EmptyState
import io.github.ammarqaisar11a55.relaysms.ui.components.FullWidthButton
import io.github.ammarqaisar11a55.relaysms.ui.components.InfoCard
import io.github.ammarqaisar11a55.relaysms.ui.components.RelayScaffold
import io.github.ammarqaisar11a55.relaysms.ui.components.SectionHeader
import io.github.ammarqaisar11a55.relaysms.ui.components.StatusRow
import io.github.ammarqaisar11a55.relaysms.ui.components.Tone
import io.github.ammarqaisar11a55.relaysms.ui.navigation.LocalAppContainer
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

/** Human wording for the link. "Online" is only claimed when the relay just said so. */
fun LinkStatus.label(): Pair<String, Tone> = when (state) {
    LinkState.NOT_PAIRED -> "Not paired" to Tone.WARN
    LinkState.REVOKED -> "Pairing was removed" to Tone.ERROR
    LinkState.PAIRED_UNCONFIRMED -> "Paired · relay not reached" to Tone.WARN
    LinkState.PAIRED -> if (peerOnline) "Paired · online" to Tone.OK else "Paired · offline" to Tone.NEUTRAL
}

@Composable
fun PairedDeviceScreen(settings: AppSettings, onBack: () -> Unit, onPair: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val peer by container.identityStore.peer.collectAsStateWithLifecycle()
    val identity by container.identityStore.identity.collectAsStateWithLifecycle()
    val status by container.linkMonitor.status.collectAsStateWithLifecycle()
    var confirmRevoke by rememberSaveable { mutableStateOf(false) }
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val otherName = if (settings.role == DeviceRole.SOURCE) "companion" else "source"

    LaunchedEffect(Unit) { container.linkMonitor.refresh() }

    RelayScaffold(title = "Paired device", onBack = onBack, snackbarHostState = snackbar) { padding ->
        val p = peer
        if (p == null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.LinkOff,
                    title = if (status.state == LinkState.REVOKED) "Pairing was removed" else "No paired device",
                    body = if (status.state == LinkState.REVOKED)
                        "The $otherName phone or the relay removed this pairing. Nothing is being forwarded. Pair again to continue."
                    else "Pair this phone with your $otherName phone to start.",
                    actionLabel = "Pair now",
                    onAction = onPair,
                )
            }
            return@RelayScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val (label, tone) = status.label()
            SectionHeader("Connection")
            StatusRow("Status", label, tone)
            status.peerLastSeenAtMillis?.let { StatusRow("Last seen by relay", formatTime(it), Tone.NEUTRAL) }
            if (settings.role == DeviceRole.SOURCE && status.state == LinkState.PAIRED) {
                StatusRow("Waiting on relay for companion", status.awaitingPeer.toString(), if (status.awaitingPeer == 0) Tone.NEUTRAL else Tone.WARN)
            }
            Text(
                if (status.confirmedAtMillis > 0) "Checked with the relay at ${formatTime(status.confirmedAtMillis)}." else "Not yet confirmed with the relay in this session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FullWidthButton(
                "Check now",
                enabled = !busy,
                outlined = true,
                onClick = { scope.launch { busy = true; container.linkMonitor.refresh(); busy = false } },
                modifier = Modifier.testTag("device_refresh"),
            )

            SectionHeader("Device")
            StatusRow("Device", PairingProtocol.fingerprint(p.identity), Tone.NEUTRAL)
            StatusRow("Verification code", p.safetyCode, Tone.NEUTRAL)
            StatusRow("Paired on", formatTime(p.linkedAtMillis), Tone.NEUTRAL)
            identity?.let { StatusRow("Relay server", it.serverUrl.substringAfter("://"), Tone.NEUTRAL) }

            SectionHeader("Remove")
            InfoCard(
                title = "Revoke this device",
                body = "Removes the pairing on the relay so nothing more is delivered between the two phones, deletes undelivered messages on the relay, and turns forwarding off.",
                action = { FullWidthButton("Revoke device", enabled = !busy, onClick = { confirmRevoke = true }, modifier = Modifier.testTag("device_revoke")) },
            )
            FullWidthButton("Forget on this phone only", enabled = !busy, outlined = true, onClick = { confirmForget = true })
        }
    }

    if (confirmRevoke) {
        ConfirmDialog(
            title = "Revoke paired device?",
            body = "The $otherName phone will stop sending or receiving messages with this phone. You can pair again later.",
            confirmLabel = "Revoke",
            destructive = true,
            onDismiss = { confirmRevoke = false },
            onConfirm = {
                confirmRevoke = false
                scope.launch {
                    busy = true
                    val ok = container.linkMonitor.revokePeer()
                    busy = false
                    if (!ok) snackbar.showSnackbar("The relay could not be reached. Nothing was changed. Try again when online.")
                }
            },
        )
    }
    if (confirmForget) {
        ConfirmDialog(
            title = "Forget on this phone only?",
            body = "Use this only if the relay cannot be reached. This phone forgets the pairing now; the relay-side pairing is removed the next time this phone reaches the relay.",
            confirmLabel = "Forget",
            destructive = true,
            onDismiss = { confirmForget = false },
            onConfirm = {
                confirmForget = false
                scope.launch { container.linkMonitor.clearLocalPairing() }
            },
        )
    }
}
