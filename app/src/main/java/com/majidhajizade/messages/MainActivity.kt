package com.majidhajizade.messages

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.content.Intent
import android.content.Context
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
import android.widget.ScrollView
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
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.NEARBY_WIFI_DEVICES
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
    private var meshLogText: TextView? = null
    private val meshLogLines = mutableListOf<String>()
    private val meshDevices = linkedMapOf<String, TextView>()
    private val meshDeviceRows = linkedMapOf<String, LinearLayout>()
    private val meshDeviceNames = linkedMapOf<String, String>()
    private var selectedMeshId: String? = null
    private var isMeshPanelVisible = false
    private var isSettingsPageVisible = false
    private var isMeshLogPageVisible = false
    private val rowViews = mutableMapOf<String, TextView>()
    private val selectedAddresses = linkedSetOf<String>()
    private val selectionOverlays = mutableMapOf<String, android.widget.ImageView>()
    private var selectionMode = false
    private lateinit var selectionHeader: LinearLayout
    private lateinit var selectionSelectedText: TextView
    private lateinit var selectionAllButton: TextView
    private lateinit var selectionActionBar: LinearLayout
private lateinit var selectionPinIcon: android.widget.ImageView
companion object {
        private const val SMS_PERMISSION_REQUEST = 2001
        private const val PREFS = "messages_settings"
        private const val MESH_ID = "mesh_id"
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
        setupMeshNotificationChannel()
        MeshSession.logHandler = { message ->
            logMesh(message)
        }
        setupMeshManager()

        requestDefaultSmsRole()
        requestContactsPermission()
        requestNotificationPermission()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 9001 && hasNearbyPermissions()) {
            meshManager?.start()
        }
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

        MeshMessageStore.loadConversations(this).forEach { meshId ->
            val latest = MeshMessageStore.loadLatestMessage(
                this,
                meshId
            ) ?: return@forEach

            conversations[meshId] = ConversationData(
                meshId,
                latest.body,
                latest.date,
                if (latest.incoming) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX
                } else {
                    Telephony.Sms.MESSAGE_TYPE_SENT
                }
            )
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


    private fun setupMeshNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "mesh_messages",
                "Mesh messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for messages received through Mesh"
            }

            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun showMeshMessageNotification(
        senderId: String,
        message: String
    ) {
        val activeChat = ConversationActivity.activeMeshId

        if (activeChat != null &&
            activeChat.equals(senderId, ignoreCase = true)
        ) {
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(
            this,
            ConversationActivity::class.java
        ).apply {
            putExtra("phone", senderId)
            putExtra("mesh_id", senderId)
            putExtra("offline_mesh", true)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            senderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, "mesh_messages")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("پیام جدید از $senderId")
            .setContentText(message)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(message)
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)
            ?.notify(senderId.hashCode(), notification)
    }

    private fun setupMeshManager() {
        val meshId = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(MESH_ID, null)
            ?: run {
                val generated = "MJ-" + java.util.UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(6)
                    .uppercase(Locale.US)

                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(MESH_ID, generated)
                    .apply()

                generated
            }

        if (MeshSession.manager != null) {
            meshManager = MeshSession.manager
            if (hasNearbyPermissions()) {
                meshManager?.start()
            }
            return
        }

        meshManager = OfflineMeshManager(
            applicationContext,
            meshId,
            object : OfflineMeshManager.Listener {

                override fun onPeerDiscovered(
                    endpointId: String,
                    name: String
                ) {
                    logMesh(
                        "DISCOVERED | peer=$name | endpoint=$endpointId"
                    )
                    runOnUiThread {
                        addMeshDevice(endpointId, name)
                        updateMeshStatus()
                    }
                }

                override fun onPeerLost(endpointId: String) {
                    logMesh(
                        "LOST | endpoint=$endpointId"
                    )
                    runOnUiThread {
                        meshDevices.remove(endpointId)
                        meshDeviceNames.remove(endpointId)
                        meshDeviceRows.remove(endpointId)?.let {
                            meshDevicesContainer?.removeView(it)
                        }
                        updateMeshStatus()
                    }
                }

                override fun onPeerConnected(
                    endpointId: String,
                    name: String
                ) {
                    logMesh(
                        "CONNECTED | peer=$name | endpoint=$endpointId"
                    )
                    if (
                        MeshSession.manager?.getTargetMeshId()
                            ?.equals(name, ignoreCase = true) == true
                    ) {
                        MeshSession.targetConnected = true
                    }

                    runOnUiThread {
                        meshDevices[endpointId]?.apply {
                            text = "Connected"
                            setTextColor(Color.rgb(25, 120, 70))
                        }
                        updateMeshStatus()
                    }
                }

                override fun onPeerDisconnected(endpointId: String) {
                    logMesh(
                        "DISCONNECTED | endpoint=$endpointId"
                    )
                    val disconnectedMeshId =
                        meshDeviceNames[endpointId]

                    if (
                        MeshSession.manager?.getTargetMeshId()
                            ?.equals(
                                disconnectedMeshId,
                                ignoreCase = true
                            ) == true
                    ) {
                        MeshSession.targetConnected = false
                    }

                    runOnUiThread {
                        meshDevices[endpointId]?.apply {
                            text = "Disconnected"
                            setTextColor(secondaryText)
                        }
                        updateMeshStatus()
                    }
                }

                override fun onMessage(
                    endpointId: String,
                    message: String
                ) {
                    logMesh(
                        "MESSAGE | endpoint=$endpointId | $message"
                    )

                    val senderId = meshDeviceNames[endpointId]
                        ?.trim()
                        ?.uppercase(Locale.US)
                        ?.takeIf {
                            it.matches(Regex("MJ-[A-Z0-9]{6}"))
                        }
                        ?: endpointId

                    runOnUiThread {
                        val activeChat = MeshSession.activeChatId

                        if (
                            activeChat != null &&
                            activeChat.equals(
                                senderId,
                                ignoreCase = true
                            )
                        ) {
                            MeshSession.activeMessageHandler?.invoke(
                                senderId,
                                message
                            )
                        } else {
                            showMeshMessageNotification(
                                senderId = senderId,
                                message = message
                            )
                        }
                    }
                }

                override fun onError(message: String) {
                    logMesh("ERROR | $message")
                    runOnUiThread {
                        meshStatusText?.text = message
                        meshStatusText?.setTextColor(
                            Color.rgb(190, 55, 55)
                        )
                        logMesh("ERROR | $message")
                    }
                }
            }
        )

        MeshSession.manager = meshManager

        if (hasNearbyPermissions()) {
            meshManager?.start()
        }
    }

    private fun logMesh(message: String) {
        val time = SimpleDateFormat(
            "HH:mm:ss.SSS",
            Locale.US
        ).format(Date())

        val line = "[$time] $message"

        synchronized(meshLogLines) {
            meshLogLines.add(line)

            if (meshLogLines.size > 500) {
                meshLogLines.removeAt(0)
            }
        }

        runOnUiThread {
            meshLogText?.text = meshLogLines.joinToString("\n")

            meshLogText?.post {
                val parent = meshLogText?.parent
                if (parent is ScrollView) {
                    parent.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun copyMeshLog() {
        val log = synchronized(meshLogLines) {
            meshLogLines.joinToString("\n")
        }

        val clipboard =
            getSystemService(android.content.ClipboardManager::class.java)

        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(
                "Messages Mesh Log",
                log
            )
        )

    }

    private fun clearMeshLog() {
        synchronized(meshLogLines) {
            meshLogLines.clear()
        }

        meshLogText?.text = ""

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
            ) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(
                Manifest.permission.NEARBY_WIFI_DEVICES
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

    private fun saveMeshDevice(meshId: String) {
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet("saved_mesh_devices", emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()

        saved.add(meshId)

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putStringSet("saved_mesh_devices", saved)
            .apply()
    }

    private fun selectMeshDevice(meshId: String) {
        val normalized = meshId.trim().uppercase(Locale.US)

        if (!normalized.matches(Regex("MJ-[A-Z0-9]{6}"))) {
            return
        }

        selectedMeshId = normalized
        saveMeshDevice(normalized)
        meshManager?.setTargetMeshId(normalized)

        MeshSession.targetConnected = meshDeviceNames.any { (_, deviceName) ->
            deviceName
                ?.trim()
                ?.uppercase(Locale.US)
                ?.equals(normalized, ignoreCase = true) == true
        }

        startActivity(
            Intent(this, ConversationActivity::class.java).apply {
                putExtra("phone", normalized)
                putExtra("mesh_id", normalized)
                putExtra("offline_mesh", true)
            }
        )

        meshDeviceRows.forEach { (endpointId, row) ->
            val deviceId = meshDeviceNames[endpointId]
                ?.trim()
                ?.uppercase(Locale.US)

            row.background = GradientDrawable().apply {
                setColor(
                    if (deviceId == normalized) {
                        Color.rgb(225, 240, 255)
                    } else {
                        Color.rgb(248, 248, 250)
                    }
                )
                cornerRadius = dp(18).toFloat()
            }
        }

        meshStatusText?.text =
            "Selected destination  •  $normalized"
    }

    private fun loadSavedMeshDevices(): Set<String> {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet("saved_mesh_devices", emptySet())
            ?.filter {
                it.matches(Regex("MJ-[A-Z0-9]{6}"))
            }
            ?.toSet()
            ?: emptySet()
    }

    private fun addMeshDevice(
        endpointId: String,
        name: String
    ) {
        if (meshDevices.containsKey(endpointId)) return

        val deviceId = name.trim().uppercase(Locale.US)

        val device = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(10), dp(8))

            background = GradientDrawable().apply {
                setColor(
                    if (deviceId == selectedMeshId) {
                        Color.rgb(225, 240, 255)
                    } else {
                        Color.rgb(248, 248, 250)
                    }
                )
                cornerRadius = dp(18).toFloat()
            }

            isClickable = true
            setOnClickListener {
                selectMeshDevice(name)
            }
        }

        val avatar = TextView(this).apply {
            text = name.trim().firstOrNull()
                ?.uppercaseChar()
                ?.toString() ?: "M"
            textSize = 16f
            setTextColor(Color.rgb(30, 55, 95))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.rgb(225, 235, 250))
                shape = GradientDrawable.OVAL
            }
        }

        device.addView(
            avatar,
            LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                rightMargin = dp(11)
            }
        )

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        info.addView(
            TextView(this@MainActivity).apply {
                text = name
                textSize = 15f
                setTextColor(Color.rgb(25, 25, 30))
                typeface = Typeface.DEFAULT_BOLD
            }
        )

        val status = TextView(this@MainActivity).apply {
            text = "Connecting…"
            textSize = 12f
            setTextColor(secondaryText)
            tag = "mesh_status"
            setPadding(0, dp(2), 0, 0)
        }

        info.addView(status)

        device.addView(
            info,
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )

        val arrow = TextView(this).apply {
            text = "›"
            textSize = 25f
            setTextColor(Color.rgb(155, 155, 162))
            gravity = Gravity.CENTER
        }

        device.addView(
            arrow,
            LinearLayout.LayoutParams(dp(28), dp(42))
        )

        meshDevices[endpointId] = status
        meshDeviceRows[endpointId] = device
        meshDeviceNames[endpointId] = deviceId

        meshDevicesContainer?.findViewWithTag<View>("mesh_empty")
            ?.let { meshDevicesContainer?.removeView(it) }

        meshDevicesContainer?.addView(
            device,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(66)
            ).apply {
                bottomMargin = dp(8)
            }
        )

        updateMeshStatus()
    }

    private fun openMeshConversation(endpointId: String) {
        logMesh("CONNECTED | Ready to open conversation | endpoint=$endpointId")
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
            subtitle = "Mesh ID and preferences"
        )

        settings.setOnClickListener {
            meshPopup?.dismiss()
            showMeshIdSettings()
        }

        card.addView(
            mesh,
            LinearLayout.LayoutParams(dp(250), dp(64))
        )

        card.addView(
            settings,
            LinearLayout.LayoutParams(dp(250), dp(64))
        )

        val savedMeshDevices = loadSavedMeshDevices()

        if (selectedMeshId == null && savedMeshDevices.isNotEmpty()) {
            selectedMeshId = savedMeshDevices.first()
            meshManager?.setTargetMeshId(selectedMeshId)
        }

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
        isMeshPanelVisible = true

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        titleBox.addView(
            TextView(this).apply {
                text = "Mesh"
                textSize = 25f
                setTextColor(Color.rgb(18, 18, 22))
                typeface = Typeface.DEFAULT_BOLD
            }
        )

        meshStatusText = TextView(this).apply {
            text = "Searching nearby devices…"
            textSize = 12f
            setTextColor(secondaryText)
            setPadding(0, dp(3), 0, 0)
        }

        titleBox.addView(meshStatusText)

        header.addView(
            titleBox,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        val close = TextView(this).apply {
            text = "×"
            textSize = 28f
            setTextColor(Color.rgb(90, 90, 96))
            gravity = Gravity.CENTER
            background = solidDrawable(
                Color.rgb(245, 245, 248),
                dp(18).toFloat()
            )

            setOnClickListener {
                returnToHome()
            }
        }

        header.addView(
            close,
            LinearLayout.LayoutParams(dp(42), dp(42))
        )

        page.addView(header)

        val myMeshId = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(MESH_ID, null)
            ?: "Unknown"

        val identityCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = solidDrawable(
                Color.rgb(243, 247, 255),
                dp(18).toFloat()
            )
        }

        identityCard.addView(
            TextView(this).apply {
                text = "YOUR MESH ID"
                textSize = 10f
                setTextColor(premiumBlue)
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
            }
        )

        identityCard.addView(
            TextView(this).apply {
                text = myMeshId
                textSize = 20f
                setTextColor(Color.rgb(25, 45, 75))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(4), 0, 0)
            }
        )

        page.addView(
            identityCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(76)
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        )

        val searchInput = EditText(this).apply {
            hint = "Search Mesh ID"
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = solidDrawable(
                Color.rgb(246, 246, 249),
                dp(16).toFloat()
            )
        }

        page.addView(
            searchInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply {
                bottomMargin = dp(14)
            }
        )

        val nearbyTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        nearbyTitle.addView(
            TextView(this).apply {
                text = "Nearby devices"
                textSize = 15f
                setTextColor(Color.rgb(30, 30, 35))
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, dp(30), 1f)
        )

        nearbyTitle.addView(
            TextView(this).apply {
                text = "${meshDevices.size}"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(secondaryText)
                background = solidDrawable(
                    Color.rgb(242, 242, 245),
                    dp(12).toFloat()
                )
                setPadding(dp(9), dp(4), dp(9), dp(4))
            }
        )

        page.addView(
            nearbyTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            )
        )

        meshDevicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (meshDevices.isEmpty()) {
            meshDevicesContainer?.addView(
                TextView(this).apply {
                    tag = "mesh_empty"
                    text = "No nearby devices yet\nKeep Mesh open to discover peers."
                    textSize = 13f
                    setTextColor(secondaryText)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(22), 0, dp(22))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(82)
                )
            )
        } else {
            meshDeviceRows.values.forEach { device ->
                meshDevicesContainer?.addView(
                    device,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(66)
                    ).apply {
                        bottomMargin = dp(8)
                    }
                )
            }
        }

        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            addView(
                meshDevicesContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        page.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )


        setContentView(page)
        updateMeshStatus()
    }

    private fun returnToHome() {
        if (!isMeshPanelVisible) {
            return
        }

        isMeshPanelVisible = false
        meshStatusText = null
        meshDevicesContainer = null
        meshLogText = null
        setContentView(createHomeScreen())
        updateMeshStatus()
    }

    override fun onBackPressed() {
        when {
            isMeshLogPageVisible -> {
                isMeshLogPageVisible = false
                showMeshIdSettings()
            }

            isSettingsPageVisible -> {
                isSettingsPageVisible = false
                setContentView(createHomeScreen())
                updateMeshStatus()
            }

            isMeshPanelVisible -> {
                returnToHome()
            }

            selectionMode || selectionHeader.visibility == View.VISIBLE -> {
                exitSelectionMode()
            }

            else -> {
                super.onBackPressed()
            }
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

        val selectionOverlay = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_check_circle)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            visibility = View.GONE
            contentDescription = "Selected"
        }

        selectionOverlay.tag = 1001
        selectionOverlays[address] = selectionOverlay

        contactFrame.addView(
            selectionOverlay,
            FrameLayout.LayoutParams(dp(26), dp(26)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(2)
                bottomMargin = dp(2)
            }
        )

        foreground.addView(contactFrame)
        selectionOverlay.bringToFront()

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }

        val name = TextView(this).apply {
            text = contactName ?: address
            textSize = 17f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

        val preview = TextView(this).apply {
            text = body
            textSize = 15f
            setTextColor(secondaryText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
            gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

        textContainer.addView(
            name,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        textContainer.addView(
            preview,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        foreground.addView(
            textContainer,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val metaContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.END
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val dateText = TextView(this).apply {
            text = formatShortDate(date)
            textSize = 13f
            setTextColor(Color.rgb(165, 165, 170))
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(0, 0, 0, 0)
        }

        metaContainer.addView(
            dateText,
            LinearLayout.LayoutParams(
                dp(54),
                dp(22)
            )
        )

        val pinned = getPinnedNumbers().contains(address)

        if (pinned) {
            val pinCircle = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(246, 246, 248))
                    shape = GradientDrawable.OVAL
                    setStroke(dp(1), Color.rgb(225, 225, 230))
                }
            }

            pinCircle.addView(
                android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_pin)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    contentDescription = "Pinned"
                },
                FrameLayout.LayoutParams(
                    dp(18),
                    dp(18)
                ).apply {
                    gravity = Gravity.CENTER
                }
            )

            metaContainer.addView(
                pinCircle,
                LinearLayout.LayoutParams(
                    dp(26),
                    dp(26)
                ).apply {
                    topMargin = dp(3)
                }
            )
        }

        foreground.addView(
            metaContainer,
            LinearLayout.LayoutParams(
                dp(54),
                dp(52)
            ).apply {
                leftMargin = dp(6)
            }
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
        selectionOverlay: android.widget.ImageView
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

            if (iconType == 0 || iconType == 1) {
                addView(
                    android.widget.ImageView(this@MainActivity).apply {
                        setImageResource(
                            if (iconType == 0) {
                                R.drawable.ic_notification
                            } else {
                                R.drawable.ic_delete
                            }
                        )
                        setColorFilter(Color.BLACK)
                        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                        contentDescription = label
                    },
                    LinearLayout.LayoutParams(
                        dp(26),
                        dp(25)
                    )
                )
            } else {
                selectionPinIcon = android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_pin)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    contentDescription = label
                }

                addView(
                    selectionPinIcon,
                    LinearLayout.LayoutParams(
                        dp(26),
                        dp(25)
                    )
                )
            }

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

        val pinned = getPinnedNumbers()
        val anySelectedPinned = selectedAddresses.any { pinned.contains(it) }

        selectionPinIcon.setImageResource(
            if (anySelectedPinned) {
                R.drawable.ic_pin_slash
            } else {
                R.drawable.ic_pin
            }
        )

        rowViews.forEach { (address, _) ->
            val overlay = selectionOverlays[address] ?: return@forEach
            val selected = selectedAddresses.contains(address)

            overlay.visibility = if (selected) View.VISIBLE else View.GONE
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

        val anySelectedPinned = selectedAddresses.any { pinned.contains(it) }

        if (anySelectedPinned) {
            selectedAddresses.forEach {
                pinned.remove(it)
            }
        } else {
            selectedAddresses.forEach {
                pinned.add(it)
            }
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

        selectionOverlays.values.forEach { it.visibility = View.GONE }
        selectionOverlays.clear()
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

        val normalized = address.trim().uppercase(Locale.US)
        val isMeshConversation = normalized.matches(
            Regex("MJ-[A-Z0-9]{6}")
        )

        intent.putExtra("phone", address)

        if (isMeshConversation) {
            intent.putExtra("mesh_id", normalized)
            intent.putExtra("offline_mesh", true)
        }

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

    private fun showMeshIdSettings() {
        meshPopup?.dismiss()
        isMeshPanelVisible = false
        isSettingsPageVisible = true
        isMeshLogPageVisible = false

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var meshId = prefs.getString(MESH_ID, null)

        if (meshId.isNullOrBlank()) {
            meshId = "MJ-" + java.util.UUID.randomUUID()
                .toString()
                .replace("-", "")
                .take(6)
                .uppercase(Locale.US)

            prefs.edit().putString(MESH_ID, meshId).apply()
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 245, 248))
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "Settings"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(20, 20, 24))
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        val close = TextView(this).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(90, 90, 96))
            background = solidDrawable(
                Color.WHITE,
                dp(18).toFloat()
            )

            setOnClickListener {
                setContentView(createHomeScreen())
                updateMeshStatus()
            }
        }

        header.addView(
            close,
            LinearLayout.LayoutParams(dp(42), dp(42))
        )

        page.addView(header)

        val meshIdCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = solidDrawable(
                Color.WHITE,
                dp(22).toFloat()
            )
        }

        meshIdCard.addView(
            TextView(this).apply {
                text = "Mesh ID"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(25, 25, 30))
            }
        )

        meshIdCard.addView(
            TextView(this).apply {
                text = meshId
                textSize = 15f
                setTextColor(secondaryText)
                setPadding(0, dp(5), 0, 0)
            }
        )

        val editMeshId = TextView(this).apply {
            text = "Edit"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(35, 35, 40))
            background = solidDrawable(
                Color.rgb(245, 245, 248),
                dp(14).toFloat()
            )
            setPadding(dp(14), dp(9), dp(14), dp(9))

            setOnClickListener {
                showMeshIdEditDialog(meshId ?: "")
            }
        }

        meshIdCard.addView(
            editMeshId,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
            ).apply {
                topMargin = dp(12)
            }
        )

        page.addView(
            meshIdCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )

        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = solidDrawable(
                Color.WHITE,
                dp(22).toFloat()
            )

            setOnClickListener {
                showMeshLog()
            }
        }

        val logIcon = TextView(this).apply {
            text = "{ }"
            textSize = 18f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(35, 35, 40))
            background = solidDrawable(
                Color.rgb(245, 245, 248),
                dp(18).toFloat()
            )
        }

        logCard.addView(
            logIcon,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        val logTitle = TextView(this).apply {
            text = "Log"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(25, 25, 30))
            setPadding(dp(14), 0, 0, 0)
        }

        logCard.addView(
            logTitle,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val arrow = TextView(this).apply {
            text = "›"
            textSize = 28f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER
        }

        logCard.addView(
            arrow,
            LinearLayout.LayoutParams(dp(28), dp(48))
        )

        page.addView(
            logCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(72)
            ).apply {
                topMargin = dp(10)
            }
        )

        setContentView(page)
    }

    private fun showMeshIdEditDialog(currentMeshId: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = solidDrawable(
                Color.WHITE,
                dp(20).toFloat()
            )
        }

        val title = TextView(this).apply {
            text = "Mesh ID"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(25, 25, 30))
        }

        val description = TextView(this).apply {
            text = "Your ID is used to identify this device for nearby offline messaging."
            textSize = 14f
            setTextColor(secondaryText)
            setPadding(0, dp(8), 0, dp(14))
        }

        val input = EditText(this).apply {
            setText(currentMeshId)
            textSize = 18f
            setSingleLine(true)
            hint = "MJ-XXXXXX"
            setSelectAllOnFocus(true)
        }

        val save = TextView(this).apply {
            text = "Save"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = solidDrawable(
                Color.rgb(35, 35, 40),
                dp(12).toFloat()
            )
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

        val popup = PopupWindow(
            card,
            dp(310),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawableCompat.white())
            isOutsideTouchable = true
            elevation = dp(18).toFloat()
        }

        save.setOnClickListener {
            val value = input.text.toString().trim().uppercase(Locale.US)

            if (!value.matches(Regex("MJ-[A-Z0-9]{6}"))) {
                logMesh("ERROR | Invalid Mesh ID format")
                popup.dismiss()
                return@setOnClickListener
            }

            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(MESH_ID, value)
                .apply()

            logMesh("Mesh ID saved | $value")
            popup.dismiss()
            showMeshIdSettings()
        }

        card.addView(title)
        card.addView(description)
        card.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            ).apply {
                bottomMargin = dp(14)
            }
        )
        card.addView(
            save,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            )
        )

        popup.showAtLocation(
            window.decorView,
            Gravity.CENTER,
            0,
            0
        )
    }

    private fun showMeshLog() {
        isMeshPanelVisible = false
        isSettingsPageVisible = false
        isMeshLogPageVisible = true

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 245, 248))
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "Mesh Log"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(20, 20, 24))
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        val close = TextView(this).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(90, 90, 96))
            background = solidDrawable(
                Color.WHITE,
                dp(18).toFloat()
            )

            setOnClickListener {
                showMeshIdSettings()
            }
        }

        header.addView(
            close,
            LinearLayout.LayoutParams(dp(42), dp(42))
        )

        page.addView(header)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val clear = TextView(this).apply {
            text = "Clear"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(35, 35, 40))
            background = solidDrawable(
                Color.WHITE,
                dp(14).toFloat()
            )
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener {
                clearMeshLog()
            }
        }

        val copy = TextView(this).apply {
            text = "Copy"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(35, 35, 40))
            background = solidDrawable(
                Color.WHITE,
                dp(14).toFloat()
            )
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener {
                copyMeshLog()
            }
        }

        actions.addView(clear)
        actions.addView(
            copy,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(8)
            }
        )

        page.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        meshLogText = TextView(this).apply {
            text = synchronized(meshLogLines) {
                meshLogLines.joinToString("\n")
            }
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(45, 45, 50))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = solidDrawable(
                Color.WHITE,
                dp(18).toFloat()
            )
        }

        val scroll = ScrollView(this).apply {
            addView(
                meshLogText,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        page.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(10)
            }
        )

        setContentView(page)
        meshLogText?.post {
            scroll.fullScroll(View.FOCUS_DOWN)
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


    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        meshPopup?.dismiss()
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
