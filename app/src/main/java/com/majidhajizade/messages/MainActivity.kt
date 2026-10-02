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
                    Manifest.permission.SEND_SMS
                ),
                SMS_PERMISSION_REQUEST
            )
        }
    }

    private fun createHomeScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 245, 247))
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }

        selectionHeader = createSelectionHeader()
        selectionHeader.visibility = View.GONE

        selectionActionBar = createSelectionActionBar()
        selectionActionBar.visibility = View.GONE

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        homeHeader = header

        val title = TextView(this).apply {
            text = "Messages"
            textSize = 34f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
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
                dp(64)
            )
        )

        root.addView(
            selectionHeader,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64)
            )
        )

        val searchBackground = GradientDrawable().apply {
            setColor(Color.rgb(242, 242, 247))
            cornerRadius = dp(23).toFloat()
        }

        val search = EditText(this).apply {
            hint = "Search"
            textSize = 16f
            setSingleLine(true)
            setTextColor(Color.BLACK)
            setHintTextColor(secondaryText)
            setPadding(dp(18), 0, dp(18), 0)
            background = searchBackground
        }

        root.addView(
            search,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply {
                leftMargin = 0
                rightMargin = 0
                topMargin = dp(10)
                bottomMargin = dp(14)
            }
        )

        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.rgb(230, 230, 235))
            }
            clipToOutline = true
            setPadding(0, dp(4), 0, dp(4))
        }
        messagesScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true

            val scrollContent = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }

            scrollContent.addView(
                messagesContainer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            scrollContent.addView(
                selectionActionBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(88)
                ).apply {
                    leftMargin = dp(8)
                    rightMargin = dp(8)
                    topMargin = dp(8)
                    bottomMargin = dp(8)
                }
            )

            addView(
                scrollContent,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(
            messagesScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                leftMargin = 0
                rightMargin = 0
            }
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
            setPadding(dp(12), dp(8), dp(12), dp(8))
            clipToOutline = false
            elevation = 0f
        }

        val contactFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                rightMargin = dp(12)
            }
        }

        val contact = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_myplaces)
            setColorFilter(Color.rgb(145, 145, 150))
            background = GradientDrawable().apply {
                setColor(Color.rgb(242, 242, 247))
                shape = GradientDrawable.OVAL
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        contactFrame.addView(
            contact,
            FrameLayout.LayoutParams(dp(52), dp(52))
        )

        val selectionOverlay = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.argb(155, 0, 122, 255))
                shape = GradientDrawable.OVAL
            }
        }

        selectionOverlay.tag = 1001

        contactFrame.addView(
            selectionOverlay,
            FrameLayout.LayoutParams(dp(52), dp(52))
        )

        foreground.addView(contactFrame)

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val name = TextView(this).apply {
            text = address
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

        textContainer.addView(name)
        textContainer.addView(preview)

        foreground.addView(
            textContainer,
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
            gravity = Gravity.TOP or Gravity.END
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(0, dp(2), 0, 0)
        }

        foreground.addView(
            dateText,
            LinearLayout.LayoutParams(
                dp(62),
                LinearLayout.LayoutParams.WRAP_CONTENT
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

        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(235, 235, 240))
        }

        messagesContainer.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                leftMargin = dp(76)
                rightMargin = dp(12)
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
        }

        selectionAllButton = TextView(this).apply {
            text = "○"
            textSize = 28f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setOnClickListener {
                if (selectedAddresses.size == rowViews.size) {
                    selectedAddresses.clear()
                } else {
                    selectedAddresses.clear()
                    selectedAddresses.addAll(rowViews.keys)
                }
                updateSelectionUI()
            }
        }

        val allBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        allBox.addView(
            selectionAllButton,
            LinearLayout.LayoutParams(dp(42), dp(42))
        )

        val allText = TextView(this).apply {
            text = "All"
            textSize = 12f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        allBox.addView(
            allText,
            LinearLayout.LayoutParams(dp(42), dp(18))
        )

        header.addView(
            allBox,
            LinearLayout.LayoutParams(dp(58), dp(64))
        )

        selectionSelectedText = TextView(this).apply {
            text = "1 Selected"
            textSize = 17f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            selectionSelectedText,
            LinearLayout.LayoutParams(0, dp(64), 1f)
        )

        val cancel = TextView(this).apply {
            text = "Cancel"
            textSize = 16f
            setTextColor(blue)
            gravity = Gravity.CENTER
            setOnClickListener {
                exitSelectionMode()
            }
        }

        header.addView(
            cancel,
            LinearLayout.LayoutParams(dp(80), dp(64))
        )

        return header
    }

    private fun createSelectionActionBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 245, 245, 247))
                cornerRadius = dp(38).toFloat()
                setStroke(dp(1), Color.argb(70, 255, 255, 255))
            }
            elevation = dp(8).toFloat()
        }

        bar.addView(
            createSelectionAction("♢", "Notifications") {
                toggleNotifications()
            },
            LinearLayout.LayoutParams(0, dp(72), 1f)
        )

        bar.addView(
            createSelectionAction("⌫", "Delete") {
                deleteSelected()
            },
            LinearLayout.LayoutParams(0, dp(72), 1f)
        )

        bar.addView(
            createSelectionAction("▱", "Pin") {
                pinSelected()
            },
            LinearLayout.LayoutParams(0, dp(72), 1f)
        )

        return bar
    }

    private fun createSelectionAction(
        icon: String,
        label: String,
        action: () -> Unit
    ): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { action() }

            addView(
                TextView(this@MainActivity).apply {
                    text = icon
                    textSize = 25f
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(38)
                )
            )

            addView(
                TextView(this@MainActivity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(24)
                )
            )
        }
    }

    private fun enterSelectionMode(address: String) {
        selectionMode = true
        selectedAddresses.clear()
        selectedAddresses.add(address)

        homeHeader?.visibility = View.GONE

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
                overlay.visibility = View.VISIBLE
                overlay.text =
                    if (selectedAddresses.contains(address)) "✓" else ""
                overlay.background = GradientDrawable().apply {
                    setColor(
                        if (selectedAddresses.contains(address)) {
                            Color.argb(155, 0, 122, 255)
                        } else {
                            Color.argb(110, 245, 245, 247)
                        }
                    )
                    shape = GradientDrawable.OVAL
                }
            }
        }
    }

    private fun findRowByAddress(address: String): View? {
        for (i in 0 until messagesContainer.childCount) {
            val child = messagesContainer.getChildAt(i)
            if (child is FrameLayout && child.tag == address) {
                return child
            }
        }
        return null
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

    private fun exitSelectionMode() {
        selectionMode = false
        selectedAddresses.clear()

        selectionHeader.visibility = View.GONE
        selectionActionBar.visibility = View.GONE
        homeHeader?.visibility = View.VISIBLE

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
        }

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
