package com.majidhajizade.messages

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable

class NewMessageActivity : Activity() {

    private lateinit var phoneEditText: EditText
    private lateinit var messageEditText: EditText
    private lateinit var contactsContainer: LinearLayout

    companion object {
        private const val SMS_PERMISSION_REQUEST = 1001
        private const val CONTACTS_PERMISSION_REQUEST = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val existingPhone = intent.getStringExtra("phone")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = TextView(this).apply {
            text = "‹"
            textSize = 38f
            setTextColor(Color.rgb(0, 122, 255))
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }

        header.addView(
            back,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        val title = TextView(this).apply {
            text = "New Message"
            textSize = 20f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        root.addView(header)

        phoneEditText = EditText(this).apply {
            hint = "Phone number"
            textSize = 17f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_PHONE

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
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val contactsTitle = TextView(this).apply {
            text = "Contacts"
            textSize = 16f
            setTextColor(Color.rgb(100, 100, 105))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(6), 0, dp(6))
        }

        root.addView(contactsTitle)

        val contactsScroll = ScrollView(this).apply {
            isFillViewport = false
        }

        contactsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        contactsScroll.addView(
            contactsContainer,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            contactsScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }

        val messageBackground = GradientDrawable().apply {
            setColor(Color.rgb(242, 242, 247))
            cornerRadius = dp(24).toFloat()
        }

        messageEditText = EditText(this).apply {
            hint = "Message"
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
            minLines = 1
            maxLines = 4
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(16), dp(8), dp(52), dp(8))
            background = messageBackground
        }

        val messageFrame = android.widget.FrameLayout(this)

        messageFrame.addView(
            messageEditText,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        val sendButton = TextView(this).apply {
            text = "↑"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)

            background = GradientDrawable().apply {
                setColor(Color.rgb(0, 95, 220))
                shape = GradientDrawable.OVAL
            }

            setOnClickListener {
                sendSms()
            }
        }

        messageFrame.addView(
            sendButton,
            android.widget.FrameLayout.LayoutParams(
                dp(42),
                dp(42),
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                rightMargin = dp(5)
            }
        )

        composer.addView(
            messageFrame,
            LinearLayout.LayoutParams(
                0,
                dp(56),
                1f
            )
        )

        root.addView(composer)

        setContentView(root)

        if (existingPhone.isNullOrBlank()) {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                loadContacts()
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.READ_CONTACTS),
                    CONTACTS_PERMISSION_REQUEST
                )
            }
        }
    }

    private fun loadContacts() {
        contactsContainer.removeAllViews()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        )

        val seen = mutableSetOf<String>()

        cursor?.use {
            val nameIndex =
                it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex =
                it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val name = it.getString(nameIndex)?.trim().orEmpty()
                val number = it.getString(numberIndex)?.trim().orEmpty()

                if (number.isEmpty() || !seen.add(number)) {
                    continue
                }

                addContact(name.ifEmpty { number }, number)
            }
        }

        if (contactsContainer.childCount == 0) {
            val empty = TextView(this).apply {
                text = "No contacts with phone numbers"
                textSize = 15f
                setTextColor(Color.rgb(120, 120, 125))
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(20))
            }

            contactsContainer.addView(
                empty,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(70)
                )
            )
        }
    }

    private fun addContact(name: String, number: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))

            setOnClickListener {
                phoneEditText.setText(number)
                phoneEditText.setSelection(phoneEditText.length())
            }
        }

        val nameView = TextView(this).apply {
            text = name
            textSize = 17f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }

        val numberView = TextView(this).apply {
            text = number
            textSize = 14f
            setTextColor(Color.rgb(110, 110, 115))
            setPadding(0, dp(2), 0, 0)
        }

        row.addView(nameView)
        row.addView(numberView)

        contactsContainer.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(62)
            )
        )

        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(230, 230, 235))
        }

        contactsContainer.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        )
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

            startActivity(
                Intent(this, ConversationActivity::class.java).apply {
                    putExtra("phone", phone)
                }
            )
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

        when (requestCode) {
            CONTACTS_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    loadContacts()
                }
            }

            SMS_PERMISSION_REQUEST -> {
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
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
