package io.github.ammarqaisar11a55.relaysms.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.ui.components.FullWidthButton
import io.github.ammarqaisar11a55.relaysms.ui.components.InfoCard
import io.github.ammarqaisar11a55.relaysms.ui.components.RelayScaffold
import io.github.ammarqaisar11a55.relaysms.ui.navigation.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Welcome to RelaySMS", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(
            "RelaySMS forwards a copy of the text messages that arrive on one of your phones to another phone you own, over the internet.",
            style = MaterialTheme.typography.bodyLarge,
        )
        InfoCard(
            icon = Icons.Filled.SimCard,
            title = "Your SIM stays where it is",
            body = "The source phone keeps the SIM and receives messages as usual. It sends a copy of the messages you choose to your paired companion phone.",
        )
        InfoCard(
            icon = Icons.Filled.Inbox,
            title = "Messages appear inside RelaySMS",
            body = "The companion phone shows forwarded messages in RelaySMS's own inbox. It does not need a SIM, only an internet connection.",
        )
        InfoCard(
            icon = Icons.Filled.Lock,
            title = "Encrypted between your phones",
            body = "Messages are encrypted on the source phone and can only be decrypted on the companion phone. The relay server stores ciphertext and cannot read them.",
        )
        InfoCard(
            icon = Icons.Outlined.Info,
            title = "What RelaySMS does not do",
            body = "It does not replace your SIM, register a phone with PTA, or guarantee instant delivery. Delivery depends on both phones having internet access and on Android allowing background work.",
        )
        Spacer(Modifier.weight(1f))
        FullWidthButton("Continue", onClick = onContinue, modifier = Modifier.testTag("welcome_continue"))
    }
}

@Composable
fun RoleSelectionScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf<DeviceRole?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }

    RelayScaffold(title = "Choose this phone's role", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Install RelaySMS on both phones and choose a different role on each. You can change this later only by resetting the app.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RoleOption(
                    role = DeviceRole.SOURCE,
                    icon = Icons.Filled.SimCard,
                    title = "Source phone",
                    body = "This phone has the SIM. It will forward a copy of the incoming messages you allow. It needs permission to receive SMS, which is requested later with an explanation.",
                    selected = selected == DeviceRole.SOURCE,
                    onSelect = { selected = DeviceRole.SOURCE },
                )
                RoleOption(
                    role = DeviceRole.COMPANION,
                    icon = Icons.Filled.Inbox,
                    title = "Companion phone",
                    body = "This phone shows the forwarded messages. It needs no SIM and is not asked for SMS permission.",
                    selected = selected == DeviceRole.COMPANION,
                    onSelect = { selected = DeviceRole.COMPANION },
                )
            }
            Spacer(Modifier.weight(1f))
            FullWidthButton(
                text = when (selected) {
                    DeviceRole.SOURCE -> "Set up as source phone"
                    DeviceRole.COMPANION -> "Set up as companion phone"
                    null -> "Select a role"
                },
                enabled = selected != null && !saving,
                onClick = {
                    val role = selected ?: return@FullWidthButton
                    saving = true
                    scope.launch { container.preferences.setRole(role) }
                },
                modifier = Modifier.testTag("role_confirm"),
            )
        }
    }
}

@Composable
private fun RoleOption(role: DeviceRole, icon: ImageVector, title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("role_${role.wireName}")
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.primaryContainer else colors.surfaceVariant,
            contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}
