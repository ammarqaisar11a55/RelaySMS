package io.github.ammarqaisar11a55.relaysms.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.core.network.ServerUrl
import io.github.ammarqaisar11a55.relaysms.core.prefs.AppSettings
import io.github.ammarqaisar11a55.relaysms.pairing.PairingFailure
import io.github.ammarqaisar11a55.relaysms.pairing.PairingState
import io.github.ammarqaisar11a55.relaysms.ui.components.FullWidthButton
import io.github.ammarqaisar11a55.relaysms.ui.components.InfoCard
import io.github.ammarqaisar11a55.relaysms.ui.components.RelayScaffold
import io.github.ammarqaisar11a55.relaysms.ui.components.Tone
import io.github.ammarqaisar11a55.relaysms.ui.navigation.LocalAppContainer
import kotlinx.coroutines.delay

@Composable
fun PairingScreen(settings: AppSettings, onBack: () -> Unit, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val role = settings.role ?: return
    val vm: PairingViewModel = viewModel { PairingViewModel(container, role) }
    val state by vm.state.collectAsStateWithLifecycle()
    var registrationCode by rememberSaveable { mutableStateOf("") }

    SecureWindow()
    RelayScaffold(
        title = if (role == DeviceRole.COMPANION) "Pair with source phone" else "Pair with companion phone",
        onBack = {
            vm.cancel()
            onBack()
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                PairingState.Idle ->
                    if (role == DeviceRole.COMPANION) {
                        CompanionStart(
                            defaultUrl = settings.serverUrl.ifEmpty { container.environment.defaultServerUrl },
                            allowCleartextLocal = container.environment.allowCleartextLocal,
                            registrationCode = registrationCode,
                            onRegistrationCode = { registrationCode = it },
                            onStart = { url -> vm.startAsCompanion(url, registrationCode) },
                        )
                    } else {
                        SourceScan(onScanned = vm::onScanned)
                    }
                is PairingState.Working -> Progress(s.step)
                is PairingState.ShowingCode -> ShowCode(s, onCancel = vm::cancel)
                is PairingState.ReviewPeer -> ReviewPeer(s, registrationCode, { registrationCode = it }, onPair = { vm.redeem(registrationCode) }, onCancel = vm::startOver)
                is PairingState.Verify -> VerifyCode(s, role, onConfirm = vm::confirm, onMismatch = vm::cancel)
                is PairingState.Completed -> Done(s, role, onDone)
                is PairingState.Failed -> Failure(s.reason, registrationCode, { registrationCode = it }, onRetry = vm::startOver)
            }
        }
    }
}

@Composable
private fun Progress(label: String) {
    CircularProgressIndicator(Modifier.padding(top = 48.dp))
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun RegistrationCodeField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(256)) },
        label = { Text("Relay registration code (optional)") },
        supportingText = { Text("Only needed if your relay server was configured to require one.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag("registration_code"),
    )
}

@Composable
private fun CompanionStart(
    defaultUrl: String,
    allowCleartextLocal: Boolean,
    registrationCode: String,
    onRegistrationCode: (String) -> Unit,
    onStart: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(defaultUrl) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Text(
        "This phone will show a code. Scan it with RelaySMS on your source phone. Keep both phones in front of you; the code is valid for five minutes and works once.",
        style = MaterialTheme.typography.bodyLarge,
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it; error = null },
        label = { Text("Relay server address") },
        placeholder = { Text("https://relay.example.com") },
        singleLine = true,
        isError = error != null,
        supportingText = { Text(error ?: "The relay you host. The source phone learns it from the code.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag("pair_server_url"),
    )
    RegistrationCodeField(registrationCode, onRegistrationCode)
    FullWidthButton(
        "Show pairing code",
        onClick = {
            when (val r = ServerUrl.parse(url, allowCleartextLocal)) {
                is ServerUrl.Result.Valid -> onStart(r.url)
                is ServerUrl.Result.Invalid -> error = r.reason
            }
        },
        modifier = Modifier.testTag("pair_start"),
    )
}

@Composable
private fun Countdown(expiresAtMillis: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = ((expiresAtMillis - now) / 1000).coerceAtLeast(0)
    Text(
        "Expires in %d:%02d".format(left / 60, left % 60),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ShowCode(state: PairingState.ShowingCode, onCancel: () -> Unit) {
    var showText by rememberSaveable { mutableStateOf(false) }
    Text("Scan this code with your source phone", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    QrCode(state.qrText, Modifier.widthIn(max = 320.dp).fillMaxWidth().testTag("pair_qr"))
    Countdown(state.expiresAtMillis)
    Text(
        "On the source phone open RelaySMS, choose \"Pair with companion phone\" and point the camera here. Do not share a picture of this code.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    TextButton(onClick = { showText = !showText }, modifier = Modifier.testTag("pair_show_text")) {
        Text(if (showText) "Hide pairing text" else "Camera not working? Show pairing text")
    }
    if (showText) {
        Text("Type or paste this text on the source phone. Treat it like the code above.", style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Text(state.qrText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pair_text"))
        }
    }
    FullWidthButton("Cancel", onClick = onCancel, outlined = true)
}

@Composable
private fun SourceScan(onScanned: (String) -> Unit) {
    var manual by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    Text(
        "On your companion phone open RelaySMS and choose \"Pair with source phone\". Then scan the code it shows.",
        style = MaterialTheme.typography.bodyLarge,
    )
    if (!manual) QrScanner(onResult = onScanned, modifier = Modifier.widthIn(max = 360.dp))
    TextButton(onClick = { manual = !manual }, modifier = Modifier.testTag("pair_manual_toggle")) {
        Text(if (manual) "Use the camera instead" else "Enter pairing text instead")
    }
    if (manual) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(1500) },
            label = { Text("Pairing text") },
            placeholder = { Text("RSMS1:…") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("pair_manual_text"),
        )
        FullWidthButton("Continue", onClick = { onScanned(text) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("pair_manual_continue"))
    }
}

@Composable
private fun ReviewPeer(
    state: PairingState.ReviewPeer,
    registrationCode: String,
    onRegistrationCode: (String) -> Unit,
    onPair: () -> Unit,
    onCancel: () -> Unit,
) {
    Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
    Text("Forward messages to this device?", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    InfoCard(
        title = "Destination",
        body = "Companion device ${state.peerFingerprint}\nRelay server ${state.serverUrl}",
    )
    Text(
        "After pairing, the messages you choose will be encrypted for that device and sent through that relay server. Continue only if you just scanned this code from your own companion phone.",
        style = MaterialTheme.typography.bodyMedium,
    )
    RegistrationCodeField(registrationCode, onRegistrationCode)
    FullWidthButton("Pair with this device", onClick = onPair, modifier = Modifier.testTag("pair_redeem"))
    FullWidthButton("Cancel", onClick = onCancel, outlined = true)
}

@Composable
private fun VerifyCode(state: PairingState.Verify, role: DeviceRole, onConfirm: () -> Unit, onMismatch: () -> Unit) {
    val other = if (role == DeviceRole.COMPANION) "source" else "companion"
    Text("Compare this code on both phones", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    Text(
        state.safetyCode,
        fontSize = 44.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.testTag("pair_safety_code").semantics { contentDescription = "Verification code " + state.safetyCode.filter { it.isDigit() }.toList().joinToString(" ") },
    )
    Text("Other device: ${state.peerFingerprint}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Countdown(state.expiresAtMillis)
    if (!state.confirmedHere) {
        Text(
            "Confirm only if your $other phone shows exactly the same six digits. If it shows a different code, an error, or nothing, choose \"They don't match\".",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        FullWidthButton("The codes match", onClick = onConfirm, modifier = Modifier.testTag("pair_confirm"))
        FullWidthButton("They don't match", onClick = onMismatch, outlined = true, modifier = Modifier.testTag("pair_mismatch"))
    } else {
        CircularProgressIndicator()
        Text(
            "Confirmed on this phone. Waiting for you to confirm on the $other phone.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        FullWidthButton("Cancel pairing", onClick = onMismatch, outlined = true)
    }
}

@Composable
private fun Done(state: PairingState.Completed, role: DeviceRole, onDone: () -> Unit) {
    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(64.dp).padding(top = 8.dp), tint = MaterialTheme.colorScheme.primary)
    Text("Paired", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("pair_done").semantics { liveRegion = LiveRegionMode.Polite })
    Text("Paired with device ${state.peerFingerprint}.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    Text(
        if (role == DeviceRole.SOURCE) "Forwarding is still off. Choose which senders to forward, then turn forwarding on."
        else "Messages forwarded by your source phone will appear in the inbox on this phone.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    FullWidthButton("Done", onClick = onDone, modifier = Modifier.testTag("pair_finish"))
}

@Composable
private fun Failure(reason: PairingFailure, registrationCode: String, onRegistrationCode: (String) -> Unit, onRetry: () -> Unit) {
    Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
    Text(
        when (reason) {
            PairingFailure.EXPIRED -> "Pairing code expired"
            PairingFailure.CANCELLED -> "Pairing cancelled"
            else -> "Pairing did not complete"
        },
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.testTag("pair_failed").semantics { liveRegion = LiveRegionMode.Assertive },
    )
    InfoCard(title = "What happened", body = reason.message(), tone = if (reason == PairingFailure.CANCELLED || reason == PairingFailure.EXPIRED) Tone.WARN else Tone.ERROR)
    if (reason == PairingFailure.REGISTRATION_CODE) RegistrationCodeField(registrationCode, onRegistrationCode)
    FullWidthButton("Start again", onClick = onRetry, modifier = Modifier.testTag("pair_retry"))
}
