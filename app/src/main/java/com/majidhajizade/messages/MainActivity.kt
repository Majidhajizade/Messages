package com.majidhajizade.messages

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.app.role.RoleManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

    private val blue = Color.rgb(0, 122, 255)
    private val premiumBlue = Color.rgb(0, 95, 220)
    private val deleteRed = Color.rgb(235, 55, 65)
    private val background = Color.WHITE
    private val secondaryText = Color.rgb(110, 110, 115)

    private lateinit var messagesContainer: LinearLayout
    private lateinit var messagesScroll: android.widget.ScrollView


    private var homeHeader: View? = null
    private var homeTitle: View? = null
    private var composeButtonView: View? = null
    private val rowViews = mutableMapOf<String, TextView>()
    private val selectedAddresses = linkedSetOf<String>()
    private var selectionMode = false
    private lateinit var selectionHeader: LinearLayout
    private lateinit var selectionSelectedText: TextView
    private lateinit var selectionAllButton: TextView
    private lateinit var selectionActionBar: LinearLayout
    private lateinit var homeSearch: EditText



    companion object {
        private const val SMS_PERMISSION_REQUEST = 2001
        private const val PREFS = "messages_settings"
        private const val PINNED = "pinned_numbers"
        private const val BLOCKED = "blocked_numbers"
        private const val MUTED = "muted_numbers"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        setContentView(createHomeScreen())

        requestDefaultSmsRole()
        requestContactsPermission()
        requestNotificationPermission()
    }

    private fun requestDefaultSmsRole() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)

            if (roleManager == null) {
                Toast.makeText(
                    this,
                    "RoleManager unavailable",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            if (!roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                Toast.makeText(
                    this,
                    "SMS role unavailable on this device",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                Toast.makeText(
                    this,
                    "Messages is already default SMS",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS),
                4001
            )
        }
    }

    override fun onResume() {
        super.onResume()

        if (checkSelfPermission(Manifest.permission.READ_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            loadMessages()
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_CONTACTS
                ),
                SMS_PERMISSION_REQUEST
            )
        }
    }

    override fun onBackPressed() {
        if (selectionMode || selectionHeader.visibility == View.VISIBLE) {
            exitSelectionMode()
            return
        }

        super.onBackPressed()
    }

    private fun createHomeScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }

        selectionHeader = createSelectionHeader()
        selectionHeader.visibility = View.GONE

        selectionActionBar = createSelectionActionBar()
        selectionActionBar.visibility = View.GONE

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        homeHeader = header

        val title = TextView(this).apply {
            text = "Messages"
            textSize = 34f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            translationY = dp(13).toFloat()
        }

        homeTitle = title

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val composeButton = TextView(this).apply {
            text = "+"
            textSize = 30f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = null
            elevation = 0f

            setOnClickListener {
                openNewMessage()
            }
        }

        composeButtonView = composeButton

        header.addView(
            composeButton,
            LinearLayout.LayoutParams(dp(50), dp(50))
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(72)
            )
        )

        root.addView(
            selectionHeader,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64)
            ).apply {
                bottomMargin = dp(0)
            }
        )

        val searchBackground = GradientDrawable().apply {
            setColor(Color.rgb(242, 242, 247))
            cornerRadius = dp(23).toFloat()
        }

        homeSearch = EditText(this).apply {
            visibility = View.GONE
            alpha = 0f
            translationY = 0f
            hint = "Search"
            textSize = 16f
            setSingleLine(true)
            setTextColor(Color.BLACK)
            setHintTextColor(secondaryText)
            setPadding(dp(18), 0, dp(18), 0)
            background = searchBackground
        }

        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            clipToOutline = false
            setPadding(0, dp(4), 0, 0)
        }
        messagesScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.WHITE)

            val scrollContent = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
            }

            scrollContent.addView(
                messagesContainer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                scrollContent,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

        }
        var lastScrollY = 0

        messagesScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (!selectionMode) {
                val delta = scrollY - lastScrollY
                lastScrollY = scrollY

                if (delta < 0 && scrollY > 0) {
                    homeSearch.visibility = View.VISIBLE
                    homeSearch.animate()
                        .alpha(1f)
                        .setDuration(220L)
                        .start()
                } else if (delta > 0) {
                    homeSearch.animate()
                        .alpha(0f)
                        .setDuration(220L)
                        .withEndAction {
                            if (!selectionMode) {
                                homeSearch.visibility = View.GONE
                            }
                        }
                        .start()
                }
            }
        }

        val chatLayer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        chatLayer.addView(
            messagesScroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        chatLayer.addView(
            selectionActionBar,
            FrameLayout.LayoutParams(
                dp(270),
                dp(52)
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
        )

        selectionActionBar.bringToFront()

        root.addView(
            homeSearch,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                topMargin = dp(6)
                bottomMargin = dp(8)
            }
        )

        root.addView(
            chatLayer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        return root
    }

    private fun loadMessages() {
        messagesContainer.removeAllViews()
        rowViews.clear()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )

        val cursor: Cursor? = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )

        val conversations = mutableMapOf<String, ConversationData>()

        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(
                    it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                ) ?: "Unknown"

                if (conversations.containsKey(address)) {
                    continue
                }

                val body = it.getString(
                    it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                ) ?: ""

                val date = it.getLong(
                    it.getColumnIndexOrThrow(Telephony.Sms.DATE)
                )

                val type = it.getInt(
                    it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                )

                conversations[address] = ConversationData(
                    address,
                    body,
                    date,
                    type
                )
            }
        }

        if (conversations.isEmpty()) {
            showEmptyState()
            return
        }

        val pinned = getPinnedNumbers()

        conversations.values
            .sortedWith(
                compareByDescending<ConversationData> {
                    pinned.contains(it.address)
                }.thenByDescending {
                    it.date
                }
            )
            .forEach {
                addConversation(
                    it.address,
                    it.body,
                    it.date,
                    it.type
                )
            }
    }


    private fun requestContactsPermission() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS),
                5001
            )
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                5002
            )
        }
    }

    private fun getContactName(phone: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val uri = android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI
            .buildUpon()
            .appendPath(phone)
            .build()

        return contentResolver.query(
            uri,
            arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun showEmptyState() {
        val empty = TextView(this).apply {
            text = "No Messages"
            textSize = 20f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(80), 0, 0)
        }

        messagesContainer.addView(
            empty,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(120)
            )
        )
    }

    private fun addConversation(
        address: String,
        body: String,
        date: Long,
        type: Int
    ) {
        val actionLayer = FrameLayout(this).apply {
            tag = address
        }

        val actionBackground = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val callAction = TextView(this).apply {
            text = "Call"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = solidDrawable(Color.TRANSPARENT, 0f)
        }

        val deleteAction = TextView(this).apply {
            text = "Delete"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = solidDrawable(Color.TRANSPARENT, 0f)
        }

        actionBackground.addView(
            callAction,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        actionBackground.addView(
            deleteAction,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        actionLayer.addView(
            actionBackground,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(76)
            )
        )

        val foreground = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = null
            setPadding(0, dp(8), 0, dp(8))
            clipToOutline = false
            elevation = 0f
        }

        val contactFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                rightMargin = dp(12)
            }
        }

        val contactName = getContactName(address)
        val avatarLetter = contactName
            ?.trim()
            ?.firstOrNull()
            ?.uppercaseChar()
            ?.toString()
            ?: "U"

        val contact = TextView(this).apply {
            text = avatarLetter
            textSize = 21f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.rgb(232, 232, 234))
                shape = GradientDrawable.OVAL
            }
        }

        contactFrame.addView(
            contact,
            FrameLayout.LayoutParams(dp(52), dp(52))
        )

        val selectionOverlay = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.rgb(105, 105, 110))
                shape = GradientDrawable.OVAL
            }
        }

        selectionOverlay.tag = 1001

        contactFrame.addView(
            selectionOverlay,
            FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(0)
                bottomMargin = dp(0)
            }
        )

        selectionOverlay.bringToFront()

        foreground.addView(contactFrame)

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val name = TextView(this).apply {
            text = contactName ?: address
            textSize = 17f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }

        val preview = TextView(this).apply {
            text = body
            textSize = 15f
            setTextColor(secondaryText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        topRow.addView(
            name,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val dateText = TextView(this).apply {
            text = formatShortDate(date)
            textSize = 13f
            setTextColor(Color.rgb(165, 165, 170))
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(0, 0, 0, 0)
        }

        topRow.addView(
            dateText,
            LinearLayout.LayoutParams(
                dp(54),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        textContainer.addView(topRow)
        textContainer.addView(preview)

        foreground.addView(
            textContainer,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        actionLayer.addView(
            foreground,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(76)
            )
        )

        callAction.setOnClickListener {
            callNumber(address)
        }

        deleteAction.setOnClickListener {
            deleteConversation(address)
        }

        setupRowTouch(
            actionLayer,
            foreground,
            address,
            callAction,
            deleteAction,
            selectionOverlay
        )

        messagesContainer.addView(
            actionLayer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(76)
            ).apply {
                leftMargin = 0
                rightMargin = 0
                topMargin = 0
                bottomMargin = 0
            }
        )

    }

    private fun setupRowTouch(
        container: FrameLayout,
        foreground: View,
        address: String,
        callAction: TextView,
        deleteAction: TextView,
        selectionOverlay: TextView
    ) {
        val callBackground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
        }

        val deleteBackground = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
        }

        callAction.background = callBackground
        deleteAction.background = deleteBackground
        callAction.setTextColor(Color.TRANSPARENT)
        deleteAction.setTextColor(Color.TRANSPARENT)
        var downX = 0f
        var downY = 0f
        var startTranslation = 0f
        var moved = false
        var longPressed = false

        val handler = android.os.Handler(mainLooper)

        val longPressRunnable = Runnable {
            if (!moved && !selectionMode) {
                longPressed = true
                foreground.performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS
                )
                enterSelectionMode(address)
            }
        }

        foreground.setOnTouchListener { _, event ->
            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTranslation = foreground.translationX
                    moved = false
                    longPressed = false

                    handler.postDelayed(longPressRunnable, 500)

                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    if (abs(dx) > dp(10) || abs(dy) > dp(10)) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }

                    if (abs(dx) > abs(dy)) {
                        val translation =
                            (startTranslation + dx)
                                .coerceIn(-dp(110).toFloat(), dp(110).toFloat())

                        foreground.translationX = translation

                        val progress =
                            (abs(translation) / dp(110).toFloat())
                                .coerceIn(0f, 1f)

                        val alpha = (progress * 140f).toInt()

                        if (translation > 0f) {
                            callBackground.setColor(
                                Color.argb(
                                    alpha,
                                    Color.red(blue),
                                    Color.green(blue),
                                    Color.blue(blue)
                                )
                            )
                            callAction.setTextColor(
                                Color.argb(alpha, Color.WHITE, Color.WHITE, Color.WHITE)
                            )

                            deleteBackground.setColor(Color.TRANSPARENT)
                            deleteAction.setTextColor(Color.TRANSPARENT)
                        } else if (translation < 0f) {
                            deleteBackground.setColor(
                                Color.argb(
                                    alpha,
                                    Color.red(deleteRed),
                                    Color.green(deleteRed),
                                    Color.blue(deleteRed)
                                )
                            )
                            deleteAction.setTextColor(
                                Color.argb(alpha, Color.WHITE, Color.WHITE, Color.WHITE)
                            )

                            callBackground.setColor(Color.TRANSPARENT)
                            callAction.setTextColor(Color.TRANSPARENT)
                        } else {
                            callBackground.setColor(Color.TRANSPARENT)
                            deleteBackground.setColor(Color.TRANSPARENT)
                            callAction.setTextColor(Color.TRANSPARENT)
                            deleteAction.setTextColor(Color.TRANSPARENT)
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)

                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    if (selectionMode && !longPressed && !moved) {
                        toggleSelection(address)
                        foreground.animate()
                            .translationX(0f)
                            .setDuration(180)
                            .start()
                        return@setOnTouchListener true
                    }

                    if (!longPressed && !moved) {
                        openConversation(address)
                    } else if (!longPressed && dx < -dp(70)) {
                        deleteConversation(address)
                    } else if (!longPressed && dx > dp(70)) {
                        callNumber(address)
                    }

                    foreground.animate()
                        .translationX(0f)
                        .setDuration(180)
                        .start()

                    callBackground.setColor(Color.TRANSPARENT)
                    deleteBackground.setColor(Color.TRANSPARENT)
                    callAction.setTextColor(Color.TRANSPARENT)
                    deleteAction.setTextColor(Color.TRANSPARENT)

                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    foreground.animate()
                        .translationX(0f)
                        .setDuration(180)
                        .start()

                    callBackground.setColor(Color.TRANSPARENT)
                    deleteBackground.setColor(Color.TRANSPARENT)
                    callAction.setTextColor(Color.TRANSPARENT)
                    deleteAction.setTextColor(Color.TRANSPARENT)
                    true
                }

                else -> true
            }
        }
    }

    private fun createSelectionHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, 0)
        }

        val allContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        selectionAllButton = TextView(this).apply {
            text = "○"
            textSize = 28f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT
            setOnClickListener {
                if (rowViews.isEmpty()) return@setOnClickListener

                if (selectedAddresses.size == rowViews.size) {
                    selectedAddresses.clear()
                } else {
                    selectedAddresses.clear()
                    selectedAddresses.addAll(rowViews.keys)
                }

                updateSelectionUI()
            }
        }

        allContainer.addView(
            selectionAllButton,
            LinearLayout.LayoutParams(dp(38), dp(34))
        )

        allContainer.addView(
            TextView(this).apply {
                text = "All"
                textSize = 13f
                setTextColor(Color.rgb(35, 35, 38))
                gravity = Gravity.CENTER
                translationY = dp(-4).toFloat()
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(dp(38), dp(22))
        )

        header.addView(
            allContainer,
            LinearLayout.LayoutParams(dp(58), dp(46))
        )

        selectionSelectedText = TextView(this).apply {
            text = "1 Selected"
            textSize = 19f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            selectionSelectedText,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            ).apply {
                leftMargin = dp(8)
            }
        )

        val cancel = TextView(this).apply {
            text = "Cancel"
            textSize = 17f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setOnClickListener {
                exitSelectionMode()
            }
        }

        header.addView(
            cancel,
            LinearLayout.LayoutParams(dp(78), dp(46))
        )

        return header
    }

    private fun createSelectionActionBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(39).toFloat()
                setStroke(dp(1), Color.rgb(225, 225, 230))
            }
            elevation = dp(16).toFloat()
        }

        bar.addView(
            createSelectionAction(0, "Notifications") {
                toggleNotifications()
            },
            LinearLayout.LayoutParams(0, dp(38), 1f)
        )

        bar.addView(
            createSelectionAction(1, "Delete") {
                deleteSelected()
            },
            LinearLayout.LayoutParams(0, dp(38), 1f)
        )

        bar.addView(
            createSelectionAction(2, "Pin") {
                pinSelected()
            },
            LinearLayout.LayoutParams(0, dp(38), 1f)
        )

        return bar
    }

    private fun createSelectionAction(
        iconType: Int,
        label: String,
        action: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { action() }

            addView(
                SelectionIconView(this@MainActivity, iconType),
                LinearLayout.LayoutParams(
                    dp(26),
                    dp(25)
                )
            )

            addView(
                TextView(this@MainActivity).apply {
                    text = label
                    textSize = 10.5f
                    setTextColor(Color.rgb(35, 35, 38))
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(17)
                )
            )
        }
    }

    private class SelectionIconView(
        context: android.content.Context,
        private val type: Int
    ) : View(context) {

        private val paint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dpLocal(2f)
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f

            when (type) {
                0 -> {
                    // Bell
                    val path = android.graphics.Path()
                    path.moveTo(cx - 9f, h * .62f)
                    path.lineTo(cx - 7f, h * .58f)
                    path.lineTo(cx - 7f, h * .39f)
                    path.quadTo(cx - 7f, h * .18f, cx, h * .16f)
                    path.quadTo(cx + 7f, h * .18f, cx + 7f, h * .39f)
                    path.lineTo(cx + 7f, h * .58f)
                    path.lineTo(cx + 9f, h * .62f)
                    path.close()
                    canvas.drawPath(path, paint)
                    canvas.drawLine(cx - 3f, h * .72f, cx + 3f, h * .72f, paint)
                    canvas.drawCircle(cx, h * .77f, 1.8f, paint)
                }

                1 -> {
                    // Trash
                    canvas.drawRoundRect(
                        cx - 7f, h * .30f, cx + 7f, h * .78f,
                        2.5f, 2.5f, paint
                    )
                    canvas.drawLine(cx - 9f, h * .24f, cx + 9f, h * .24f, paint)
                    canvas.drawLine(cx - 4f, h * .18f, cx + 4f, h * .18f, paint)
                    canvas.drawLine(cx - 3f, h * .39f, cx - 3f, h * .68f, paint)
                    canvas.drawLine(cx + 3f, h * .39f, cx + 3f, h * .68f, paint)
                }

                2 -> {
                    // Angled pin
                    val path = android.graphics.Path()
                    path.moveTo(cx - 7f, h * .24f)
                    path.lineTo(cx + 5f, h * .36f)
                    path.lineTo(cx + 2f, h * .48f)
                    path.lineTo(cx + 7f, h * .53f)
                    path.lineTo(cx + 3f, h * .70f)
                    path.lineTo(cx - 1f, h * .48f)
                    path.lineTo(cx - 10f, h * .40f)
                    path.close()
                    canvas.drawPath(path, paint)
                    canvas.drawLine(cx + 1f, h * .70f, cx - 7f, h * .88f, paint)
                }
            }
        }

        private fun dpLocal(value: Float): Float {
            return value * resources.displayMetrics.density
        }
    }

    private fun enterSelectionMode(address: String) {
        selectionMode = true
        selectedAddresses.clear()
        selectedAddresses.add(address)

        homeHeader?.visibility = View.GONE
        homeSearch.visibility = View.GONE

        updateSelectionUI()
    }

    private fun toggleSelection(address: String) {
        if (!selectionMode) return

        if (selectedAddresses.contains(address)) {
            selectedAddresses.remove(address)
        } else {
            selectedAddresses.add(address)
        }

        if (selectedAddresses.isEmpty()) {
            exitSelectionMode()
        } else {
            updateSelectionUI()
        }
    }

    private fun updateSelectionUI() {
        selectionHeader.visibility = View.VISIBLE
        selectionActionBar.visibility = View.VISIBLE

        selectionSelectedText.text = "${selectedAddresses.size} Selected"

        selectionAllButton.text =
            if (selectedAddresses.size == rowViews.size) "●" else "○"

        rowViews.forEach { (address, _) ->
            val row = findRowByAddress(address)
            val overlay = row?.getTag(1001) as? TextView

            if (overlay != null) {
                val selected = selectedAddresses.contains(address)

                overlay.visibility = View.VISIBLE
                overlay.text = if (selected) "✓" else ""
                overlay.background = GradientDrawable().apply {
                    setColor(
                        if (selected) {
                            Color.rgb(105, 105, 110)
                        } else {
                            Color.TRANSPARENT
                        }
                    )
                    shape = GradientDrawable.OVAL
                }
            }
        }
    }

    private fun findRowByAddress(address: String): View? {
        fun find(view: View): View? {
            if (view.tag == address) return view

            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val result = find(view.getChildAt(i))
                    if (result != null) return result
                }
            }

            return null
        }

        return find(messagesContainer)
    }

    private fun deleteSelected() {
        selectedAddresses.toList().forEach {
            deleteConversationFromProvider(it)
        }
        exitSelectionMode()
        loadMessages()
    }

    private fun toggleNotifications() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val muted = prefs.getStringSet(MUTED, emptySet())?.toMutableSet()
            ?: mutableSetOf()

        selectedAddresses.forEach {
            if (muted.contains(it)) muted.remove(it) else muted.add(it)
        }

        prefs.edit().putStringSet(MUTED, muted).apply()
        Toast.makeText(this, "Notifications updated", Toast.LENGTH_SHORT).show()
    }

    private fun pinSelected() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val pinned = prefs.getStringSet(PINNED, emptySet())?.toMutableSet()
            ?: mutableSetOf()

        selectedAddresses.forEach {
            if (pinned.contains(it)) pinned.remove(it) else pinned.add(it)
        }

        prefs.edit().putStringSet(PINNED, pinned).apply()
        exitSelectionMode()
        loadMessages()
    }

    private fun deleteConversation(address: String) {
        deleteConversationFromProvider(address)
        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
        loadMessages()
    }

    private fun deleteConversationFromProvider(address: String) {
        try {
            contentResolver.delete(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.ADDRESS} = ?",
                arrayOf(address)
            )
        } catch (_: Exception) {
        }
    }

    private fun callNumber(address: String) {
        try {
            val intent = Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:${Uri.encode(address)}")
            )
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Cannot call this number",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedAddresses.clear()

        selectionHeader.visibility = View.GONE
        selectionActionBar.visibility = View.GONE
        homeHeader?.visibility = View.VISIBLE
        homeSearch.visibility = View.VISIBLE

        for (i in 0 until messagesContainer.childCount) {
            val child = messagesContainer.getChildAt(i)
            if (child is FrameLayout) {
                child.findViewWithTag<TextView>(1001)?.visibility = View.GONE
            }
        }
    }

    private fun getPinnedNumbers(): Set<String> {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(PINNED, emptySet()) ?: emptySet()
    }

    private fun formatShortDate(timestamp: Long): String {
        return SimpleDateFormat(
            "dd MMM",
            Locale.getDefault()
        ).format(Date(timestamp))
    }

    private fun openConversation(address: String) {
        val intent = Intent(
            this,
            ConversationActivity::class.java
        )
        intent.putExtra("phone", address)
        startActivity(intent)
    }

    private fun openNewMessage() {
        startActivity(
            Intent(this, NewMessageActivity::class.java)
        )
    }

    private fun solidDrawable(
        color: Int,
        radius: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private object ColorDrawableCompat {
        fun white(): GradientDrawable {
            return GradientDrawable().apply {
                setColor(Color.WHITE)
            }
        }
    }

    private data class ConversationData(
        val address: String,
        val body: String,
        val date: Long,
        val type: Int
    )

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }
}
