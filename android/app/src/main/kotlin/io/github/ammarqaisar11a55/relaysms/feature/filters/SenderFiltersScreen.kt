package io.github.ammarqaisar11a55.relaysms.feature.filters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppSettings
import io.github.ammarqaisar11a55.relaysms.ui.components.RelayScaffold
import io.github.ammarqaisar11a55.relaysms.ui.components.SectionHeader
import io.github.ammarqaisar11a55.relaysms.ui.navigation.LocalAppContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SenderFiltersScreen(settings: AppSettings, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val stores = container.sourceStores
    val scope = rememberCoroutineScope()
    val filters by stores.filters.collectAsStateWithLifecycle(emptyList())
    val suggestions by stores.suggestions.collectAsStateWithLifecycle(emptyList())
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun add(raw: String) {
        scope.launch {
            if (stores.addFilter(raw, System.currentTimeMillis())) {
                input = ""
                error = null
            } else {
                error = "Enter a phone number, short code or sender name (up to 64 characters)."
            }
        }
    }

    RelayScaffold(title = "Sender filters", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader("Which messages are forwarded")
            Column(Modifier.selectableGroup()) {
                ModeOption(
                    title = "Only senders I choose",
                    body = "Recommended. Messages from anyone else stay on this phone only.",
                    selected = settings.allowlistOnly,
                    tag = "mode_allowlist",
                    onSelect = { scope.launch { container.preferences.setAllowlistOnly(true) } },
                )
                ModeOption(
                    title = "All senders",
                    body = "Every incoming SMS is forwarded, including personal messages.",
                    selected = !settings.allowlistOnly,
                    tag = "mode_all",
                    onSelect = { scope.launch { container.preferences.setAllowlistOnly(false) } },
                )
            }
            Text("Changes apply to the next message that arrives.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionHeader("Chosen senders")
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(64); error = null },
                    label = { Text("Add sender") },
                    placeholder = { Text("e.g. 8558 or HBL") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "A number, short code, or the sender name exactly as it appears in the SMS.") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add(input) }),
                    modifier = Modifier.weight(1f).testTag("filter_input"),
                )
                IconButton(onClick = { add(input) }, enabled = input.isNotBlank(), modifier = Modifier.padding(top = 8.dp).testTag("filter_add")) {
                    Icon(Icons.Filled.Add, contentDescription = "Add sender")
                }
            }
            if (filters.isEmpty()) {
                Text(
                    if (settings.allowlistOnly) "No senders chosen. Nothing will be forwarded until you add one." else "No senders chosen. All senders are forwarded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (f in filters) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.display, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { stores.removeFilter(f.pattern) } }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${f.display}")
                    }
                }
            }

            if (suggestions.isNotEmpty()) {
                SectionHeader("Recently seen on this phone")
                Text("Senders of SMS that arrived since RelaySMS was set up. Tap to add.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in suggestions.take(20)) {
                        AssistChip(onClick = { add(s) }, label = { Text(s) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeOption(title: String, body: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
