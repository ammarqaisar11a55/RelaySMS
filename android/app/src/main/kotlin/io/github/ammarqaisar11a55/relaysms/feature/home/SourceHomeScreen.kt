package io.github.ammarqaisar11a55.relaysms.feature.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ammarqaisar11a55.relaysms.core.database.OutboxState
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppSettings
import io.github.ammarqaisar11a55.relaysms.core.prefs.ForwardingStats
import io.github.ammarqaisar11a55.relaysms.feature.pairing.label
import io.github.ammarqaisar11a55.relaysms.pairing.LinkState
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

fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
fun SourceHomeScreen(
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenDevice: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var smsGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECEIVE_SMS)) }
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val peer by container.identityStore.peer.collectAsStateWithLifecycle()
    val link by container.linkMonitor.status.collectAsStateWithLifecycle()
    val paired = peer != null
    LifecycleResumeEffect(Unit) {
        smsGranted = hasPermission(context, Manifest.permission.RECEIVE_SMS)
        val job = container.appScope.launch { container.linkMonitor.refresh() }
        onPauseOrDispose { job.cancel() }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        smsGranted = granted
        denied = !granted
    }

    val stats by container.preferences.stats.collectAsStateWithLifecycle(ForwardingStats())
    val pending by container.database.outboxDao().observePendingCount().collectAsStateWithLifecycle(0)
    val filters by container.sourceStores.filters.collectAsStateWithLifecycle(emptyList())
    val delivered by remember { container.database.outboxDao().observeCount(OutboxState.DELIVERED) }.collectAsStateWithLifecycle(0)
    val expired by remember { container.database.outboxDao().observeCount(OutboxState.EXPIRED) }.collectAsStateWithLifecycle(0)
    val failed by remember { container.database.outboxDao().observeCount(OutboxState.FAILED) }.collectAsStateWithLifecycle(0)
    val online by container.networkMonitor.online.collectAsStateWithLifecycle()

    // Forwarding can only be effective with the permission; never show it as active without it.
    val canForward = smsGranted && paired
    val forwardingActive = settings.forwardingEnabled && canForward

    RelayScaffold(
        title = "RelaySMS · Source",
        actions = {
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings")) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
        modifier = Modifier.testTag("source_home"),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!smsGranted) {
                InfoCard(
                    icon = Icons.Filled.Sms,
                    title = "Permission needed to receive SMS",
                    body = "To forward messages, RelaySMS must be allowed to see new SMS as they arrive on this phone. " +
                        "It reads only new incoming messages, and only forwards the senders you choose.",
                    tone = Tone.WARN,
                    action = { FullWidthButton("Review and allow", onClick = { showRationale = true }, modifier = Modifier.testTag("sms_permission_request")) },
                )
            }
            if (denied && !smsGranted) {
                InfoCard(
                    icon = Icons.Filled.Block,
                    title = "Permission was not granted",
                    body = "If no Android prompt appeared, Android is treating RelaySMS as a restricted app because it was installed from a file. " +
                        "Open App info, tap the ⋮ menu, choose \"Allow restricted settings\", then come back and try again. " +
                        "If you tapped \"Don't allow\", you can grant SMS under Permissions on the same screen.",
                    tone = Tone.ERROR,
                    action = { FullWidthButton("Open App info", onClick = { openAppSettings(context) }, outlined = true) },
                )
            }

            if (!paired) {
                InfoCard(
                    icon = Icons.Filled.Sms,
                    title = if (link.state == LinkState.REVOKED) "Pairing was removed" else "Pair a companion phone",
                    body = if (link.state == LinkState.REVOKED) "The companion phone is no longer paired. Forwarding was turned off and unsent messages were discarded. Pair again to continue."
                    else "Messages are encrypted for one specific companion phone. Pair it before turning forwarding on.",
                    tone = if (link.state == LinkState.REVOKED) Tone.ERROR else Tone.NEUTRAL,
                    action = { FullWidthButton("Pair with companion phone", onClick = onOpenPairing, modifier = Modifier.testTag("open_pairing")) },
                )
            }

            SectionHeader("Forwarding")
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(
                        value = settings.forwardingEnabled,
                        enabled = canForward,
                        role = Role.Switch,
                        onValueChange = { on -> scope.launch { container.preferences.setForwardingEnabled(on) } },
                    )
                    .testTag("forwarding_switch"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Forward incoming SMS", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            !smsGranted -> "Allow SMS access first"
                            !paired -> "Pair a companion phone first"
                            settings.forwardingEnabled -> "On. Tap to pause forwarding."
                            else -> "Off. Nothing is forwarded."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.forwardingEnabled && paired, onCheckedChange = null, enabled = canForward)
            }
            if (forwardingActive && settings.allowlistOnly && filters.isEmpty()) {
                InfoCard(
                    icon = Icons.Filled.Warning,
                    title = "No senders selected",
                    body = "Forwarding is on, but only chosen senders are forwarded and none are chosen yet. Nothing will be forwarded.",
                    tone = Tone.WARN,
                )
            }
            FullWidthButton(
                text = if (settings.allowlistOnly) "Sender filters (${filters.size} chosen)" else "Sender filters (all senders)",
                onClick = onOpenFilters,
                outlined = true,
                modifier = Modifier.testTag("open_filters"),
            )

            SectionHeader("Status")
            StatusRow("SMS permission", if (smsGranted) "Granted" else "Not granted", if (smsGranted) Tone.OK else Tone.WARN)
            StatusRow(
                "Forwarding",
                when {
                    forwardingActive -> "On"
                    settings.forwardingEnabled && !smsGranted -> "Blocked: no permission"
                    else -> "Off"
                },
                if (forwardingActive) Tone.OK else Tone.NEUTRAL,
            )
            link.label().let { (label, tone) -> StatusRow("Companion phone", label, tone) }
            if (paired) FullWidthButton("Paired device", onClick = onOpenDevice, outlined = true, modifier = Modifier.testTag("open_device"))
            StatusRow("This phone's internet", if (online) "Online" else "No connection", if (online) Tone.OK else Tone.WARN)

            SectionHeader("Delivery")
            StatusRow("Waiting to be sent", pending.toString(), if (pending == 0) Tone.NEUTRAL else Tone.WARN)
            StatusRow("Sent to the relay (last 7 days)", delivered.toString(), Tone.NEUTRAL)
            if (paired && link.state == LinkState.PAIRED) {
                StatusRow("On the relay, not yet received by companion", link.awaitingPeer.toString(), if (link.awaitingPeer == 0) Tone.NEUTRAL else Tone.WARN)
            }
            if (expired > 0) StatusRow("Expired before they could be sent", expired.toString(), Tone.ERROR)
            if (failed > 0) StatusRow("Refused by the relay or unreadable", failed.toString(), Tone.ERROR)
            if (expired > 0 || failed > 0) {
                Text(
                    "These messages were not forwarded. They are still in this phone's normal SMS app. Counts cover the last 7 days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pending > 0) {
                Text(
                    if (online) "Messages wait encrypted on this phone until the relay accepts them. Unsent messages are discarded after 72 hours."
                    else "This phone is offline. Messages wait encrypted here and are sent when it is back online; unsent messages are discarded after 72 hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Activity on this phone")
            StatusRow("SMS seen", stats.received.toString(), Tone.NEUTRAL)
            StatusRow("Accepted for forwarding", stats.queued.toString(), Tone.NEUTRAL)
            StatusRow("Skipped by filters", stats.filtered.toString(), Tone.NEUTRAL)
            if (stats.queueFull > 0) StatusRow("Not forwarded: queue full", stats.queueFull.toString(), Tone.ERROR)
            if (stats.notForwarded > 0) StatusRow("Not forwarded: unreadable or storage error", stats.notForwarded.toString(), Tone.ERROR)
            Text(
                if (stats.lastEventAtMillis > 0) "Last SMS seen: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(stats.lastEventAtMillis))
                else "No SMS seen yet since setup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Keep RelaySMS installed and do not use \"Force stop\" on it: Android does not deliver new SMS to a force-stopped app until it is opened again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("Allow RelaySMS to receive SMS?") },
            text = {
                Text(
                    "RelaySMS uses this permission for one thing: to notice a new SMS when it arrives on this phone so that a copy can be forwarded to your paired companion phone.\n\n" +
                        "• Forwarding stays off until you turn it on.\n" +
                        "• Only senders you choose are forwarded, unless you choose all.\n" +
                        "• Messages are encrypted before they leave this phone.\n\n" +
                        "Android's own prompt comes next. It describes the whole SMS permission group as \"send and view SMS messages\"; RelaySMS on this phone only receives.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        launcher.launch(Manifest.permission.RECEIVE_SMS)
                    },
                    modifier = Modifier.testTag("sms_permission_continue"),
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showRationale = false }) { Text("Not now") } },
        )
    }
}
