package com.puja.user.lock

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.puja.user.R

class BiometricLockActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "applock_prefs"
        const val KEY_LOCK_ENABLED = "lock_enabled"
        const val KEY_LOCK_SET_ONCE = "lock_set_once"

        /** Returns true if app lock is enabled */
        fun isLockEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCK_ENABLED, false)

        /** Returns true if the device has biometric or PIN/pattern/password configured */
        fun canAuthenticate(context: Context): Boolean {
            val result = BiometricManager.from(context)
                .canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            return result == BiometricManager.BIOMETRIC_SUCCESS
        }

        /** Enable or disable the app lock */
        fun setLockEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LOCK_ENABLED, enabled)
                .putBoolean(KEY_LOCK_SET_ONCE, true)
                .apply()
        }

        /**
         * Called once on first app launch.
         * Auto-enables lock if the device already has a screen lock set up.
         * Does nothing on subsequent launches.
         */
        fun autoDetectAndEnable(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_LOCK_SET_ONCE, false)) return
            val shouldEnable = canAuthenticate(context)
            prefs.edit()
                .putBoolean(KEY_LOCK_ENABLED, shouldEnable)
                .putBoolean(KEY_LOCK_SET_ONCE, true)
                .apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots and hide from app switcher preview
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_lock)
        showBiometricPrompt()
    }

    override fun onBackPressed() {
        // Back button sends app to background — does not dismiss the lock screen
        moveTaskToBack(false)
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                AppLockManager.unlock()
                finish() // Return to the app
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                        // User explicitly cancelled — exit the whole app
                        finishAffinity()
                    }
                    BiometricPrompt.ERROR_NO_BIOMETRICS,
                    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> {
                        // Device lock was removed — auto-disable app lock and let through
                        setLockEnabled(this@BiometricLockActivity, false)
                        AppLockManager.unlock()
                        finish()
                    }
                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                        // Too many failed attempts — still close the app
                        finishAffinity()
                    }
                    else -> {
                        // Any other error — re-show the prompt
                        showBiometricPrompt()
                    }
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Wrong fingerprint/face — the system prompt handles the feedback, do nothing here
            }
        }

        val prompt = BiometricPrompt(this, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("N𓆙G Series")
            .setSubtitle("Verify your identity to continue")
            .setDescription("Use your fingerprint, face or screen lock PIN")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(promptInfo)
    }
}
