package io.github.ammarqaisar11a55.relaysms.feature.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ammarqaisar11a55.relaysms.core.database.MessageOrigin
import io.github.ammarqaisar11a55.relaysms.inbox.InboxMessage
import io.github.ammarqaisar11a55.relaysms.otp.OtpDetector
import io.github.ammarqaisar11a55.relaysms.otp.OtpResult
import io.github.ammarqaisar11a55.relaysms.ui.components.ConfirmDialog
import io.github.ammarqaisar11a55.relaysms.ui.components.EmptyState
import io.github.ammarqaisar11a55.relaysms.ui.components.FullWidthButton
import io.github.ammarqaisar11a55.relaysms.ui.components.LoadingState
import io.github.ammarqaisar11a55.relaysms.ui.components.RelayScaffold
import io.github.ammarqaisar11a55.relaysms.ui.components.SectionHeader
import io.github.ammarqaisar11a55.relaysms.ui.components.Tone
import io.github.ammarqaisar11a55.relaysms.ui.components.toneColors
import io.github.ammarqaisar11a55.relaysms.ui.navigation.LocalAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MessageDetailScreen(messageId: String, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // null = still loading, Missing = deleted or expired.
    val loaded by remember(messageId) { container.inboxRepository.observe(messageId) }
        .collectAsStateWithLifecycle(initialValue = LOADING)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val message = loaded as? InboxMessage

    LaunchedEffect(message?.id, message?.isRead) {
        if (message != null && !message.isRead) container.inboxRepository.markRead(message.id)
    }

    fun copy(text: String, what: String) {
        SecureClipboard.copy(context, text)
        container.clipboardClearer.schedule(text)
        scope.launch { snackbar.showSnackbar("$what copied") }
    }

    RelayScaffold(
        title = message?.address ?: "Message",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            if (message != null) {
                IconButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("message_delete")) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete message")
                }
            }
        },
    ) { padding ->
        when {
            loaded === LOADING -> LoadingState(Modifier.padding(padding))
            message == null -> EmptyState(
                icon = Icons.Filled.SearchOff,
                title = "Message not available",
                body = "It was deleted or has passed the retention period you set.",
                modifier = Modifier.padding(padding),
                actionLabel = "Back to messages",
                onAction = onBack,
            )
            else -> {
                val otp = remember(message.body) { OtpDetector.detect(message.body) }
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (otp.best != null) CodeCard(otp) { copy(it, "Code") }
                    SelectionContainer {
                        Text(highlight(message.body, otp), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("message_body"))
                    }
                    FullWidthButton("Copy whole message", onClick = { copy(message.body, "Message") }, outlined = true, modifier = Modifier.testTag("message_copy_all"))

                    SectionHeader("Details")
                    DetailLine("From", message.address)
                    DetailLine("Received by source phone", Times.full(message.receivedAtMillis))
                    DetailLine("Stored on this phone", Times.full(message.storedAtMillis))
                    DetailLine("Arrived via", if (message.origin == MessageOrigin.RELAY) "Relay, end-to-end encrypted" else "This phone's SIM")
                    if (message.partCount > 1) DetailLine("SMS parts", message.partCount.toString())
                }
            }
        }
    }

    if (confirmDelete && message != null) {
        ConfirmDialog(
            title = "Delete this message?",
            body = "It is erased from this phone. The original SMS on your source phone is not affected.",
            confirmLabel = "Delete",
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    container.inboxRepository.delete(message.id)
                    // Navigation must run on the main thread whatever dispatcher resumed us.
                    withContext(Dispatchers.Main.immediate) { onBack() }
                }
            },
        )
    }
}

private val LOADING = Any()

@Composable
private fun CodeCard(otp: OtpResult, onCopy: (String) -> Unit) {
    val (bg, fg) = toneColors(Tone.OK)
    Card(colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (otp.ambiguous) "This message has more than one possible code" else "Likely verification code",
                style = MaterialTheme.typography.labelLarge,
            )
            val shown = if (otp.ambiguous) otp.candidates else listOfNotNull(otp.best)
            for ((index, c) in shown.withIndex()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(c.code, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("otp_code_$index"))
                    Button(onClick = { onCopy(c.code) }, modifier = Modifier.testTag("otp_copy_$index")) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Text("  Copy code")
                    }
                }
            }
            Text(
                "Found on this phone by pattern matching. Check it against the message before using it. RelaySMS never enters codes for you.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun highlight(body: String, otp: OtpResult) = buildAnnotatedString {
    val (bg, fg) = toneColors(Tone.OK)
    val ranges = otp.candidates.filter { it.end <= body.length }.sortedBy { it.start }
    var cursor = 0
    for (c in ranges) {
        if (c.start < cursor) continue
        append(body.substring(cursor, c.start))
        withStyle(SpanStyle(background = bg, color = fg, fontWeight = FontWeight.SemiBold)) { append(body.substring(c.start, c.end)) }
        cursor = c.end
    }
    append(body.substring(cursor))
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
