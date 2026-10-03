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
            setOnClickListener { finish() }
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
            android.widget.LinearLayout.LayoutParams(0, dp(48), 1f)
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

        val recipientBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.rgb(247, 247, 249), 18)
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }

        val recipientRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val recipientLabel = TextView(this).apply {
            text = "Recipient"
            textSize = 17f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }

        val plusButton = TextView(this).apply {
            text = "+"
            textSize = 28f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        recipientRow.addView(
            recipientLabel,
            android.widget.LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            )
        )

        recipientRow.addView(
            plusButton,
            android.widget.LinearLayout.LayoutParams(dp(48), dp(52))
        )

        recipientBox.addView(
            recipientRow,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )

        phoneEditText = EditText(this).apply {
            hint = "Phone number or contact name"
            textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(4), 0, dp(4), 0)

            if (!existingPhone.isNullOrBlank()) {
                setText(existingPhone)
                isEnabled = false
            }
        }

        recipientBox.addView(
            phoneEditText,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        contactsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundedBackground(Color.WHITE, 18)
            elevation = dp(3).toFloat()
            visibility = View.GONE
        }

        val contactsScroll = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
        }

        contactsScroll.addView(
            contactsContainer,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        recipientBox.addView(
            contactsScroll,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(6)
            }
        )

        plusButton.setOnClickListener {
            if (contactsScroll.visibility == View.VISIBLE) {
                contactsScroll.visibility = View.GONE
                contactsContainer.visibility = View.GONE
                plusButton.text = "+"
            } else {
                showContacts(contactsScroll, plusButton)
            }
        }

        root.addView(
            recipientBox,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(108)
            ).apply {
                topMargin = dp(12)
            }
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
                GradientDrawable().apply {
                    setColor(Color.rgb(0, 122, 255))
                    cornerRadius = dp(28).toFloat()
                    setStroke(dp(1), Color.rgb(218, 218, 223))
                }
            )
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { sendSms() }
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

        val fixedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fixedContent.addView(
            header,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        setContentView(root)

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS),
                CONTACTS_PERMISSION_REQUEST
            )
        }
    }

    private fun showContacts(
        scroll: ScrollView,
        plusButton: TextView
    ) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS),
                CONTACTS_PERMISSION_REQUEST
            )
            return
        }

        scroll.visibility = View.VISIBLE
        contactsContainer.visibility = View.VISIBLE
        plusButton.text = "×"

        filterContacts(phoneEditText.text.toString())
    }

    private fun filterContacts(query: String) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        contactsContainer.removeAllViews()

        val q = query.trim().lowercase()

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
                    q.isNotEmpty() &&
                    !name.lowercase().contains(q) &&
                    !number.contains(q)
                ) continue

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(6), dp(12), dp(6))

                    setOnClickListener {
                        phoneEditText.setText(number)
                        phoneEditText.setSelection(phoneEditText.length())
                        scroll.visibility = View.GONE
                        contactsContainer.visibility = View.GONE
                        plusButton.text = "+"
                    }
                }

                row.addView(
                    TextView(this).apply {
                        text = name
                        textSize = 16f
                        setTextColor(Color.BLACK)
                        typeface = Typeface.DEFAULT_BOLD
                    },
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(27)
                    )
                )

                row.addView(
                    TextView(this).apply {
                        text = number
                        textSize = 14f
                        setTextColor(Color.GRAY)
                    },
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(23)
                    )
                )

                contactsContainer.addView(
                    row,
                    android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(54)
                    )
                )
            }
        }
    }

    private fun plusButtonAfterSelection(
        scroll: ScrollView,
        plusButton: TextView
    ) {
        scroll.visibility = View.GONE
        contactsContainer.visibility = View.GONE
        plusButton.text = "+"
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
