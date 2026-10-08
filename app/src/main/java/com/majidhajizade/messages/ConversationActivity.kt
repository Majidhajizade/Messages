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
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationActivity : Activity() {

    private lateinit var messagesContainer: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var scrollView: ScrollView

    private val blue = Color.rgb(0, 122, 255)
    private val secondaryText = Color.rgb(110, 110, 115)

    private lateinit var phone: String
    private lateinit var offlineMeshManager: OfflineMeshManager

    companion object {
        private const val SEND_PERMISSION = 3001
        private const val PICK_CONTACT = 3002
        private const val NEARBY_PERMISSION = 3003
        private const val ACTION_DELIVERED = "com.majidhajizade.messages.SMS_DELIVERED"

        @Volatile
        var activeMeshId: String? = null
            private set
    }

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
            val messageId = intent?.getStringExtra("message_id")
            val success = resultCode == Activity.RESULT_OK

            if (messageId != null) {
                updateMessageStatus(messageId, success)
                if (success) { saveSentMessage(intent?.getStringExtra("message") ?: return); loadConversation() }

                val errorText = when (resultCode) {
                    Activity.RESULT_OK -> "SMS sent successfully"
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic failure"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio is off"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service"
                    SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS limit exceeded"
                    else -> "SMS error code: $resultCode"
                }

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

        phone = intent.getStringExtra("phone") ?: run {
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
        val deliveredFilter = IntentFilter(ACTION_DELIVERED)

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
                )
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
        val currentChatId =
            phone.trim().uppercase(Locale.US)

        if (
            MeshSession.activeChatId
                ?.equals(currentChatId, ignoreCase = true) == true
        ) {
            MeshSession.activeMessageHandler = null
            MeshSession.activeChatId = null
        }

        runCatching {
            unregisterReceiver(smsSentReceiver)
        }
        runCatching {
            unregisterReceiver(smsDeliveredReceiver)
        }

        if (activeMeshId == phone.trim().uppercase(Locale.US)) {
            activeMeshId = null
        }

        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        if (::messagesContainer.isInitialized &&
            checkSelfPermission(Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED
        ) {
            loadConversation()
        }
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

    private fun createScreen(): View {
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(245, 245, 247))
        }

        val header = android.widget.FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 255, 255, 255))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.rgb(225, 225, 230))
            }
            elevation = dp(4).toFloat()
            setPadding(dp(8), dp(28), dp(8), dp(8))
        }

        val backCapsule = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), Color.rgb(225, 225, 230))
            }
            elevation = dp(2).toFloat()
            setOnClickListener {
                finish()
            }
        }

        val backCircle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back_arrow)
            contentDescription = "Back"
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                shape = GradientDrawable.OVAL
            }
        }

        backCapsule.addView(
            backCircle,
            LinearLayout.LayoutParams(dp(38), dp(38))
        )

        header.addView(
            backCapsule,
            android.widget.FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = dp(2)
            }
        )

        val contactName = getContactDisplayName(phone)

        val profileCapsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), 0, dp(12), 0)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), Color.rgb(225, 225, 230))
            }
            elevation = dp(2).toFloat()
        }

        val profile = TextView(this).apply {
            text = contactName.firstOrNull()?.uppercase() ?: "?"
            textSize = 17f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.rgb(232, 232, 234))
                shape = GradientDrawable.OVAL
            }
        }

        profileCapsule.addView(
            profile,
            LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                rightMargin = dp(8)
            }
        )

        profileCapsule.addView(
            TextView(this).apply {
                text = contactName
                textSize = 16f
                setTextColor(Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(
                dp(150),
                dp(38)
            )
        )

        header.addView(
            profileCapsule,
            android.widget.FrameLayout.LayoutParams(
                dp(210),
                dp(48)
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        val actionsCapsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), Color.rgb(225, 225, 230))
            }
            elevation = dp(2).toFloat()
        }

        if (!phone.matches(Regex("MJ-[A-Z0-9]{6}"))) {
            val callButton = ImageButton(this).apply {
                setImageResource(R.drawable.ic_call)
                setColorFilter(Color.BLACK)
                contentDescription = "Call"
                background = null
                scaleType = android.widget.ImageView.ScaleType.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))

                setOnClickListener {
                    val dialIntent = Intent(
                        Intent.ACTION_DIAL,
                        Uri.parse("tel:${Uri.encode(phone)}")
                    )
                    startActivity(dialIntent)
                }
            }

            actionsCapsule.addView(
                callButton,
                LinearLayout.LayoutParams(dp(38), dp(38))
            )
        }

        val moreButton = TextView(this).apply {
            text = "⋮"
            textSize = 27f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = "More"
        }

        actionsCapsule.addView(
            moreButton,
            LinearLayout.LayoutParams(dp(38), dp(38))
        )

        header.addView(
            actionsCapsule,
            android.widget.FrameLayout.LayoutParams(
                dp(82),
                dp(48)
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = dp(2)
            }
        )

        scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 245, 247))
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
                dp(82)
            ).apply {
                gravity = Gravity.TOP
            }
        )

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(28).toFloat()
            }
        }

        val addContact = TextView(this).apply {
            text = "+"
            textSize = 28f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            background = null
            setPadding(0, 0, 0, dp(2))

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
            setTextColor(Color.BLACK)
            setHintTextColor(Color.rgb(145, 145, 150))
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

            elevation = dp(2).toFloat()

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

        if (success) {
            bubble.findViewWithTag<View>("failed_icon")?.let {
                bubble.removeView(it)
            }

            if (bubble.findViewWithTag<View>("status_icon") == null) {
                val icon = TextView(this).apply {
                    tag = "status_icon"
                    text = "✓"
                    textSize = 13f
                    setTextColor(secondaryText)
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                }

                bubble.addView(
                    icon,
                    0,
                    LinearLayout.LayoutParams(dp(20), dp(22)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setMargins(dp(7), 0, dp(2), 0)
                    }
                )
            }

            return
        }

        if (bubble.findViewWithTag<View>("failed_icon") != null) {
            return
        }

        val icon = TextView(this).apply {
            tag = "failed_icon"
            text = "!"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
            }
        }

        bubble.addView(
            icon,
            0,
            LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(dp(8), 0, dp(7), 0)
            }
        )
    }

    private fun updateMessageDelivered(messageId: String) {
        val wrapper =
            messagesContainer.findViewWithTag<LinearLayout>(messageId)
                ?: return

        val bubble =
            wrapper.findViewWithTag<LinearLayout>("message_bubble")
                ?: return

        val icon =
            bubble.findViewWithTag<TextView>("status_icon")
                ?: return

        icon.text = "✓"
        icon.setTextColor(blue)
    }

    private fun loadConversation() {
        messagesContainer.removeAllViews()

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

            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
            return
        }

        if (checkSelfPermission(Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val projection = arrayOf(
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.ADDRESS
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

        cursor?.use {
            val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIndex = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)

            while (it.moveToNext()) {
                val body = it.getString(bodyIndex) ?: ""
                val date = it.getLong(dateIndex)
                val type = it.getInt(typeIndex)

                addMessage(
                    body = body,
                    date = date,
                    incoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX
                )
            }
        }

        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private var lastMessageDate: Long? = null
    private var lastTimeView: TextView? = null

    private fun addMessage(
        body: String,
        date: Long,
        incoming: Boolean,
        messageId: String? = null
    ) {
        val previousDate = lastMessageDate
        val closeToPrevious = previousDate != null &&
            date - previousDate < 60_000L

        if (closeToPrevious) {
            lastTimeView?.visibility = View.GONE
        }

        val bubble = LinearLayout(this).apply {
            tag = "message_bubble"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            background = GradientDrawable().apply {
                setColor(
                    if (incoming) Color.rgb(232, 232, 237)
                    else Color.rgb(64, 201, 198)
                )
                cornerRadius = dp(22).toFloat()
            }
        }

        val animatedEmoji = isAnimatedEmoji(body)

        val messageText = TextView(this).apply {
            text = body
            textSize = if (animatedEmoji) 42f else 16f
            setTextColor(Color.BLACK)
            maxWidth = (resources.displayMetrics.widthPixels * 0.66f).toInt()
            setPadding(
                if (animatedEmoji) dp(8) else dp(14),
                if (animatedEmoji) dp(4) else dp(7),
                if (animatedEmoji) dp(8) else dp(14),
                if (animatedEmoji) dp(4) else dp(7)
            )
        }

        if (animatedEmoji) {
            bubble.background = null
            animateEmoji(messageText, body)
        }

        bubble.addView(
            messageText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val wrapper = LinearLayout(this).apply {
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
            text = SimpleDateFormat(
                "HH:mm",
                Locale.getDefault()
            ).format(Date(date))
            textSize = 11f
            setTextColor(secondaryText)
            setPadding(dp(4), dp(2), dp(4), 0)
            gravity = if (incoming) Gravity.END else Gravity.START
        }

        wrapper.addView(
            time,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        messagesContainer.addView(wrapper)

        lastMessageDate = date
        lastTimeView = time
    }

    private fun isAnimatedEmoji(body: String): Boolean {
        return body == "❤️" ||
            body == "😂" ||
            body == "🔥" ||
            body == "🎉"
    }

    private fun animateEmoji(view: TextView, emoji: String) {
        view.pivotX = view.width / 2f
        view.pivotY = view.height / 2f

        view.post {
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f

            when (emoji) {
                "❤️" -> {
                    ValueAnimator.ofFloat(1f, 1.18f, 1f).apply {
                        duration = 650
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            val scale = it.animatedValue as Float
                            view.scaleX = scale
                            view.scaleY = scale
                        }
                        start()
                    }
                }

                "😂" -> {
                    ValueAnimator.ofFloat(-4f, 4f, -4f, 0f).apply {
                        duration = 500
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            view.translationX = it.animatedValue as Float
                        }
                        start()
                    }
                }

                "🔥" -> {
                    ValueAnimator.ofFloat(0.92f, 1.08f, 0.96f, 1.04f, 1f).apply {
                        duration = 700
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            val scale = it.animatedValue as Float
                            view.scaleX = scale
                            view.scaleY = scale
                        }
                        start()
                    }
                }

                "🎉" -> {
                    ValueAnimator.ofFloat(-8f, 8f, -5f, 5f, 0f).apply {
                        duration = 800
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            view.rotation = it.animatedValue as Float
                        }
                        start()
                    }
                }
            }
        }
    }

    private fun saveSentMessage(message: String) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, phone)
            put(Telephony.Sms.BODY, message)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            put(Telephony.Sms.READ, 1)
        }

        runCatching {
            contentResolver.insert(
                Telephony.Sms.CONTENT_URI,
                values
            )
        }
    }

    private fun sendMessage() {
        val message = messageInput.text.toString().trim()

        if (message.isEmpty()) {
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
            val normalizedPhone = normalizePhoneNumber(phone)

            val sentIntent = Intent("com.majidhajizade.messages.SMS_SENT").apply {
                setPackage(packageName)
                putExtra("message_id", messageId)
            putExtra("message", message)
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }

            val sentPendingIntent = PendingIntent.getBroadcast(
                this,
                messageId.hashCode(),
                sentIntent,
                flags
            )

            val deliveredIntent = Intent(ACTION_DELIVERED).apply {
                setPackage(packageName)
                putExtra("message_id", messageId)
            }

            val deliveredPendingIntent = PendingIntent.getBroadcast(
                this,
                messageId.hashCode() + 100000,
                deliveredIntent,
                flags
            )

            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)

            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    normalizedPhone,
                    null,
                    message,
                    sentPendingIntent,
                    deliveredPendingIntent
                )
            } else {
                val sentIntents = ArrayList<PendingIntent>()
                val deliveryIntents = ArrayList<PendingIntent>()

                parts.forEachIndexed { index, _ ->
                    val partIntent = Intent(
                        "com.majidhajizade.messages.SMS_SENT"
                    ).apply {
                        setPackage(packageName)
                        putExtra("message_id", messageId)
                        putExtra("message", message)
                    }

                    sentIntents.add(
                        PendingIntent.getBroadcast(
                            this,
                            messageId.hashCode() + index + 1,
                            partIntent,
                            flags
                        )
                    )

                    val partDeliveredIntent = Intent(ACTION_DELIVERED).apply {
                        setPackage(packageName)
                        putExtra("message_id", messageId)
                    }

                    deliveryIntents.add(
                        PendingIntent.getBroadcast(
                            this,
                            messageId.hashCode() + 100001 + index,
                            partDeliveredIntent,
                            flags
                        )
                    )
                }

                smsManager.sendMultipartTextMessage(
                    normalizedPhone,
                    null,
                    parts,
                    sentIntents,
                    deliveryIntents
                )
            }

        } catch (e: Exception) {
            updateMessageStatus(messageId, false)

            Toast.makeText(
                this,
                "SMS failed: ${e.message ?: "Unknown error"}",
                Toast.LENGTH_LONG
            ).show()
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
