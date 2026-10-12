package io.github.ammarqaisar11a55.relaysms.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.model.ThemeMode
import io.github.ammarqaisar11a55.relaysms.core.network.ServerUrl
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppSettings
import io.github.ammarqaisar11a55.relaysms.ui.components.ConfirmDialog
import io.github.ammarqaisar11a55.relaysms.ui.components.FullWidthButton
import io.github.ammarqaisar11a55.relaysms.ui.components.RelayScaffold
import io.github.ammarqaisar11a55.relaysms.ui.components.SectionHeader
import io.github.ammarqaisar11a55.relaysms.ui.navigation.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var resetting by rememberSaveable { mutableStateOf(false) }

    RelayScaffold(title = "Settings", onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader("Appearance")
            Column(Modifier.selectableGroup()) {
                ThemeOption("Follow system", ThemeMode.SYSTEM, settings.themeMode) { scope.launch { container.preferences.setThemeMode(it) } }
                ThemeOption("Light", ThemeMode.LIGHT, settings.themeMode) { scope.launch { container.preferences.setThemeMode(it) } }
                ThemeOption("Dark", ThemeMode.DARK, settings.themeMode) { scope.launch { container.preferences.setThemeMode(it) } }
            }

            SectionHeader("Relay server")
            ServerUrlEditor(
                current = settings.serverUrl.ifEmpty { container.environment.defaultServerUrl },
                allowCleartextLocal = container.environment.allowCleartextLocal,
                onSave = { url ->
                    scope.launch {
                        container.preferences.setServerUrl(url)
                        snackbar.showSnackbar("Relay server saved")
                    }
                },
            )

            SectionHeader("Privacy")
            Text(
                "Messages are encrypted on the source phone and decrypted only on the companion phone. " +
                    "The relay server stores ciphertext until it is delivered or expires. RelaySMS has no analytics " +
                    "and does not back up messages or keys to the cloud.",
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionHeader("About")
            Text(
                "Role: " + when (settings.role) {
                    DeviceRole.SOURCE -> "Source phone"
                    DeviceRole.COMPANION -> "Companion phone"
                    null -> "Not chosen"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Version ${container.environment.versionName}", style = MaterialTheme.typography.bodyMedium)

            SectionHeader("Reset")
            Text(
                "Resetting stops forwarding, removes the pairing, deletes this phone's keys and stored messages, and lets you choose a role again.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FullWidthButton(
                "Reset RelaySMS on this phone",
                onClick = { confirmReset = true },
                enabled = !resetting,
                outlined = true,
                modifier = Modifier.testTag("reset_app"),
            )
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Reset RelaySMS?",
            body = "This phone will stop forwarding or receiving messages. Its pairing, keys and stored messages will be deleted. This cannot be undone.",
            confirmLabel = "Reset",
            destructive = true,
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                resetting = true
                // Runs on the application scope so the wipe completes even if this screen is destroyed.
                container.appScope.launch { container.resetCoordinator.resetEverything() }
            },
        )
    }
}

@Composable
private fun ThemeOption(label: String, mode: ThemeMode, current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = mode == current, role = Role.RadioButton, onClick = { onSelect(mode) })
            .testTag("theme_${mode.key}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = mode == current, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ServerUrlEditor(current: String, allowCleartextLocal: Boolean, onSave: (String) -> Unit) {
    var text by rememberSaveable(current) { mutableStateOf(current) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; error = null },
        label = { Text("Server address") },
        placeholder = { Text("https://relay.example.com") },
        singleLine = true,
        isError = error != null,
        supportingText = { Text(error ?: "The address of the relay server you host. Both phones must use the same one.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag("server_url"),
    )
    FullWidthButton(
        "Save server address",
        enabled = text.trim() != current,
        onClick = {
            when (val r = ServerUrl.parse(text, allowCleartextLocal)) {
                is ServerUrl.Result.Valid -> onSave(r.url)
                is ServerUrl.Result.Invalid -> error = r.reason
            }
        },
        modifier = Modifier.testTag("server_url_save"),
    )
}
