package com.majidhajizade.messages

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
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

    private val nearbyPermissions = if (android.os.Build.VERSION.SDK_INT >= 31) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private fun ensureNearbyPermissions() {
        val missing = nearbyPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 9001)
        }
    }


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
    private var floatingComposeButton: View? = null
    private var meshManager: OfflineMeshManager? = null
    private var meshPopup: PopupWindow? = null
    private var meshStatusText: TextView? = null
    private var meshDevicesContainer: LinearLayout? = null
    private val meshDevices = linkedMapOf<String, TextView>()
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
        ensureNearbyPermissions()

        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        setContentView(createHomeScreen())
        setupMeshManager()

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

        val menuButton = TextView(this).apply {
            text = "⋮"
            textSize = 30f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            translationY = dp(13).toFloat()
            contentDescription = "More"
            setPadding(dp(8), 0, dp(2), 0)

            setOnClickListener {
                showHeaderMenu()
            }
        }

        header.addView(
            menuButton,
            LinearLayout.LayoutParams(
                dp(42),
                dp(60)
            )
        )

        val headerLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            clipChildren = false
            clipToPadding = false
        }

        headerLayer.addView(
            header,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(72)
            )
        )

        headerLayer.addView(
            selectionHeader,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(72)
            )
        )

        root.addView(
            headerLayer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(72)
            )
        )

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

        val floatingButton = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Color.rgb(218, 218, 223))
            }
            elevation = dp(18).toFloat()
            isClickable = true
            isFocusable = true

            addView(
                MessengerComposeIcon(this@MainActivity),
                FrameLayout.LayoutParams(
                    dp(54),
                    dp(54),
                    Gravity.CENTER
                )
            )

            setOnClickListener {
                openNewMessage()
            }

            visibility = View.VISIBLE
            alpha = 1f
            translationY = 0f
        }

        floatingComposeButton = floatingButton

        chatLayer.addView(
            floatingButton,
            FrameLayout.LayoutParams(
                dp(54),
                dp(54)
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(18)
                bottomMargin = dp(84)
            }
        )

        var lastScrollY = 0
        var scrollInitialized = false

        messagesScroll.setOnScrollChangeListener { _, scrollY, _, _, _ ->
            if (!scrollInitialized) {
                lastScrollY = scrollY
                scrollInitialized = true
                return@setOnScrollChangeListener
            }

            if (scrollY < lastScrollY - dp(2)) {
                // Scrolling up: smoothly shrink and hide.
                floatingButton.animate().cancel()

                floatingButton.animate()
                    .alpha(0f)
                    .scaleX(0.72f)
                    .scaleY(0.72f)
                    .translationY(dp(10).toFloat())
                    .setDuration(320L)
                    .setInterpolator(
                        android.view.animation.AccelerateDecelerateInterpolator()
                    )
                    .start()

            } else if (scrollY > lastScrollY + dp(2)) {
                // Scrolling down: smoothly grow and show.
                floatingButton.animate().cancel()

                floatingButton.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(360L)
                    .setInterpolator(
                        android.view.animation.DecelerateInterpolator()
                    )
                    .start()
            }

            lastScrollY = scrollY
        }

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


    private fun setupMeshManager() {
        meshManager = OfflineMeshManager(
            this,
            object : OfflineMeshManager.Listener {

                override fun onPeerDiscovered(
                    endpointId: String,
                    name: String
                ) {
                    runOnUiThread {
                        addMeshDevice(endpointId, name)
                        updateMeshStatus()
                    }
                }

                override fun onPeerLost(endpointId: String) {
                    runOnUiThread {
                        meshDevices.remove(endpointId)?.let {
                            meshDevicesContainer?.removeView(it)
                        }
                        updateMeshStatus()
                    }
                }

                override fun onPeerConnected(
                    endpointId: String,
                    name: String
                ) {
                    runOnUiThread {
                        meshDevices[endpointId]?.apply {
                            text = "$name  •  Connected"
                            setTextColor(Color.rgb(25, 120, 70))
                        }
                        updateMeshStatus()
                    }
                }

                override fun onPeerDisconnected(endpointId: String) {
                    runOnUiThread {
                        meshDevices[endpointId]?.let { device ->
                            val current = device.text
                                .toString()
                                .substringBefore("  •")

                            device.text = "$current  •  Disconnected"
                            device.setTextColor(secondaryText)
                        }
                        updateMeshStatus()
                    }
                }

                override fun onMessage(
                    endpointId: String,
                    message: String
                ) {
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        meshStatusText?.text = message
                        meshStatusText?.setTextColor(
                            Color.rgb(190, 55, 55)
                        )
                        Toast.makeText(
                            this@MainActivity,
                            message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                }
            }
        )

        if (hasNearbyPermissions()) {
            meshManager?.start()
        }
    }

    private fun hasNearbyPermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateMeshStatus() {
        val connected = meshDevices.values.any {
            it.text.toString().contains("Connected")
        }

        meshStatusText?.apply {
            text = when {
                connected -> "Connected"
                meshDevices.isNotEmpty() -> "Nearby devices found"
                else -> "Searching nearby devices…"
            }

            setTextColor(
                if (connected) {
                    Color.rgb(25, 120, 70)
                } else {
                    secondaryText
                }
            )
        }
    }

    private fun addMeshDevice(
        endpointId: String,
        name: String
    ) {
        if (meshDevices.containsKey(endpointId)) return

        val device = TextView(this).apply {
            text = "$name  •  Connecting…"
            textSize = 16f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)

            background = GradientDrawable().apply {
                setColor(Color.rgb(248, 248, 250))
                cornerRadius = dp(14).toFloat()
            }

            isClickable = true

            setOnClickListener {
                openMeshConversation(endpointId)
            }
        }

        meshDevices[endpointId] = device

        meshDevicesContainer?.addView(
            device,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply {
                bottomMargin = dp(8)
            }
        )

        updateMeshStatus()
    }

    private fun openMeshConversation(endpointId: String) {
        Toast.makeText(
            this,
            "Device connected. Open a conversation to chat.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showHeaderMenu() {
        meshPopup?.dismiss()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))

            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(230, 230, 235))
            }

            elevation = dp(18).toFloat()
        }

        val mesh = createMenuItem(
            title = "Mesh",
            subtitle = "Nearby offline devices"
        )

        mesh.setOnClickListener {
            showMeshPanel()
        }

        val settings = createMenuItem(
            title = "Settings",
            subtitle = "Coming soon"
        )

        settings.setOnClickListener {
        }

        card.addView(
            mesh,
            LinearLayout.LayoutParams(dp(250), dp(64))
        )

        card.addView(
            settings,
            LinearLayout.LayoutParams(dp(250), dp(64))
        )

        meshPopup = PopupWindow(
            card,
            dp(270),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                }
            )
            elevation = dp(18).toFloat()
            isOutsideTouchable = true
            isFocusable = true
        }

        meshPopup?.showAsDropDown(
            homeHeader,
            0,
            -dp(4)
        )
    }

    private fun createMenuItem(
        title: String,
        subtitle: String
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)

            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
            }

            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 17f
                    setTextColor(Color.BLACK)
                    typeface = Typeface.DEFAULT_BOLD
                }
            )

            addView(
                TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(secondaryText)
                    setPadding(0, dp(3), 0, 0)
                }
            )
        }
    }

    private fun showMeshPanel() {
        meshPopup?.dismiss()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))

            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.rgb(225, 225, 230))
            }

            elevation = dp(20).toFloat()
        }

        val title = TextView(this).apply {
            text = "Mesh"
            textSize = 25f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
        }

        card.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            )
        )

        meshStatusText = TextView(this).apply {
            text = "Searching nearby devices…"
            textSize = 13f
            setTextColor(secondaryText)
        }

        card.addView(
            meshStatusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(26)
            )
        )

        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(235, 235, 238))
        }

        card.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(12)
            }
        )

        meshDevicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        card.addView(
            meshDevicesContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        if (meshDevices.isEmpty()) {
            meshDevicesContainer?.addView(
                TextView(this).apply {
                    text = "No nearby devices yet"
                    textSize = 14f
                    setTextColor(secondaryText)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(18), 0, dp(18))
                }
            )
        }

        meshPopup = PopupWindow(
            card,
            dp(300),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                }
            )
            elevation = dp(20).toFloat()
            isOutsideTouchable = true
            isFocusable = true
        }

        meshPopup?.showAsDropDown(
            homeHeader,
            -dp(8),
            -dp(4)
        )

        updateMeshStatus()
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
        var downX = 0f
        var downY = 0f
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

                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)

                    if (selectionMode && !longPressed && !moved) {
                        toggleSelection(address)
                        return@setOnTouchListener true
                    }

                    if (!longPressed && !moved) {
                        openConversation(address)
                    }

                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
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
            setPadding(0, 0, 0, 0)
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
            LinearLayout.LayoutParams(dp(38), dp(38))
        )

        allContainer.addView(
            TextView(this).apply {
                text = "All"
                textSize = 13f
                setTextColor(Color.rgb(35, 35, 38))
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(dp(38), dp(22))
        )

        header.addView(
            allContainer,
            LinearLayout.LayoutParams(dp(58), dp(72))
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
            LinearLayout.LayoutParams(dp(78), dp(72))
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
        selectionHeader.visibility = View.VISIBLE
        floatingComposeButton?.animate()?.cancel()
        floatingComposeButton?.visibility = View.GONE
        floatingComposeButton?.alpha = 0f
        floatingComposeButton?.translationY = dp(18).toFloat()

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
        floatingComposeButton?.animate()?.cancel()
        floatingComposeButton?.alpha = 1f
        floatingComposeButton?.translationY = 0f
        floatingComposeButton?.visibility = View.VISIBLE

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

    private class MessengerComposeIcon(
        context: android.content.Context
    ) : ImageView(context) {

        init {
            setImageResource(R.drawable.floti)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dpLocal(7f).toInt(), dpLocal(7f).toInt(), dpLocal(7f).toInt(), dpLocal(7f).toInt())
            contentDescription = "New message"
        }

        private fun dpLocal(value: Float): Float {
            return value * resources.displayMetrics.density
        }
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


    override fun onDestroy() {
        meshPopup?.dismiss()
        meshManager?.stop()
        super.onDestroy()
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
