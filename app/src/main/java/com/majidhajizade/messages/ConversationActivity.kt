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

class ConversationActivity : Activity() {

    private lateinit var messagesContainer: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var scrollView: ScrollView
    private lateinit var profileCapsule: android.widget.FrameLayout
    private lateinit var profile: TextView
    private lateinit var contactTitle: TextView
    private var searchMode = false
    private var searchQuery = ""

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
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

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

        if (intent.getBooleanExtra("offline_mesh", false)) {
            activeMeshId = phone
                .trim()
                .uppercase(Locale.US)
        }

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

        offlineMeshManager = MeshSession.manager
            ?: run {
                finish()
                return
            }

        MeshSession.activeChatId = phone
            .trim()
            .uppercase(Locale.US)

        MeshSession.activeMessageHandler = { senderId, message ->
            if (
                senderId.equals(
                    phone.trim().uppercase(Locale.US),
                    ignoreCase = true
                ) && !isContactBlocked()
            ) {
                runOnUiThread {
                    val receivedDate = System.currentTimeMillis()

                    addMessage(
                        body = message,
                        date = receivedDate,
                        incoming = true
                    )

                    MeshMessageStore.saveMessage(
                        this@ConversationActivity,
                        phone,
                        StoredMeshMessage(
                            body = message,
                            date = receivedDate,
                            incoming = true
                        )
                    )

                    scrollView.post {
                        scrollView.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }

        intent.getStringExtra("mesh_id")
            ?.trim()
            ?.uppercase(Locale.US)
            ?.let { targetMeshId ->
                offlineMeshManager.setTargetMeshId(targetMeshId)
            }

        ensureNearbyPermissions()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == NEARBY_PERMISSION) {
            if (hasNearbyPermissions()) {
                offlineMeshManager.start()
            } else {
                Toast.makeText(
                    this,
                    "Nearby permission is required for offline messaging",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun ensureNearbyPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(
                missing.toTypedArray(),
                NEARBY_PERMISSION
            )
        } else {
            offlineMeshManager.start()
        }
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

    override fun onResume() {
        super.onResume()

        if (::phone.isInitialized) {
            activeConversationPhone = phone
        }

        if (::messagesContainer.isInitialized &&
            checkSelfPermission(Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED
        ) {
            loadConversation()

            if (smsContentObserver == null) {
                smsContentObserver = object : ContentObserver(
                    Handler(Looper.getMainLooper())
                ) {
                    override fun onChange(selfChange: Boolean) {
                        super.onChange(selfChange)
                        if (::phone.isInitialized &&
                            ::messagesContainer.isInitialized &&
                            checkSelfPermission(Manifest.permission.READ_SMS)
                                == PackageManager.PERMISSION_GRANTED
                        ) {
                            loadConversation()
                        }
                    }
                }
                contentResolver.registerContentObserver(
                    Telephony.Sms.CONTENT_URI,
                    true,
                    smsContentObserver!!
                )
            }
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

    private fun isDarkThemeEnabled(): Boolean =
        getSharedPreferences("messages_settings", MODE_PRIVATE)
            .getBoolean("dark_mode", false)

    private fun createChatWallpaper(darkTheme: Boolean): android.graphics.drawable.Drawable {
        val colors = if (darkTheme) {
            intArrayOf(
                Color.rgb(30, 53, 87),
                Color.rgb(21, 26, 54),
                Color.rgb(28, 67, 82),
                Color.rgb(42, 69, 65)
            )
        } else {
            intArrayOf(
                Color.rgb(219, 221, 187),
                Color.rgb(107, 165, 135),
                Color.rgb(213, 216, 141),
                Color.rgb(136, 184, 132)
            )
        }

        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            colors
        )

        val bitmap = android.graphics.BitmapFactory.decodeResource(
            resources,
            R.drawable.telegram_default_pattern
        )

        val pattern = android.graphics.drawable.BitmapDrawable(
            resources,
            bitmap
        ).apply {
            gravity = Gravity.FILL
            paint.isFilterBitmap = true
            paint.colorFilter = android.graphics.PorterDuffColorFilter(
                if (darkTheme) Color.rgb(10, 24, 34) else Color.rgb(73, 105, 83),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            alpha = if (darkTheme) 55 else 87
        }

        return android.graphics.drawable.LayerDrawable(arrayOf(gradient, pattern))
    }

    private inner class MessageBubbleDrawable(
        private val bubbleColor: Int,
        private val incomingMessage: Boolean
    ) : android.graphics.drawable.Drawable() {

        private val paint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG
        )
        private var compact = false

        fun setCompact(value: Boolean) {
            if (compact != value) {
                compact = value
                invalidateSelf()
            }
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.isEmpty) return

            val left = b.left.toFloat()
            val top = b.top.toFloat()
            val right = b.right.toFloat()
            val bottom = b.bottom.toFloat()
            val tailSize = dp(5).toFloat()
            val bodyBottom = bottom - tailSize
            val bodyHeight = bodyBottom - top

            paint.style = android.graphics.Paint.Style.FILL
            paint.color = bubbleColor
            paint.shader = if (incomingMessage) {
                null
            } else {
                android.graphics.LinearGradient(
                    left,
                    top,
                    right,
                    bodyBottom,
                    intArrayOf(
                        android.graphics.Color.rgb(0, 208, 234),
                        android.graphics.Color.rgb(0, 133, 209)
                    ),
                    null,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }

            val radius = if (compact) {
                (bodyHeight / 2f).coerceAtLeast(0f)
            } else {
                dp(22).toFloat().coerceAtMost(bodyHeight / 2f)
            }

            val body = android.graphics.RectF(
                left,
                top,
                right,
                bodyBottom
            )
            canvas.drawRoundRect(body, radius, radius, paint)

            // A curved tail is drawn in the same paint so its fill
            // exactly matches the bubble, including the outgoing gradient.
            val tail = android.graphics.Path()
            if (incomingMessage) {
                tail.moveTo(left + dp(17), bodyBottom - dp(9))
                tail.cubicTo(
                    left + dp(12), bodyBottom - dp(2),
                    left + dp(6), bottom - dp(1),
                    left, bottom
                )
                tail.cubicTo(
                    left + dp(2), bottom - dp(7),
                    left + dp(5), bottom - dp(8),
                    left + dp(9), bodyBottom
                )
            } else {
                tail.moveTo(right - dp(17), bodyBottom - dp(9))
                tail.cubicTo(
                    right - dp(12), bodyBottom - dp(2),
                    right - dp(6), bottom - dp(1),
                    right, bottom
                )
                tail.cubicTo(
                    right - dp(2), bottom - dp(7),
                    right - dp(5), bottom - dp(8),
                    right - dp(9), bodyBottom
                )
            }
            tail.close()
            canvas.drawPath(tail, paint)

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

        val header = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
            setPadding(dp(8), dp(40), dp(8), dp(8))
        }

        val backCapsule = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(115, 255, 255, 255))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.argb(180, 255, 255, 255))
            }
            elevation = dp(3).toFloat()
            setOnClickListener {
                finish()
            }
        }

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
            LinearLayout.LayoutParams(dp(34), dp(34))
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
            background = GradientDrawable().apply {
                setColor(Color.argb(115, 255, 255, 255))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.argb(180, 255, 255, 255))
            }
            elevation = dp(3).toFloat()
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
                textSize = 17f
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

        if (isUnsavedNumber) {
            val unsavedIcon = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_profile_unsaved)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = "Unknown contact"
            }
            profileCapsule.addView(
                unsavedIcon,
                android.widget.FrameLayout.LayoutParams(dp(34), dp(34)).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    leftMargin = dp(6)
                }
            )
        }

        if (!isUnsavedNumber) {
            profileCapsule.addView(
                profile,
                android.widget.FrameLayout.LayoutParams(dp(34), dp(34)).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    leftMargin = dp(6)
                }
            )
        }

        profileCapsule.addView(
            TextView(this).apply {
                text = contactName
                tag = "contact_title"
                textSize = 16f
                setTextColor(if (isDarkThemeEnabled()) Color.WHITE else Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                leftMargin = dp(46)
                rightMargin = dp(46)
            }
        )

        header.addView(
            profileCapsule,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(56)
                rightMargin = dp(56)
            }
        )

        contactTitle = profileCapsule.findViewWithTag("contact_title")
        profileCapsule.isClickable = true
        profileCapsule.isFocusable = true
        profileCapsule.setOnClickListener {
            if (!searchMode) showContactDetails(contactName)
        }
        profileCapsule.setOnLongClickListener {
            enterSearchMode()
            true
        }

        val moreCapsule = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(115, 255, 255, 255))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.argb(180, 255, 255, 255))
            }
            elevation = dp(3).toFloat()
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
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

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

            setBackgroundColor(Color.TRANSPARENT)
        }

        messagesContainer = LinearLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(16), dp(94), dp(16), dp(12))
        }

        scrollView.addView(messagesContainer)

        root.addView(
            scrollView,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            header,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                dp(96)
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
                topMargin = dp(104)
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
            background = GradientDrawable().apply {
                setColor(if (isDarkThemeEnabled()) Color.rgb(48, 48, 55) else Color.WHITE)
                cornerRadius = dp(28).toFloat()
                setStroke(
                    dp(1),
                    if (isDarkThemeEnabled()) Color.rgb(67, 67, 75)
                    else Color.rgb(232, 232, 236)
                )
            }
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
                setColor(Color.rgb(0, 122, 255))
                shape = GradientDrawable.OVAL
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

        val composerParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(12), dp(6), dp(12), dp(24))
        }

        root.addView(composer, composerParams)

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
            composer.translationY = -(imeBottom + dp(1)).toFloat()
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

            if (bubble.findViewWithTag<View>("status_icon") == null) {
                val icon = LinearLayout(this).apply {
                    tag = "status_icon"
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    contentDescription = "Sent"
                    addView(
                        android.widget.ImageView(this@ConversationActivity).apply {
                            setImageResource(R.drawable.ic_message_tick)
                            setColorFilter(secondaryText)
                            contentDescription = "Sent"
                        },
                        LinearLayout.LayoutParams(dp(16), dp(16))
                    )
                }
                val metaRow = wrapper.findViewWithTag<LinearLayout>("message_meta_row")
                if (metaRow != null) {
                    metaRow.addView(
                        icon,
                        LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                            leftMargin = dp(2)
                        }
                    )
                }
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
        val sendingIcon = TextView(this).apply {
            tag = "sending_icon"
            text = "◷"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(secondaryText)
            contentDescription = "Sending"
        }
        val metaRow = wrapper.findViewWithTag<LinearLayout>("message_meta_row")
        if (metaRow != null) {
            metaRow.addView(
                sendingIcon,
                LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    leftMargin = dp(2)
                }
            )
        } else {
            bubble.addView(
                sendingIcon,
                LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setMargins(dp(8), 0, dp(7), 0)
                }
            )
        }

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
        val wrapper =
            messagesContainer.findViewWithTag<LinearLayout>(messageId)
                ?: return

        val bubble =
            wrapper.findViewWithTag<LinearLayout>("message_bubble")
                ?: return

        val icon =
            wrapper.findViewWithTag<LinearLayout>("status_icon")
                ?: return

        if (icon.childCount < 2) {
            icon.layoutParams = icon.layoutParams.apply {
                width = dp(24)
                height = dp(16)
            }
            icon.addView(
                android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_message_tick)
                    setColorFilter(blue)
                    contentDescription = "Delivered"
                },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    leftMargin = dp(-8)
                }
            )
        }

        for (index in 0 until icon.childCount) {
            (icon.getChildAt(index) as? android.widget.ImageView)
                ?.setColorFilter(blue)
        }
        icon.contentDescription = "Delivered"
    }

    private fun enterSearchMode() {
        if (searchMode) return
        searchMode = true
        searchQuery = ""

        profileCapsule.removeAllViews()
        profileCapsule.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), Color.rgb(220, 220, 225))
        }

        val searchIcon = TextView(this).apply {
            text = "⌕"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            contentDescription = "Search"
        }
        profileCapsule.addView(
            searchIcon,
            android.widget.FrameLayout.LayoutParams(dp(40), dp(48)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        )

        val searchInput = EditText(this).apply {
            tag = "chat_search_input"
            hint = "Search"
            textSize = 16f
            setSingleLine(true)
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            background = null
            setPadding(dp(2), 0, dp(2), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    filterVisibleMessages()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        profileCapsule.addView(
            searchInput,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                leftMargin = dp(40)
                rightMargin = dp(44)
                gravity = Gravity.CENTER_VERTICAL
            }
        )

        val close = TextView(this).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            contentDescription = "Close search"
            setOnClickListener {
                searchMode = false
                searchQuery = ""
                profileCapsule.removeAllViews()
                profileCapsule.background = GradientDrawable().apply {
                    setColor(Color.argb(115, 255, 255, 255))
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), Color.argb(180, 255, 255, 255))
                }
                profileCapsule.addView(
                    profile,
                    android.widget.FrameLayout.LayoutParams(dp(34), dp(34)).apply {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        leftMargin = dp(6)
                    }
                )
                profileCapsule.addView(
                    contactTitle,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        dp(48)
                    ).apply {
                        leftMargin = dp(46)
                        rightMargin = dp(46)
                    }
                )
                filterVisibleMessages()
            }
        }
        profileCapsule.addView(
            close,
            android.widget.FrameLayout.LayoutParams(dp(44), dp(48)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        )

        searchInput.requestFocus()
        searchInput.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun filterVisibleMessages() {
        if (!::messagesContainer.isInitialized) return

        val query = searchQuery.trim()
        val count = messagesContainer.childCount

        if (query.isEmpty()) {
            for (i in 0 until count) {
                messagesContainer.getChildAt(i).visibility = View.VISIBLE
            }
            return
        }

        // Filter message rows by their message body.
        for (i in 0 until count) {
            val child = messagesContainer.getChildAt(i)
            val bodyText = messageBodyText(
                child.findViewWithTag<View>("message_body")
            )

            if (bodyText != null) {
                child.visibility = if (
                    bodyText.contains(query, ignoreCase = true)
                ) View.VISIBLE else View.GONE
            }
        }

        // Only toggle explicitly tagged date separators.
        for (i in 0 until count) {
            val child = messagesContainer.getChildAt(i)
            if (child.tag != "date_header_row") continue

            var hasMatch = false
            var j = i + 1

            while (j < count) {
                val next = messagesContainer.getChildAt(j)
                if (next.tag == "date_header_row") break

                val bodyText = messageBodyText(
                    next.findViewWithTag<View>("message_body")
                )
                if (bodyText != null && next.visibility == View.VISIBLE) {
                    hasMatch = true
                    break
                }
                j++
            }

            child.visibility = if (hasMatch) View.VISIBLE else View.GONE
        }
    }

    private var isPopulatingConversation = false

    private fun loadConversation() {
        isPopulatingConversation = true
        messagesContainer.removeAllViews()
        lastMessageDate = null
        lastTimeView = null
        lastDateHeaderKey = null

        if (activeMeshId != null) {
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
            filterVisibleMessages()
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
        filterVisibleMessages()
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
                text = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(date))
                textSize = 12f
                setTextColor(Color.rgb(90, 90, 95))
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(5), dp(14), dp(5))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(230, 230, 234))
                    cornerRadius = dp(18).toFloat()
                }
            }
            val dateHeaderRow = LinearLayout(this).apply {
                tag = "date_header_row"
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(8))
                addView(dateHeader, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            messagesContainer.addView(dateHeaderRow)
            lastDateHeaderKey = dayKey
        }

        val previousDate = lastMessageDate
        val closeToPrevious = previousDate != null &&
            date - previousDate < 60_000L

        if (closeToPrevious) {
            lastTimeView?.visibility = View.GONE
        }

        val bubble = LinearLayout(this).apply {
            tag = "message_bubble"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(1))
            background = MessageBubbleDrawable(
                if (isDarkThemeEnabled()) {
                    if (incoming) Color.rgb(48, 48, 54)
                    else Color.rgb(0, 95, 220)
                } else {
                    if (incoming) Color.rgb(232, 232, 237)
                    else Color.rgb(64, 201, 198)
                },
                incoming
            )
        }

        val emojiAssetPath = emojiAnimationAsset(body)
        val compactSingleLine =
            emojiAssetPath == null &&
            !body.contains('\n') &&
            body.length <= 18 &&
            body.none { it == '\u200d' || it == '\ufe0f' }
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
                    if (isDarkThemeEnabled()) Color.WHITE else Color.BLACK
                )
                maxWidth = (resources.displayMetrics.widthPixels * 0.66f).toInt()
                autoLinkMask = Linkify.WEB_URLS or Linkify.PHONE_NUMBERS
                movementMethod = LinkMovementMethod.getInstance()
                if (compactSingleLine) {
                    setPadding(dp(14), dp(2), dp(6), dp(2))
                } else {
                    setPadding(dp(14), dp(8), dp(10), dp(2))
                }
            }

            bubble.addView(
                messageText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
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

        wrapper.addView(
            bubble,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

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
            if (compactSingleLine) {
                bubble.orientation = LinearLayout.HORIZONTAL
                bubble.gravity = Gravity.BOTTOM
            }
            val metaRow = LinearLayout(this).apply {
                tag = "message_meta_row"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or
                    if (compactSingleLine) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
                if (compactSingleLine) {
                    setPadding(0, 0, dp(8), 0)
                } else {
                    setPadding(dp(10), 0, dp(8), dp(4))
                }
                visibility = View.GONE
                alpha = 0f
                translationY = dp(4).toFloat()
            }
            metaRow.addView(
                time,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(16)
                )
            )

            if (!incoming && messageId == null && activeMeshId == null) {
                val tickColor = if (delivered) {
                    Color.rgb(0, 122, 255)
                } else {
                    secondaryText
                }

                val statusIcon = LinearLayout(this).apply {
                    tag = "status_icon"
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    contentDescription = if (delivered) "Delivered" else "Sent"

                    addView(
                        android.widget.ImageView(this@ConversationActivity).apply {
                            setImageResource(R.drawable.ic_message_tick)
                            setColorFilter(tickColor)
                            contentDescription = if (delivered) "Delivered" else "Sent"
                        },
                        LinearLayout.LayoutParams(dp(16), dp(16))
                    )

                    if (delivered) {
                        addView(
                            android.widget.ImageView(this@ConversationActivity).apply {
                                setImageResource(R.drawable.ic_message_tick)
                                setColorFilter(tickColor)
                                contentDescription = "Delivered"
                            },
                            LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                                leftMargin = dp(-8)
                            }
                        )
                    }
                }

                metaRow.addView(
                    statusIcon,
                    LinearLayout.LayoutParams(
                        dp(if (delivered) 24 else 16),
                        dp(16)
                    ).apply {
                        leftMargin = dp(2)
                    }
                )
            }

            if (!incoming && messageId != null) {
                val sendingIcon = TextView(this).apply {
                    tag = "sending_icon"
                    text = "◷"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(225, 240, 255))
                    contentDescription = "Sending"
                }
                metaRow.addView(
                    sendingIcon,
                    LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                        leftMargin = dp(2)
                    }
                )
            }

            wrapper.addView(
                metaRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(16)
                ).apply {
                    topMargin = -dp(16)
                    gravity = if (incoming) Gravity.START else Gravity.END
                }
            )

            var metadataVisible = false
            bubble.isClickable = true
            bubble.setOnClickListener {
                metadataVisible = !metadataVisible

                bubble.animate().cancel()
                metaRow.animate().cancel()

                if (metadataVisible) {
                    metaRow.visibility = View.VISIBLE
                    metaRow.alpha = 0f
                    metaRow.translationY = dp(4).toFloat()

                    bubble.animate()
                        .translationY(-dp(16).toFloat())
                        .setDuration(220)
                        .setInterpolator(DecelerateInterpolator())
                        .start()

                    metaRow.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    bubble.animate()
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(DecelerateInterpolator())
                        .start()

                    metaRow.animate()
                        .alpha(0f)
                        .translationY(dp(4).toFloat())
                        .setDuration(180)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            if (!metadataVisible) {
                                metaRow.visibility = View.GONE
                            }
                        }
                        .start()
                }
            }

            val bodyText = bubble.findViewWithTag<TextView>("message_body")
            bodyText?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                bubble.post {
                    val shape = bubble.background as? MessageBubbleDrawable
                        ?: return@post
                    shape.setCompact(compactSingleLine)
                }
            }
        } else {
            // Keep the animated-emoji layout and its timestamp behavior unchanged.
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

        messagesContainer.addView(wrapper)

        // پیام جدید انیمیشن خودش را اجرا می‌کند؛ تاریخچه جداگانه اجرا می‌شود.
        if (!isPopulatingConversation) {
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

        if (activeMeshId != null) {
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
