package io.github.ammarqaisar11a55.relaysms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ammarqaisar11a55.relaysms.ui.theme.LocalStatusColors

enum class Tone { NEUTRAL, OK, WARN, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = actions,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** Background and content colours for a status tone. */
@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val s = LocalStatusColors.current
    val c = MaterialTheme.colorScheme
    return when (tone) {
        Tone.NEUTRAL -> c.surfaceVariant to c.onSurfaceVariant
        Tone.OK -> s.ok to s.onOk
        Tone.WARN -> s.warn to s.onWarn
        Tone.ERROR -> c.errorContainer to c.onErrorContainer
    }
}

/** A titled card used for explanations and status summaries. */
@Composable
fun InfoCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: Tone = Tone.NEUTRAL,
    action: (@Composable () -> Unit)? = null,
) {
    val (bg, fg) = toneColors(tone)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (action != null) {
                    Spacer(Modifier.heightIn(min = 4.dp))
                    action()
                }
            }
        }
    }
}

/** One labelled status line, e.g. "Pairing — Not paired". */
@Composable
fun StatusRow(label: String, value: String, tone: Tone, modifier: Modifier = Modifier) {
    val (bg, fg) = toneColors(tone)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
            Text(value, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = "Loading") {
    Box(modifier.fillMaxSize().semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionTestTag: String = "empty_state_action",
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.heightIn(min = 16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.heightIn(min = 8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.heightIn(min = 24.dp))
            Button(onClick = onAction, modifier = Modifier.testTag(actionTestTag)) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    EmptyState(
        icon = Icons.Filled.ErrorOutline,
        title = "Something went wrong",
        body = message,
        modifier = modifier,
        actionLabel = if (onRetry != null) "Try again" else null,
        onAction = onRetry,
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("dialog_confirm"),
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Composable
fun FullWidthButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, outlined: Boolean = false) {
    if (outlined) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
    }
}
