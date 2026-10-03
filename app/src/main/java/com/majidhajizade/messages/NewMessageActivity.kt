package com.majidhajizade.messages

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
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
    private lateinit var recipientInputContainer: LinearLayout
    private lateinit var recipientSearch: EditText

    private var selectedContact: String? = null

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
            setPadding(dp(16), dp(36), dp(16), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = TextView(this).apply {
            text = "‹  Back"
            textSize = 17f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL

            setOnClickListener {
                finish()
            }
        }

        val title = TextView(this).apply {
            text = "New Message"
            textSize = 18f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        header.addView(
            back,
            android.widget.LinearLayout.LayoutParams(dp(85), dp(48))
        )

        header.addView(
            title,
            android.widget.LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        header.addView(
            TextView(this),
            android.widget.LinearLayout.LayoutParams(dp(85), dp(48))
        )

        root.addView(
            header,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        val recipientHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val recipientTitle = TextView(this).apply {
            text = "Recipient"
            textSize = 18f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }

        val plus = TextView(this).apply {
            text = "+"
            textSize = 28f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setOnClickListener {
                showContacts()
            }
        }

        recipientHeader.addView(
            recipientTitle,
            android.widget.LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
            )
        )

        recipientHeader.addView(
            plus,
            android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
        )

        root.addView(
            recipientHeader,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(12)
            }
        )

        recipientInputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        phoneEditText = EditText(this).apply {
            hint = "Phone number"
            textSize = 17f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_PHONE
            setPadding(dp(4), 0, dp(4), 0)

            if (!existingPhone.isNullOrBlank()) {
                setText(existingPhone)
                isEnabled = false
            }
        }

        recipientInputContainer.addView(
            phoneEditText,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        recipientSearch = EditText(this).apply {
            hint = "Search contacts"
            textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            visibility = View.GONE
            setPadding(dp(16), 0, dp(16), 0)

            background = roundedBackground(
                Color.rgb(245, 245, 247),
                24
            )

            addTextChangedListener(
                object : android.text.TextWatcher {
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
                        filterContacts(s?.toString().orEmpty())
                    }

                    override fun afterTextChanged(
                        s: android.text.Editable?
                    ) = Unit
                }
            )
        }

        recipientInputContainer.addView(
            recipientSearch,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(6)
            }
        )

        root.addView(
            recipientInputContainer,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        messageEditText = EditText(this).apply {
            hint = "Message"
            textSize = 17f
            gravity = Gravity.TOP
            minLines = 4
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }

        root.addView(
            messageEditText,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(130)
            ).apply {
                topMargin = dp(8)
            }
        )

        val sendButton = TextView(this).apply {
            text = "Send"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackground(
                roundedBackground(
                    Color.rgb(0, 122, 255),
                    28
                )
            )
            setPadding(dp(20), dp(10), dp(20), dp(10))

            setOnClickListener {
                sendSms()
            }
        }

        root.addView(
            sendButton,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply {
                topMargin = dp(16)
            }
        )

        contactsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedBackground(
                Color.WHITE,
                24
            )
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }

        root.addView(
            contactsContainer,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(14)
            }
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        scroll.addView(
            root,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(scroll)

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS),
                CONTACTS_PERMISSION_REQUEST
            )
        }
    }

    private fun showContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS),
                CONTACTS_PERMISSION_REQUEST
            )
            return
        }

        recipientSearch.visibility = View.VISIBLE
        contactsContainer.visibility = View.VISIBLE
        recipientSearch.requestFocus()

        filterContacts("")
    }

    private fun filterContacts(query: String) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        contactsContainer.removeAllViews()

        val normalizedQuery = query.trim().lowercase()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->

            val seen = mutableSetOf<String>()

            val nameIndex = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val numberIndex = cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val number = cursor.getString(numberIndex) ?: continue

                if (!seen.add(number)) continue

                if (
                    normalizedQuery.isNotEmpty() &&
                    !name.lowercase().contains(normalizedQuery) &&
                    !number.contains(normalizedQuery)
                ) {
                    continue
                }

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))

                    setOnClickListener {
                        selectContact(name, number)
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
                    setTextColor(Color.GRAY)
                }

                row.addView(
                    nameView,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(28)
                    )
                )

                row.addView(
                    numberView,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(24)
                    )
                )

                contactsContainer.addView(
                    row,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(58)
                    )
                )
            }
        }
    }

    private fun selectContact(name: String, number: String) {
        selectedContact = number
        phoneEditText.setText(number)
        phoneEditText.isEnabled = false

        recipientSearch.visibility = View.GONE
        contactsContainer.visibility = View.GONE

        val capsule = TextView(this).apply {
            text = "$name   ×"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            background = roundedBackground(
                Color.rgb(232, 232, 235),
                22
            )

            setOnClickListener {
                selectedContact = null
                phoneEditText.setText("")
                phoneEditText.isEnabled = true
                visibility = View.GONE
                phoneEditText.visibility = View.VISIBLE
            }
        }

        phoneEditText.visibility = View.GONE

        recipientInputContainer.addView(
            capsule,
            0,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )
    }

    private fun sendSms() {
        val phone = phoneEditText.text.toString().trim()
        val message = messageEditText.text.toString().trim()

        if (phone.isEmpty()) {
            phoneEditText.error = "Enter recipient"
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

        if (requestCode == CONTACTS_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            showContacts()
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
