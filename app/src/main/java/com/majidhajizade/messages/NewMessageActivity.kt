package com.majidhajizade.messages

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class NewMessageActivity : Activity() {

    private lateinit var contactsContainer: LinearLayout
    private lateinit var searchInput: EditText

    companion object {
        private const val CONTACTS_PERMISSION_REQUEST = 1002
        private val PAGE_BG = Color.rgb(239, 239, 242)
        private val TEXT_COLOR = Color.rgb(28, 28, 30)
        private val MUTED_COLOR = Color.rgb(125, 125, 130)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = PAGE_BG
        window.navigationBarColor = PAGE_BG
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val existingPhone = intent.getStringExtra("phone")
        if (!existingPhone.isNullOrBlank()) {
            openConversation(existingPhone)
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAGE_BG)
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }

        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back_custom)
            contentDescription = "Back"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { finish() }
        }

        header.addView(
            back,
            LinearLayout.LayoutParams(dp(44), dp(52))
        )

        header.addView(
            TextView(this).apply {
                text = "New message"
                textSize = 20f
                setTextColor(TEXT_COLOR)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ).apply {
                leftMargin = dp(8)
            }
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(76)
            )
        )

        val contentScroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(12))
        }

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(Color.WHITE, 32)
            setPadding(dp(16), 0, dp(16), 0)
        }

        val searchIcon = TextView(this).apply {
            text = "⌕"
            textSize = 27f
            setTextColor(MUTED_COLOR)
            gravity = Gravity.CENTER
        }

        searchBox.addView(
            searchIcon,
            LinearLayout.LayoutParams(dp(30), dp(52))
        )

        searchInput = EditText(this).apply {
            hint = "Search Contacts"
            textSize = 16f
            setTextColor(TEXT_COLOR)
            setHintTextColor(MUTED_COLOR)
            setSingleLine(true)
            background = null
            setPadding(dp(6), 0, dp(4), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }

        searchBox.addView(
            searchInput,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            )
        )

        content.addView(
            searchBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply {
                bottomMargin = dp(14)
            }
        )

        val actionsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, 20)
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }

        actionsCard.addView(
            actionRow("Enter Number", "＋") {
                showEnterNumberDialog()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        )

        actionsCard.addView(
            divider(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                leftMargin = dp(48)
            }
        )

        actionsCard.addView(
            actionRow("New Group", "♧") {
                Toast.makeText(
                    this,
                    "Group messaging is not available yet",
                    Toast.LENGTH_SHORT
                ).show()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        )

        content.addView(
            actionsCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
            }
        )

        val contactsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(Color.WHITE, 20)
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }

        contactsCard.addView(
            TextView(this).apply {
                text = "All contacts"
                textSize = 16f
                setTextColor(TEXT_COLOR)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(2), dp(2), dp(2), dp(10))
            }
        )

        contactsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        contactsCard.addView(
            contactsContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(
            contactsCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        contentScroll.addView(
            content,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            contentScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        searchInput.addTextChangedListener(object : TextWatcher {
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
                loadContacts(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        if (
            checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS),
                CONTACTS_PERMISSION_REQUEST
            )
        } else {
            loadContacts()
        }
    }

    private fun actionRow(
        label: String,
        symbol: String,
        action: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }

            val icon = TextView(this@NewMessageActivity).apply {
                text = symbol
                textSize = 24f
                setTextColor(TEXT_COLOR)
                gravity = Gravity.CENTER
            }

            addView(
                icon,
                LinearLayout.LayoutParams(dp(42), dp(48))
            )

            addView(
                TextView(this@NewMessageActivity).apply {
                    text = label
                    textSize = 16f
                    setTextColor(TEXT_COLOR)
                    gravity = Gravity.CENTER_VERTICAL
                },
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    leftMargin = dp(6)
                }
            )
        }
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(Color.rgb(235, 235, 238))
        }
    }

    private fun loadContacts(query: String = "") {
        if (
            checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        contactsContainer.removeAllViews()

        val normalizedQuery = query.trim().lowercase()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI
        )

        val seen = mutableSetOf<String>()

        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val photoIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI
                )

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val number = cursor.getString(numberIndex) ?: continue
                    val photoUri = if (photoIndex >= 0) {
                        cursor.getString(photoIndex)
                    } else {
                        null
                    }

                    if (!seen.add(number)) continue

                    if (
                        normalizedQuery.isNotEmpty() &&
                        !name.lowercase().contains(normalizedQuery) &&
                        !number.lowercase().contains(normalizedQuery)
                    ) continue

                    contactsContainer.addView(
                        contactRow(name, number, photoUri),
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(68)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Keep the screen usable if the contacts provider is unavailable.
        }

        if (contactsContainer.childCount == 0) {
            contactsContainer.addView(
                TextView(this).apply {
                    text = if (normalizedQuery.isEmpty()) {
                        "No contacts found"
                    } else {
                        "No matching contacts"
                    }
                    textSize = 14f
                    setTextColor(MUTED_COLOR)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(24))
                }
            )
        }
    }

    private fun contactRow(
        name: String,
        number: String,
        photoUri: String?
    ): View {
        val avatarSize = dp(52)

        val avatarContainer = android.widget.FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                avatarGradientColors(name)
            ).apply {
                shape = GradientDrawable.OVAL
            }
            clipChildren = true
            clipToPadding = true
        }

        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = name
            clipToOutline = true

            if (!photoUri.isNullOrBlank()) {
                try {
                    contentResolver.openInputStream(Uri.parse(photoUri))?.use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream)
                        if (bitmap != null) setImageBitmap(bitmap)
                    }
                } catch (_: Exception) {
                    // Use the initial when the photo cannot be loaded.
                }
            }
        }

        if (avatar.drawable != null) {
            avatar.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
            }
            avatarContainer.addView(
                avatar,
                android.widget.FrameLayout.LayoutParams(
                    avatarSize,
                    avatarSize
                )
            )
        } else {
            avatarContainer.addView(
                TextView(this).apply {
                    text = name.trim().firstOrNull()
                        ?.uppercaseChar()?.toString() ?: "U"
                    textSize = 21f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        avatarGradientColors(name)
                    ).apply {
                        shape = GradientDrawable.OVAL
                    }
                    contentDescription = name
                },
                android.widget.FrameLayout.LayoutParams(
                    avatarSize,
                    avatarSize
                )
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { openConversation(number) }

            addView(
                avatarContainer,
                LinearLayout.LayoutParams(avatarSize, avatarSize)
            )

            val details = LinearLayout(this@NewMessageActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)

                addView(
                    TextView(this@NewMessageActivity).apply {
                        text = name
                        textSize = 16f
                        setTextColor(TEXT_COLOR)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                )

                addView(
                    TextView(this@NewMessageActivity).apply {
                        text = number
                        textSize = 14f
                        setTextColor(MUTED_COLOR)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                )
            }

            addView(
                details,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }
    }

    private fun showEnterNumberDialog() {
        val input = EditText(this).apply {
            hint = "Phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val container = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(54)
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter Number")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val phone = input.text.toString().trim()
                if (phone.isBlank()) {
                    input.error = "Enter a phone number"
                    return@setOnClickListener
                }
                dialog.dismiss()
                openConversation(phone)
            }
        }

        dialog.show()
    }

    private fun openConversation(phone: String) {
        startActivity(
            Intent(this, ConversationActivity::class.java)
                .putExtra("phone", phone.trim())
        )
    }

    private fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
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

        val first = name.trim().firstOrNull()?.uppercaseChar()
            ?: return colors[0]

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

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CONTACTS_PERMISSION_REQUEST) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                loadContacts(searchInput.text.toString())
            } else {
                Toast.makeText(
                    this,
                    "Contacts permission denied",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
