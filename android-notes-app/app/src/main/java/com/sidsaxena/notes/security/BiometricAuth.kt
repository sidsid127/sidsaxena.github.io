package com.sidsaxena.notes.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** True if the device has a fingerprint/face/PIN set up that we can gate the app with. */
fun canUseDeviceLock(context: Context): Boolean {
    val manager = BiometricManager.from(context)
    return manager.canAuthenticate(ALLOWED_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
}

fun Context.findActivity(): FragmentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is FragmentActivity) return context
        context = context.baseContext
    }
    return null
}

/** Shows the system unlock UI (fingerprint/face/PIN, whatever the phone already uses). */
fun requestDeviceUnlock(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onFailure: () -> Unit
) {
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Notes")
        .setSubtitle("Use your fingerprint, face, or screen lock")
        .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
        .build()

    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }

            override fun onAuthenticationFailed() {
                // A single wrong attempt; let the user try again rather than failing out.
            }
        }
    )
    prompt.authenticate(promptInfo)
}
