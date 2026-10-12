package io.github.ammarqaisar11a55.relaysms.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ammarqaisar11a55.relaysms.AppContainer
import io.github.ammarqaisar11a55.relaysms.core.model.DeviceRole
import io.github.ammarqaisar11a55.relaysms.pairing.PairingFailure
import io.github.ammarqaisar11a55.relaysms.pairing.PairingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PairingViewModel(private val container: AppContainer, role: DeviceRole) : ViewModel() {
    private val manager = container.newPairingManager(role)
    val state: StateFlow<PairingState> = manager.state
    private var job: Job? = null

    private fun run(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            block()
            if (manager.state.value is PairingState.Completed) container.onPairingCompleted()
        }
    }

    fun startAsCompanion(serverUrl: String, registrationCode: String) = run { manager.startAsCompanion(serverUrl, registrationCode) }
    fun onScanned(text: String) = manager.reviewScanned(text)
    fun redeem(registrationCode: String) = run { manager.redeemAsSource(registrationCode) }
    fun confirm() = run { manager.confirm() }

    fun cancel() {
        job?.cancel()
        // Runs on the application scope so the relay is told even if the screen closes right away.
        container.appScope.launch { manager.cancel() }
    }

    fun startOver() {
        job?.cancel()
        manager.reset()
    }

    override fun onCleared() {
        val unfinished = manager.state.value.let { it !is PairingState.Completed && it !is PairingState.Idle && it !is PairingState.Failed }
        if (unfinished) container.appScope.launch { manager.cancel() }
    }
}

fun PairingFailure.message(): String = when (this) {
    PairingFailure.INVALID_CODE -> "That is not a valid RelaySMS pairing code. Show a new code on the companion phone and scan it again."
    PairingFailure.EXPIRED -> "The pairing code expired. Start again and finish within five minutes."
    PairingFailure.ALREADY_USED -> "This pairing code was already used. If that was not you, do not continue: create a new code and check both phones."
    PairingFailure.CANCELLED -> "Pairing was cancelled. No device was paired."
    PairingFailure.NETWORK -> "The relay server could not be reached. Check the internet connection and the server address."
    PairingFailure.SERVER -> "The relay server reported an error. Try again in a moment."
    PairingFailure.RATE_LIMITED -> "Too many attempts. Wait a minute and try again."
    PairingFailure.REGISTRATION_CODE -> "This relay server requires its registration code. Enter it and try again."
    PairingFailure.KEY_MISMATCH -> "The relay server returned different keys than the scanned code. Pairing was stopped for your safety."
    PairingFailure.PROOF_INVALID -> "The source phone's identity could not be verified. Pairing was stopped for your safety. Create a new code and scan it directly from this screen."
    PairingFailure.LOCAL_KEYS -> "This phone's keys could not be loaded. Reset RelaySMS in Settings and pair again."
}
