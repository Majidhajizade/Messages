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
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
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
import org.json.JSONArray
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
    private var isDarkMode = false
    private val screenBackground: Int
        get() = if (isDarkMode) Color.rgb(12, 12, 14) else Color.WHITE
    private val primaryText: Int
        get() = if (isDarkMode) Color.WHITE else Color.BLACK
    private val secondaryThemeText: Int
        get() = if (isDarkMode) Color.rgb(175, 175, 185) else secondaryText

    private lateinit var messagesContainer: LinearLayout
    private lateinit var messagesScroll: android.widget.ScrollView
    private var showingArchivedChats = false
    private var archivedAvatarView: View? = null
    private var archivedChatsRowView: View? = null
    private var archivedChatsRowHiddenByScroll = false


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
    private val selectionDragHandles = mutableMapOf<String, View>()
    private var selectionMode = false
    private var selectionBackCallback: OnBackInvokedCallback? = null
    private lateinit var selectionHeader: LinearLayout
    private lateinit var selectionSelectedText: TextView
    private lateinit var selectionActionBar: LinearLayout
private lateinit var selectionPinIcon: android.widget.ImageView
private lateinit var selectionNotificationIcon: android.widget.ImageView
companion object {
        private const val SMS_PERMISSION_REQUEST = 2001
        private const val PREFS = "messages_settings"
        private const val MESH_ID = "mesh_id"
        private const val PINNED = "pinned_numbers"
        private const val PINNED_ORDER = "pinned_order"
        private const val BLOCKED = "blocked_numbers"
        private const val MUTED = "muted_numbers"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDarkMode = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean("dark_mode", false)
        ensureNearbyPermissions()

        window.statusBarColor = screenBackground
        window.navigationBarColor = screenBackground
        window.decorView.systemUiVisibility =
            if (isDarkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

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

    private fun toggleThemeWithAnimation() {
        val oldScrollY = if (::messagesScroll.isInitialized) messagesScroll.scrollY else 0
        isDarkMode = !isDarkMode

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean("dark_mode", isDarkMode)
            .apply()

        window.statusBarColor = screenBackground
        window.navigationBarColor = screenBackground
        window.decorView.systemUiVisibility =
            if (isDarkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val newRoot = createHomeScreen()
        setContentView(newRoot)
        loadMessages()

        newRoot.post {
            if (oldScrollY > 0) messagesScroll.scrollTo(0, oldScrollY)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val radius = kotlin.math.hypot(
                    newRoot.width.toDouble(),
                    newRoot.height.toDouble()
                ).toFloat()
                val reveal = android.view.ViewAnimationUtils.createCircularReveal(
                    newRoot,
                    0,
                    newRoot.height,
                    0f,
                    radius
                )
                reveal.duration = 520L
                reveal.interpolator =
                    android.view.animation.AccelerateDecelerateInterpolator()
                reveal.start()
            } else {
                newRoot.alpha = 0f
                newRoot.animate().alpha(1f).setDuration(260L).start()
            }
        }
    }

    private fun createHomeScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(screenBackground)
            setPadding(0, 0, 0, dp(12))
            clipChildren = false
            clipToPadding = false
        }

        selectionHeader = createSelectionHeader().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(84)
            )
        }
        selectionHeader.visibility = View.GONE

        selectionActionBar = createSelectionActionBar()
        selectionActionBar.visibility = View.GONE

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(screenBackground)
            setPadding(dp(20), 0, dp(20), 0)
        }
        homeHeader = header

        val title = TextView(this).apply {
            text = if (showingArchivedChats) "Archived chats" else "Messages"
            textSize = 28f
            setTextColor(primaryText)
            typeface = Typeface.DEFAULT_BOLD
            translationY = dp(5).toFloat()
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
            textSize = 26f
            setTextColor(primaryText)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            translationY = dp(5).toFloat()
            contentDescription = "More"
            translationX = dp(5).toFloat()
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
            setBackgroundColor(screenBackground)
            clipChildren = false
            clipToPadding = false
            elevation = 0f
        }

        headerLayer.addView(
            header,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(84)
            )
        )

        headerLayer.addView(
            selectionHeader,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(84)
            )
        )

        root.addView(
            headerLayer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(84)
            )
        )

        messagesContainer = LinearLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(screenBackground)
            clipToOutline = false
            setPadding(0, 0, 0, 0)
        }
        messagesScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(screenBackground)

            isVerticalScrollBarEnabled = false
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            verticalScrollbarPosition = View.SCROLLBAR_POSITION_RIGHT
            setScrollBarSize(dp(2))
            setPadding(0, paddingTop, 0, paddingBottom)
            clipToPadding = false

            val scrollContent = LinearLayout(this@MainActivity).apply {
                setPadding(0, 0, 0, 0)
                clipToPadding = false
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(screenBackground)
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
            clipChildren = true
            clipToPadding = true
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
                setColors(avatarGradientColors("A"))
                orientation = GradientDrawable.Orientation.TL_BR
            }
            elevation = dp(18).toFloat()
            isClickable = true
            isFocusable = true

            addView(
                MessengerComposeIcon(this@MainActivity),
                FrameLayout.LayoutParams(
                    dp(48),
                    dp(48),
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
                rightMargin = dp(42)
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

            // Once the archive row moves under the header by half its avatar,
            // remove it for this list session and allow pull-down to add it again.
            val archiveRow = archivedChatsRowView
            val archiveAvatar = archivedAvatarView
            if (!showingArchivedChats &&
                archiveRow != null &&
                archiveAvatar != null &&
                scrollY >= (archiveAvatar.height.takeIf { it > 0 } ?: dp(52)) / 2) {

                val rowHeight = archiveRow.height.takeIf { it > 0 } ?: dp(76)
                messagesContainer.removeView(archiveRow)
                archivedChatsRowView = null
                archivedAvatarView = null
                archivedChatsRowHiddenByScroll = true

                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("archived_chats_row_enabled", false)
                    .apply()

                messagesScroll.scrollTo(0, (scrollY - rowHeight).coerceAtLeast(0))
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

        val chatLayerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply {
            topMargin = 0
            rightMargin = -dp(20)
        }

        val pullOverlay = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            isClickable = false
        }
        val pullBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(22), dp(8))
            visibility = View.GONE
            alpha = 0f
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = 0f
                setColor(Color.rgb(190, 190, 195))
            }
        }
        val arrowCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }
        val arrow = TextView(this).apply {
            text = "↓"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(70, 70, 75))
        }
        arrowCircle.addView(arrow, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        pullBanner.addView(arrowCircle, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            rightMargin = dp(14)
        })
        val pullLabel = TextView(this).apply {
            text = "Pull down for archive"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        pullBanner.addView(pullLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        pullOverlay.addView(pullBanner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(68), Gravity.TOP
        ).apply {
            leftMargin = -dp(20)
            rightMargin = 0
            topMargin = 0
        })
        root.addView(chatLayer, chatLayerParams)
        chatLayer.addView(pullOverlay, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        pullBanner.isClickable = false

        var pullStartY = 0f
        var pullStartX = 0f
        var pullStartScrollY = 0
        var pullDistance = 0f
        var pulling = false
        val pullThreshold = dp(76).toFloat()
        val maxPull = dp(136).toFloat()

        fun updatePullBanner(distance: Float) {
            val d = distance.coerceIn(0f, maxPull)
            val ready = d >= pullThreshold
            val bannerColor = if (ready) {
                Color.rgb(0, 129, 255)
            } else {
                Color.rgb(190, 190, 195)
            }

            val params = pullBanner.layoutParams
            params.height = dp(68) + (d * 0.5f).toInt()
            pullBanner.layoutParams = params
            pullBanner.translationY = 0f
            pullBanner.alpha = (d / dp(12).toFloat()).coerceIn(0f, 1f)
            pullBanner.background = GradientDrawable().apply {
                cornerRadius = 0f
                setColor(bannerColor)
            }

            // حرکت کششی با مقاومت؛ فهرست کادر را از جلو می‌پوشاند.
            messagesScroll.translationY = d * 0.72f
            pullLabel.text = if (ready)
                "Release for archive" else "Pull down for archive"
            arrow.animate().rotation(if (ready) 180f else 0f)
                .setDuration(90L).start()
            pullBanner.visibility = View.VISIBLE
        }

        messagesScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pullStartY = event.rawY
                    pullStartX = event.rawX
                    pullStartScrollY = messagesScroll.scrollY
                    pullDistance = 0f
                    pulling = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - pullStartY
                    val dx = event.rawX - pullStartX

                    if (showingArchivedChats) {
                        if (messagesScroll.scrollY <= dp(2) &&
                            dy < -dp(24) &&
                            -dy > kotlin.math.abs(dx) * 1.5f) {
                            messagesScroll.animate().cancel()
                            messagesScroll.animate()
                                .translationY(-dp(48).toFloat())
                                .alpha(0f)
                                .setDuration(220L)
                                .setInterpolator(
                                    android.view.animation.AccelerateDecelerateInterpolator()
                                )
                                .withEndAction {
                                    showingArchivedChats = false
                                    setContentView(createHomeScreen())
                                    loadMessages()
                                    messagesScroll.post {
                                        messagesScroll.scrollTo(0, 0)
                                        messagesScroll.translationY = 0f
                                        messagesScroll.alpha = 1f
                                    }
                                }.start()
                            true
                        } else {
                            false
                        }
                    } else if (
                        messagesScroll.scrollY == 0 &&
                        archivedAvatarView == null &&
                        !getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getBoolean("archived_chats_row_enabled", false) &&
                        (
                            pulling ||
                            (
                                dy - pullStartScrollY > dp(24) &&
                                dy > kotlin.math.abs(dx) * 1.5f
                            )
                        )
                    ) {
                        pulling = true
                        pullDistance = (dy - pullStartScrollY).coerceAtLeast(0f)
                        updatePullBanner(pullDistance)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!pulling) {
                        false
                    } else {
                        val ready = event.actionMasked == MotionEvent.ACTION_UP &&
                            pullDistance >= pullThreshold
                        pulling = false
                        pullDistance = 0f
                        pullBanner.animate().cancel()

                        if (ready) {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putBoolean("archived_chats_row_enabled", true)
                                .apply()
                            archivedChatsRowHiddenByScroll = false
                            messagesScroll.scrollTo(0, 0)
                            loadMessages()

                            pullBanner.post {
                                val avatar = archivedAvatarView
                                if (avatar == null || avatar.width == 0 ||
                                    pullBanner.width == 0) {
                                    pullBanner.animate().alpha(0f)
                                        .setDuration(180L)
                                        .withEndAction {
                                            pullBanner.visibility = View.GONE
                                            pullBanner.alpha = 0f
                                            pullBanner.layoutParams.height = dp(68)
                                            pullBanner.requestLayout()
                                            messagesScroll.translationY = 0f
                                        }.start()
                                } else {
                                    val bannerPos = IntArray(2)
                                    val avatarPos = IntArray(2)
                                    pullBanner.getLocationOnScreen(bannerPos)
                                    avatar.getLocationOnScreen(avatarPos)

                                    val dx = avatarPos[0] + avatar.width / 2f -
                                        (bannerPos[0] + pullBanner.width / 2f)
                                    val dy = avatarPos[1] + avatar.height / 2f -
                                        (bannerPos[1] + pullBanner.height / 2f)

                                    pullBanner.pivotX = pullBanner.width / 2f
                                    pullBanner.pivotY = pullBanner.height / 2f
                                    pullBanner.animate()
                                        .translationX(dx)
                                        .translationY(dy)
                                        .scaleX(0.12f)
                                        .scaleY(0.12f)
                                        .alpha(0f)
                                        .setDuration(420L)
                                        .setInterpolator(
                                            android.view.animation.AccelerateDecelerateInterpolator()
                                        )
                                        .withEndAction {
                                            pullBanner.visibility = View.GONE
                                            pullBanner.alpha = 0f
                                            pullBanner.translationX = 0f
                                            pullBanner.translationY = 0f
                                            pullBanner.scaleX = 1f
                                            pullBanner.scaleY = 1f
                                            pullBanner.layoutParams.height = dp(68)
                                            pullBanner.requestLayout()
                                            messagesScroll.translationY = 0f
                                        }.start()
                                }
                            }
                        } else {
                            val interpolator =
                                android.view.animation.AccelerateDecelerateInterpolator()

                            pullBanner.animate().cancel()
                            messagesScroll.animate().cancel()

                            // بنر و فهرست هم‌زمان و نرم به جای خود برمی‌گردند.
                            messagesScroll.animate()
                                .translationY(0f)
                                .setDuration(240L)
                                .setInterpolator(interpolator)
                                .start()

                            pullBanner.animate()
                                .alpha(0f)
                                .translationX(0f)
                                .translationY(0f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(240L)
                                .setInterpolator(interpolator)
                                .withEndAction {
                                    pullBanner.visibility = View.GONE
                                    pullBanner.alpha = 0f
                                    pullBanner.layoutParams.height = dp(68)
                                    pullBanner.requestLayout()
                                }
                                .start()
                        }
                        true
                    }
                }

                else -> pulling
            }
        }

        return root
    }

    private fun loadMessages() {
        messagesContainer.removeAllViews()
        archivedAvatarView = null
        archivedChatsRowView = null
        rowViews.clear()
        selectionOverlays.clear()
        selectionDragHandles.clear()

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
        val archivedAddresses = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet("archived_conversations", emptySet())
            .orEmpty()

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

        if (showingArchivedChats) {
            conversations.keys.retainAll(archivedAddresses)
        } else {
            archivedAddresses.forEach { conversations.remove(it) }
            if (getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean("archived_chats_row_enabled", false)) {
                addArchivedChatsRow()
            }
        }

        if (conversations.isEmpty()) {
            showEmptyState()
            return
        }

        val pinned = getPinnedNumbers()
        val pinnedOrder = getPinnedOrder()
        pinned.forEach { address ->
            if (!pinnedOrder.contains(address)) pinnedOrder.add(address)
        }
        if (pinnedOrder != getPinnedOrder()) savePinnedOrder(pinnedOrder)

        val pinnedRank = pinnedOrder.withIndex().associate {
            it.value to it.index
        }

        conversations.values
            .sortedWith(
                compareByDescending<ConversationData> {
                    pinned.contains(it.address)
                }.thenBy {
                    if (pinned.contains(it.address)) {
                        pinnedRank[it.address] ?: Int.MAX_VALUE
                    } else {
                        Int.MAX_VALUE
                    }
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

        if (selectionMode) {
            selectedAddresses.retainAll(rowViews.keys)
            if (selectedAddresses.isNotEmpty()) {
                updateSelectionUI()
            } else {
                exitSelectionMode()
            }
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
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColors(avatarGradientColors(name))
                orientation = GradientDrawable.Orientation.TL_BR
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
                setColor(screenBackground)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), if (isDarkMode) Color.rgb(55, 55, 62) else Color.rgb(230, 230, 235))
            }

            elevation = dp(18).toFloat()
        }

        val themeItem = createMenuItem(
            title = if (isDarkMode) "Day Mode" else "Night Mode",
            subtitle = "",
            iconRes = if (isDarkMode) R.drawable.ic_day_mode else R.drawable.ic_night_mode
        )
        themeItem.setOnClickListener {
            meshPopup?.dismiss()
            toggleThemeWithAnimation()
        }
        card.addView(
            themeItem,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
        )

        val mesh = createMenuItem(
            title = "Mesh",
            subtitle = ""
        )

        mesh.setOnClickListener {
            showMeshPanel()
        }

        val settings = createMenuItem(
            title = "Settings",
            subtitle = ""
        )

        settings.setOnClickListener {
            meshPopup?.dismiss()
            showMeshIdSettings()
        }

        card.addView(
            mesh,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
        )

        card.addView(
            settings,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48))
        )

        val savedMeshDevices = loadSavedMeshDevices()

        if (selectedMeshId == null && savedMeshDevices.isNotEmpty()) {
            selectedMeshId = savedMeshDevices.first()
            meshManager?.setTargetMeshId(selectedMeshId)
        }

        meshPopup = PopupWindow(
            card,
            ViewGroup.LayoutParams.WRAP_CONTENT,
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
        subtitle: String,
        iconRes: Int? = null
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)

            if (iconRes != null) {
                addView(
                    ImageView(this@MainActivity).apply {
                        setImageResource(iconRes)
                        imageTintList = android.content.res.ColorStateList.valueOf(primaryText)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    },
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                        rightMargin = dp(12)
                    }
                )
            }

            val labels = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(
                labels,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            background = GradientDrawable().apply {
                setColor(screenBackground)
                cornerRadius = dp(14).toFloat()
            }

            labels.addView(
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 17f
                    setTextColor(primaryText)
                    typeface = Typeface.DEFAULT_BOLD
                }
            )

            labels.addView(
                TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(secondaryThemeText)
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (showingArchivedChats) {
            showingArchivedChats = false
            setContentView(createHomeScreen())
            loadMessages()
            return
        }
        if (selectionMode) {
            exitSelectionMode()
            return
        }

        if (isMeshLogPageVisible) {
            isMeshLogPageVisible = false
            showMeshIdSettings()
            return
        }

        if (isSettingsPageVisible) {
            isSettingsPageVisible = false
            setContentView(createHomeScreen())
            updateMeshStatus()
            return
        }

        if (isMeshPanelVisible) {
            returnToHome()
            return
        }

        super.onBackPressed()
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

    private fun addArchivedChatsRow() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(screenBackground)
            isClickable = true
            isFocusable = true
            contentDescription = "Archived chats"
        }

        val iconFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isDarkMode) Color.rgb(35, 65, 100)
                    else Color.rgb(225, 240, 255))
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.archive_down)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Archive"
        }
        iconFrame.addView(icon, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))

        archivedAvatarView = iconFrame
        archivedChatsRowView = row
        iconFrame.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(0, 129, 255))
        }
        row.addView(iconFrame, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            rightMargin = dp(12)
        })
        row.addView(TextView(this).apply {
            text = "Archived chats"
            textSize = 17f
            setTextColor(primaryText)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        row.setOnClickListener {
            showingArchivedChats = true
            setContentView(createHomeScreen())
            loadMessages()
        }
        messagesContainer.addView(row, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(76)
        ))
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
            clipChildren = false
            clipToPadding = false
        }

        val archivePanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = solidDrawable(Color.TRANSPARENT, 0f)
        }

        val archiveBlueHalf = View(this).apply {
            setBackgroundColor(Color.rgb(192, 192, 192))
        }

        val archiveRightHalf = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(48), 0)
            setBackgroundColor(Color.rgb(0, 129, 255))
        }

        val archiveIcon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.archive_down)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Archive"
        }

        val archiveLabel = TextView(this).apply {
            text = "Archive"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        archiveRightHalf.addView(
            archiveIcon,
            LinearLayout.LayoutParams(dp(26), dp(26))
        )
        archiveRightHalf.addView(
            archiveLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        archivePanel.addView(
            archiveBlueHalf,
            LinearLayout.LayoutParams(0, dp(68), 1f)
        )
        archivePanel.addView(
            archiveRightHalf,
            LinearLayout.LayoutParams(0, dp(68), 1f)
        )

        actionLayer.addView(
            archivePanel,
            FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.78f).toInt(),
                dp(68),
                Gravity.RIGHT or Gravity.CENTER_VERTICAL
            ).apply {
                rightMargin = -dp(20)
            }
        )

        val actionBackground = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (isDarkMode) visibility = View.GONE
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
        archivePanel.bringToFront()

        val foreground = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            background = null
            setPadding(dp(4), dp(8), dp(4), dp(8))
            clipToOutline = false
            elevation = 0f
        }

        val contactFrame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            translationX = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                rightMargin = dp(12)
            }
        }

        val contactName = getContactName(address)
        val isUnsavedNumber = contactName.isNullOrBlank() &&
            address.any { it.isDigit() }

        if (isUnsavedNumber) {
            val contact = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_profile_unsaved)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(13), dp(13), dp(13), dp(13))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(235, 237, 240))
                    shape = GradientDrawable.OVAL
                }
                contentDescription = "Unknown contact"
            }
            contactFrame.addView(
                contact,
                FrameLayout.LayoutParams(dp(52), dp(52))
            )
        } else {
            val avatarName = contactName?.trim().orEmpty().ifEmpty { address.trim() }
            val avatarLetter = avatarName.firstOrNull()
                ?.uppercaseChar()
                ?.toString()
                ?: "U"

            val contact = TextView(this).apply {
                text = avatarLetter
                textSize = 21f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColors(avatarGradientColors(avatarName))
                    orientation = GradientDrawable.Orientation.TL_BR
                    shape = GradientDrawable.OVAL
                }
            }
            contactFrame.addView(
                contact,
                FrameLayout.LayoutParams(dp(52), dp(52))
            )
        }

        val selectionOverlay = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.check)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            visibility = View.GONE
            contentDescription = "Selected"
            translationX = dp(5).toFloat()
            translationY = dp(5).toFloat()
        }

        selectionOverlay.tag = 1001
        selectionOverlays[address] = selectionOverlay

        contactFrame.addView(
            selectionOverlay,
            FrameLayout.LayoutParams(dp(26), dp(26)).apply {
                gravity = Gravity.BOTTOM or Gravity.RIGHT
                rightMargin = 0
                bottomMargin = 0
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
            setTextColor(primaryText)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_LOCALE
        }

        val preview = TextView(this).apply {
            text = body
            textSize = 15f
            setTextColor(secondaryThemeText)
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

        val pinned = getPinnedNumbers().contains(address)

        val dateText = TextView(this).apply {
            text = formatShortDate(date)
            translationX = -dp(12).toFloat()
            textSize = 11f
            setTextColor(
                if (isDarkMode) Color.rgb(205, 205, 212)
                else Color.rgb(125, 125, 132)
            )
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isSingleLine = true
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        }

        if (pinned) {
            val dateCapsule = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(dp(5), 0, dp(5), 0)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(240, 240, 240))
                    cornerRadius = dp(12).toFloat()
                    setStroke(
                        dp(1),
                        if (isDarkMode) Color.rgb(78, 78, 86)
                        else Color.rgb(205, 205, 212)
                    )
                }
            }

            dateCapsule.addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_pin)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setColorFilter(
                        if (isDarkMode) Color.rgb(190, 190, 198)
                        else Color.rgb(130, 130, 138)
                    )
                    contentDescription = "Pinned"
                },
                LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                    rightMargin = dp(6)
                }
            )

            dateCapsule.addView(
                dateText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(20)
                )
            )

            dateText.minWidth = dp(44)

            metaContainer.addView(
                dateCapsule,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(24)
                )
            )

            val dragHandle = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(2), dp(3), dp(2))
                contentDescription = "Reorder pinned conversation"
                visibility = View.GONE
            }

            repeat(2) { index ->
                dragHandle.addView(
                    View(this@MainActivity).apply {
                        background = GradientDrawable().apply {
                            setColor(
                                if (isDarkMode) Color.rgb(145, 145, 153)
                                else Color.rgb(165, 165, 172)
                            )
                            cornerRadius = dp(1).toFloat()
                        }
                    },
                    LinearLayout.LayoutParams(dp(18), dp(2)).apply {
                        if (index == 0) bottomMargin = dp(4)
                    }
                )
            }

            selectionDragHandles[address] = dragHandle

            var dragStartY = 0f
            var didDrag = false
            var lastTargetAddress: String? = null

            dragHandle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartY = event.rawY
                        didDrag = false
                        lastTargetAddress = address
                        dragHandle.alpha = 0.65f
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(event.rawY - dragStartY) > dp(8)) {
                            didDrag = true
                        }
                        if (didDrag) {
                            lastTargetAddress = findPinnedAddressAtY(event.rawY)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        dragHandle.alpha = 1f
                        if (didDrag) {
                            val finalTargetAddress =
                                findPinnedAddressAtY(event.rawY)
                                    ?: lastTargetAddress
                            reorderPinnedConversation(
                                address,
                                event.rawY,
                                finalTargetAddress
                            )
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        dragHandle.alpha = 1f
                        true
                    }

                    else -> true
                }
            }

            metaContainer.addView(
                dragHandle,
                LinearLayout.LayoutParams(dp(26), dp(18)).apply {
                    topMargin = 0
                }
            )
        } else {
            metaContainer.addView(
                dateText,
                LinearLayout.LayoutParams(dp(54), dp(22))
            )
        }

        foreground.addView(
            metaContainer,
            LinearLayout.LayoutParams(
                if (pinned) dp(88) else dp(54),
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



        actionLayer.tag = address
        rowViews[address] = name

        setupRowTouch(
            actionLayer,
            foreground,
            address,
            callAction,
            deleteAction,
            selectionOverlay,
            archivePanel
        )

        messagesContainer.addView(
            actionLayer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(76)
            ).apply {
                leftMargin = dp(2)
                rightMargin = dp(2)
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
        selectionOverlay: android.widget.ImageView,
        archivePanel: View
    ) {
        var downX = 0f
        var downY = 0f
        var moved = false
        var longPressed = false
        var archiveSwipe = false
        var archiveReady = false
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val archiveLimit = screenWidth * 0.75f
        val archiveThreshold = screenWidth / 2f

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
                    archiveSwipe = false
                    archiveReady = false
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

                    if (!selectionMode && dx < -dp(10) && abs(dx) > abs(dy)) {
                        archiveSwipe = true
                        foreground.parent?.requestDisallowInterceptTouchEvent(true)
                        archivePanel.visibility = View.VISIBLE
                        foreground.translationX = dx.coerceAtLeast(-archiveLimit)
                        foreground.background = GradientDrawable().apply {
                            setColor(if (isDarkMode) Color.rgb(28, 28, 30) else Color.WHITE)
                            cornerRadii = floatArrayOf(
                                0f, 0f,
                                dp(14).toFloat(), dp(14).toFloat(),
                                dp(14).toFloat(), dp(14).toFloat(),
                                0f, 0f
                            )
                        }
                        // Archive only when the floating chat's right edge reaches screen center.
                        val parentLocation = IntArray(2)
                        container.getLocationOnScreen(parentLocation)
                        val chatRightEdge = parentLocation[0] +
                            foreground.left + foreground.width +
                            foreground.translationX
                        archiveReady = chatRightEdge <= screenWidth / 2f

                        val leftHalf = (archivePanel as? android.widget.LinearLayout)
                            ?.getChildAt(0)
                        val rightHalf = (archivePanel as? android.widget.LinearLayout)
                            ?.getChildAt(1)

                        // Blue before the midpoint; gray once archiving is ready.
                        val panelColor = if (archiveReady) {
                            Color.rgb(192, 192, 192)
                        } else {
                            Color.rgb(0, 129, 255)
                        }
                        leftHalf?.setBackgroundColor(panelColor)
                        rightHalf?.setBackgroundColor(panelColor)
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)

                    if (archiveSwipe) {
                        if (archiveReady) {
                            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                            val archived = prefs.getStringSet(
                                "archived_conversations",
                                emptySet()
                            ).orEmpty().toMutableSet()
                            archived.add(address)
                            prefs.edit().putStringSet(
                                "archived_conversations",
                                archived
                            ).apply()
                            foreground.translationX = 0f
                            archivePanel.visibility = View.GONE
                            loadMessages()
                        } else {
                            foreground.animate()
                                .translationX(0f)
                                .setDuration(280L)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .start()
                            foreground.background = null
                            archivePanel.visibility = View.GONE
                        }
                        return@setOnTouchListener true
                    }

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
                    if (archiveSwipe) {
                        foreground.animate()
                                .translationX(0f)
                                .setDuration(280L)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .start()
                        foreground.background = null
                        archivePanel.visibility = View.GONE
                    }
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

        val cancel = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_cross)
            if (isDarkMode) setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Cancel selection"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                exitSelectionMode()
            }
        }

        header.addView(
            cancel,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        selectionSelectedText = TextView(this).apply {
            text = "1 Selected"
            textSize = 19f
            setTextColor(if (isDarkMode) Color.WHITE else Color.BLACK)
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

        return header
    }

    private fun createSelectionActionBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(if (isDarkMode) Color.rgb(43, 43, 49) else Color.WHITE)
                cornerRadius = dp(39).toFloat()
                setStroke(
                    dp(1),
                    if (isDarkMode) Color.rgb(67, 67, 75)
                    else Color.rgb(225, 225, 230)
                )
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

            if (iconType == 0) {
                selectionNotificationIcon =
                    NotificationSlashIcon(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_notification)
                        if (isDarkMode) setColorFilter(Color.WHITE)
                        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                        contentDescription = label
                    }
                addView(
                    selectionNotificationIcon,
                    LinearLayout.LayoutParams(dp(26), dp(25))
                )
            } else if (iconType == 1) {
                addView(
                    android.widget.ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_delete)
                        setColorFilter(if (isDarkMode) Color.WHITE else Color.BLACK)
                        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                        contentDescription = label
                    },
                    LinearLayout.LayoutParams(dp(26), dp(25))
                )
            } else {
                selectionPinIcon = android.widget.ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_pin)
                    if (isDarkMode) setColorFilter(Color.WHITE)
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
                    setTextColor(if (isDarkMode) Color.WHITE else Color.rgb(35, 35, 38))
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
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            selectionBackCallback == null
        ) {
            selectionBackCallback = OnBackInvokedCallback {
                runOnUiThread {
                    if (selectionMode) exitSelectionMode()
                }
            }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                selectionBackCallback!!
            )
        }
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

        val muted = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(MUTED, emptySet()) ?: emptySet()
        val anySelectedMuted = selectedAddresses.any { muted.contains(it) }
        (selectionNotificationIcon as? NotificationSlashIcon)
            ?.setMuted(anySelectedMuted)

        val pinned = getPinnedNumbers()
        val anySelectedPinned = selectedAddresses.any { pinned.contains(it) }

        selectionPinIcon.setImageResource(
            if (anySelectedPinned) {
                R.drawable.ic_pin_slash
            } else {
                R.drawable.ic_pin
            }
        )

        selectionOverlays.forEach { (address, overlay) ->
            val selected = selectedAddresses.contains(address)

            overlay.visibility = if (selected) View.VISIBLE else View.GONE

            if (selected) {
                overlay.bringToFront()
                overlay.elevation = dp(8).toFloat()
            }

            overlay.invalidate()
        }

        val showDragHandles = selectionMode && pinned.size >= 2
        selectionDragHandles.forEach { (address, handle) ->
            handle.visibility = if (showDragHandles && pinned.contains(address)) {
                View.VISIBLE
            } else {
                View.GONE
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

        val anySelectedMuted = selectedAddresses.any { muted.contains(it) }

        if (anySelectedMuted) {
            selectedAddresses.forEach { muted.remove(it) }
        } else {
            muted.addAll(selectedAddresses)
        }

        prefs.edit().putStringSet(MUTED, muted).apply()
        updateSelectionUI()
        Toast.makeText(this, "Notifications updated", Toast.LENGTH_SHORT).show()
    }

    private fun pinSelected() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val pinned = prefs.getStringSet(PINNED, emptySet())?.toMutableSet()
            ?: mutableSetOf()

        val anySelectedPinned = selectedAddresses.any { pinned.contains(it) }

        val order = getPinnedOrder()

        if (anySelectedPinned) {
            selectedAddresses.forEach {
                pinned.remove(it)
                order.remove(it)
            }
        } else {
            selectedAddresses.forEach {
                pinned.add(it)
                if (!order.contains(it)) order.add(it)
            }
        }

        prefs.edit().putStringSet(PINNED, pinned).apply()
        savePinnedOrder(order)
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
        selectionBackCallback?.let {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
        selectionBackCallback = null

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
        selectionDragHandles.values.forEach { it.visibility = View.GONE }
    }

    private fun getPinnedNumbers(): Set<String> {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(PINNED, emptySet()) ?: emptySet()
    }

    private fun findPinnedAddressAtY(rawY: Float): String? {
        val pinned = getPinnedNumbers()
        for (index in 0 until messagesContainer.childCount) {
            val child = messagesContainer.getChildAt(index)
            val address = child.tag as? String ?: continue
            if (!pinned.contains(address)) continue

            val location = IntArray(2)
            child.getLocationOnScreen(location)
            if (rawY >= location[1] &&
                rawY <= location[1] + child.height
            ) {
                return address
            }
        }
        return null
    }

    private fun reorderPinnedConversation(
        address: String,
        rawY: Float,
        detectedTargetAddress: String? = null
    ) {
        if (!selectionMode) return

        val pinned = getPinnedNumbers()
        if (pinned.size < 2 || !pinned.contains(address)) return

        val targetAddress = detectedTargetAddress ?: findPinnedAddressAtY(rawY)
        if (targetAddress == null || !pinned.contains(targetAddress)) return

        val order = getPinnedOrder()
        pinned.forEach { if (!order.contains(it)) order.add(it) }

        val from = order.indexOf(address)
        val to = order.indexOf(targetAddress)
        if (from < 0 || to < 0 || from == to) return

        val moving = order.removeAt(from)
        order.add(to.coerceIn(0, order.size), moving)

        savePinnedOrder(order)
        loadMessages()
    }

    private fun getPinnedOrder(): MutableList<String> {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(PINNED_ORDER, null) ?: return mutableListOf()

        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index -> array.getString(index) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun savePinnedOrder(order: List<String>) {
        val array = JSONArray()
        order.forEach { array.put(it) }

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(PINNED_ORDER, array.toString())
            .apply()
    }

    private fun formatShortDate(timestamp: Long): String {
        return SimpleDateFormat(
            "MMM d",
            Locale.US
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

        private val plusPaint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.rgb(0, 166, 255)
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeWidth = dpLocal(3.5f)
        }

        init {
            setImageResource(R.drawable.floti)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(
                dpLocal(9f).toInt(),
                dpLocal(9f).toInt(),
                dpLocal(9f).toInt(),
                dpLocal(9f).toInt()
            )
            contentDescription = "New message"
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f
            val cy = height / 2f
            val arm = dpLocal(9f)

            canvas.drawLine(cx - arm, cy, cx + arm, cy, plusPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, plusPaint)
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
            LinearLayout.LayoutParams(dp(32), dp(32))
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
            Color.rgb(0, 166, 255),   // #00A6FF
            Color.rgb(38, 185, 154),
            Color.rgb(139, 120, 230),
            Color.rgb(245, 159, 66),
            Color.rgb(232, 93, 117),
            Color.rgb(50, 150, 216),
            Color.rgb(25, 169, 116),
            Color.rgb(193, 125, 223)
        )

        val first = name.trim().firstOrNull()?.uppercaseChar() ?: return colors[0]
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

    private class NotificationSlashIcon(
        context: android.content.Context
    ) : android.widget.ImageView(context) {

        private var slashProgress = 0f
        private val slashPaint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = android.graphics.Color.BLACK
            strokeWidth = 2f * resources.displayMetrics.density
            strokeCap = android.graphics.Paint.Cap.ROUND
            style = android.graphics.Paint.Style.STROKE
        }

        fun setMuted(muted: Boolean) {
            val target = if (muted) 1f else 0f
            if (slashProgress == target) return

            android.animation.ValueAnimator
                .ofFloat(slashProgress, target)
                .apply {
                    duration = 220L
                    interpolator =
                        android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        slashProgress = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)

            if (slashProgress <= 0f) return

            val inset = width * 0.22f
            val startX = inset
            val startY = height * 0.78f
            val endX = width - inset
            val endY = height * 0.22f
            val currentEndX = startX + (endX - startX) * slashProgress
            val currentEndY = startY + (endY - startY) * slashProgress

            canvas.drawLine(
                startX, startY, currentEndX, currentEndY, slashPaint
            )
        }
    }

}
