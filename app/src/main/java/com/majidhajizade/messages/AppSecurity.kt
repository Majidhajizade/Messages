package com.majidhajizade.messages

import android.widget.TextView
import android.widget.LinearLayout
import android.view.WindowManager
import android.view.View
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import android.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import android.os.Build
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object AppSecurity {
    private const val PREFS = "messages_security"
    private const val PIN_SALT = "pin_salt"
    private const val PIN_HASH = "pin_hash"
    private const val PIN_ENABLED = "pin_enabled"
    private const val AUTO_LOCK_PENDING = "auto_lock_pending"
    private const val AUTO_LOCK_ENABLED = "auto_lock_enabled"
    private const val CHAT_LOCK_PREFIX = "chat_locked_"
    private const val ITERATIONS = 160_000
    private const val FAILED_PIN_ATTEMPTS = "failed_pin_attempts"
    private const val PIN_RETRY_UNTIL = "pin_retry_until"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) =
        prefs(context).getBoolean(PIN_ENABLED, false)

    fun isAutoLockEnabled(context: Context) =
        prefs(context).getBoolean(AUTO_LOCK_ENABLED, true)

    fun isAutoLockPending(context: Context) =
        isEnabled(context) && isAutoLockEnabled(context) &&
            prefs(context).getBoolean(AUTO_LOCK_PENDING, false)

    fun markAutoLockPending(context: Context) {
        if (isEnabled(context) && isAutoLockEnabled(context)) {
            prefs(context).edit().putBoolean(AUTO_LOCK_PENDING, true).apply()
        }
    }

    fun clearAutoLockPending(context: Context) {
        prefs(context).edit().putBoolean(AUTO_LOCK_PENDING, false).apply()
    }

    fun savePin(context: Context, pin: String): Boolean {
        if (pin.length < 4 || pin.any { !it.isDigit() }) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = deriveHash(pin, salt)
        prefs(context).edit()
            .putString(PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putBoolean(PIN_ENABLED, true)
            .putBoolean(AUTO_LOCK_PENDING, false)
            .remove(FAILED_PIN_ATTEMPTS)
            .remove(PIN_RETRY_UNTIL)
            .apply()
        return true
    }

    private fun remainingPinRetryMillis(context: Context): Long {
        return (prefs(context).getLong(PIN_RETRY_UNTIL, 0L) -
            System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun resetPinFailures(context: Context) {
        prefs(context).edit()
            .remove(FAILED_PIN_ATTEMPTS)
            .remove(PIN_RETRY_UNTIL)
            .apply()
    }

    private fun recordFailedPinAttempt(context: Context) {
        val p = prefs(context)
        val attempts = p.getInt(FAILED_PIN_ATTEMPTS, 0) + 1
        val editor = p.edit().putInt(FAILED_PIN_ATTEMPTS, attempts)

        if (attempts >= 3) {
            val delay = ((attempts - 2) * 5_000L).coerceAtMost(30_000L)
            editor.putLong(
                PIN_RETRY_UNTIL,
                System.currentTimeMillis() + delay
            )
        }
        editor.apply()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        if (remainingPinRetryMillis(context) > 0L) return false

        val p = prefs(context)
        val saltValue = p.getString(PIN_SALT, null) ?: return false
        val hashValue = p.getString(PIN_HASH, null) ?: return false

        val valid = runCatching {
            MessageDigest.isEqual(
                deriveHash(pin, Base64.decode(saltValue, Base64.NO_WRAP)),
                Base64.decode(hashValue, Base64.NO_WRAP)
            )
        }.getOrDefault(false)

        if (valid) {
            resetPinFailures(context)
        } else {
            recordFailedPinAttempt(context)
        }
        return valid
    }

    fun disable(context: Context) {
        prefs(context).edit()
            .remove(PIN_SALT)
            .remove(PIN_HASH)
            .remove(FAILED_PIN_ATTEMPTS)
            .remove(PIN_RETRY_UNTIL)
            .putBoolean(PIN_ENABLED, false)
            .putBoolean(AUTO_LOCK_PENDING, false)
            .apply()
    }

    fun isChatLocked(context: Context, chatId: String) =
        prefs(context).getBoolean(chatPreferenceKey(chatId), false)

    fun setChatLocked(context: Context, chatId: String, locked: Boolean) {
        prefs(context).edit().putBoolean(chatPreferenceKey(chatId), locked).apply()
    }

    private fun chatPreferenceKey(chatId: String): String {
        val normalized = chatId.trim().uppercase()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return CHAT_LOCK_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    private fun deriveHash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun showUnlock(activity: FragmentActivity, onSuccess: () -> Unit) {
        if (!isEnabled(activity)) {
            onSuccess()
            return
        }

        val dialog = Dialog(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val backgroundColor = Color.rgb(20, 29, 38)
        val keyColor = Color.rgb(39, 53, 66)
        val accentColor = Color.rgb(51, 144, 236)
        val secondaryColor = Color.rgb(174, 190, 203)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(backgroundColor)
        }

        val title = TextView(activity).apply {
            text = "Messages"
            textSize = 27f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
            )
        )

        val subtitle = TextView(activity).apply {
            text = "Enter your passcode"
            textSize = 15f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER
        }
        root.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
            )
        )

        val pinDisplay = TextView(activity).apply {
            text = ""
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            letterSpacing = 0.18f
        }
        val pinDisplayParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
        )
        pinDisplayParams.topMargin = dp(12)
        pinDisplayParams.bottomMargin = dp(14)
        root.addView(pinDisplay, pinDisplayParams)

        val pin = StringBuilder()
        var unlockButton: TextView? = null

        fun refreshPin() {
            pinDisplay.text = "• ".repeat(pin.length).trim()
            val canUnlock = pin.length >= 4
            unlockButton?.isEnabled = canUnlock
            unlockButton?.alpha = if (canUnlock) 1f else 0.55f
        }

        fun makeKey(label: String, action: () -> Unit): TextView {
            return TextView(activity).apply {
                text = label
                textSize = if (label.length > 1) 15f else 25f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(keyColor)
                    setStroke(dp(1), Color.rgb(57, 73, 87))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
        }

        val keypad = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        fun addKeyRow(labels: List<String>) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            labels.forEach { label ->
                val key = if (label.isEmpty()) {
                    View(activity)
                } else {
                    makeKey(label) {
                        when (label) {
                            "⌫" -> {
                                if (pin.isNotEmpty()) {
                                    pin.deleteCharAt(pin.length - 1)
                                    refreshPin()
                                }
                            }
                            else -> {
                                if (label.length == 1 && label[0] in '0'..'9') {
                                    if (pin.length < 32) {
                                        pin.append(label)
                                        refreshPin()
                                    }
                                }
                            }
                        }
                    }
                }

                val params = LinearLayout.LayoutParams(0, dp(62), 1f)
                params.setMargins(dp(6), dp(5), dp(6), dp(5))
                row.addView(key, params)
            }

            keypad.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(72)
                )
            )
        }

        addKeyRow(listOf("1", "2", "3"))
        addKeyRow(listOf("4", "5", "6"))
        addKeyRow(listOf("7", "8", "9"))
        addKeyRow(listOf("", "0", "⌫"))

        root.addView(
            keypad,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val actionParams = LinearLayout.LayoutParams(0, dp(50), 1f)
        actionParams.setMargins(dp(5), dp(14), dp(5), 0)

        val manager = BiometricManager.from(activity)
        val biometricAvailable = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS

        val biometricButton = TextView(activity).apply {
            text = "Fingerprint"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isEnabled = biometricAvailable
            alpha = if (biometricAvailable) 1f else 0.4f
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(keyColor)
            }
        }
        actions.addView(biometricButton, actionParams)

        unlockButton = TextView(activity).apply {
            text = "Unlock"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isEnabled = false
            alpha = 0.55f
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(accentColor)
            }
            setOnClickListener {
                val remaining = remainingPinRetryMillis(activity)
                if (remaining > 0L) {
                    val seconds = (remaining + 999L) / 1000L
                    subtitle.text = "Try again in $seconds seconds."
                    subtitle.setTextColor(Color.rgb(255, 117, 117))
                    pin.clear()
                    refreshPin()
                } else if (verifyPin(activity, pin.toString())) {
                    clearAutoLockPending(activity)
                    dialog.dismiss()
                    onSuccess()
                } else {
                    val wait = remainingPinRetryMillis(activity)
                    subtitle.text = if (wait > 0L) {
                        "Too many attempts. Try again in ${(wait + 999L) / 1000L} seconds."
                    } else {
                        "Incorrect passcode. Try again."
                    }
                    subtitle.setTextColor(Color.rgb(255, 117, 117))
                    pin.clear()
                    refreshPin()
                }
            }
        }
        actions.addView(unlockButton, actionParams)

        root.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
            )
        )

        biometricButton.setOnClickListener {
            if (!biometricAvailable) return@setOnClickListener

            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        resetPinFailures(activity)
                        clearAutoLockPending(activity)
                        dialog.dismiss()
                        onSuccess()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence
                    ) {
                        Toast.makeText(
                            activity, errString, Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Messages")
                .setSubtitle("Authenticate to continue")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
                )
                .setNegativeButtonText("Use passcode")
                .build()

            prompt.authenticate(info)
        }

        dialog.setContentView(root)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(backgroundColor))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.12f }
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                statusBarColor = backgroundColor
                navigationBarColor = backgroundColor
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun showPinSetup(activity: FragmentActivity) {
        val first = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "At least 4 digits"
        }

        AlertDialog.Builder(activity)
            .setTitle("Set numeric passcode")
            .setView(first)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val pin = first.text.toString()
                if (pin.length < 4 || pin.any { !it.isDigit() }) {
                    Toast.makeText(
                        activity, "Use at least 4 digits", Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }

                val confirm = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    hint = "Confirm passcode"
                }
                AlertDialog.Builder(activity)
                    .setTitle("Confirm passcode")
                    .setView(confirm)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save") { _, _ ->
                        if (confirm.text.toString() != pin) {
                            Toast.makeText(
                                activity, "Passcodes do not match",
                                Toast.LENGTH_LONG
                            ).show()
                        } else if (savePin(activity, pin)) {
                            Toast.makeText(
                                activity, "Passcode enabled",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .show()
            }
            .show()
    }

    fun showSettings(activity: FragmentActivity) {
        val enabled = isEnabled(activity)
        val auto = isAutoLockEnabled(activity)
        val items = if (enabled) {
            arrayOf(
                "Change passcode",
                "Turn off passcode",
                "Auto-lock in background: " + if (auto) "ON" else "OFF"
            )
        } else {
            arrayOf(
                "Set passcode",
                "Auto-lock in background: " + if (auto) "ON" else "OFF"
            )
        }

        AlertDialog.Builder(activity)
            .setTitle("Security")
            .setItems(items) { _, which ->
                if (!enabled && which == 0) {
                    showPinSetup(activity)
                } else if (enabled && which == 0) {
                    showUnlock(activity) { showPinSetup(activity) }
                } else if (enabled && which == 1) {
                    showUnlock(activity) {
                        disable(activity)
                        Toast.makeText(
                            activity, "Passcode disabled",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    val newValue = !isAutoLockEnabled(activity)
                    prefs(activity).edit()
                        .putBoolean(AUTO_LOCK_ENABLED, newValue).apply()
                    Toast.makeText(
                        activity,
                        if (newValue) "Auto-lock enabled" else "Auto-lock disabled",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
