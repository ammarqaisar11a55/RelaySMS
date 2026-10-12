package io.github.ammarqaisar11a55.relaysms.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ammarqaisar11a55.relaysms.feature.pairing.label
import io.github.ammarqaisar11a55.relaysms.pairing.LinkState
import io.github.ammarqaisar11a55.relaysms.ui.components.FullWidthButton
import io.github.ammarqaisar11a55.relaysms.ui.navigation.LocalAppContainer
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.ammarqaisar11a55.relaysms.ui.components.InfoCard
import io.github.ammarqaisar11a55.relaysms.ui.components.RelayScaffold
import io.github.ammarqaisar11a55.relaysms.ui.components.SectionHeader
import io.github.ammarqaisar11a55.relaysms.ui.components.StatusRow
import io.github.ammarqaisar11a55.relaysms.ui.components.Tone

@Composable
fun CompanionHomeScreen(onOpenSettings: () -> Unit, onOpenPairing: () -> Unit, onOpenDevice: () -> Unit) {
    val container = LocalAppContainer.current
    val peer by container.identityStore.peer.collectAsStateWithLifecycle()
    val status by container.linkMonitor.status.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        val job = container.appScope.launch { container.linkMonitor.refresh() }
        onPauseOrDispose { job.cancel() }
    }
    RelayScaffold(
        title = "RelaySMS · Companion",
        actions = {
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings")) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
        modifier = Modifier.testTag("companion_home"),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (peer == null) {
                InfoCard(
                    icon = Icons.Filled.Inbox,
                    title = if (status.state == LinkState.REVOKED) "Pairing was removed" else "This is the companion phone",
                    body = if (status.state == LinkState.REVOKED) "The pairing with your source phone no longer exists. Pair again to receive messages."
                    else "Forwarded messages will appear here after you pair this phone with your source phone.",
                    tone = if (status.state == LinkState.REVOKED) Tone.ERROR else Tone.NEUTRAL,
                    action = { FullWidthButton("Pair with source phone", onClick = onOpenPairing, modifier = Modifier.testTag("open_pairing")) },
                )
            }
            SectionHeader("Status")
            val (label, tone) = status.label()
            StatusRow("Source phone", label, tone)
            if (peer != null) FullWidthButton("Paired device", onClick = onOpenDevice, outlined = true, modifier = Modifier.testTag("open_device"))
        }
    }
}
