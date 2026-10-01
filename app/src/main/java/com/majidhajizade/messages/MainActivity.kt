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
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    private lateinit var allButton: TextView

    private val selectedAddresses = linkedSetOf<String>()
    private val rowViews = mutableMapOf<String, TextView>()

    private var selectionMode = false
    private var draggingSelection = false
    private var dragSelectState = true
    private var lastDragAddress: String? = null

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
            background = background
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "Messages"
            textSize = 34f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val composeButton = TextView(this).apply {
            text = "✎"
            textSize = 30f
            setTextColor(blue)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(4), 0)

            setOnClickListener {
                openNewMessage()
            }
        }

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
                topMargin = dp(10)
                bottomMargin = dp(14)
            }
        )

        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        messagesScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(
                messagesContainer,
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
            )
        )

        selectionBar = createSelectionBar()
        selectionBar.visibility = View.GONE

        root.addView(
            selectionBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(82)
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
        val actionLayer = FrameLayout(this)

        val actionBackground = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val callAction = TextView(this).apply {
            text = "Call"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = solidDrawable(blue, 0f)
        }

        val deleteAction = TextView(this).apply {
            text = "Delete"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = solidDrawable(deleteRed, 0f)
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
            background = ColorDrawableCompat.white()
            setPadding(0, dp(8), 0, dp(8))
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

        foreground.addView(
            contact,
            LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                rightMargin = dp(12)
            }
        )

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
            setTextColor(secondaryText)
            gravity = Gravity.TOP or Gravity.END
            typeface = Typeface.DEFAULT_BOLD
        }

        foreground.addView(
            dateText,
            LinearLayout.LayoutParams(
                dp(62),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val selectionCircle = TextView(this).apply {
            text = "✓"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(premiumBlue)
                shape = GradientDrawable.OVAL
            }
        }

        foreground.addView(
            selectionCircle,
            LinearLayout.LayoutParams(
                dp(28),
                dp(28)
            ).apply {
                leftMargin = dp(8)
            }
        )

        actionLayer.addView(
            foreground,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(76)
            )
        )

        rowViews[address] = selectionCircle

        callAction.setOnClickListener {
            callNumber(address)
        }

        deleteAction.setOnClickListener {
            deleteConversation(address)
        }

        setupRowTouch(
            actionLayer,
            foreground,
            address
        )

        messagesContainer.addView(
            actionLayer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(76)
            )
        )

        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(230, 230, 230))
        }

        messagesContainer.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        )
    }

    private fun setupRowTouch(
        container: FrameLayout,
        foreground: View,
        address: String
    ) {
        var downX = 0f
        var downY = 0f
        var startTranslation = 0f
        var moved = false
        var longPressed = false

        val handler = android.os.Handler(mainLooper)
        val longPressRunnable = Runnable {
            if (!moved && !selectionMode) {
                longPressed = true
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

                    if (selectionMode) {
                        draggingSelection = true
                        lastDragAddress = address
                    } else {
                        handler.postDelayed(longPressRunnable, 500)
                    }

                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    if (abs(dx) > dp(10) || abs(dy) > dp(10)) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }

                    if (selectionMode) {
                        if (abs(dy) > dp(8)) {
                            val location = IntArray(2)
                            messagesContainer.getLocationOnScreen(location)
                            val y = event.rawY - location[1]
                            selectRowsByY(y, dy > 0)
                        }
                        return@setOnTouchListener true
                    }

                    if (abs(dx) > abs(dy)) {
                        foreground.translationX =
                            (startTranslation + dx)
                                .coerceIn(-dp(110).toFloat(), dp(110).toFloat())
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)

                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    if (selectionMode) {
                        if (!moved && !longPressed && abs(dy) < dp(12)) {
                            toggleSelection(address)
                        }
                        draggingSelection = false
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

                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    foreground.animate()
                        .translationX(0f)
                        .setDuration(180)
                        .start()
                    draggingSelection = false
                    true
                }

                else -> true
            }
        }
    }

    private fun enterSelectionMode(address: String) {
        selectionMode = true
        selectedAddresses.clear()
        selectedAddresses.add(address)
        updateSelectionUI()
    }

    private fun toggleSelection(address: String) {
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

    private fun selectRowsByY(y: Float, selecting: Boolean) {
        var currentTop = 0

        for (i in 0 until messagesContainer.childCount) {
            val child = messagesContainer.getChildAt(i)

            if (child is FrameLayout) {
                val bottom = currentTop + child.height

                if (y >= currentTop && y <= bottom) {
                    val address = rowViews.entries
                        .firstOrNull { it.value.parent?.parent === child }
                        ?.key

                    if (address != null && address != lastDragAddress) {
                        if (selecting) {
                            selectedAddresses.add(address)
                        } else {
                            selectedAddresses.remove(address)
                        }

                        lastDragAddress = address
                        updateSelectionUI()
                    }
                }

                currentTop = bottom + dp(1)
            }
        }
    }

    private fun updateSelectionUI() {
        selectionBar.visibility = View.VISIBLE

        val allCount = rowViews.size
        val selectedCount = selectedAddresses.size

        allButton.text =
            if (selectedCount == allCount) "✓" else "○"

        selectionCount.text =
            if (selectedCount == allCount) "All" else "$selectedCount"

        rowViews.forEach { (address, circle) ->
            circle.visibility = View.VISIBLE
            circle.text =
                if (selectedAddresses.contains(address)) "✓" else ""
            circle.setBackgroundColor(
                if (selectedAddresses.contains(address)) {
                    premiumBlue
                } else {
                    Color.rgb(225, 225, 230)
                }
            )
        }
    }

    private fun createSelectionBar(): LinearLayout {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        allButton = TextView(this).apply {
            text = "○"
            textSize = 28f
            setTextColor(premiumBlue)
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

        top.addView(
            allButton,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        selectionCount = TextView(this).apply {
            text = "All"
            textSize = 17f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL
            typeface = Typeface.DEFAULT_BOLD
        }

        top.addView(
            selectionCount,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val cancel = TextView(this).apply {
            text = "Cancel"
            textSize = 16f
            setTextColor(premiumBlue)
            gravity = Gravity.CENTER
            setOnClickListener {
                exitSelectionMode()
            }
        }

        top.addView(
            cancel,
            LinearLayout.LayoutParams(dp(80), dp(48))
        )

        wrapper.addView(
            top,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.rgb(248, 248, 250))
                cornerRadius = dp(26).toFloat()
            }
        }

        val notification = createActionButton(
            "♢",
            "Notifications"
        ) {
            toggleNotifications()
        }

        val delete = createActionButton(
            "⌫",
            "Delete"
        ) {
            deleteSelected()
        }

        val more = createActionButton(
            "⋮",
            "More"
        ) {
            showMorePopup()
        }

        actions.addView(
            notification,
            LinearLayout.LayoutParams(0, dp(66), 1f)
        )

        actions.addView(
            delete,
            LinearLayout.LayoutParams(0, dp(66), 1f)
        )

        actions.addView(
            more,
            LinearLayout.LayoutParams(0, dp(66), 1f)
        )

        wrapper.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(66)
            )
        )

        return wrapper
    }

    private fun createActionButton(
        icon: String,
        label: String,
        action: () -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener {
                action()
            }
        }

        val iconView = TextView(this).apply {
            text = icon
            textSize = 22f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        val text = TextView(this).apply {
            this.text = label
            textSize = 11f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }

        box.addView(
            iconView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            )
        )

        box.addView(
            text,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(24)
            )
        )

        return box
    }

    private fun toggleNotifications() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val muted = prefs.getStringSet(MUTED, emptySet())
            ?.toMutableSet() ?: mutableSetOf()

        selectedAddresses.forEach {
            if (muted.contains(it)) {
                muted.remove(it)
            } else {
                muted.add(it)
            }
        }

        prefs.edit().putStringSet(MUTED, muted).apply()

        Toast.makeText(
            this,
            "Notifications updated",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun deleteSelected() {
        val numbers = selectedAddresses.toList()

        numbers.forEach {
            deleteConversationFromProvider(it)
        }

        exitSelectionMode()
        loadMessages()
    }

    private fun deleteConversation(address: String) {
        deleteConversationFromProvider(address)
        Toast.makeText(
            this,
            "Deleted",
            Toast.LENGTH_SHORT
        ).show()
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

    private fun showMorePopup() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
            }
        }

        val pin = createPopupItem("pin to top") {
            pinSelected()
        }

        val block = createPopupItem("Block") {
            blockSelected()
        }

        box.addView(pin)
        box.addView(block)

        val popup = PopupWindow(
            box,
            dp(190),
            dp(112),
            true
        )

        popup.elevation = dp(10).toFloat()
        popup.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
            }
        )

        popup.showAtLocation(
            window.decorView,
            Gravity.CENTER,
            0,
            0
        )
    }

    private fun createPopupItem(
        text: String,
        action: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)

            setOnClickListener {
                action()
            }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        }
    }

    private fun pinSelected() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val pinned = prefs.getStringSet(PINNED, emptySet())
            ?.toMutableSet() ?: mutableSetOf()

        selectedAddresses.forEach {
            if (pinned.contains(it)) {
                pinned.remove(it)
            } else {
                pinned.add(it)
            }
        }

        prefs.edit().putStringSet(PINNED, pinned).apply()

        exitSelectionMode()
        loadMessages()
    }

    private fun blockSelected() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val blocked = prefs.getStringSet(BLOCKED, emptySet())
            ?.toMutableSet() ?: mutableSetOf()

        selectedAddresses.forEach {
            blocked.add(it)
        }

        prefs.edit().putStringSet(BLOCKED, blocked).apply()

        exitSelectionMode()
        loadMessages()

        Toast.makeText(
            this,
            "Blocked",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        draggingSelection = false
        selectedAddresses.clear()
        lastDragAddress = null

        selectionBar.visibility = View.GONE

        rowViews.values.forEach {
            it.visibility = View.GONE
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
        if (selectionMode) {
            toggleSelection(address)
            return
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
