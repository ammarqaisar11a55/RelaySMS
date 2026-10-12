package io.github.ammarqaisar11a55.relaysms.security

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional lock in front of the app's screens, opened with the phone's own fingerprint, face,
 * PIN, pattern or password.
 *
 * This is a screen gate. It keeps someone who picks up the unlocked phone out of the inbox; it
 * does not add a second layer of encryption, because the storage key is not tied to the unlock.
 */
class AppLock {
    private val _locked = MutableStateFlow(true)

    /** Whether the gate is closed. Only meaningful while the user has the lock turned on. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun unlock() {
        _locked.value = false
    }

    /** Every time the app leaves the screen, the gate closes again. */
    fun onBackground() {
        _locked.value = true
    }

    companion object {
        /** An app lock is only offered when the phone itself has a secure screen lock. */
        fun deviceHasSecureLock(context: Context): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

        /** Shows Android's own unlock dialog. RelaySMS never sees the PIN or the biometric. */
        fun prompt(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onUnavailable: () -> Unit = {}) {
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL || errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT) onUnavailable()
                }
            }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Use your screen lock")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .setConfirmationRequired(false)
                .build()
            try {
                BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
            } catch (e: RuntimeException) {
                onUnavailable()
            }
        }
    }
}

/**
 * Decides whether the window must be protected from screenshots, screen recording and the
 * recent-apps preview: when the user's setting says so, or while any screen with pairing secrets
 * asks for it.
 */
class WindowSecurity(private val apply: (secure: Boolean) -> Unit) {
    private var settingOn = true
    private var requests = 0

    @Synchronized
    fun setSetting(on: Boolean) {
        settingOn = on
        apply(settingOn || requests > 0)
    }

    @Synchronized
    fun acquire() {
        requests++
        apply(true)
    }

    @Synchronized
    fun release() {
        requests = (requests - 1).coerceAtLeast(0)
        apply(settingOn || requests > 0)
    }
}
