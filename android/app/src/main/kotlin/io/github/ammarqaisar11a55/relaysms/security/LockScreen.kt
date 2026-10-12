package io.github.ammarqaisar11a55.relaysms.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.ammarqaisar11a55.relaysms.ui.components.FullWidthButton

/** Shown instead of every other screen while the app lock is closed. Shows no message data. */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val activity = LocalContext.current as? FragmentActivity
    val ask = { if (activity != null) AppLock.prompt(activity, "Unlock RelaySMS", onSuccess = onUnlocked) }
    LaunchedEffect(Unit) { ask() }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp).testTag("lock_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("RelaySMS is locked", style = MaterialTheme.typography.headlineSmall)
        Text("Unlock with your fingerprint, face or screen lock to see your messages.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        FullWidthButton("Unlock", onClick = ask, modifier = Modifier.testTag("lock_unlock"))
    }
}
