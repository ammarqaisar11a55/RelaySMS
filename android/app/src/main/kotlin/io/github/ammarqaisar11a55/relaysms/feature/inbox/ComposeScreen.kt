package io.github.ammarqaisar11a55.relaysms.feature.inbox

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ammarqaisar11a55.relaysms.sms.defaultapp.DefaultSmsRole
import io.github.ammarqaisar11a55.relaysms.sms.send.RemoteSendPolicy
import io.github.ammarqaisar11a55.relaysms.sms.send.SendFailure
import io.github.ammarqaisar11a55.relaysms.sms.send.SendRoute
import io.github.ammarqaisar11a55.relaysms.sms.send.SubmitResult
import io.github.ammarqaisar11a55.relaysms.ui.components.ConfirmDialog
import io.github.ammarqaisar11a55.relaysms.ui.components.FullWidthButton
import io.github.ammarqaisar11a55.relaysms.ui.components.InfoCard
import io.github.ammarqaisar11a55.relaysms.ui.components.RelayScaffold
import io.github.ammarqaisar11a55.relaysms.ui.components.SectionHeader
import io.github.ammarqaisar11a55.relaysms.ui.components.Tone
import io.github.ammarqaisar11a55.relaysms.ui.navigation.LocalAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why this phone cannot send by itself right now, in words; null when it can. */
@Composable
private fun thisPhoneProblem(): String? {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        problem = when {
            !DefaultSmsRole.isHeld(context) && !container.smsSender.hasPermission() ->
                "This phone may send SMS itself only while RelaySMS is its default SMS app."
            else -> when (container.smsSender.unavailableReason()) {
                null -> null
                SendFailure.RADIO_OFF -> "Airplane mode is on."
                else -> "This phone has no SIM with mobile service."
            }
        }
        onPauseOrDispose { }
    }
    return problem
}

@Composable
fun ComposeScreen(initialTo: String, initialText: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val peer by container.identityStore.peer.collectAsStateWithLifecycle()
    val localProblem = thisPhoneProblem()
    var to by rememberSaveable { mutableStateOf(initialTo) }
    var text by rememberSaveable { mutableStateOf(initialText) }
    var route by rememberSaveable(peer != null) { mutableStateOf(if (peer != null) SendRoute.VIA_SOURCE else SendRoute.THIS_PHONE) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val normalized = RemoteSendPolicy.normalizeRecipient(to)
    val routeUsable = if (route == SendRoute.VIA_SOURCE) peer != null else localProblem == null
    val canSend = normalized != null && text.isNotBlank() && text.length <= RemoteSendPolicy.MAX_TEXT_CHARS && routeUsable

    RelayScaffold(title = "New message", onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = to,
                onValueChange = { to = it.take(40); error = null },
                label = { Text("To") },
                placeholder = { Text("Phone number") },
                singleLine = true,
                isError = to.isNotBlank() && normalized == null,
                supportingText = { if (to.isNotBlank() && normalized == null) Text("Enter a phone number or short code.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().testTag("compose_to"),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(RemoteSendPolicy.MAX_TEXT_CHARS); error = null },
                label = { Text("Message") },
                minLines = 3,
                maxLines = 8,
                supportingText = { Text("${text.length} / ${RemoteSendPolicy.MAX_TEXT_CHARS}") },
                modifier = Modifier.fillMaxWidth().testTag("compose_text"),
            )

            SectionHeader("Send with")
            Column(Modifier.selectableGroup()) {
                RouteOption(
                    title = "Paired source phone",
                    body = if (peer != null) "Sent from your source phone's SIM and number. It must be online and must have \"Let the companion send SMS\" turned on."
                    else "Not available: this phone is not paired.",
                    selected = route == SendRoute.VIA_SOURCE,
                    enabled = peer != null,
                    tag = "route_source",
                    onSelect = { route = SendRoute.VIA_SOURCE },
                )
                RouteOption(
                    title = "This phone's SIM",
                    body = localProblem?.let { "Not available: $it" } ?: "Sent from this phone's own number.",
                    selected = route == SendRoute.THIS_PHONE,
                    enabled = localProblem == null,
                    tag = "route_local",
                    onSelect = { route = SendRoute.THIS_PHONE },
                )
            }
            if (!routeUsable) {
                InfoCard(
                    title = "This message cannot be sent yet",
                    body = if (route == SendRoute.VIA_SOURCE) "Pair this phone with your source phone first."
                    else (localProblem ?: "") + " RelaySMS still works as an inbox over the internet without a SIM.",
                    tone = Tone.WARN,
                    modifier = Modifier.testTag("compose_unavailable"),
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("compose_error")) }
            FullWidthButton("Review and send", enabled = canSend, onClick = { confirm = true }, modifier = Modifier.testTag("compose_review"))
            Text(
                "Nothing is sent until you confirm. Standard SMS charges of the sending SIM apply.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirm && normalized != null) {
        ConfirmDialog(
            title = "Send this SMS?",
            body = (if (route == SendRoute.VIA_SOURCE) "It will be sent to $normalized from your paired source phone's number."
            else "It will be sent to $normalized from this phone's number.") + "\n\n\"" + text.take(200) + (if (text.length > 200) "…" else "") + "\"",
            confirmLabel = "Send",
            onDismiss = { confirm = false },
            onConfirm = {
                confirm = false
                scope.launch {
                    when (val r = container.outgoingMessages.send(route, to, text)) {
                        is SubmitResult.Queued -> withContext(Dispatchers.Main.immediate) { onBack() }
                        is SubmitResult.Refused -> error = RemoteSendPolicy.describe(r.reason)
                    }
                }
            },
        )
    }
}

@Composable
private fun RouteOption(title: String, body: String, selected: Boolean, enabled: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Short status of an outgoing message. "Sent" means handed to the network, not delivered. */
fun outgoingStatusLabel(status: String, origin: String): Pair<String, Tone> = when {
    status == io.github.ammarqaisar11a55.relaysms.core.database.MessageStatus.PENDING ->
        (if (origin == io.github.ammarqaisar11a55.relaysms.core.database.MessageOrigin.RELAY) "Waiting for source phone · not sent yet" else "Sending…") to Tone.WARN
    status == io.github.ammarqaisar11a55.relaysms.core.database.MessageStatus.SENT -> "Sent to the network" to Tone.NEUTRAL
    status == io.github.ammarqaisar11a55.relaysms.core.database.MessageStatus.DELIVERED -> "Delivered" to Tone.OK
    io.github.ammarqaisar11a55.relaysms.core.database.MessageStatus.isFailed(status) -> "Not sent" to Tone.ERROR
    else -> status to Tone.NEUTRAL
}
