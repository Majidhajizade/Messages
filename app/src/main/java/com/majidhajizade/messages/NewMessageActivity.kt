package com.majidhajizade.messages

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class NewMessageActivity : Activity() {

    companion object {
        private const val PICK_CONTACT = 4101
        private const val PHONE_STATE_PERMISSION = 3004
        private const val PREFS = "messages_settings"
    }

    private lateinit var sheet: LinearLayout
    private lateinit var numberInput: EditText
    private lateinit var selectedName: TextView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton

    private var selectedPhone: String? = null
    private var selectedSmsSlot = 0
    private var hasExplicitSmsSlot = false
    private var simColorAnimator: ValueAnimator? = null
    private var closing = false

    private val green = Color.rgb(50, 205, 50)
    private val premiumBlue = Color.rgb(0, 95, 220)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun buttonColor(): Int =
        if (selectedSmsSlot == 1) premiumBlue else green

    private fun pill(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        getSharedPreferences(PREFS, MODE_PRIVATE).apply {
            selectedSmsSlot = getInt("selected_sms_slot", 0).coerceIn(0, 1)
            hasExplicitSmsSlot = getBoolean("selected_sms_slot_explicit", false)
        }

        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.WHITE
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            setOnClickListener { closeSheet() }
        }

        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(
                    dp(27).toFloat(), dp(27).toFloat(),
                    dp(27).toFloat(), dp(27).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            elevation = dp(12).toFloat()
            clipChildren = false
            clipToPadding = false
            setOnClickListener { /* Keep taps inside the sheet. */ }
        }

        buildHeader()
        buildRecipientRow()
        buildComposer()

        root.addView(
            sheet,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM
            ).apply {
                topMargin = dp(96)
            }
        )

        setContentView(root)
        installSheetDragGestures(sheet)

        var imeWasVisible = false
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val imeBottom = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.ime()
            ).bottom
            val imeVisible = imeBottom > 0

            if (view.paddingBottom != imeBottom) {
                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    imeBottom
                )
            }

            imeWasVisible = imeVisible
            insets
        }

        root.post {
            androidx.core.view.ViewCompat.requestApplyInsets(root)
        }

        root.post {
            val statusInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.decorView.rootWindowInsets
                    ?.getInsets(WindowInsets.Type.statusBars())?.top ?: dp(24)
            } else {
                dp(24)
            }

            (sheet.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.topMargin = statusInset + dp(72)
                sheet.layoutParams = params
            }

            sheet.post {
                sheet.translationY = sheet.height.toFloat().coerceAtLeast(root.height.toFloat())
                sheet.animate()
                    .translationY(0f)
                    .setDuration(380L)
                    .setInterpolator(
                        android.view.animation.PathInterpolator(
                            0.22f, 1f, 0.36f, 1f
                        )
                    )
                    .start()
            }
        }

        // بازکردن خودکار کیبورد برای نوشتن پیام؛
        // اگر صفحه با شماره مشخص باز شده باشد، رفتار قبلی حفظ می‌شود.
        if (intent.getStringExtra("phone").isNullOrBlank()) {
            sheet.postDelayed({
                if (!isFinishing && !closing) {
                    messageInput.requestFocus()

                    val imm = getSystemService(
                        android.content.Context.INPUT_METHOD_SERVICE
                    ) as android.view.inputmethod.InputMethodManager

                    imm.showSoftInput(
                        messageInput,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
                    )
                }
            }, 500L)
        }

        // Preserve the existing direct-open behavior if a phone was supplied.
        intent.getStringExtra("phone")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { phone ->
                openConversation(phone, null)
            }
    }

    private fun buildHeader() {
        val header = FrameLayout(this)

         header.addView(
            TextView(this).apply {
                text = "New Message"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(25, 25, 28))
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
                Gravity.CENTER
            )
        )

        // Soft white circular close button, aligned with the title.
        val closeButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_cross_custom)
            contentDescription = "Close sheet"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(242, 242, 245))
            }
            elevation = dp(3).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { closeSheet() }
        }

        header.addView(
            closeButton,
            FrameLayout.LayoutParams(
                dp(40),
                dp(40),
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                marginEnd = dp(5)
            }
        )

        sheet.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )
    }

    private fun buildRecipientRow() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.rgb(232, 232, 236))
            }
            setPadding(dp(13), dp(5), dp(6), dp(5))
        }

        row.addView(
            TextView(this).apply {
                text = "To:"
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.rgb(45, 45, 50))
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40)
            )
        )

        val recipientPill = FrameLayout(this).apply {
            background = pill(Color.rgb(241, 241, 245), 19)
            setPadding(dp(12), 0, dp(12), 0)
        }

        numberInput = EditText(this).apply {
            hint = "Number"
            textSize = 16f
            setTextColor(Color.rgb(35, 35, 40))
            setHintTextColor(Color.rgb(135, 135, 142))
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            background = null
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }

        selectedName = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(0, 122, 255))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            setOnClickListener {
                selectedPhone = null
                visibility = View.GONE
                numberInput.visibility = View.VISIBLE
                numberInput.setText("")
                numberInput.requestFocus()
            }
        }

        recipientPill.addView(
            numberInput,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            )
        )
        recipientPill.addView(
            selectedName,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            )
        )

        row.addView(
            recipientPill,
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(10)
                rightMargin = dp(8)
            }
        )

        val chooseContact = ImageButton(this).apply {
            setImageResource(R.drawable.ic_plus_chat)
            contentDescription = "Choose contact"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = pill(Color.rgb(235, 235, 239), 24)
            setColorFilter(Color.rgb(55, 55, 60))
            setOnClickListener { pickContact() }
        }

        row.addView(
            chooseContact,
            LinearLayout.LayoutParams(dp(40), dp(40))
        )

        sheet.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply {
                topMargin = dp(8)
            }
        )
    }

    private fun buildComposer() {
        val spacer = View(this)
        sheet.addView(
            spacer,
            LinearLayout.LayoutParams(1, 0, 1f)
        )

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }

         val messageCapsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(Color.rgb(245, 245, 248), 25)
            setPadding(dp(12), dp(4), dp(5), dp(4))
        }

        messageInput = EditText(this).apply {
            hint = "Message"
            textSize = 16f
            setTextColor(Color.BLACK)
            setHintTextColor(Color.rgb(140, 140, 148))
            setSingleLine(false)
            minLines = 1
            maxLines = 4
            minHeight = dp(40)
            setPadding(dp(2), dp(5), dp(4), dp(5))
            background = null
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }

        messageCapsule.addView(
            messageInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        sendButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send_arrow)
            contentDescription = "Send message; hold to change SIM"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(buttonColor())
                setStroke(dp(2), buttonColor())
            }
            elevation = dp(1).toFloat()

            setOnClickListener { sendDraft() }
            setOnLongClickListener {
                toggleSmsSlot()
                true
            }
        }

        messageCapsule.addView(
            sendButton,
            LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                leftMargin = dp(4)
            }
        )

        composer.addView(
            messageCapsule,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(6)
            }
        )

        sheet.addView(
            composer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )
    }

    private fun installSheetDragGestures(view: View) {
        var startRawY = 0f
        var startTranslationY = 0f
        var draggingDown = false

        val dragListener = View.OnTouchListener { touched, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startRawY = event.rawY
                    startTranslationY = sheet.translationY
                    draggingDown = true
                    false
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!draggingDown) {
                        false
                    } else {
                        val deltaY = event.rawY - startRawY
                        if (deltaY > dp(4)) {
                            sheet.translationY =
                                (startTranslationY + deltaY).coerceAtLeast(0f)
                        }
                        false
                    }
                }

                android.view.MotionEvent.ACTION_UP -> {
                    if (draggingDown) {
                        val deltaY = event.rawY - startRawY
                        draggingDown = false

                        if (deltaY > sheet.height * 0.22f) {
                            closeSheet()
                        } else {
                            sheet.animate()
                                .translationY(0f)
                                .setDuration(220L)
                                .setInterpolator(
                                    android.view.animation.DecelerateInterpolator()
                                )
                                .start()
                        }
                    }
                    false
                }

                android.view.MotionEvent.ACTION_CANCEL -> {
                    draggingDown = false
                    sheet.animate()
                        .translationY(0f)
                        .setDuration(180L)
                        .start()
                    false
                }

                else -> false
            }
        }

        fun attachRecursively(target: View) {
            // کنترل‌های ورودی و دکمه‌ها باید همچنان لمس عادی داشته باشند.
            if (target !is EditText &&
                target !is ImageButton &&
                target !is ImageView
            ) {
                target.setOnTouchListener(dragListener)
            }

            if (target is ViewGroup) {
                for (i in 0 until target.childCount) {
                    attachRecursively(target.getChildAt(i))
                }
            }
        }

        attachRecursively(view)
    }

    private fun pickContact() {
        try {
            startActivityForResult(
                Intent(
                    Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                ),
                PICK_CONTACT
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Contacts app is unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_CONTACT || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use

                val nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )

                val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""

                if (number.isNotBlank()) {
                    selectedPhone = number.trim()
                    selectedName.text = name.ifBlank { number }
                    numberInput.visibility = View.GONE
                    selectedName.visibility = View.VISIBLE
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Could not read selected contact", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendDraft() {
        val recipient = selectedPhone ?: numberInput.text.toString().trim()
        val message = messageInput.text.toString().trim()

        if (recipient.isBlank()) {
            numberInput.visibility = View.VISIBLE
            selectedName.visibility = View.GONE
            numberInput.error = "Enter a number or choose a contact"
            numberInput.requestFocus()
            return
        }

        if (message.isBlank()) return

        val intent = Intent(this, ConversationActivity::class.java)
            .putExtra("phone", recipient)
            .putExtra("initial_message", message)

        startActivity(intent)
        overridePendingTransition(0, 0)
        finish()
    }

    @Suppress("MissingPermission")
    private fun toggleSmsSlot() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                PHONE_STATE_PERMISSION
            )
            return
        }

        val active = try {
            SubscriptionManager.from(this).activeSubscriptionInfoList ?: emptyList()
        } catch (_: Exception) {
            return
        }

        // Keep the selection and button color unchanged when SIM 2 is absent.
        if (active.none { it.simSlotIndex == 1 }) return

        val target = if (selectedSmsSlot == 1) 0 else 1
        if (active.none { it.simSlotIndex == target }) return

        if (target == selectedSmsSlot && hasExplicitSmsSlot) return

        selectedSmsSlot = target
        hasExplicitSmsSlot = true

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("selected_sms_slot", selectedSmsSlot)
            .putBoolean("selected_sms_slot_explicit", true)
            .apply()

        animateSendButtonColor()
    }

    private fun animateSendButtonColor() {
        val bg = sendButton.background as? GradientDrawable ?: return
        simColorAnimator?.cancel()

        val target = buttonColor()
        val start = if (selectedSmsSlot == 1) green else premiumBlue

        simColorAnimator = ValueAnimator.ofArgb(start, target).apply {
            duration = 420L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val color = it.animatedValue as Int
                bg.setColor(color)
                bg.setStroke(dp(2), color)
            }
            start()
        }

        sendButton.animate().cancel()
        sendButton.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(140L)
            .withEndAction {
                sendButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PHONE_STATE_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            toggleSmsSlot()
        }
    }

    private fun openConversation(phone: String, message: String?) {
        val intent = Intent(this, ConversationActivity::class.java)
            .putExtra("phone", phone)
        if (!message.isNullOrBlank()) {
            intent.putExtra("initial_message", message)
        }
        startActivity(intent)
        finish()
    }

    private fun closeSheet() {
        if (closing) return
        closing = true

        sheet.animate().cancel()
        sheet.animate()
            .translationY(sheet.height.toFloat())
            .setDuration(240L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                finish()
                overridePendingTransition(0, 0)
            }
            .start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        closeSheet()
    }
}
