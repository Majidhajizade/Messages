package com.majidhajizade.messages

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

    companion object {
        private const val SEND_PERMISSION = 3001
        private const val PICK_CONTACT = 3002
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

        setContentView(createScreen())

        val filter = IntentFilter("com.majidhajizade.messages.SMS_SENT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                smsSentReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(smsSentReceiver, filter)
        }

        loadConversation()
    }

    override fun onDestroy() {
        runCatching {
            unregisterReceiver(smsSentReceiver)
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

    private fun createScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 245, 247))
        }

        val header = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(24), 0, dp(8))
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 38f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER

            setOnClickListener {
                finish()
            }
        }

        header.addView(
            back,
            android.widget.FrameLayout.LayoutParams(dp(42), dp(50)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = dp(2)
            }
        )

        val title = TextView(this).apply {
            text = phone
            textSize = 18f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        header.addView(
            title,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            )
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(82)
            )
        )

        scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 245, 247))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        scrollView.addView(messagesContainer)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
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
            textSize = 30f
            setTextColor(Color.rgb(0, 95, 220))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
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
                composer.animate()
                    .translationY(-dp(3).toFloat())
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                composer.animate()
                    .translationY(0f)
                    .setDuration(180L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        val send = TextView(this).apply {
            text = "↑"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD

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

        val composerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(12), dp(6), dp(12), dp(8))
        }

        root.addView(composer, composerParams)

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

        if (success) {
            wrapper.findViewWithTag<View>("failed_row")?.let {
                val parent = it.parent as? LinearLayout
                parent?.removeView(it)
            }
            return
        }

        if (wrapper.findViewWithTag<View>("failed_row") != null) {
            return
        }

        val row = LinearLayout(this).apply {
            tag = "failed_row"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = TextView(this).apply {
            text = "!"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD

            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
            }
        }

        row.addView(
            icon,
            LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                setMargins(0, 0, dp(6), 0)
            }
        )

        val bubble = wrapper.getChildAt(0)
        wrapper.removeView(bubble)

        row.addView(
            bubble,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        wrapper.addView(row, 0)
    }

    private fun loadConversation() {
        messagesContainer.removeAllViews()

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

    private fun addMessage(
        body: String,
        date: Long,
        incoming: Boolean,
        messageId: String? = null
    ) {
        val bubble = TextView(this).apply {
            text = body
            textSize = 16f
            maxWidth = (resources.displayMetrics.widthPixels * 0.66f).toInt()
            setTextColor(
                if (incoming) Color.BLACK else Color.WHITE
            )
            setPadding(
                dp(14),
                dp(9),
                dp(14),
                dp(9)
            )

            background = GradientDrawable().apply {
                setColor(
                    if (incoming) Color.rgb(232, 232, 237) else Color.rgb(174, 205, 255)
                )
                cornerRadius = dp(22).toFloat()
            }

            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val drawable = background as? GradientDrawable
                    ?: return@addOnLayoutChangeListener

                drawable.cornerRadius = if (lineCount <= 1) {
                    view.height / 2f
                } else {
                    dp(18).toFloat()
                }
            }
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (incoming) {
                Gravity.START
            } else {
                Gravity.END
            }
            setPadding(0, dp(4), 0, dp(4))

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
        }

        wrapper.addView(
            time,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        messagesContainer.addView(wrapper)
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
            scrollView.fullScroll(View.FOCUS_DOWN)
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

            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)

            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    normalizedPhone,
                    null,
                    message,
                    sentPendingIntent,
                    null
                )
            } else {
                val sentIntents = ArrayList<PendingIntent>()

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
                }

                smsManager.sendMultipartTextMessage(
                    normalizedPhone,
                    null,
                    parts,
                    sentIntents,
                    null
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == SEND_PERMISSION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            sendMessage()
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
