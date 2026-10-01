package com.majidhajizade.messages

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        phone = intent.getStringExtra("phone") ?: run {
            finish()
            return
        }

        setContentView(createScreen())
        loadConversation()
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
            setBackgroundColor(Color.WHITE)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(24), dp(12), dp(10))
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 38f
            setTextColor(blue)
            gravity = Gravity.CENTER

            setOnClickListener {
                finish()
            }
        }

        header.addView(
            back,
            LinearLayout.LayoutParams(dp(50), dp(50))
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
            LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            )
        )

        root.addView(header)

        scrollView = ScrollView(this).apply {
            isFillViewport = true
        }

        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
            setPadding(dp(6), dp(6), dp(6), dp(6))

            background = GradientDrawable().apply {
                setColor(Color.rgb(242, 242, 247))
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), Color.rgb(225, 225, 230))
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
            LinearLayout.LayoutParams(dp(46), dp(46))
        )

        messageInput = EditText(this).apply {
            hint = "Message"
            textSize = 16f
            setSingleLine(false)
            minLines = 1
            maxLines = 4
            minHeight = dp(46)
            setPadding(dp(8), dp(7), dp(8), dp(7))
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
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD

            background = GradientDrawable().apply {
                setColor(Color.rgb(0, 95, 220))
                shape = GradientDrawable.OVAL
            }

            elevation = dp(3).toFloat()

            setOnClickListener {
                sendMessage()
            }
        }

        composer.addView(
            send,
            LinearLayout.LayoutParams(dp(46), dp(46))
        )

        val composerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(12), dp(6), dp(12), dp(10))
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
        incoming: Boolean
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
                    if (incoming) {
                        Color.rgb(242, 242, 247)
                    } else {
                        blue
                    }
                )
                cornerRadius = dp(18).toFloat()
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

        try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)

            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    phone,
                    null,
                    message,
                    null,
                    null
                )
            } else {
                smsManager.sendMultipartTextMessage(
                    phone,
                    null,
                    parts,
                    null,
                    null
                )
            }

            messageInput.text.clear()

            Toast.makeText(
                this,
                "SMS sent",
                Toast.LENGTH_SHORT
            ).show()

            messagesContainer.postDelayed({
                loadConversation()
            }, 500)

        } catch (e: Exception) {
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
