package com.majidhajizade.messages


import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ContentValues
import android.content.IntentFilter
import android.database.ContentObserver
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.PhoneNumberUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieAnimationView
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.text.util.Linkify
import android.text.method.LinkMovementMethod
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationActivity : androidx.fragment.app.FragmentActivity() {
    private var securityGateShowing = false
    private var conversationInitialized = false
    private var chatUnlockedForCurrentVisit = false


    private lateinit var messagesContainer: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var scrollView: ScrollView
    private lateinit var profileCapsule: android.widget.FrameLayout
    private lateinit var profile: TextView
    private lateinit var contactTitle: TextView
    private lateinit var contactAvatarView: View
    private lateinit var contactNameRow: LinearLayout

    private val blue = Color.rgb(0, 122, 255)
    private val secondaryText = Color.rgb(110, 110, 115)

    private lateinit var phone: String
    private var smsContentObserver: ContentObserver? = null
    private lateinit var offlineMeshManager: OfflineMeshManager

    companion object {
        private const val SEND_PERMISSION = 3001
        private const val PICK_CONTACT = 3002
        private const val NEARBY_PERMISSION = 3003
        private const val ACTION_DELIVERED = "com.majidhajizade.messages.SMS_DELIVERED"

        @Volatile
        var activeMeshId: String? = null
            private set

        @Volatile
        var activeConversationPhone: String? = null
    }

    private val savedSentMessageIds = mutableSetOf<String>()
    private val smsAttemptResults = mutableMapOf<String, MutableMap<Int, Boolean>>()
    private val smsAttemptMessages = mutableMapOf<String, String>()
    private val smsAttemptErrorCodes = mutableMapOf<String, Int>()

    private val smsDeliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val messageId = intent?.getStringExtra("message_id")
            if (messageId != null && resultCode == Activity.RESULT_OK) {
                updateMessageDelivered(messageId)
            }
        }
    }

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val messageId = intent?.getStringExtra("message_id") ?: return
            val attemptId = intent.getStringExtra("attempt_id") ?: messageId
            val partIndex = intent.getIntExtra("part_index", 0)
            val totalParts = intent.getIntExtra("total_parts", 1).coerceAtLeast(1)
            val success = resultCode == Activity.RESULT_OK

            val results = smsAttemptResults.getOrPut(attemptId) { mutableMapOf() }
            intent.getStringExtra("message")?.let {
                smsAttemptMessages[attemptId] = it
            }
            if (!success) smsAttemptErrorCodes[attemptId] = resultCode
            results[partIndex] = success

            if (results.size < totalParts) return

            val attemptSucceeded =
                (0 until totalParts).all { results[it] == true }
            val sentMessage = smsAttemptMessages.remove(attemptId)
            smsAttemptResults.remove(attemptId)
            val errorCode = smsAttemptErrorCodes.remove(attemptId)

            updateMessageStatus(messageId, attemptSucceeded)

            if (attemptSucceeded && sentMessage != null &&
                savedSentMessageIds.add(messageId)
            ) {
                saveSentMessage(sentMessage, messageId)
            }

            val errorText = if (attemptSucceeded) {
                "SMS sent successfully"
            } else {
                when (errorCode) {
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic failure"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio is off"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service"
                    SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS limit exceeded"
                    else -> "SMS error code: ${errorCode ?: resultCode}"
                }
            }

            if (!attemptSucceeded) {
                Toast.makeText(
                    this@ConversationActivity,
                    errorText,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // اجازه نمایش پس‌زمینه در محدوده نوار وضعیت
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        window.decorView.systemUiVisibility =
            if (!isDarkThemeEnabled()) {
                window.decorView.systemUiVisibility or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                window.decorView.systemUiVisibility and
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }

        val uriPhone = intent.data?.schemeSpecificPart
            ?.substringBefore('?')
            ?.removePrefix("//")
            ?.trim('/')
            ?.let { Uri.decode(it).trim() }
            ?.takeIf { it.isNotBlank() }

        val extraPhone = listOf(
            "phone",
            Intent.EXTRA_PHONE_NUMBER,
            "address",
            "recipient",
            "phone_number"
        ).firstNotNullOfOrNull { key ->
            intent.getStringExtra(key)?.trim()?.takeIf { it.isNotBlank() }
        }

        phone = extraPhone ?: uriPhone ?: run {
            Toast.makeText(
                this,
                "شماره مخاطب از برنامه تلفن دریافت نشد",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        // گفت‌وگوهای Mesh موقتاً غیرفعال‌اند؛ پیامک عادی بدون تغییر می‌ماند.

        val needsGlobalUnlock =
            AppSecurity.isEnabled(this) && AppSecurity.isAutoLockPending(this)
        val needsChatUnlock =
            AppSecurity.isEnabled(this) && AppSecurity.isChatLocked(this, phone)

        if (needsGlobalUnlock || needsChatUnlock) {
            showConversationSecurityGate { initializeConversation() }
        } else {
            initializeConversation()
        }

    }


    private fun initializeConversation() {
        if (conversationInitialized || isFinishing) return
        conversationInitialized = true
        setContentView(createScreen())

        val filter = IntentFilter("com.majidhajizade.messages.SMS_SENT")
        val deliveredFilter = IntentFilter(SmsDeliveredReceiver.ACTION_DELIVERED_UI)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                smsSentReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                smsDeliveredReceiver,
                deliveredFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(smsSentReceiver, filter)

            @Suppress("DEPRECATION")
            registerReceiver(smsDeliveredReceiver, deliveredFilter)
        }

        loadConversation()

        ensureSmsContentObserver()

        // راه‌اندازی Mesh و درخواست مجوزهای نزدیک‌برد موقتاً متوقف است.
    }

    private fun showConversationSecurityGate(onUnlocked: () -> Unit) {
        if (securityGateShowing || isFinishing) return
        securityGateShowing = true
        setContentView(android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        })
        AppSecurity.showUnlock(this) {
            securityGateShowing = false
            chatUnlockedForCurrentVisit = true
            onUnlocked()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // Mesh موقتاً غیرفعال است؛ مجوزهای نزدیک‌برد باعث شروع آن نمی‌شوند.
    }

    private fun ensureNearbyPermissions() {
        // Mesh موقتاً غیرفعال است؛ مجوزی درخواست نمی‌شود و مدیر Mesh شروع نمی‌شود.
    }

    override fun onDestroy() {
        if (::phone.isInitialized) {
            val currentChatId = phone.trim().uppercase(Locale.US)

            if (
                MeshSession.activeChatId
                    ?.equals(currentChatId, ignoreCase = true) == true
            ) {
                MeshSession.activeMessageHandler = null
                MeshSession.activeChatId = null
            }

            if (activeMeshId == currentChatId) {
                activeMeshId = null
            }
        }

        runCatching {
            unregisterReceiver(smsSentReceiver)
        }
        runCatching {
            unregisterReceiver(smsDeliveredReceiver)
        }

        super.onDestroy()
    }

    private fun ensureSmsContentObserver() {
        if (!::phone.isInitialized ||
            !::messagesContainer.isInitialized ||
            smsContentObserver != null ||
            checkSelfPermission(Manifest.permission.READ_SMS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                if (::phone.isInitialized &&
                    ::messagesContainer.isInitialized &&
                    checkSelfPermission(Manifest.permission.READ_SMS) ==
                        PackageManager.PERMISSION_GRANTED
                ) {
                    loadConversation()
                }
            }
        }

        smsContentObserver = observer
        contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            observer
        )
    }

    override fun onResume() {
        super.onResume()

        if (conversationInitialized &&
            AppSecurity.isEnabled(this) &&
            (
                AppSecurity.isAutoLockPending(this) ||
                (
                    ::phone.isInitialized &&
                    AppSecurity.isChatLocked(this, phone) &&
                    !chatUnlockedForCurrentVisit
                )
            ) &&
            !securityGateShowing
        ) {
            showConversationSecurityGate {
                setContentView(createScreen())
                loadConversation()
                ensureSmsContentObserver()
            }
            return
        }

        if (::phone.isInitialized) {
            activeConversationPhone = phone
        }

        if (::messagesContainer.isInitialized &&
            checkSelfPermission(Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            loadConversation()
            ensureSmsContentObserver()
        }
    }

    override fun onPause() {
        if (::phone.isInitialized &&
            PhoneNumberUtils.compare(activeConversationPhone ?: "", phone)
        ) {
            activeConversationPhone = null
        }

        smsContentObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }
        smsContentObserver = null

        super.onPause()
    }

    override fun onUserLeaveHint() {
        chatUnlockedForCurrentVisit = false
        super.onUserLeaveHint()
    }

    private fun hasNearbyPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getContactDisplayName(address: String): String {
        if (address.matches(Regex("MJ-[A-Z0-9]{6}"))) {
            return address
        }

        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )

            contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(
                        cursor.getColumnIndexOrThrow(
                            ContactsContract.PhoneLookup.DISPLAY_NAME
                        )
                    )
                } else {
                    null
                }
            } ?: address
        }.getOrDefault(address)
    }

    private fun isDarkThemeEnabled(): Boolean {
        val prefs = getSharedPreferences("messages_settings", MODE_PRIVATE)
        val systemDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return when (prefs.getString("theme_mode", null)) {
            "day" -> false
            "night" -> true
            "system" -> systemDark
            else -> if (prefs.contains("dark_mode")) {
                prefs.getBoolean("dark_mode", false)
            } else {
                systemDark
            }
        }
    }

    private fun createChatWallpaper(darkTheme: Boolean): android.graphics.drawable.Drawable {
        return android.graphics.drawable.ColorDrawable(
            if (darkTheme) Color.BLACK else Color.WHITE
        )
    }

    private inner class MessageBubbleDrawable(
        private val bubbleColor: Int,
        private val incomingMessage: Boolean
    ) : android.graphics.drawable.Drawable() {

        private val paint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG
        )
        private var compact = false
        private var groupPosition = 0

        fun setCompact(value: Boolean) {
            if (compact != value) {
                compact = value
                invalidateSelf()
            }
        }

        fun setGroupPosition(value: Int) {
            val safeValue = value.coerceIn(0, 3)
            if (groupPosition != safeValue) {
                groupPosition = safeValue
                invalidateSelf()
            }
        }

        fun extendGroup() {
            // A bubble that gains a following sibling is no longer the tail.
            // Preserve the first-item state; later items become middle items.
            setGroupPosition(if (groupPosition == 0 || groupPosition == 3) 1 else 2)
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.isEmpty) return

            val left = b.left.toFloat()
            val top = b.top.toFloat()
            val right = b.right.toFloat()
            val bottom = b.bottom.toFloat()
            val width = right - left
            val height = bottom - top
            if (width <= 0f || height <= 0f) return

            paint.style = android.graphics.Paint.Style.FILL
            paint.color = bubbleColor
            paint.shader = if (incomingMessage) {
                null
            } else {
                android.graphics.LinearGradient(
                    left, top, right, bottom,
                    intArrayOf(
                        android.graphics.Color.rgb(50, 205, 50),
                        android.graphics.Color.rgb(50, 205, 50)
                    ),
                    null,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }

            val path = android.graphics.Path()
            val rect = android.graphics.RectF()
            val padding = dp(2).toFloat()

            // Telegram-style grouped bubbles: intermediate messages have no tail.
            // The corners facing the adjacent message become smaller.
            if (groupPosition == 1 || groupPosition == 2) {
                val tailInset = dp(8).toFloat()
                val bodyLeft = if (incomingMessage) left + tailInset else left + padding
                val bodyRight = if (!incomingMessage) right - tailInset else right - padding

                rect.set(
                    bodyLeft,
                    top + padding,
                    bodyRight,
                    bottom - padding
                )

                val r = dp(18).toFloat().coerceAtMost(height / 2f)
                val joined = dp(5).toFloat().coerceAtMost(height / 2f)

                val topLeft = if (incomingMessage && groupPosition == 2) joined else r
                val topRight = if (!incomingMessage && groupPosition == 2) joined else r
                val bottomRight = if (!incomingMessage) joined else r
                val bottomLeft = if (incomingMessage) joined else r

                path.addRoundRect(
                    rect,
                    floatArrayOf(
                        topLeft, topLeft,
                        topRight, topRight,
                        bottomRight, bottomRight,
                        bottomLeft, bottomLeft
                    ),
                    android.graphics.Path.Direction.CW
                )
                canvas.drawPath(path, paint)
                paint.shader = null
                return
            }

            // Telegram-style TYPE_TEXT bubble geometry.
            val radius = dp(22).toFloat()
                .coerceAtMost((height - padding) / 2f)
                .coerceAtMost(width / 2f)
            val smallRadius = dp(6).toFloat()
            val tailInset = dp(8).toFloat()
            val topTailRadius = if (groupPosition == 3) {
                smallRadius.coerceAtMost(height / 2f)
            } else {
                radius
            }

            if (!incomingMessage) {
                // Outgoing: rounded body with a lower-right tail.
                path.moveTo(right - dp(3), bottom - padding)
                path.lineTo(left + padding + radius, bottom - padding)

                rect.set(
                    left + padding,
                    bottom - padding - radius * 2f,
                    left + padding + radius * 2f,
                    bottom - padding
                )
                path.arcTo(rect, 90f, 90f, false)

                path.lineTo(left + padding, top + padding + radius)

                rect.set(
                    left + padding,
                    top + padding,
                    left + padding + radius * 2f,
                    top + padding + radius * 2f
                )
                path.arcTo(rect, 180f, 90f, false)

                path.lineTo(right - tailInset - topTailRadius, top + padding)
                rect.set(
                    right - tailInset - topTailRadius * 2f,
                    top + padding,
                    right - tailInset,
                    top + padding + topTailRadius * 2f
                )
                path.arcTo(rect, 270f, 90f, false)

                path.lineTo(right - tailInset, bottom - padding - smallRadius - dp(3))

                rect.set(
                    right - tailInset,
                    bottom - padding - smallRadius * 2f - dp(9),
                    right - dp(7) + smallRadius * 2f,
                    bottom - padding - dp(1)
                )
                path.arcTo(rect, 180f, -83f, false)
            } else {
                // Incoming: mirrored Telegram-style lower-left tail.
                path.moveTo(left + dp(3), bottom - padding)
                path.lineTo(right - padding - radius, bottom - padding)

                rect.set(
                    right - padding - radius * 2f,
                    bottom - padding - radius * 2f,
                    right - padding,
                    bottom - padding
                )
                path.arcTo(rect, 90f, -90f, false)

                path.lineTo(right - padding, top + padding + radius)

                rect.set(
                    right - padding - radius * 2f,
                    top + padding,
                    right - padding,
                    top + padding + radius * 2f
                )
                path.arcTo(rect, 0f, -90f, false)

                path.lineTo(left + tailInset + topTailRadius, top + padding)
                rect.set(
                    left + tailInset,
                    top + padding,
                    left + tailInset + topTailRadius * 2f,
                    top + padding + topTailRadius * 2f
                )
                path.arcTo(rect, 270f, -90f, false)

                path.lineTo(left + tailInset, bottom - padding - smallRadius - dp(3))

                rect.set(
                    left + dp(7) - smallRadius * 2f,
                    bottom - padding - smallRadius * 2f - dp(9),
                    left + dp(8),
                    bottom - padding - dp(1)
                )
                path.arcTo(rect, 0f, 83f, false)
            }

            path.close()
            canvas.drawPath(path, paint)
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(
            colorFilter: android.graphics.ColorFilter?
        ) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int =
            android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun createScreen(): View {
        val darkTheme = isDarkThemeEnabled()
        val root = android.widget.FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            background = createChatWallpaper(darkTheme)
        }

        // لایه محوشونده بالای صفحه، با حفظ تم روشن و تاریک
        // پوشش مات بالای پیام‌ها؛ کنترل‌های هدر در لایه بالاتر باقی می‌مانند
        // پوشش کوتاه و شفاف بالای چت با محوشدن تدریجی به سمت پایین
        val topFade = View(this).apply {
            isClickable = false
            isFocusable = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    if (darkTheme) Color.argb(145, 0, 0, 0)
                    else Color.argb(145, 255, 255, 255),
                    if (darkTheme) Color.argb(100, 0, 0, 0)
                    else Color.argb(100, 255, 255, 255),
                    if (darkTheme) Color.argb(40, 0, 0, 0)
                    else Color.argb(40, 255, 255, 255),
                    Color.TRANSPARENT
                )
            ).apply {
                setColors(
                    intArrayOf(
                        if (darkTheme) Color.argb(145, 0, 0, 0)
                        else Color.argb(145, 255, 255, 255),
                        if (darkTheme) Color.argb(100, 0, 0, 0)
                        else Color.argb(100, 255, 255, 255),
                        if (darkTheme) Color.argb(40, 0, 0, 0)
                        else Color.argb(40, 255, 255, 255),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.35f, 0.72f, 1f)
                )
            }
        }

        val header = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            setPadding(dp(8), dp(24), dp(8), dp(8))
        }

        val backCapsule: android.widget.FrameLayout = if (darkTheme) {
            LiquidGlassView(this).apply {
                cornerRadius = dp(24).toFloat()
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(24).toFloat()
                }
                material = GlassMaterial.REGULAR
                refractionHeight = dp(5).toFloat()
                bevelWidth = dp(8).toFloat()
                dispersionStrength = 0.04f
                enableSensorHighlight = true
                enablePressEffect = true
                pressScale = 0.96f
                enableAdaptiveTint = false
                glassTint = Color.argb(38, 255, 255, 255)
            }
        } else {
            android.widget.FrameLayout(this).apply {
                elevation = dp(2).toFloat()
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                    cornerRadius = dp(24).toFloat()
                }
            }
        }
        backCapsule.setOnClickListener { finish() }

        val backCircle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back_arrow)
            contentDescription = "Back"
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = null
            setOnClickListener {
                finish()
            }
        }

        backCapsule.addView(
            backCircle,
            android.widget.FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
        )

        header.addView(
            backCapsule,
            android.widget.FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = dp(2)
            }
        )

        val contactName = getContactDisplayName(phone)

        profileCapsule = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
        }

        val isUnsavedNumber = !phone.matches(Regex("MJ-[A-Z0-9]{6}")) &&
            contactName == phone

        profile = if (isUnsavedNumber) {
            TextView(this).apply {
                text = ""
                background = GradientDrawable().apply {
                    setColor(Color.rgb(220, 224, 230))
                    shape = GradientDrawable.OVAL
                }
            }
        } else {
            TextView(this).apply {
                text = contactName.firstOrNull()?.uppercase() ?: "?"
                textSize = 25f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColors(avatarGradientColors(contactName))
                    orientation = GradientDrawable.Orientation.TL_BR
                    shape = GradientDrawable.OVAL
                }
            }
        }

        contactAvatarView = if (isUnsavedNumber) {
            android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_profile_unsaved)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = "Unknown contact"
            }
        } else {
            profile
        }

        profileCapsule.addView(
            contactAvatarView,
            android.widget.FrameLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(5)
            }
        )

        contactTitle = TextView(this).apply {
            text = contactName
            tag = "contact_title"
            textSize = 14f
            setTextColor(if (isDarkThemeEnabled()) Color.WHITE else Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = "Open contact details"
            setOnClickListener {
                showContactDetails(contactName)
            }
        }

        val contactDetailsIcon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_contact_details)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Contact details"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showContactDetails(contactName)
            }
        }

        contactNameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            translationX = -dp(13).toFloat()
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Open contact details"
            setOnClickListener {
                showContactDetails(contactName)
            }
        }

        contactNameRow.addView(
            contactDetailsIcon,
            LinearLayout.LayoutParams(dp(22), dp(28)).apply {
                rightMargin = dp(5)
            }
        )
        contactNameRow.addView(
            contactTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(28)
            )
        )

        val contactNameGlass: android.widget.FrameLayout = if (darkTheme) {
            LiquidGlassView(this).apply {
                cornerRadius = dp(17).toFloat()
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(17).toFloat()
                }
                material = GlassMaterial.REGULAR
                refractionHeight = dp(3).toFloat()
                bevelWidth = dp(4).toFloat()
                dispersionStrength = 0.025f
                enableSensorHighlight = true
                enablePressEffect = true
                pressScale = 0.98f
                enableAdaptiveTint = false
                glassTint = Color.argb(30, 255, 255, 255)
            }
        } else {
            android.widget.FrameLayout(this).apply {
                elevation = dp(2).toFloat()
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                    cornerRadius = dp(17).toFloat()
                }
            }
        }
        contactNameGlass.setPadding(dp(3), 0, dp(3), 0)
        contactNameGlass.addView(
            contactNameRow,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(28),
                Gravity.CENTER
            )
        )

        profileCapsule.addView(
            contactNameGlass,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(30)
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(1)
            }
        )

        header.addView(
            profileCapsule,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                dp(92)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(56)
                rightMargin = dp(56)
            }
        )

        profileCapsule.isClickable = true
        profileCapsule.isFocusable = true
        profileCapsule.setOnClickListener {
            showContactDetails(contactName)
        }

        val moreCapsule: android.widget.FrameLayout = if (darkTheme) {
            LiquidGlassView(this).apply {
                cornerRadius = dp(24).toFloat()
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(24).toFloat()
                }
                material = GlassMaterial.REGULAR
                refractionHeight = dp(5).toFloat()
                bevelWidth = dp(8).toFloat()
                dispersionStrength = 0.04f
                enableSensorHighlight = true
                enablePressEffect = true
                pressScale = 0.96f
                enableAdaptiveTint = false
                glassTint = Color.argb(38, 255, 255, 255)
            }
        } else {
            android.widget.FrameLayout(this).apply {
                elevation = dp(2).toFloat()
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                    cornerRadius = dp(24).toFloat()
                }
            }
        }

        val moreButton = TextView(this).apply {
            text = "⋮"
            textSize = 27f
            setTextColor(if (isDarkThemeEnabled()) Color.WHITE else Color.BLACK)
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = "More"
        }

        moreCapsule.addView(
            moreButton,
            android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER)
        )

        moreButton.setOnClickListener { anchor ->
            val menu = android.widget.PopupMenu(this, anchor)
            val locked = AppSecurity.isChatLocked(this, phone)
            menu.menu.add(if (locked) "Unlock chat" else "Lock chat")
            menu.setOnMenuItemClickListener {
                if (!AppSecurity.isEnabled(this)) {
                    AppSecurity.showSettings(this)
                } else if (locked) {
                    AppSecurity.showUnlock(this) {
                        AppSecurity.setChatLocked(this, phone, false)
                        Toast.makeText(this, "Chat unlocked", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    AppSecurity.setChatLocked(this, phone, true)
                    Toast.makeText(
                        this, "Chat will lock when reopened", Toast.LENGTH_SHORT
                    ).show()
                }
                true
            }
            menu.show()
        }

        header.addView(
            moreCapsule,
            android.widget.FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = dp(2)
            }
        )

        scrollView = ScrollView(this).apply {
            clipChildren = false
            clipToPadding = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER

            setBackgroundColor(Color.TRANSPARENT)
        }

        messagesContainer = LinearLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(16), dp(148), dp(16), dp(12))
        }

        scrollView.addView(messagesContainer)

        if (darkTheme) {
            listOf(backCapsule, contactNameGlass, moreCapsule).forEach { glass ->
                (glass as? LiquidGlassView)?.apply {
                    backdropSource = scrollView
                    enableDynamicBackground = true
                }
            }
        }

        root.addView(
            scrollView,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            topFade,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                dp(170)
            ).apply { gravity = Gravity.TOP }
        )
        root.addView(
            header,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                dp(150)
            ).apply {
                gravity = Gravity.TOP
            }
        )

        val floatingDateCapsule = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(115, 0, 0, 0))
                cornerRadius = dp(18).toFloat()
            }
            elevation = dp(3).toFloat()
            alpha = 0f
            visibility = View.GONE
        }

        root.addView(
            floatingDateCapsule,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(160)
            }
        )

        val scrollJumpButton = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back_arrow)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            elevation = dp(5).toFloat()
            alpha = 0f
            visibility = View.GONE
            contentDescription = "Jump through messages"
            setOnClickListener {
                val maxY =
                    (messagesContainer.height - scrollView.height).coerceAtLeast(0)
                val targetY = if (tag == "jump_bottom") maxY else 0
                scrollView.post {
                    scrollView.smoothScrollTo(0, targetY)
                }
            }
        }

        val hideDateCapsule = Runnable {
            floatingDateCapsule.animate()
                .alpha(0f)
                .setDuration(250L)
                .withEndAction {
                    floatingDateCapsule.visibility = View.GONE
                }
                .start()
        }

        scrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY != oldScrollY) {
                var activeDate: TextView? = null

                for (i in 0 until messagesContainer.childCount) {
                    val child = messagesContainer.getChildAt(i)
                    if (child.tag == "date_header_row" &&
                        child.top <= scrollY + dp(120)) {
                        activeDate =
                            (child as? LinearLayout)?.getChildAt(0) as? TextView
                    }
                }

                if (activeDate != null) {
                    floatingDateCapsule.text = activeDate.text
                    floatingDateCapsule.animate().cancel()
                    floatingDateCapsule.alpha = 1f
                    floatingDateCapsule.visibility = View.VISIBLE
                    floatingDateCapsule.removeCallbacks(hideDateCapsule)
                    floatingDateCapsule.postDelayed(hideDateCapsule, 2000L)
                }

                val maxY =
                    (messagesContainer.height - scrollView.height).coerceAtLeast(0)
                val atTop = scrollY <= dp(4)
                val atBottom = scrollY >= maxY - dp(4)
                val scrollingUp = scrollY < oldScrollY
                val targetRotation = if (scrollingUp) 90f else 270f
                val shouldHide = maxY == 0 || atTop || atBottom

                scrollJumpButton.animate().cancel()
                scrollJumpButton.alpha = 0f
                scrollJumpButton.visibility = View.GONE

                if (!shouldHide) {
                    scrollJumpButton.tag =
                        if (scrollingUp) "jump_bottom" else "jump_top"
                    scrollJumpButton.rotation = targetRotation
                    scrollJumpButton.postDelayed({
                        val currentMaxY =
                            (messagesContainer.height - scrollView.height)
                                .coerceAtLeast(0)
                        val nowAtTop = scrollView.scrollY <= dp(4)
                        val nowAtBottom =
                            scrollView.scrollY >= currentMaxY - dp(4)

                        if (currentMaxY > 0 && !nowAtTop && !nowAtBottom) {
                            scrollJumpButton.visibility = View.VISIBLE
                            scrollJumpButton.animate()
                                .alpha(1f)
                                .setDuration(180L)
                                .start()
                        }
                    }, 250L)
                }
            }
        }

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setBackgroundColor(Color.TRANSPARENT)
        }

        val addContact = ImageButton(this).apply {
            setImageResource(R.drawable.ic_plus_chat)
            if (isDarkThemeEnabled()) {
                setColorFilter(Color.WHITE)
            } else {
                clearColorFilter()
            }
            contentDescription = "Add contact"
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = null

            setOnClickListener {
                val intent = Intent(
                    Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                )
                startActivityForResult(intent, PICK_CONTACT)
            }
        }

        composer.addView(
            addContact,
            LinearLayout.LayoutParams(dp(40), dp(40))
        )

        messageInput = EditText(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            hint = "Message"
            textSize = 16f
            setTextColor(if (isDarkThemeEnabled()) Color.WHITE else Color.BLACK)
            setHintTextColor(if (isDarkThemeEnabled()) Color.rgb(150, 150, 160) else Color.rgb(145, 145, 150))
            setSingleLine(false)
            minLines = 1
            maxLines = 4
            minHeight = dp(40)
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = null
            gravity = Gravity.CENTER_VERTICAL
        }

        val inputParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )

        composer.addView(messageInput, inputParams)

        fun animateComposerHeight(target: Int) {
            val start = composer.height.coerceAtLeast(dp(58))

            if (start == target) return

            ValueAnimator.ofInt(start, target).apply {
                duration = 180L
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    composer.layoutParams =
                        composer.layoutParams.apply {
                            height = animator.animatedValue as Int
                        }
                    composer.requestLayout()
                }
                start()
            }
        }

        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                val lines = messageInput.lineCount.coerceIn(1, 4)
                val targetHeight = dp(58 + (lines - 1) * 24)
                animateComposerHeight(targetHeight)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        messageInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                messageInput.postDelayed({
                    scrollView.fullScroll(View.FOCUS_DOWN)
                }, 120L)
            }
        }

        val send = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send_arrow)
            contentDescription = "Send"
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.rgb(11, 218, 81))
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.rgb(11, 218, 81))
            }

            elevation = dp(1).toFloat()

            setOnClickListener {
                sendMessage()
            }
        }

        composer.addView(
            send,
            LinearLayout.LayoutParams(dp(36), dp(36))
        )

        val composerGlass: android.widget.FrameLayout = if (darkTheme) {
            LiquidGlassView(this).apply {
                cornerRadius = dp(28).toFloat()
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(28).toFloat()
                }
                material = GlassMaterial.REGULAR
                refractionHeight = dp(5).toFloat()
                bevelWidth = dp(7).toFloat()
                dispersionStrength = 0.035f
                enableSensorHighlight = true
                enablePressEffect = true
                enableAdaptiveTint = false
                glassTint = Color.argb(38, 255, 255, 255)
                clipChildren = false
                clipToPadding = false
                backdropSource = scrollView
                enableDynamicBackground = true
            }
        } else {
            android.widget.FrameLayout(this).apply {
                elevation = dp(2).toFloat()
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                    cornerRadius = dp(28).toFloat()
                }
                clipChildren = false
                clipToPadding = false
            }
        }
        composerGlass.addView(
            composer,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val composerParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(12), dp(6), dp(12), dp(24))
        }

        root.addView(composerGlass, composerParams)

        root.addView(
            scrollJumpButton,
            android.widget.FrameLayout.LayoutParams(dp(46), dp(46)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(90)
            }
        )

        fun positionScrollJumpButton() {
            val params = scrollJumpButton.layoutParams as android.widget.FrameLayout.LayoutParams
            params.bottomMargin = composer.height + dp(32)
            scrollJumpButton.layoutParams = params
        }

        composer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            positionScrollJumpButton()
        }
        composer.post { positionScrollJumpButton() }
        composer.post {
            val bottomPadding = composer.height + dp(2) + dp(24)
            messagesContainer.setPadding(
                dp(16),
                dp(94),
                dp(16),
                bottomPadding
            )
        }


        root.setOnApplyWindowInsetsListener { _, insets ->
            val imeBottom = insets.getInsets(
                android.view.WindowInsets.Type.ime()
            ).bottom
            composerGlass.translationY = -(imeBottom + dp(1)).toFloat()
            insets
        }
        root.requestApplyInsets()


        return root
    }

    private fun showContactDetails(contactName: String) {
        val isMeshId = phone.matches(Regex("MJ-[A-Z0-9]{6}"))
        val isSavedContact = !isMeshId && contactName != phone

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            setBackgroundColor(if (isDarkThemeEnabled()) Color.rgb(12, 12, 14) else Color.rgb(245, 245, 247))
        }

        val topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back_arrow)
            if (isDarkThemeEnabled()) setColorFilter(Color.WHITE)
            contentDescription = "Back"
            background = null
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { finishContactDetails() }
        }

        topBar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(topBar)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(34), 0, 0)
        }

        val avatar: View = if (!isSavedContact && !isMeshId) {
            android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_profile_unsaved)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(235, 237, 240))
                    shape = GradientDrawable.OVAL
                }
                contentDescription = "Unknown contact"
            }
        } else {
            TextView(this).apply {
                text = contactName.firstOrNull()?.uppercase() ?: "?"
                textSize = 50f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColors(avatarGradientColors(contactName))
                    orientation = GradientDrawable.Orientation.TL_BR
                }
            }
        }

        content.addView(
            avatar,
            LinearLayout.LayoutParams(dp(102), dp(102))
        )

        val title = TextView(this).apply {
            text = if (isSavedContact) contactName else phone
            textSize = 22f
            setTextColor(if (isDarkThemeEnabled()) Color.WHITE else Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        content.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(18)
        })

        if (isSavedContact) {
            content.addView(TextView(this).apply {
                text = phone
                textSize = 16f
                setTextColor(if (isDarkThemeEnabled()) Color.rgb(175, 175, 185) else secondaryText)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(7)
            })
        }

        page.addView(content)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        var notificationsEnabled = true

        fun makeAction(
            label: String,
            iconRes: Int,
            onClick: () -> Unit
        ): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(if (isDarkThemeEnabled()) Color.rgb(43, 43, 49) else Color.WHITE)
                    cornerRadius = dp(18).toFloat()
                }
                isClickable = true
                isFocusable = true
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { onClick() }
            }

            val icon = android.widget.ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(if (isDarkThemeEnabled()) Color.WHITE else Color.BLACK)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                contentDescription = label
            }

            card.addView(icon, LinearLayout.LayoutParams(dp(27), dp(27)))

            val text = TextView(this).apply {
                this.text = label
                textSize = 12f
                setTextColor(if (isDarkThemeEnabled()) Color.WHITE else Color.BLACK)
                gravity = Gravity.CENTER
                maxLines = 2
            }

            text.tag = "contact_action_label"
            card.addView(text, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(5)
            })

            return card
        }

        actions.addView(
            makeAction("Notifications", R.drawable.ic_notification_on) {
                notificationsEnabled = !notificationsEnabled
                Toast.makeText(
                    this,
                    if (notificationsEnabled) "Notifications enabled" else "Notifications disabled",
                    Toast.LENGTH_SHORT
                ).show()
            },
            LinearLayout.LayoutParams(0, dp(78), 1f).apply {
                rightMargin = dp(7)
            }
        )

        actions.addView(
            makeAction("Call", R.drawable.ic_call) {
                if (!isMeshId) {
                    startActivity(Intent(
                        Intent.ACTION_DIAL,
                        Uri.parse("tel:${Uri.encode(phone)}")
                    ))
                } else {
                    Toast.makeText(
                        this,
                        "Phone calls are unavailable for this ID",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            LinearLayout.LayoutParams(0, dp(78), 1f).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            }
        )

        actions.addView(
            makeAction(
                if (isContactBlocked()) "Unblock" else "Block",
                R.drawable.ic_block
            ) {
                val wasBlocked = isContactBlocked()
                val action = if (wasBlocked) "Unblock" else "Block"

                android.app.AlertDialog.Builder(this)
                    .setTitle("$action contact?")
                    .setMessage(
                        if (wasBlocked)
                            "This contact will be allowed to message you again."
                        else
                            "Messages from this contact will be blocked in this app, and you won't be able to send messages to them."
                    )
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(action) { _, _ ->
                        val prefs = getSharedPreferences(
                            "messages_settings",
                            MODE_PRIVATE
                        )
                        val blocked = prefs.getStringSet(
                            "blocked_numbers",
                            emptySet()
                        )?.toMutableSet() ?: mutableSetOf()

                        if (wasBlocked) {
                            blocked.remove(phone.trim())
                        } else {
                            blocked.add(phone.trim())
                        }

                        prefs.edit()
                            .putStringSet("blocked_numbers", blocked)
                            .apply()

                        val label = actions.getChildAt(2)
                            .findViewWithTag<TextView>("contact_action_label")
                        label?.text = if (wasBlocked) "Block" else "Unblock"

                        Toast.makeText(
                            this,
                            if (wasBlocked) "Contact unblocked" else "Contact blocked",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .show()
            },
            LinearLayout.LayoutParams(0, dp(78), 1f).apply {
                leftMargin = dp(7)
            }
        )

        page.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(18)
        })

        setContentView(page)
    }

    private fun finishContactDetails() {
        setContentView(createScreen())
        loadConversation()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_CONTACT &&
            resultCode == RESULT_OK &&
            data?.data != null
        ) {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            contentResolver.query(
                data.data!!,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val numberIndex = cursor.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )

                    if (numberIndex >= 0) {
                        val number = cursor.getString(numberIndex)
                        if (!number.isNullOrBlank()) {
                            messageInput.setText(number)
                            messageInput.setSelection(messageInput.text.length)
                        }
                    }
                }
            }
        }
    }

    private fun updateMessageStatus(
        messageId: String,
        success: Boolean
    ) {
        val wrapper = messagesContainer.findViewWithTag<LinearLayout>(messageId)
            ?: return

        val bubble = wrapper.findViewWithTag<LinearLayout>("message_bubble")
            ?: return

        wrapper.findViewWithTag<View>("sending_icon")?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
        }

        if (success) {
            wrapper.findViewWithTag<View>("failed_icon")?.let {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
            }


            return
        }

        if (wrapper.findViewWithTag<View>("failed_icon") != null) {
            return
        }

        val icon = TextView(this).apply {
            tag = "failed_icon"
            text = "!"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            contentDescription = "Tap to retry sending"
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
            }
            setOnClickListener {
                retryFailedMessage(messageId)
            }
        }

        val metaRow = wrapper.findViewWithTag<LinearLayout>("message_meta_row")
        if (metaRow != null) {
            metaRow.addView(
                icon,
                LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                    leftMargin = dp(3)
                }
            )
        } else {
            bubble.addView(
                icon,
                LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setMargins(dp(8), 0, dp(7), 0)
                }
            )
        }
    }

    private fun retryFailedMessage(messageId: String) {
        val wrapper = messagesContainer.findViewWithTag<LinearLayout>(messageId)
            ?: return
        val bubble = wrapper.findViewWithTag<LinearLayout>("message_bubble")
            ?: return
        val failedIcon = wrapper.findViewWithTag<View>("failed_icon")
            ?: return
        val message = messageBodyText(
            bubble.findViewWithTag<View>("message_body")
        ).orEmpty()

        if (message.isBlank()) return

        if (isContactBlocked()) {
            Toast.makeText(
                this,
                "This contact is blocked. Unblock to retry.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (checkSelfPermission(Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.SEND_SMS),
                SEND_PERMISSION
            )
            return
        }

        // Remove the retry button while this one manual attempt is in progress.
        (failedIcon.parent as? android.view.ViewGroup)?.removeView(failedIcon)

        try {
            sendSmsAttempt(messageId, message, true)
        } catch (e: Exception) {
            updateMessageStatus(messageId, false)
            Toast.makeText(
                this,
                "SMS retry failed: ${e.message ?: "Unknown error"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateMessageDelivered(messageId: String) {
        // تأیید تحویل حفظ می‌شود؛ آیکون تیک نمایش داده نمی‌شود.
    }

    private var isPopulatingConversation = false

    private fun loadConversation() {
        isPopulatingConversation = true
        messagesContainer.removeAllViews()
        lastMessageDate = null
        lastTimeView = null
        lastDateHeaderKey = null
        lastMessageBubbleDrawable = null
        lastBubbleIncoming = null
        lastBubbleDate = null
        lastBubbleDayKey = null

        if (MainActivity.MESH_ENABLED && activeMeshId != null) {
            MeshMessageStore.loadMessages(
                this,
                activeMeshId!!
            ).forEach { message ->
                addMessage(
                    body = message.body,
                    date = message.date,
                    incoming = message.incoming
                )
            }

            isPopulatingConversation = false
            playAllLargeEmojiAnimations()
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
            return
        }

        if (checkSelfPermission(Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            isPopulatingConversation = false
            return
        }

        val projection = arrayOf(
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.ADDRESS,
            Telephony.Sms._ID
        )

        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms.ADDRESS} = ?",
            arrayOf(phone),
            "${Telephony.Sms.DATE} ASC"
        )

        lastMessageDate = null
        lastTimeView = null

        val deliveredRows = getSharedPreferences(
            "messages_settings",
            MODE_PRIVATE
        ).getStringSet(
            SmsDeliveredReceiver.PREF_DELIVERED_ROW_IDS,
            emptySet()
        )?.toSet() ?: emptySet()

        cursor?.use {
            val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIndex = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val idIndex = it.getColumnIndexOrThrow(Telephony.Sms._ID)

            while (it.moveToNext()) {
                val body = it.getString(bodyIndex) ?: ""
                val date = it.getLong(dateIndex)
                val type = it.getInt(typeIndex)
                val incoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX
                val rowId = it.getString(idIndex)

                addMessage(
                    body = body,
                    date = date,
                    incoming = incoming,
                    delivered = !incoming && rowId in deliveredRows
                )
            }
        }

        isPopulatingConversation = false
        playAllLargeEmojiAnimations()
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun playAllLargeEmojiAnimations() {
        messagesContainer.post {
            for (i in 0 until messagesContainer.childCount) {
                val row = messagesContainer.getChildAt(i)
                val emojiView =
                    (row.findViewWithTag<View>("message_body") as? LottieAnimationView)
                if (emojiView != null && emojiView.isAttachedToWindow) {
                    emojiView.progress = 0f
                    emojiView.playAnimation()
                }
            }
        }
    }

    private var lastMessageDate: Long? = null
    private var lastDateHeaderKey: String? = null
    private var lastTimeView: TextView? = null

    private var lastMessageBubbleDrawable: MessageBubbleDrawable? = null
    private var lastBubbleIncoming: Boolean? = null
    private var lastBubbleDate: Long? = null
    private var lastBubbleDayKey: String? = null

    private fun addMessage(
        body: String,
        date: Long,
        incoming: Boolean,
        messageId: String? = null,
        delivered: Boolean = false
    ) {
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(date))
        if (dayKey != lastDateHeaderKey) {
            val dateHeader = TextView(this).apply {
                text = when {
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(date)) ==
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) -> "Today"
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(date)) ==
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
                            Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
                        ) -> "Yesterday"
                    else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(date))
                }
                textSize = 12f
                setTextColor(if (isDarkThemeEnabled()) Color.LTGRAY else Color.rgb(120, 120, 125))
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            val dateHeaderRow = LinearLayout(this).apply {
                tag = "date_header_row"
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(8))
                addView(
                    dateHeader,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            messagesContainer.addView(dateHeaderRow)
            lastDateHeaderKey = dayKey
        }

        val previousDate = lastMessageDate
        val closeToPrevious = previousDate != null &&
            date - previousDate < 60_000L


        val bubble = LinearLayout(this).apply {
            tag = "message_bubble"
            contentDescription = if (incoming) "incoming_message_bubble" else "outgoing_message_bubble"
            orientation = LinearLayout.VERTICAL
            setBaselineAligned(false)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(1))
            background = MessageBubbleDrawable(
                if (incoming) {
                    if (isDarkThemeEnabled()) Color.rgb(48, 48, 55)
                    else Color.rgb(236, 236, 236)
                } else {
                    Color.rgb(50, 205, 50)
                },
                incoming
            )
        }

        val emojiAssetPath = emojiAnimationAsset(body)
        val compactSingleLine =
            emojiAssetPath == null &&
            !body.contains('\n') &&
            body.length <= 18 &&
            body.none { it == '\u200d' || it == '\ufe0f' } &&
            body.trim().isNotEmpty()
        if (compactSingleLine) {
            bubble.minimumHeight = dp(38)
        }

        if (emojiAssetPath != null) {
            bubble.background = null

            val emojiContainer = android.widget.FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
            }

            val effectSizePx = ((resources.displayMetrics.widthPixels / 3f)
                .toInt().coerceAtLeast(dp(120))) * 2

            val miniEmojiView = LottieAnimationView(this).apply {
                tag = "small_animated_emoji"
                contentDescription = body
                setRenderMode(com.airbnb.lottie.RenderMode.HARDWARE)
                setAnimation(emojiReactionAsset(body) ?: emojiAssetPath)
                repeatCount = 0
                progress = 0f
                visibility = View.GONE
            }

            val animationView = LottieAnimationView(this).apply {
                tag = "message_body"
                contentDescription = body
                setRenderMode(com.airbnb.lottie.RenderMode.HARDWARE)
                setAnimation(emojiAssetPath)
                repeatCount = 0
                progress = 0f

                val sourceEmojiView = this

                setOnClickListener {
                    progress = 0f
                    playAnimation()

                    // بازخورد لمسی برای هر بار لمس ایموجی قلب
                    if (body.contains("❤")) {
                        performHapticFeedback(
                            android.view.HapticFeedbackConstants.VIRTUAL_KEY
                        )
                    }

                    miniEmojiView.animate().cancel()
                    miniEmojiView.cancelAnimation()

                    val sourceLocation = IntArray(2)
                    sourceEmojiView.getLocationOnScreen(sourceLocation)

                    val animationRoot = window.decorView.findViewById<android.view.ViewGroup>(
                        android.R.id.content
                    ) ?: return@setOnClickListener
                    val rootLocation = IntArray(2)
                    animationRoot.getLocationOnScreen(rootLocation)

                    val startX = sourceLocation[0] - rootLocation[0] +
                        (sourceEmojiView.width - effectSizePx) / 2f - dp(12)
                    val startY = sourceLocation[1] - rootLocation[1] +
                        (sourceEmojiView.height - effectSizePx) / 2f

                    (miniEmojiView.parent as? android.view.ViewGroup)
                        ?.removeView(miniEmojiView)

                    animationRoot.clipChildren = false
                    animationRoot.clipToPadding = false
                    miniEmojiView.layoutParams =
                        android.widget.FrameLayout.LayoutParams(
                            effectSizePx,
                            effectSizePx,
                            Gravity.TOP or Gravity.START
                        )
                    animationRoot.addView(miniEmojiView)

                    miniEmojiView.animate().cancel()
                    miniEmojiView.x = startX
                    miniEmojiView.y = startY
                    miniEmojiView.scaleX = 0.90f
                    miniEmojiView.scaleY = 0.90f
                    miniEmojiView.alpha = 1f
                    miniEmojiView.progress = 0f
                    miniEmojiView.visibility = View.VISIBLE
                    miniEmojiView.playAnimation()
                    miniEmojiView.bringToFront()

                    val targetX = startX +
                        if (incoming) effectSizePx * 0.90f else -effectSizePx * 0.90f
                    val targetY = startY - effectSizePx * 0.65f

                    miniEmojiView.animate()
                        .x(targetX)
                        .y(targetY)
                        .scaleX(1.25f)
                        .scaleY(1.25f)
                        .alpha(0f)
                        .setDuration(5000)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            miniEmojiView.animate().cancel()
                            miniEmojiView.cancelAnimation()
                            (miniEmojiView.parent as? android.view.ViewGroup)
                                ?.removeView(miniEmojiView)
                            animationRoot.removeView(miniEmojiView)
                            emojiContainer.addView(
                                miniEmojiView,
                                android.widget.FrameLayout.LayoutParams(
                                    effectSizePx,
                                    effectSizePx,
                                    Gravity.CENTER
                                )
                            )
                            miniEmojiView.scaleX = 1f
                            miniEmojiView.scaleY = 1f
                            miniEmojiView.alpha = 1f
                            miniEmojiView.visibility = View.GONE
                        }
                        .start()
                }
            }

            emojiContainer.addView(
                animationView,
                android.widget.FrameLayout.LayoutParams(
                    dp(88),
                    dp(88),
                    Gravity.CENTER
                )
            )

            emojiContainer.addView(
                miniEmojiView,
                android.widget.FrameLayout.LayoutParams(
                    effectSizePx,
                    effectSizePx,
                    Gravity.CENTER
                )
            )

            bubble.addView(
                emojiContainer,
                LinearLayout.LayoutParams(dp(88), dp(88))
            )
        } else {
            val messageText = TextView(this).apply {
                tag = "message_body"
                text = body
                textSize = 16f
                setTextColor(
                    if (!incoming || isDarkThemeEnabled()) Color.WHITE
                    else Color.BLACK
                )
                maxWidth = (resources.displayMetrics.widthPixels * 0.66f).toInt()
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                autoLinkMask = Linkify.WEB_URLS or Linkify.PHONE_NUMBERS
                movementMethod = LinkMovementMethod.getInstance()
                if (compactSingleLine) {
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(7), dp(18), dp(7))
                } else {
                    setPadding(dp(18), dp(9), dp(18), dp(9))
                }
            }

            if (compactSingleLine) {
                val inlineRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBaselineAligned(false)
                    layoutDirection = if (incoming) {
                        View.LAYOUT_DIRECTION_LTR
                    } else {
                        View.LAYOUT_DIRECTION_RTL
                    }
                    tag = "message_inline_row"
                }

                inlineRow.addView(
                    messageText,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )

                bubble.addView(
                    inlineRow,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            } else {
                bubble.addView(
                    messageText,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        val wrapper = LinearLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            orientation = LinearLayout.VERTICAL
            gravity = if (incoming) Gravity.START else Gravity.END
            setPadding(
                0,
                if (previousDate == null) dp(4)
                else if (closeToPrevious) dp(2)
                else dp(8),
                0,
                0
            )

            if (messageId != null) {
                tag = messageId
            }
        }

        val time = TextView(this).apply {
            tag = "message_time"
            text = SimpleDateFormat(
                "HH:mm",
                Locale.getDefault()
            ).format(Date(date))
            textSize = 11f
            setTextColor(if (isDarkThemeEnabled()) Color.WHITE else Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }

        if (emojiAssetPath == null) {
            // ساعت بیرون حباب می‌ماند و با کشیدن پیام آشکار می‌شود.
            val messageLayer = android.widget.FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
            }

            val maxReveal = dp(64)

            time.apply {
                gravity = Gravity.CENTER_VERTICAL
                alpha = 0f
                visibility = View.VISIBLE
                setPadding(dp(5), 0, dp(5), 0)
            }

            val timeParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = if (incoming) {
                    Gravity.START or Gravity.CENTER_VERTICAL
                } else {
                    Gravity.END or Gravity.CENTER_VERTICAL
                }
                marginStart = if (incoming) dp(8) else 0
                marginEnd = if (incoming) 0 else dp(8)
            }
            // حباب در جای فعلی می‌ماند؛ ساعت بالاتر از آن رسم می‌شود.
            messageLayer.addView(
                bubble,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = if (incoming) {
                        Gravity.START or Gravity.CENTER_VERTICAL
                    } else {
                        Gravity.END or Gravity.CENTER_VERTICAL
                    }
                }
            )

            messageLayer.addView(time, timeParams)

            var downX = 0f
            var downY = 0f
            var horizontalDrag = false

            val groupSwipeRows = mutableListOf<Pair<View, TextView>>()
            val applyGroupSwipe: (Float) -> Unit = { rawDx ->
                val resistedDx = (rawDx * 0.28f).coerceIn(
                    -maxReveal.toFloat(),
                    maxReveal.toFloat()
                )
                val alpha = (
                    kotlin.math.abs(resistedDx) / maxReveal.toFloat()
                ).coerceIn(0f, 1f)
                val expectedSide = if (incoming) {
                    "incoming_message_bubble"
                } else {
                    "outgoing_message_bubble"
                }

                for (i in 0 until messagesContainer.childCount) {
                    val row = messagesContainer.getChildAt(i) as? LinearLayout ?: continue
                    if (row.visibility != View.VISIBLE) continue
                    val rect = android.graphics.Rect()
                    if (!row.getGlobalVisibleRect(rect)) continue

                    val itemBubble = row.findViewWithTag<LinearLayout>("message_bubble") ?: continue
                    if (itemBubble.contentDescription != expectedSide) continue
                    val layer = itemBubble.parent as? android.widget.FrameLayout ?: continue
                    val itemTime = layer.findViewWithTag<TextView>("message_time") ?: continue

                    if (groupSwipeRows.none { it.first === itemBubble }) {
                        groupSwipeRows.add(itemBubble to itemTime)
                    }

                    itemBubble.animate().cancel()
                    itemTime.animate().cancel()
                    itemBubble.translationX = resistedDx

                    // ساعت را بیرون حباب و در سمت مناسب پیام نمایش بده.
                    val outsideOffset = if (incoming) {
                        -dp(8).toFloat()
                    } else {
                        dp(8).toFloat()
                    }
                    itemTime.translationX = resistedDx + outsideOffset
                    itemTime.alpha = alpha
                    itemTime.bringToFront()
                }
            }

            val resetGroupSwipe: () -> Unit = {
                groupSwipeRows.forEach { pair ->
                    pair.first.animate()
                        .translationX(0f)
                        .setDuration(280L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()

                    pair.second.animate()
                        .translationX(0f)
                        .alpha(0f)
                        .setDuration(260L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
                groupSwipeRows.clear()
            }

            bubble.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.animate().cancel()
                        time.animate().cancel()
                        downX = event.rawX
                        downY = event.rawY
                        horizontalDrag = false
                        // جلوگیری از ربوده‌شدن لمس توسط والد پیش از تشخیص جهت حرکت
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    }

                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY

                        // اگر حرکت عمودی است، اجازهٔ اسکرول را به والد برگردان.
                        if (!horizontalDrag &&
                            kotlin.math.abs(dy) > dp(8) &&
                            kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.1f
                        ) {
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            return@setOnTouchListener false
                        }

                        if (!horizontalDrag &&
                            kotlin.math.abs(dx) > dp(18) &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.1f
                        ) {
                            horizontalDrag = true
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }

                        if (horizontalDrag) {
                            // حباب‌ها و ساعت‌های سمت انتخاب‌شده با مقاومت حرکت می‌کنند.
                            applyGroupSwipe(dx)
                        }

                        // تا زمانی که والد اسکرول رویداد را نگیرد،
                        // زنجیرهٔ لمس روی حباب حفظ می‌شود.
                        true
                    }

                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        if (horizontalDrag) {
                            resetGroupSwipe()

                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            horizontalDrag = false
                            true
                        } else {
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                            false
                        }
                    }

                    else -> false
                }
            }

            wrapper.addView(
                messageLayer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val bodyText = bubble.findViewWithTag<View>("message_body")

            // متن یا ایموجی ممکن است لمس را قبل از رسیدن به حباب دریافت کند.
            // لمس را مصرف نمی‌کنیم تا لینک‌ها و کلیک ایموجی حفظ شوند.
            var bodyDownX = 0f
            var bodyDownY = 0f
            var bodyDragging = false

            bodyText?.setOnTouchListener { touchedView, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        bodyDownX = event.rawX
                        bodyDownY = event.rawY
                        bodyDragging = false
                        false
                    }

                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - bodyDownX
                        val dy = event.rawY - bodyDownY

                        if (!bodyDragging &&
                            kotlin.math.abs(dx) > dp(18) &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.1f
                        ) {
                            bodyDragging = true
                            touchedView.parent?.requestDisallowInterceptTouchEvent(true)
                            bubble.animate().cancel()
                            time.animate().cancel()
                        }

                        if (bodyDragging) {
                            applyGroupSwipe(dx)
                        }

                        bodyDragging
                    }

                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = bodyDragging
                        if (wasDragging) {
                            resetGroupSwipe()
                            touchedView.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        bodyDragging = false
                        wasDragging
                    }

                    else -> bodyDragging
                }
            }

            bodyText?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                bubble.post {
                    val shape = bubble.background as? MessageBubbleDrawable
                        ?: return@post
                    shape.setCompact(compactSingleLine)
                }
            }
        } else {
            // رفتار ساعت ایموجی‌های متحرک فعلاً حفظ می‌شود.
            wrapper.addView(
                bubble,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            time.gravity = if (incoming) Gravity.END else Gravity.START
            time.setPadding(dp(4), dp(2), dp(4), 0)
            wrapper.addView(
                time,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // Group only adjacent regular bubbles from the same side and same day.
        // A group is broken by a sender change, a day change, an animated emoji,
        // or a gap longer than two minutes.
        val currentShape = bubble.background as? MessageBubbleDrawable
        val previousShape = lastMessageBubbleDrawable
        val previousDateForGroup = lastBubbleDate
        val groupWindowMs = 2 * 60 * 1000L

        val sameGroup = currentShape != null &&
            previousShape != null &&
            lastBubbleIncoming == incoming &&
            previousDateForGroup != null &&
            date >= previousDateForGroup &&
            date - previousDateForGroup <= groupWindowMs &&
            lastBubbleDayKey == dayKey

        if (sameGroup) {
            previousShape!!.extendGroup()
            currentShape!!.setGroupPosition(3) // last message: show tail
        } else {
            currentShape?.setGroupPosition(0) // standalone message: show tail
        }

        lastMessageBubbleDrawable = currentShape
        lastBubbleIncoming = if (currentShape != null) incoming else null
        lastBubbleDate = if (currentShape != null) date else null
        lastBubbleDayKey = if (currentShape != null) dayKey else null

        messagesContainer.addView(wrapper)

        // پیام تازه با محوشدن و حرکت بسیار کوتاه، نرم ظاهر می‌شود.
        if (!isPopulatingConversation) {
            wrapper.alpha = 0f
            wrapper.translationY = dp(8).toFloat()
            wrapper.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()

            val bodyEmojiView =
                (bubble.findViewWithTag<View>("message_body") as? LottieAnimationView)
            bodyEmojiView?.post {
                if (bodyEmojiView.isAttachedToWindow) {
                    bodyEmojiView.progress = 0f
                    bodyEmojiView.playAnimation()
                }
            }
        }

        lastMessageDate = date
        lastTimeView = time
    }

    private fun messageBodyText(view: View?): String? {
        return when (view) {
            is TextView -> view.text.toString()
            is LottieAnimationView -> view.contentDescription?.toString()
            else -> null
        }
    }

    private fun emojiAnimationAsset(body: String): String? {
        return when (body) {
            "❤️" -> "emoji/heart.json"
            "😂" -> "emoji/laugh.json"
            "🔥" -> "emoji/fire.json"
            "🎉" -> "emoji/party.json"
            else -> null
        }
    }

    private fun emojiReactionAsset(body: String): String? {
        return when (body) {
            "❤️", "❤" -> "emoji/reactions/heart.json"
            "😂" -> "emoji/reactions/laugh.json"
            "🔥" -> "emoji/reactions/fire.json"
            "🎉" -> "emoji/reactions/party.json"
            else -> null
        }
    }

    private fun saveSentMessage(message: String, messageId: String) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, phone)
            put(Telephony.Sms.BODY, message)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.READ, 1)
        }

        val rowId = try {
            contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                ?.lastPathSegment
        } catch (e: Exception) {
            Log.e("MessagesSms", "Failed to save sent SMS row", e)
            null
        } ?: run {
            Log.e("MessagesSms", "SMS row insert returned no row ID")
            return
        }

        val prefs = getSharedPreferences("messages_settings", MODE_PRIVATE)
        prefs.edit()
            .putString("sms_row_for_message_$messageId", rowId)
            .apply()

        val deliveredIds = prefs.getStringSet(
            SmsDeliveredReceiver.PREF_DELIVERED_MESSAGE_IDS,
            emptySet()
        ) ?: emptySet()

        if (messageId in deliveredIds) {
            val deliveredRows = prefs.getStringSet(
                "sms_delivered_row_ids",
                emptySet()
            )?.toMutableSet() ?: mutableSetOf()

            deliveredRows.add(rowId)
            prefs.edit()
                .putStringSet("sms_delivered_row_ids", deliveredRows)
                .apply()
        }
    }

    private fun isContactBlocked(): Boolean {
        val blocked = getSharedPreferences(
            "messages_settings",
            MODE_PRIVATE
        ).getStringSet("blocked_numbers", emptySet()) ?: emptySet()
        return blocked.contains(phone.trim())
    }

    private fun sendMessage() {
        val message = messageInput.text.toString().trim()

        if (message.isEmpty()) {
            return
        }

        if (isContactBlocked()) {
            Toast.makeText(
                this,
                "This contact is blocked. Unblock to send messages.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (MainActivity.MESH_ENABLED && activeMeshId != null) {
            if (!offlineMeshManager.isTargetConnected()) {
                MeshSession.log(
                    "ERROR | Target device is not connected"
                )
                return
            }

            val sentDate = System.currentTimeMillis()

            addMessage(
                body = message,
                date = sentDate,
                incoming = false
            )

            MeshMessageStore.saveMessage(
                this@ConversationActivity,
                phone,
                StoredMeshMessage(
                    body = message,
                    date = sentDate,
                    incoming = false
                )
            )

            messageInput.text.clear()

            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }

            offlineMeshManager.sendMessage(message)
            return
        }

        if (checkSelfPermission(Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.SEND_SMS),
                SEND_PERMISSION
            )
            return
        }

        val messageId = System.currentTimeMillis().toString()

        // Show the outgoing message immediately.
        addMessage(
            body = message,
            date = System.currentTimeMillis(),
            incoming = false,
            messageId = messageId
        )

        messageInput.text.clear()

        scrollView.post {
            if (scrollView.canScrollVertically(1)) {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }

        try {
            sendSmsAttempt(messageId, message, false)

        } catch (e: Exception) {
            updateMessageStatus(messageId, false)

            Toast.makeText(
                this,
                "SMS failed: ${e.message ?: "Unknown error"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun sendSmsAttempt(
        messageId: String,
        message: String,
        isRetry: Boolean
    ) {
        val normalizedPhone = normalizePhoneNumber(phone)
        val smsManager = SmsManager.getDefault()
        val parts = smsManager.divideMessage(message)
        val attemptCode = (System.nanoTime() and 0x3fffffffL).toInt()
        val attemptId = if (isRetry) "$messageId-$attemptCode" else messageId
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }

        fun sentPendingIntent(index: Int): PendingIntent {
            val sentIntent = Intent("com.majidhajizade.messages.SMS_SENT").apply {
                setPackage(packageName)
                putExtra("message_id", messageId)
                putExtra("message", message)
                putExtra("attempt_id", attemptId)
                putExtra("part_index", index)
                putExtra("total_parts", parts.size)
            }
            return PendingIntent.getBroadcast(
                this,
                attemptCode + index + 1,
                sentIntent,
                flags
            )
        }

        fun deliveredPendingIntent(index: Int): PendingIntent {
            val deliveredIntent = Intent(
                this,
                SmsDeliveredReceiver::class.java
            ).apply {
                action = SmsDeliveredReceiver.ACTION_DELIVERED
                putExtra("message_id", messageId)
            }
            return PendingIntent.getBroadcast(
                this,
                attemptCode + 100001 + index,
                deliveredIntent,
                flags
            )
        }

        if (parts.size == 1) {
            smsManager.sendTextMessage(
                normalizedPhone,
                null,
                message,
                sentPendingIntent(0),
                deliveredPendingIntent(0)
            )
        } else {
            val sentIntents = ArrayList<PendingIntent>()
            val deliveryIntents = ArrayList<PendingIntent>()
            parts.indices.forEach { index ->
                sentIntents.add(sentPendingIntent(index))
                deliveryIntents.add(deliveredPendingIntent(index))
            }
            smsManager.sendMultipartTextMessage(
                normalizedPhone,
                null,
                parts,
                sentIntents,
                deliveryIntents
            )
        }
    }

    private fun normalizePhoneNumber(value: String): String {
        val number = value.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")

        return when {
            number.startsWith("+98") -> number
            number.startsWith("0098") -> "+" + number.substring(2)
            number.startsWith("98") -> "+" + number
            number.startsWith("09") -> "+98" + number.substring(1)
            else -> number
        }
    }

    private fun avatarGradientColors(name: String): IntArray {
        val base = avatarColor(name)

        fun mix(value: Int, target: Int, amount: Float): Int =
            (value + (target - value) * amount).toInt()

        val light = Color.rgb(
            mix(Color.red(base), 255, 0.28f),
            mix(Color.green(base), 255, 0.28f),
            mix(Color.blue(base), 255, 0.28f)
        )

        val dark = Color.rgb(
            mix(Color.red(base), 0, 0.22f),
            mix(Color.green(base), 0, 0.22f),
            mix(Color.blue(base), 0, 0.22f)
        )

        return intArrayOf(light, base, dark)
    }

    private fun avatarColor(name: String): Int {
        val colors = intArrayOf(
            Color.rgb(0, 166, 255),
            Color.rgb(38, 185, 154),
            Color.rgb(139, 120, 230),
            Color.rgb(245, 159, 66),
            Color.rgb(232, 93, 117),
            Color.rgb(50, 150, 216),
            Color.rgb(25, 169, 116),
            Color.rgb(193, 125, 223)
        )
        val first = name.trim().firstOrNull()?.uppercaseChar() ?: return colors[0]
        val index = when (first) {
            in 'A'..'C', in 'ا'..'ث' -> 0
            in 'D'..'F', in 'ج'..'خ' -> 1
            in 'G'..'I', in 'د'..'ز' -> 2
            in 'J'..'L', in 'س'..'ض' -> 3
            in 'M'..'O', in 'ط'..'ف' -> 4
            in 'P'..'R', in 'ق'..'م' -> 5
            in 'S'..'U', in 'ن'..'ی' -> 6
            in 'V'..'Z' -> 7
            else -> 0
        }
        return colors[index]
    }


    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
