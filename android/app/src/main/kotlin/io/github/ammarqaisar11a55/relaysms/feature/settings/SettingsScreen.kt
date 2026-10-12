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
import androidx.compose.foundation.selection.toggleable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.ammarqaisar11a55.relaysms.background.BatteryOptimization
import io.github.ammarqaisar11a55.relaysms.background.ConnectionService
import io.github.ammarqaisar11a55.relaysms.feature.home.openAppSettings
import io.github.ammarqaisar11a55.relaysms.ui.components.InfoCard
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ammarqaisar11a55.relaysms.feature.pairing.label
import io.github.ammarqaisar11a55.relaysms.ui.components.ConfirmDialog
import io.github.ammarqaisar11a55.relaysms.ui.components.StatusRow
import io.github.ammarqaisar11a55.relaysms.ui.components.Tone
import io.github.ammarqaisar11a55.relaysms.ui.components.FullWidthButton
import io.github.ammarqaisar11a55.relaysms.ui.components.RelayScaffold
import io.github.ammarqaisar11a55.relaysms.ui.components.SectionHeader
import io.github.ammarqaisar11a55.relaysms.ui.navigation.LocalAppContainer
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenPairing: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    val peer by container.identityStore.peer.collectAsStateWithLifecycle()
    val identity by container.identityStore.identity.collectAsStateWithLifecycle()
    val link by container.linkMonitor.status.collectAsStateWithLifecycle()

    RelayScaffold(title = "Settings", onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader("Connection")
            link.label().let { (label, tone) ->
                StatusRow(if (settings.role == DeviceRole.SOURCE) "Companion phone" else "Source phone", label, tone)
            }
            FullWidthButton(
                if (peer == null) "Pair a device" else "Paired device",
                onClick = { if (peer == null) onOpenPairing() else onOpenDevice() },
                outlined = true,
                modifier = Modifier.testTag("settings_device"),
            )

            if (settings.role == DeviceRole.SOURCE) {
                SectionHeader("Forwarding")
                StatusRow(
                    "Forwarding",
                    if (settings.forwardingEnabled && peer != null) "On" else "Off",
                    if (settings.forwardingEnabled && peer != null) Tone.OK else Tone.NEUTRAL,
                )
                FullWidthButton(
                    if (settings.allowlistOnly) "Sender filters (chosen senders only)" else "Sender filters (all senders)",
                    onClick = onOpenFilters,
                    outlined = true,
                )
            }

            if (settings.role == DeviceRole.COMPANION) {
                SectionHeader("Message retention")
                Text(
                    "Messages older than the chosen period are erased from this phone automatically. Erased messages cannot be recovered.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(Modifier.selectableGroup()) {
                    for (days in AppSettings.RETENTION_CHOICES) {
                        ChoiceRow(
                            label = when (days) {
                                0 -> "Keep until I delete them"
                                7 -> "7 days"
                                365 -> "1 year"
                                else -> "$days days"
                            },
                            selected = settings.retentionDays == days,
                            tag = "retention_$days",
                            onSelect = {
                                scope.launch {
                                    container.preferences.setRetentionDays(days)
                                    container.applyRetention()
                                }
                            },
                        )
                    }
                }
                FullWidthButton("Delete all messages on this phone", onClick = { confirmDeleteAll = true }, outlined = true, modifier = Modifier.testTag("delete_all_messages"))
            }

            BackgroundSettings(settings, paired = peer != null)

            if (settings.role == DeviceRole.COMPANION) {
                NotificationSettings(settings)

                SectionHeader("Copied codes")
                Text(
                    "RelaySMS copies a code only when you tap Copy. It can then remove that code from the clipboard after a while, " +
                        "if the clipboard still holds it and RelaySMS is open to check. Keyboards and other apps may see copied text " +
                        "before it is removed; this depends on your Android version and cannot be guaranteed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(Modifier.selectableGroup()) {
                    for ((seconds, label) in listOf(0 to "Do not clear", 30 to "After 30 seconds", 60 to "After 1 minute", 120 to "After 2 minutes", 300 to "After 5 minutes")) {
                        ChoiceRow(label, settings.clipboardClearSeconds == seconds, "clipboard_$seconds") {
                            scope.launch { container.preferences.setClipboardClearSeconds(seconds) }
                        }
                    }
                }
            }

            SectionHeader("Appearance")
            Column(Modifier.selectableGroup()) {
                ChoiceRow("Follow system", settings.themeMode == ThemeMode.SYSTEM, "theme_system") { scope.launch { container.preferences.setThemeMode(ThemeMode.SYSTEM) } }
                ChoiceRow("Light", settings.themeMode == ThemeMode.LIGHT, "theme_light") { scope.launch { container.preferences.setThemeMode(ThemeMode.LIGHT) } }
                ChoiceRow("Dark", settings.themeMode == ThemeMode.DARK, "theme_dark") { scope.launch { container.preferences.setThemeMode(ThemeMode.DARK) } }
            }

            SectionHeader("Relay server")
            val registered = identity
            if (registered != null) {
                Text(registered.serverUrl, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "This phone is registered with this relay. To use a different relay, reset RelaySMS below and pair again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
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
            }

            SectionHeader("Privacy")
            Text(
                "Messages are encrypted on the source phone and decrypted only on the companion phone. " +
                    "The relay server stores ciphertext until it is delivered or expires, and can see only that two devices " +
                    "are paired, when they connect, and the size and time of each message. Messages on this phone are " +
                    "stored encrypted with a key kept in Android Keystore. RelaySMS has no analytics, sends nothing to " +
                    "third parties, and excludes messages and keys from cloud backup and device transfer. After a reinstall " +
                    "or on a new phone, stored messages and keys are gone and a new pairing is required.",
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

    if (confirmDeleteAll) {
        ConfirmDialog(
            title = "Delete all messages?",
            body = "Every message stored in RelaySMS on this phone is erased. The original SMS on your source phone are not affected. This cannot be undone.",
            confirmLabel = "Delete all",
            destructive = true,
            onDismiss = { confirmDeleteAll = false },
            onConfirm = {
                confirmDeleteAll = false
                scope.launch {
                    container.inboxRepository.deleteAll()
                    snackbar.showSnackbar("All messages deleted")
                }
            },
        )
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
private fun BackgroundSettings(settings: AppSettings, paired: Boolean) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var unrestricted by remember { mutableStateOf(BatteryOptimization.isUnrestricted(context)) }
    LifecycleResumeEffect(Unit) {
        unrestricted = BatteryOptimization.isUnrestricted(context)
        onPauseOrDispose { }
    }

    SectionHeader("Background delivery")
    if (settings.role == DeviceRole.COMPANION) {
        SwitchRow(
            title = "Stay connected in the background",
            subtitle = "On: RelaySMS keeps a connection to your relay with an ongoing notification, so messages usually arrive within seconds. " +
                "Off: when the app is closed, messages arrive at Android's next background sync, which can take 15 minutes or longer.",
            checked = settings.backgroundConnection,
            enabled = paired,
            tag = "background_connection",
            onChange = { on ->
                scope.launch {
                    container.preferences.setBackgroundConnection(on)
                    if (on) ConnectionService.start(context) else ConnectionService.stop(context)
                }
            },
        )
    } else {
        Text(
            "The source phone needs no background connection: Android starts RelaySMS when an SMS arrives, and queued messages are sent as soon as a network is available.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    StatusRow("Battery use", if (unrestricted) "Unrestricted" else "Optimised by Android", if (unrestricted) Tone.OK else Tone.NEUTRAL)
    Text(
        "Android can delay or pause background work to save battery, most of all after the phone has been still with the screen off for a long time. " +
            "Setting RelaySMS to \"Unrestricted\" reduces these delays. On Samsung phones also check Settings → Battery → Background usage limits and keep RelaySMS out of \"Sleeping apps\". " +
            "RelaySMS cannot guarantee instant delivery on every device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FullWidthButton("Open battery settings", onClick = { BatteryOptimization.openSettings(context) }, outlined = true, modifier = Modifier.testTag("battery_settings"))
}

@Composable
private fun NotificationSettings(settings: AppSettings) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var canPost by remember { mutableStateOf(container.messageNotifier.canPost()) }
    var askedAndDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canPost = container.messageNotifier.canPost()
        askedAndDenied = !granted
    }
    LifecycleResumeEffect(Unit) {
        canPost = container.messageNotifier.canPost()
        onPauseOrDispose { }
    }

    SectionHeader("Notifications")
    SwitchRow(
        title = "Notify me about new messages",
        subtitle = "Messages are stored whether or not notifications are on.",
        checked = settings.notificationsEnabled,
        tag = "notifications_enabled",
        onChange = { scope.launch { container.preferences.setNotificationsEnabled(it) } },
    )
    SwitchRow(
        title = "Show sender and text",
        subtitle = "Off: notifications only say \"New message received\". On: the sender and text appear in the notification shade. " +
            "On a locked screen the content stays hidden unless your phone is set to show all notification content.",
        checked = settings.notificationPreview,
        enabled = settings.notificationsEnabled,
        tag = "notification_preview",
        onChange = { scope.launch { container.preferences.setNotificationPreview(it) } },
    )
    if (settings.notificationsEnabled && !canPost) {
        InfoCard(
            title = "Android is blocking notifications",
            body = if (askedAndDenied) "Notification permission was not granted. You can allow it in App info → Notifications."
            else "RelaySMS needs Android's notification permission to announce new messages.",
            tone = Tone.WARN,
            action = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && !askedAndDenied) {
                    FullWidthButton("Allow notifications", onClick = { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.testTag("notifications_allow"))
                } else {
                    FullWidthButton("Open App info", onClick = { openAppSettings(context) }, outlined = true)
                }
            },
        )
    }
    Text(
        "Android decides when background apps may run. RelaySMS cannot promise that a notification appears the instant a message is sent.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
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
