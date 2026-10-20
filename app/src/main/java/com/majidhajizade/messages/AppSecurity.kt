package com.majidhajizade.messages

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.widget.TextView
import android.widget.LinearLayout
import android.view.WindowManager
import android.view.View
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
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
    private const val FINGERPRINT_ENABLED = "fingerprint_enabled"
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

    fun isFingerprintEnabled(context: Context) =
        prefs(context).getBoolean(FINGERPRINT_ENABLED, true)

    fun setFingerprintEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(FINGERPRINT_ENABLED, enabled).apply()
    }

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
            alpha = 0f
            translationY = dp(18).toFloat()
        }

        var unlockInProgress = false
        val lockAnimation = LottieAnimationView(activity).apply {
            setAnimation(R.raw.passcode_lock)
            repeatCount = LottieDrawable.INFINITE
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Lock"
        }
        root.addView(
            lockAnimation,
            LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                bottomMargin = dp(12)
            }
        )

        val title = TextView(activity).apply {
            text = "Messages"
            textSize = 27f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(activity).apply {
            text = "Enter your passcode"
            textSize = 15f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER
        }

        val titleContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(
                title,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
                )
            )
            addView(
                subtitle,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
                )
            )
        }

        root.addView(
            titleContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val pinDots = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val pinDotsParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
        )
        pinDotsParams.topMargin = dp(12)
        pinDotsParams.bottomMargin = dp(14)
        root.addView(pinDots, pinDotsParams)

        val pin = StringBuilder()
        var unlockButton: TextView? = null

        fun refreshPin() {
            while (pinDots.childCount > pin.length) {
                pinDots.removeViewAt(pinDots.childCount - 1)
            }

            while (pinDots.childCount < pin.length) {
                val dot = TextView(activity).apply {
                    text = "•"
                    textSize = 30f
                    typeface = android.graphics.Typeface.DEFAULT
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    alpha = 0f
                    scaleX = 0.5f
                    scaleY = 0.5f
                }
                pinDots.addView(
                    dot,
                    LinearLayout.LayoutParams(dp(24), dp(48))
                )
                dot.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            val canUnlock = pin.length >= 4
            unlockButton?.isEnabled = canUnlock
            unlockButton?.alpha = if (canUnlock) 1f else 0.55f
        }

        var shakeDirection = -1f
        var titleShakeAnimation:
            androidx.dynamicanimation.animation.SpringAnimation? = null
        var titleShakeBaseX: Float? = null

        fun shakePasscodeTitle() {
            titleShakeAnimation?.cancel()

            val view = titleContainer
            val baseX = titleShakeBaseX ?: view.translationX
            titleShakeBaseX = baseX
            view.translationX = baseX

            val shift = dp(12).toFloat() * shakeDirection
            shakeDirection = -shakeDirection

            val spring = androidx.dynamicanimation.animation.SpringAnimation(
                view,
                androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X,
                baseX
            )

            spring.spring = androidx.dynamicanimation.animation.SpringForce(
                baseX
            ).setStiffness(600f)

            spring.setStartVelocity(-shift * 100f)
            spring.addEndListener { animation, _, _, _ ->
                if (titleShakeAnimation === animation) {
                    view.translationX = baseX
                    titleShakeAnimation = null
                    titleShakeBaseX = null
                }
            }

            titleShakeAnimation = spring
            spring.start()
        }

        fun makeKey(label: String, action: () -> Unit): TextView {
            val letters = mapOf(
                "2" to "ABC",
                "3" to "DEF",
                "4" to "GHI",
                "5" to "JKL",
                "6" to "MNO",
                "7" to "PQRS",
                "8" to "TUV",
                "9" to "WXYZ"
            )

            return TextView(activity).apply {
                text = if (letters.containsKey(label)) {
                    android.text.SpannableString("$label\n${letters[label]}").apply {
                        setSpan(
                            android.text.style.RelativeSizeSpan(0.42f),
                            label.length + 1,
                            length,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                } else {
                    label
                }
                textSize = if (label == "⌫") 15f else 25f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 0.9f)
                includeFontPadding = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(keyColor)
                    setStroke(dp(1), Color.rgb(57, 73, 87))
                }
                isClickable = true
                isFocusable = true
                isHapticFeedbackEnabled = true

                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            view.animate().cancel()
                            view.animate()
                                .scaleX(0.92f)
                                .scaleY(0.92f)
                                .setDuration(80L)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            view.animate().cancel()
                            view.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(120L)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
                        }
                    }
                    false
                }

                setOnClickListener {
                    performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP
                    )
                    action()
                }
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

                val params = LinearLayout.LayoutParams(dp(60), dp(60))
                params.setMargins(dp(8), dp(8), dp(8), dp(8))
                if (key is TextView) {
                    key.alpha = 0f
                    key.scaleX = 0.7f
                    key.scaleY = 0.7f
                }
                row.addView(key, params)
            }

            keypad.addView(
                row,
                LinearLayout.LayoutParams(
                    dp(236), dp(76)
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
                dp(236),
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
        val biometricAvailable = isFingerprintEnabled(activity) &&
            manager.canAuthenticate(
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
        biometricButton.visibility =
            if (biometricAvailable) android.view.View.VISIBLE
            else android.view.View.GONE
        actions.addView(biometricButton, actionParams)

        var unlockFinished = false

        fun completeUnlock() {
            if (unlockFinished) return
            unlockFinished = true
            if (dialog.isShowing) dialog.dismiss()
            onSuccess()
        }

        fun finishUnlock() {
            if (unlockInProgress || unlockFinished) return
            unlockInProgress = true
            unlockButton?.isEnabled = false
            biometricButton.isEnabled = false

            lockAnimation.setAnimation(R.raw.passcode_lock_close)
            lockAnimation.repeatCount = 0
            lockAnimation.removeAllAnimatorListeners()
            lockAnimation.addAnimatorListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    completeUnlock()
                }
            })
            lockAnimation.playAnimation()

            // Fallback if the close animation does not finish.
            root.postDelayed({
                completeUnlock()
            }, 1200L)
        }

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
                    finishUnlock()
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
                    shakePasscodeTitle()
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
                        finishUnlock()
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
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
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

        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(320L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        lockAnimation.post {
            if (dialog.isShowing && !unlockInProgress) {
                lockAnimation.playAnimation()
            }
        }

        for (i in 1 until root.childCount) {
            val child = root.getChildAt(i)
            if (child === keypad) {
                child.alpha = 1f
                child.scaleX = 1f
                child.scaleY = 1f
                for (rowIndex in 0 until keypad.childCount) {
                    val row = keypad.getChildAt(rowIndex) as? LinearLayout
                        ?: continue
                    for (keyIndex in 0 until row.childCount) {
                        val key = row.getChildAt(keyIndex)
                        if (key !is TextView) continue
                        key.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setStartDelay(
                                180L + rowIndex * 55L + keyIndex * 24L
                            )
                            .setDuration(220L)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
            } else {
                child.alpha = 0f
                child.scaleX = 0.92f
                child.scaleY = 0.92f
                child.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(((i - 1) * 35L).coerceAtMost(210L))
                    .setDuration(240L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
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
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(16), 0)
            setBackgroundColor(Color.WHITE)
        }

        val back = TextView(activity).apply {
            text = "‹"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(51, 144, 236))
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(48), dp(56)))

        val title = TextView(activity).apply {
            text = "Passcode"
            textSize = 20f
            setTextColor(Color.rgb(33, 33, 33))
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(
            title,
            LinearLayout.LayoutParams(0, dp(56), 1f)
        )
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
        )

        val dividerColor = Color.rgb(230, 230, 230)
        root.addView(View(activity).apply {
            setBackgroundColor(dividerColor)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ))

        val scroll = android.widget.ScrollView(activity)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        scroll.addView(list)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        fun addSection(label: String) {
            val heading = TextView(activity).apply {
                text = label.uppercase()
                textSize = 12f
                setTextColor(Color.rgb(51, 144, 236))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(12), dp(16), dp(8))
            }
            list.addView(heading, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ))
        }

        fun addDivider() {
            val line = View(activity).apply {
                setBackgroundColor(dividerColor)
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
            params.leftMargin = dp(20)
            list.addView(line, params)
        }

        fun addActionRow(
            label: String,
            detail: String? = null,
            action: () -> Unit
        ) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(12))
                isClickable = true
                isFocusable = true
                setBackgroundResource(
                    android.R.drawable.list_selector_background
                )
            }

            val textColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }

            textColumn.addView(TextView(activity).apply {
                text = label
                textSize = 16f
                setTextColor(Color.rgb(33, 33, 33))
            })

            if (detail != null) {
                val description = TextView(activity).apply {
                    text = detail
                    textSize = 13f
                    setTextColor(Color.rgb(117, 117, 117))
                    setPadding(0, dp(4), 0, 0)
                }
                textColumn.addView(description)
            }

            row.addView(textColumn, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            row.setOnClickListener { action() }

            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addDivider()
        }

        fun addToggleRow(
            label: String,
            detail: String,
            initialValue: Boolean,
            enabled: Boolean = true,
            onChanged: (Boolean) -> Unit
        ) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(10), dp(16), dp(10))
                isClickable = true
                isFocusable = true
                setBackgroundResource(
                    android.R.drawable.list_selector_background
                )
            }

            val textColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }

            textColumn.addView(TextView(activity).apply {
                text = label
                textSize = 16f
                setTextColor(Color.rgb(33, 33, 33))
            })
            textColumn.addView(TextView(activity).apply {
                text = detail
                textSize = 13f
                setTextColor(Color.rgb(117, 117, 117))
                setPadding(0, dp(4), 0, 0)
            })

            val toggle = android.widget.Switch(activity).apply {
                isChecked = initialValue
                isEnabled = enabled
                contentDescription = label
            }
            if (!enabled) {
                row.alpha = 0.55f
            }

            row.addView(textColumn, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            row.addView(toggle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)
            ))

            toggle.setOnCheckedChangeListener { _, checked ->
                onChanged(checked)
            }
            row.setOnClickListener {
                if (enabled) {
                    toggle.isChecked = !toggle.isChecked
                }
            }

            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addDivider()
        }

        back.setOnClickListener { dialog.dismiss() }

        addSection("Passcode")
        if (enabled) {
            addActionRow("Change passcode", "Change your current passcode") {
                dialog.dismiss()
                showUnlock(activity) {
                    showPinSetup(activity)
                }
            }
            addActionRow("Turn off passcode", "Disable the app passcode") {
                dialog.dismiss()
                showUnlock(activity) {
                    disable(activity)
                    Toast.makeText(
                        activity, "Passcode disabled",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            addActionRow("Set passcode", "Protect the app with a numeric code") {
                dialog.dismiss()
                showPinSetup(activity)
            }
        }

        addSection("Security")
        addToggleRow(
            "Fingerprint",
            "Unlock with biometric authentication",
            isFingerprintEnabled(activity),
            enabled = enabled
        ) { value ->
            setFingerprintEnabled(activity, value)
            Toast.makeText(
                activity,
                if (value) "Fingerprint enabled" else "Fingerprint disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        addToggleRow(
            "Auto-lock in background",
            "Lock automatically when the app goes to the background",
            isAutoLockEnabled(activity)
        ) { value ->
            prefs(activity).edit()
                .putBoolean(AUTO_LOCK_ENABLED, value)
                .apply()
            Toast.makeText(
                activity,
                if (value) "Auto-lock enabled" else "Auto-lock disabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.WHITE))
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }
}
