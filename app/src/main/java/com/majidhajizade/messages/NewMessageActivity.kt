package com.majidhajizade.messages

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.telephony.SmsManager
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class NewMessageActivity : Activity() {

    private lateinit var phoneEditText: EditText
    private lateinit var messageEditText: EditText

    companion object {
        private const val SMS_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val existingPhone = intent.getStringExtra("phone")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val cancel = TextView(this).apply {
            text = "‹  Back"
            textSize = 17f
            setTextColor(Color.rgb(0, 122, 255))
            gravity = Gravity.CENTER_VERTICAL

            setOnClickListener {
                finish()
            }
        }

        val title = TextView(this).apply {
            text = if (existingPhone.isNullOrBlank()) {
                "New Message"
            } else {
                existingPhone
            }
            textSize = 17f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        header.addView(
            cancel,
            LinearLayout.LayoutParams(dp(85), dp(48))
        )

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        root.addView(header)

        phoneEditText = EditText(this).apply {
            hint = "Phone number"
            textSize = 17f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_PHONE

            if (!existingPhone.isNullOrBlank()) {
                setText(existingPhone)
                isEnabled = false
            }
        }

        root.addView(
            phoneEditText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        messageEditText = EditText(this).apply {
            hint = "Message"
            textSize = 17f
            gravity = Gravity.TOP
            minLines = 4
            inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        root.addView(
            messageEditText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(130)
            )
        )

        val sendButton = TextView(this).apply {
            text = "Send"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(0, 122, 255))
            setPadding(dp(20), dp(10), dp(20), dp(10))

            setOnClickListener {
                sendSms()
            }
        }

        root.addView(
            sendButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply {
                topMargin = dp(16)
            }
        )

        setContentView(root)
    }

    private fun sendSms() {
        val phone = phoneEditText.text.toString().trim()
        val message = messageEditText.text.toString().trim()

        if (phone.isEmpty()) {
            phoneEditText.error = "Enter phone number"
            phoneEditText.requestFocus()
            return
        }

        if (message.isEmpty()) {
            messageEditText.error = "Enter message"
            messageEditText.requestFocus()
            return
        }

        if (checkSelfPermission(Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.SEND_SMS),
                SMS_PERMISSION_REQUEST
            )
            return
        }

        sendSmsNow(phone, message)
    }

    private fun sendSmsNow(phone: String, message: String) {
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

            Toast.makeText(
                this,
                "SMS sent",
                Toast.LENGTH_SHORT
            ).show()

            val intent = Intent(this, ConversationActivity::class.java)
            intent.putExtra("phone", phone)
            startActivity(intent)
            finish()

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

        if (requestCode == SMS_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                sendSms()
            } else {
                Toast.makeText(
                    this,
                    "SMS permission is required",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
