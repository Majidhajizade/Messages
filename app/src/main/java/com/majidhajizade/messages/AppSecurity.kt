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
import androidx.core.hardware.fingerprint.FingerprintManagerCompat
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private const val PIN_LENGTH = "pin_length"
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

    fun requestManualLock(context: Context) {
        if (isEnabled(context)) {
            prefs(context).edit()
                .putBoolean(AUTO_LOCK_PENDING, true)
                .apply()
        }
    }

    fun savePin(context: Context, pin: String): Boolean {
        if (pin.length !in 4..8 || pin.any { !it.isDigit() }) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = deriveHash(pin, salt)
        prefs(context).edit()
            .putString(PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putInt(PIN_LENGTH, pin.length)
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

    private fun verifyPinWithoutRecording(
        context: Context,
        pin: String
    ): Boolean {
        if (remainingPinRetryMillis(context) > 0L) return false

        val p = prefs(context)
        val saltValue = p.getString(PIN_SALT, null) ?: return false
        val hashValue = p.getString(PIN_HASH, null) ?: return false

        return runCatching {
            MessageDigest.isEqual(
                deriveHash(pin, Base64.decode(saltValue, Base64.NO_WRAP)),
                Base64.decode(hashValue, Base64.NO_WRAP)
            )
        }.getOrDefault(false)
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
            .remove(PIN_LENGTH)
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

    fun showUnlock(activity: FragmentActivity, sourceX: Int? = null, sourceY: Int? = null, onSuccess: () -> Unit) {
        if (!isEnabled(activity)) {
            onSuccess()
            return
        }

        val dialog = Dialog(
            activity,
            android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen
        )
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

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            // Keep the background edge-to-edge while content avoids system bars
            // and display cutouts.
            view.setPadding(
                safeInsets.left + dp(24),
                safeInsets.top + dp(24),
                safeInsets.right + dp(24),
                safeInsets.bottom + dp(24)
            )
            insets
        }

        var unlockInProgress = false
        val lockAnimation = LottieAnimationView(activity).apply {
            setAnimation(R.raw.passcode_lock)
            repeatCount = 0
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Telegram-style lock animation"
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
        var fingerprintKey: View? = null

        val fingerprintManager = FingerprintManagerCompat.from(activity)
        val biometricAvailable = isFingerprintEnabled(activity) &&
            fingerprintManager.isHardwareDetected &&
            fingerprintManager.hasEnrolledFingerprints()

        var unlockFinished = false
        var finishUnlock: () -> Unit = {}

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
                text = if (label == "BACKSPACE" || label == "FINGERPRINT") {
                    ""
                } else if (letters.containsKey(label)) {
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
                textSize = if (label == "BACKSPACE" ||
                    label == "FINGERPRINT") 15f else 25f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER

                val iconDrawable = when (label) {
                    "BACKSPACE" -> ContextCompat.getDrawable(
                        activity, R.drawable.ic_passcode_backspace
                    )
                    "FINGERPRINT" -> ContextCompat.getDrawable(
                        activity, R.drawable.fingerprint_custom
                    )
                    else -> null
                }
                if (iconDrawable != null) {
                    val iconSize = when (label) {
                        "BACKSPACE" -> dp(40)
                        "FINGERPRINT" -> dp(34)
                        else -> dp(32)
                    }
                    iconDrawable.setBounds(0, 0, iconSize, iconSize)
                    compoundDrawablePadding = dp(2)
                    setCompoundDrawables(null, iconDrawable, null, null)
                    translationY = when (label) {
                        "BACKSPACE" -> dp(12).toFloat()
                        "FINGERPRINT" -> dp(17).toFloat()
                        else -> dp(4).toFloat()
                    }
                }
                if (label == "FINGERPRINT" && !biometricAvailable) {
                    isEnabled = false
                    alpha = 0.4f
                }
                setLineSpacing(0f, 0.9f)
                includeFontPadding = false
                background = if (label == "FINGERPRINT" || label == "BACKSPACE") {
                    null
                } else {
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(keyColor)
                        setStroke(dp(1), Color.rgb(57, 73, 87))
                    }
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

        var startBiometricAuth: (() -> Unit)? = null
        var biometricPromptStarted = false
        var fingerprintCancellationSignal: CancellationSignal? = null

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
                        if (label != "FINGERPRINT") {
                            fingerprintCancellationSignal?.cancel()
                            fingerprintCancellationSignal = null
                            biometricPromptStarted = false
                        }
                        when (label) {
                            "BACKSPACE" -> {
                                if (pin.isNotEmpty()) {
                                    pin.deleteCharAt(pin.length - 1)
                                    refreshPin()
                                }
                            }
                            "FINGERPRINT" -> {
                                startBiometricAuth?.invoke()
                            }
                            else -> {
                                if (label.length == 1 && label[0] in '0'..'9') {
                                    val savedLength = prefs(activity)
                                        .getInt(PIN_LENGTH, 0)
                                    val inputLimit = if (savedLength > 0) {
                                        savedLength
                                    } else {
                                        8
                                    }

                                    if (pin.length < inputLimit) {
                                        pin.append(label)
                                        refreshPin()

                                        if (savedLength > 0 &&
                                            pin.length == savedLength
                                        ) {
                                            val entered = pin.toString()
                                            if (verifyPin(activity, entered)) {
                                                clearAutoLockPending(activity)
                                                finishUnlock()
                                            } else {
                                                val wait =
                                                    remainingPinRetryMillis(activity)
                                                subtitle.text = if (wait > 0L) {
                                                    "Too many attempts. Try again in " +
                                                        "${(wait + 999L) / 1000L} seconds."
                                                } else {
                                                    "Incorrect passcode. Try again."
                                                }
                                                subtitle.setTextColor(
                                                    Color.rgb(255, 117, 117)
                                                )
                                                pin.clear()
                                                refreshPin()
                                                shakePasscodeTitle()
                                            }
                                        } else if (savedLength == 0 &&
                                            pin.length in 4..8
                                        ) {
                                            val legacyPin = pin.toString()

                                            if (verifyPinWithoutRecording(
                                                    activity, legacyPin
                                                )
                                            ) {
                                                prefs(activity).edit()
                                                    .putInt(PIN_LENGTH, pin.length)
                                                    .apply()
                                                resetPinFailures(activity)
                                                clearAutoLockPending(activity)
                                                finishUnlock()
                                            } else if (pin.length == 8) {
                                                val valid = verifyPin(activity, legacyPin)
                                                if (valid) {
                                                    prefs(activity).edit()
                                                        .putInt(PIN_LENGTH, pin.length)
                                                        .apply()
                                                    clearAutoLockPending(activity)
                                                    finishUnlock()
                                                } else {
                                                    val wait =
                                                        remainingPinRetryMillis(activity)
                                                    subtitle.text = if (wait > 0L) {
                                                        "Too many attempts. Try again in " +
                                                            "${(wait + 999L) / 1000L} seconds."
                                                    } else {
                                                        "Incorrect passcode. Try again."
                                                    }
                                                    subtitle.setTextColor(
                                                        Color.rgb(255, 117, 117)
                                                    )
                                                    pin.clear()
                                                    refreshPin()
                                                    shakePasscodeTitle()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (label == "FINGERPRINT") fingerprintKey = key

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
        addKeyRow(listOf("BACKSPACE", "0", "FINGERPRINT"))

        root.addView(
            keypad,
            LinearLayout.LayoutParams(
                dp(236),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        fun completeUnlock() {
            if (unlockFinished) return
            unlockFinished = true
            if (dialog.isShowing) dialog.dismiss()
            onSuccess()
        }

        finishUnlock = {
            if (!unlockInProgress && !unlockFinished) {
                unlockInProgress = true
                fingerprintKey?.isEnabled = false

                lockAnimation.cancelAnimation()
                lockAnimation.setAnimation(R.raw.passcode_lock_close)
                lockAnimation.repeatCount = 0
                lockAnimation.removeAllAnimatorListeners()
                lockAnimation.progress = 0f
                lockAnimation.addAnimatorListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        lockAnimation.progress = 1f
                        completeUnlock()
                    }
                })
                lockAnimation.playAnimation()

                // Fallback: keep the lock on its closed final frame.
                root.postDelayed({
                    if (!unlockFinished) {
                        lockAnimation.cancelAnimation()
                        lockAnimation.progress = 1f
                        completeUnlock()
                    }
                }, 1000L)
            }
        }

        startBiometricAuth = {
            if (biometricAvailable && !biometricPromptStarted &&
                !unlockInProgress && !unlockFinished
            ) {
                biometricPromptStarted = true
                val signal = CancellationSignal()
                fingerprintCancellationSignal = signal
                try {
                    fingerprintManager.authenticate(
                        null,
                        0,
                        signal,
                        object : FingerprintManagerCompat.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(
                                result: FingerprintManagerCompat.AuthenticationResult
                            ) {
                                biometricPromptStarted = false
                                fingerprintCancellationSignal = null
                                resetPinFailures(activity)
                                clearAutoLockPending(activity)
                                finishUnlock()
                            }

                            override fun onAuthenticationFailed() {
                                // اثر انگشت شناسایی نشد؛ امکان تلاش دوباره وجود دارد.
                            }

                            override fun onAuthenticationError(
                                errorCode: Int,
                                errString: CharSequence
                            ) {
                                val cancelledByPin =
                                    fingerprintCancellationSignal == null
                                biometricPromptStarted = false
                                fingerprintCancellationSignal = null
                                if (!cancelledByPin && !unlockFinished &&
                                    !unlockInProgress && !activity.isFinishing
                                ) {
                                    Toast.makeText(
                                        activity, errString, Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        null
                    )
                } catch (e: SecurityException) {
                    biometricPromptStarted = false
                    fingerprintCancellationSignal = null
                    Toast.makeText(
                        activity,
                        "Fingerprint authentication is unavailable",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        fingerprintKey?.setOnClickListener {
            startBiometricAuth?.invoke()
        }

        dialog.setContentView(root)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setBackgroundColor(backgroundColor)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            WindowCompat.setDecorFitsSystemWindows(this, false)

            // Keep status and navigation bars visible, but draw behind them.
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }

            // Permit edge-to-edge drawing into short-edge display cutouts.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            decorView.systemUiVisibility =
                decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

            WindowInsetsControllerCompat(this, decorView).apply {
                // Dark lock-screen background: use light system-bar icons.
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
                show(WindowInsetsCompat.Type.systemBars())
            }

            attributes = attributes.apply { dimAmount = 0.12f }
        }
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        ViewCompat.requestApplyInsets(root)

        root.post {
            if (!dialog.isShowing) return@post

            root.animate().cancel()
            root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320L)
                .setInterpolator(DecelerateInterpolator())
                .start()

            // Play Telegram's original lock animation on the visible icon.
            lockAnimation.translationX = 0f
            lockAnimation.translationY = 0f

            val iconLocation = IntArray(2)
            lockAnimation.getLocationInWindow(iconLocation)
            val iconCenterX = iconLocation[0] + lockAnimation.width / 2f
            val iconCenterY = iconLocation[1] + lockAnimation.height / 2f

            val animationOriginX = sourceX?.toFloat() ?: iconCenterX
            val animationOriginY = sourceY?.toFloat() ?: iconCenterY

            if (sourceX != null && sourceY != null) {
                lockAnimation.translationX = animationOriginX - iconCenterX
                lockAnimation.translationY = animationOriginY - iconCenterY
            }

            lockAnimation.progress = 0f
            lockAnimation.playAnimation()

            if (sourceX != null && sourceY != null) {
                lockAnimation.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .setDuration(420L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            val keyDistances = ArrayList<Pair<View, Float>>()
            for (rowIndex in 0 until keypad.childCount) {
                val row = keypad.getChildAt(rowIndex) as? LinearLayout
                    ?: continue
                for (keyIndex in 0 until row.childCount) {
                    val key = row.getChildAt(keyIndex)
                    if (key !is TextView) continue

                    val location = IntArray(2)
                    key.getLocationInWindow(location)
                    val dx = location[0] + key.width / 2f - animationOriginX
                    val dy = location[1] + key.height / 2f - animationOriginY
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                    keyDistances.add(key to distance)
                }
            }

            val maxDistance = keyDistances.maxOfOrNull { it.second }
                ?.coerceAtLeast(1f) ?: 1f

            for (i in 1 until root.childCount) {
                val child = root.getChildAt(i)
                if (child === keypad) {
                    child.alpha = 1f
                    child.scaleX = 1f
                    child.scaleY = 1f
                    continue
                }

                child.animate().cancel()
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

            // Keys farther from the lock icon start later, like Telegram.
            for ((key, distance) in keyDistances) {
                val targetAlpha =
                    if (key === fingerprintKey && !biometricAvailable) 0.4f
                    else 1f

                key.animate().cancel()
                key.alpha = 0f
                key.scaleX = 0.7f
                key.scaleY = 0.7f
                key.animate()
                    .alpha(targetAlpha)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(
                        (distance / maxDistance * 360f).toLong()
                    )
                    .setDuration(232L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            if (biometricAvailable) {
                startBiometricAuth?.invoke()
            }
        }

    }

    private fun showPinSetup(activity: FragmentActivity) {
        val first = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4 to 8 digits"
        }

        AlertDialog.Builder(activity)
            .setTitle("Set numeric passcode")
            .setView(first)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val pin = first.text.toString()
                if (pin.length !in 4..8 || pin.any { !it.isDigit() }) {
                    Toast.makeText(
                        activity, "Use 4 to 8 digits", Toast.LENGTH_LONG
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

        val dialog = Dialog(
            activity,
            android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen
        )
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            clipToPadding = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
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
            "Unlock with fingerprint",
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
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            decorView.setBackgroundColor(Color.WHITE)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            WindowCompat.setDecorFitsSystemWindows(this, false)

            // Keep system bars visible while drawing edge-to-edge.
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            decorView.systemUiVisibility =
                decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

            WindowInsetsControllerCompat(this, decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
        ViewCompat.requestApplyInsets(root)
    }
}
