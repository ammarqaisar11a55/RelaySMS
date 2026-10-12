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
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.ammarqaisar11a55.relaysms.ui.components.InfoCard
import io.github.ammarqaisar11a55.relaysms.ui.components.RelayScaffold
import io.github.ammarqaisar11a55.relaysms.ui.components.SectionHeader
import io.github.ammarqaisar11a55.relaysms.ui.components.StatusRow
import io.github.ammarqaisar11a55.relaysms.ui.components.Tone

@Composable
private fun SettingsAction(onOpenSettings: () -> Unit) {
    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings")) {
        Icon(Icons.Filled.Settings, contentDescription = "Settings")
    }
}

@Composable
fun SourceHomeScreen(onOpenSettings: () -> Unit) {
    RelayScaffold(title = "RelaySMS · Source", actions = { SettingsAction(onOpenSettings) }, modifier = Modifier.testTag("source_home")) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard(
                icon = Icons.Filled.SimCard,
                title = "This is the source phone",
                body = "Nothing is forwarded yet. Forwarding stays off until you pair a companion phone and turn it on yourself.",
            )
            SectionHeader("Status")
            StatusRow("Companion phone", "Not paired", Tone.WARN)
            StatusRow("Forwarding", "Off", Tone.NEUTRAL)
        }
    }
}

@Composable
fun CompanionHomeScreen(onOpenSettings: () -> Unit) {
    RelayScaffold(title = "RelaySMS · Companion", actions = { SettingsAction(onOpenSettings) }, modifier = Modifier.testTag("companion_home")) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard(
                icon = Icons.Filled.Inbox,
                title = "This is the companion phone",
                body = "Forwarded messages will appear here after you pair this phone with your source phone.",
            )
            SectionHeader("Status")
            StatusRow("Source phone", "Not paired", Tone.WARN)
        }
    }
}
