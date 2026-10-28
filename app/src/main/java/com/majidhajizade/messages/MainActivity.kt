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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private fun configureMainSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.Q
        ) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkMode
            isAppearanceLightNavigationBars = !isDarkMode
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private var securityGateShowing = false


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
        if (!MESH_ENABLED) return
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
        get() = if (isDarkMode) Color.BLACK else Color.WHITE
    private val primaryText: Int
        get() = if (isDarkMode) Color.WHITE else Color.BLACK
    private val secondaryThemeText: Int
        get() = if (isDarkMode) Color.rgb(175, 175, 185) else secondaryText

    private lateinit var messagesContainer: LinearLayout
    private var smsSearchQuery: String = ""
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
    private var headerMenuButton: View? = null
    private var meshStatusText: TextView? = null
    private var meshDevicesContainer: LinearLayout? = null
    private var meshLogText: TextView? = null
    private val meshLogLines = mutableListOf<String>()
    private val meshDevices = linkedMapOf<String, TextView>()
    private val meshDeviceRows = linkedMapOf<String, LinearLayout>()
    private val meshDeviceNames = linkedMapOf<String, String>()
    private val meshFloatingAvatars = linkedMapOf<String, View>()
    private var meshFloatingContainer: LinearLayout? = null
    private var isMainActivityResumed = false
    private val pendingMeshChatRequests = mutableSetOf<String>()
    private val outgoingMeshChatRequests = mutableSetOf<String>()
    private var selectedMeshId: String? = null
    private var isMeshPanelVisible = false
    private var meshBackgroundPlayer: android.media.MediaPlayer? = null
    private var meshBackgroundSurface: android.view.Surface? = null

    private fun releaseMeshBackgroundVideo() {
        meshBackgroundPlayer?.runCatching {
            setOnPreparedListener(null)
            setOnErrorListener(null)
            setOnInfoListener(null)
            if (isPlaying) pause()
            reset()
            release()
        }
        meshBackgroundPlayer = null

        meshBackgroundSurface?.runCatching { release() }
        meshBackgroundSurface = null
    }
    private var isSettingsPageVisible = false
    private var isMeshLogPageVisible = false
    private val rowViews = mutableMapOf<String, TextView>()
    private val selectedAddresses = linkedSetOf<String>()
    private val selectionOverlays = mutableMapOf<String, android.widget.ImageView>()
    private val selectionDragHandles = mutableMapOf<String, View>()
    private var selectionMode = false
    private var selectionBackCallback: OnBackInvokedCallback? = null
    private lateinit var selectionHeader: View
    private lateinit var selectionSelectedText: TextView
    private lateinit var selectionActionBar: View
private lateinit var selectionPinIcon: android.widget.ImageView
private lateinit var selectionNotificationIcon: android.widget.ImageView
companion object {
        const val MESH_ENABLED = false
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
        val themePrefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val themeMode = themePrefs.getString("theme_mode", null)
            ?: if (themePrefs.contains("dark_mode")) {
                if (themePrefs.getBoolean("dark_mode", false)) "night" else "day"
            } else {
                "system"
            }
        if (!themePrefs.contains("theme_mode")) {
            themePrefs.edit().putString("theme_mode", themeMode).apply()
        }
        isDarkMode = when (themeMode) {
            "day" -> false
            "night" -> true
            else -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        // Mesh موقتاً غیرفعال است؛ مجوزهای نزدیک‌برد درخواست نمی‌شوند.

        window.statusBarColor = screenBackground
        window.navigationBarColor = screenBackground
        window.decorView.systemUiVisibility =
            if (isDarkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        setContentView(createHomeScreen())
        configureMainSystemBars()
        // راه‌اندازی Mesh تا اطلاع ثانوی متوقف است.

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

        // درخواست مجوز Mesh موقتاً غیرفعال است.
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

    private fun showSecurityGate(sourceX: Int? = null, sourceY: Int? = null) {
        if (!AppSecurity.isEnabled(this) || securityGateShowing) return

        securityGateShowing = true
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(screenBackground)
        })
        configureMainSystemBars()

        AppSecurity.showUnlock(this, sourceX, sourceY) {
            securityGateShowing = false
            setContentView(createHomeScreen())
            configureMainSystemBars()
            if (checkSelfPermission(Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                loadMessages()
            } else {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.READ_SMS,
                        Manifest.permission.RECEIVE_SMS,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.READ_CONTACTS
                    ), SMS_PERMISSION_REQUEST
                )
            }
        }
    }

    private fun lockNow(sourceView: android.view.View? = null) {
        if (!AppSecurity.isEnabled(this) || securityGateShowing) return
        AppSecurity.requestManualLock(this)

        val location = IntArray(2)
        val sourceX = sourceView?.let {
            it.getLocationInWindow(location)
            location[0] + it.width / 2
        }
        val sourceY = sourceView?.let {
            it.getLocationInWindow(location)
            location[1] + it.height / 2
        }

        showSecurityGate(sourceX, sourceY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureMainSystemBars()
    }

    override fun onResume() {
        super.onResume()
        isMainActivityResumed = true

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val savedThemeMode = prefs.getString("theme_mode", null)
        val useSystemTheme = savedThemeMode == "system" ||
            (savedThemeMode == null && !prefs.contains("dark_mode"))

        val shouldShowSecurityGate = AppSecurity.isEnabled(this) &&
            AppSecurity.isAutoLockPending(this) &&
            !securityGateShowing

        if (useSystemTheme) {
            val systemDark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

            if (systemDark != isDarkMode) {
                if (securityGateShowing || shouldShowSecurityGate) {
                    // صفحه را هنگام نمایش یا آماده‌سازی قفل بازسازی نکن.
                    isDarkMode = systemDark
                } else {
                    applyThemeModeWithAnimation("system")
                }
            }
        }

        configureMainSystemBars()

        if (shouldShowSecurityGate) {
            showSecurityGate()
            return
        }

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
        applyThemeModeWithAnimation(if (isDarkMode) "day" else "night")
    }

    private fun applyThemeModeWithAnimation(mode: String) {
        val oldScrollY = if (::messagesScroll.isInitialized) messagesScroll.scrollY else 0
        isDarkMode = when (mode) {
            "day" -> false
            "night" -> true
            else -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString("theme_mode", mode)
            .putBoolean("dark_mode", isDarkMode)
            .apply()

        window.statusBarColor = screenBackground
        window.navigationBarColor = screenBackground
        window.decorView.systemUiVisibility =
            if (isDarkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val newRoot = createHomeScreen()
        setContentView(newRoot)
        configureMainSystemBars()
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

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                safe.left,
                safe.top,
                safe.right,
                0
            )
            insets
        }

        selectionHeader = createSelectionHeader().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(72)
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

        val searchButton = TextView(this).apply {
            text = "⌕"
            textSize = 32f
            setTextColor(primaryText)
            gravity = Gravity.CENTER
            contentDescription = "Search messages"
            setOnClickListener { showSmsSearchPage() }
        }

        searchButton.visibility = android.view.View.GONE
        header.addView(
            searchButton,
            LinearLayout.LayoutParams(dp(42), dp(60))
        )

        if (AppSecurity.isEnabled(this)) {
            val lockButton = ImageView(this).apply {
                setImageResource(R.drawable.lock_keyhole)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(10), dp(14), dp(10), dp(14))
                imageAlpha = 255
                setColorFilter(primaryText)
                contentDescription = "Lock app"
                isClickable = true
                isFocusable = true
                setOnClickListener { clickedView -> lockNow(clickedView) }
            }
            header.addView(
                lockButton,
                LinearLayout.LayoutParams(dp(42), dp(60))
            )
        }

        val menuButton = TextView(this).apply {
            text = "⋮"
            textSize = 26f
            setTextColor(primaryText)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            contentDescription = "More"
            translationX = dp(5).toFloat()
            setPadding(dp(8), 0, dp(2), 0)

            setOnClickListener {
                showHeaderMenu()
            }
        }

        headerMenuButton = menuButton
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
                setColor(Color.rgb(0, 127, 255))
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
        val pullBanner = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(14), dp(8), dp(22), dp(8))
            visibility = View.GONE
            alpha = 0f
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = 0f
                setColor(Color.rgb(190, 190, 195))
            }
        }

        val pullContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
        arrowCircle.addView(
            arrow,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
        )
        pullContent.addView(
            arrowCircle,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                rightMargin = dp(14)
            }
        )
        pullBanner.addView(
            pullContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.LEFT or Gravity.CENTER_VERTICAL
            )
        )

        val pullLabel = TextView(this).apply {
            text = "Pull down for archive"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
        }
        pullBanner.addView(
            pullLabel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(8)
            }
        )
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
                                    setArchiveScreen(false)
                                }.start()
                            true
                        } else {
                            false
                        }
                    } else if (
                        archivedAvatarView == null &&
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getStringSet("archived_conversations", emptySet())
                            .orEmpty().isNotEmpty() &&
                        !getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getBoolean("archived_chats_row_enabled", false) &&
                        (
                            pulling ||
                            (
                                messagesScroll.scrollY == 0 &&
                                pullStartScrollY == 0 &&
                                dy > dp(24) &&
                                dy > kotlin.math.abs(dx) * 1.5f
                            )
                        )
                    ) {
                        if (pulling) {
                            pullDistance = dy.coerceAtLeast(0f)
                            if (dy <= 0f) {
                                pulling = false
                                pullStartY = event.rawY
                                pullStartX = event.rawX
                                pullStartScrollY = messagesScroll.scrollY
                                messagesScroll.animate().cancel()
                                messagesScroll.translationY = 0f
                                pullBanner.animate().cancel()
                                pullBanner.alpha = 0f
                                pullBanner.visibility = View.GONE
                                pullBanner.layoutParams.height = dp(68)
                                pullBanner.requestLayout()
                                false
                            } else {
                                updatePullBanner(pullDistance)
                                true
                            }
                        } else {
                            pulling = true
                            pullDistance = dy
                            updatePullBanner(pullDistance)
                            true
                        }
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

        // Adapt Telegram's hidden archive row behavior to this ScrollView.
        // Capture this screen's container so an old observer cannot mutate
        // the container belonging to a subsequently created screen.
        val archiveContainerForScroll = messagesContainer
        messagesScroll.viewTreeObserver.addOnScrollChangedListener {
            val row = archivedChatsRowView
            if (
                !showingArchivedChats &&
                !archivedChatsRowHiddenByScroll &&
                row != null &&
                row.parent === archiveContainerForScroll &&
                row.height > 0 &&
                messagesScroll.scrollY >= row.bottom
            ) {
                val oldScrollY = messagesScroll.scrollY
                val removedHeight = row.bottom.coerceAtLeast(0)

                archivedChatsRowHiddenByScroll = true
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("archived_chats_row_enabled", false)
                    .apply()

                archiveContainerForScroll.removeView(row)
                if (archivedChatsRowView === row) {
                    archivedChatsRowView = null
                    archivedAvatarView = null
                }

                // Compensate for the removed row to avoid a visible jump.
                messagesScroll.post {
                    messagesScroll.scrollTo(
                        0,
                        (oldScrollY - removedHeight).coerceAtLeast(0)
                    )
                }
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
        val hiddenAddresses = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet("hidden_conversations", emptySet())
            .orEmpty()

        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(
                    it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                ) ?: "Unknown"

                if (smsSearchQuery.isBlank() && address in hiddenAddresses) continue

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

        // گفتگوهای Mesh تا زمان فعال‌سازی مجدد نمایش داده نمی‌شوند.

        val searchQuery = smsSearchQuery.trim()
        if (searchQuery.isNotEmpty()) {
            val matchingAddresses = mutableSetOf<String>()
            val searchProjection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY
            )
            val searchCursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                searchProjection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            searchCursor?.use { rows ->
                while (rows.moveToNext()) {
                    val address = rows.getString(
                        rows.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    ) ?: "Unknown"
                    val body = rows.getString(
                        rows.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    ) ?: ""
                    if (
                        address.contains(searchQuery, ignoreCase = true) ||
                        body.contains(searchQuery, ignoreCase = true)
                    ) {
                        matchingAddresses.add(address)
                    }
                }
            }

            matchingAddresses.addAll(
                conversations.keys.filter { address ->
                    getContactName(address).orEmpty()
                        .contains(searchQuery, ignoreCase = true)
                }
            )

            conversations.keys.retainAll(matchingAddresses)
        }

        val hasArchivedConversations =
            conversations.keys.any { it in archivedAddresses }

        if (showingArchivedChats) {
            conversations.keys.retainAll(archivedAddresses)
        } else {
            archivedAddresses.forEach { conversations.remove(it) }

            val archiveRowEnabled = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("archived_chats_row_enabled", false)

            if (archiveRowEnabled && hasArchivedConversations) {
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

    private fun sendMeshChatRequest(meshId: String) {
        val target = meshId.trim().uppercase(Locale.US)
        if (!target.matches(Regex("MJ-[A-Z0-9]{6}"))) {
            Toast.makeText(this, "Invalid Mesh ID", Toast.LENGTH_SHORT).show()
            return
        }

        val manager = meshManager
        if (manager == null) {
            Toast.makeText(this, "Mesh is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        outgoingMeshChatRequests.add(target)
        manager.sendMeshMessage(target, "CHAT_REQUEST")
        Toast.makeText(
            this,
            "Chat request sent to $target. Waiting for response.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun handleIncomingMeshChatRequest(senderId: String) {
        if (!senderId.matches(Regex("MJ-[A-Z0-9]{6}"))) return

        if (isMainActivityResumed) {
            showIncomingMeshChatRequestDialog(senderId)
        } else {
            showIncomingMeshChatRequestNotification(senderId)
        }
    }

    private fun showIncomingMeshChatRequestDialog(senderId: String) {
        if (!pendingMeshChatRequests.add(senderId)) return

        android.app.AlertDialog.Builder(this)
            .setTitle("Chat request")
            .setMessage("$senderId wants to start a conversation with you.")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ ->
                pendingMeshChatRequests.remove(senderId)
                meshManager?.sendMeshMessage(senderId, "CHAT_ACCEPT")
                openMeshConversation(senderId)
            }
            .setNegativeButton("Decline") { _, _ ->
                pendingMeshChatRequests.remove(senderId)
                meshManager?.sendMeshMessage(senderId, "CHAT_DECLINE")
            }
            .setOnCancelListener {
                pendingMeshChatRequests.remove(senderId)
            }
            .show()
    }

    private fun showIncomingMeshChatRequestNotification(senderId: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("mesh_chat_request_sender", senderId)
            putExtra("mesh_chat_request_action", "prompt")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            senderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, "mesh_messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Chat request from $senderId")
            .setContentText("Open to accept or decline the request.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)
            ?.notify(senderId.hashCode(), notification)
    }

    private fun handleMeshRequestIntent(intent: Intent?) {
        val senderId = intent?.getStringExtra("mesh_chat_request_sender")
            ?.trim()
            ?.uppercase(Locale.US)
            ?: return

        if (!senderId.matches(Regex("MJ-[A-Z0-9]{6}"))) return

        intent.removeExtra("mesh_chat_request_sender")
        intent.removeExtra("mesh_chat_request_action")
        showIncomingMeshChatRequestDialog(senderId)
    }

    private fun setupMeshManager() {
        if (!MESH_ENABLED) return
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
                        removeFloatingMeshAvatar(endpointId)
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
                        if (isMeshPanelVisible) {
                            addFloatingMeshAvatar(endpointId, name)
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
                        removeFloatingMeshAvatar(endpointId)
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
                        when (message) {
                            "CHAT_REQUEST" -> {
                                handleIncomingMeshChatRequest(senderId)
                            }

                            "CHAT_ACCEPT" -> {
                                if (outgoingMeshChatRequests.remove(senderId)) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "$senderId accepted your chat request",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    openMeshConversation(senderId)
                                }
                            }

                            "CHAT_DECLINE" -> {
                                if (outgoingMeshChatRequests.remove(senderId)) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "$senderId declined your chat request",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }

                            else -> {
                                val activeChat = MeshSession.activeChatId
                                if (
                                    activeChat != null &&
                                    activeChat.equals(senderId, ignoreCase = true)
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
                    }
                }

                override fun onError(message: String) {
                    logMesh("ERROR | $message")
                    runOnUiThread {
                        meshStatusText?.text = message
                        meshStatusText?.setTextColor(
                            Color.rgb(190, 55, 55)
                        )
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

    private fun addFloatingMeshAvatar(endpointId: String, name: String) {
        val container = meshFloatingContainer ?: return
        val meshId = name.trim().uppercase(Locale.US)
        if (!meshId.matches(Regex("MJ-[A-Z0-9]{6}"))) return

        meshFloatingAvatars[endpointId]?.let {
            if (it.parent === container) return
            meshFloatingAvatars.remove(endpointId)
        }

        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        val avatar = ImageView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                avatarGradientColors(meshId)
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.argb(220, 255, 255, 255))
            }
            clipToOutline = true
            setImageResource(R.drawable.mesh_mobile)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(13), dp(13), dp(13), dp(13))
            contentDescription = "Send chat request to $meshId"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                sendMeshChatRequest(meshId)
            }
        }

        item.addView(
            avatar,
            LinearLayout.LayoutParams(dp(54), dp(54))
        )
        item.addView(
            TextView(this).apply {
                text = meshId
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(5f, 0f, 1f, Color.BLACK)
                setPadding(0, dp(5), 0, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            item,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        meshFloatingAvatars[endpointId] = item
    }

    private fun removeFloatingMeshAvatar(endpointId: String) {
        meshFloatingAvatars.remove(endpointId)?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
    }

    private fun openMeshConversation(endpointId: String) {
        val target = endpointId.trim().uppercase(Locale.US)
        if (!target.matches(Regex("MJ-[A-Z0-9]{6}"))) {
            logMesh("Cannot open Mesh conversation: invalid Mesh ID")
            return
        }

        startActivity(
            Intent(this, ConversationActivity::class.java).apply {
                putExtra("phone", target)
                putExtra("mesh_id", target)
                putExtra("offline_mesh", true)
            }
        )
    }

    private fun showSmsSearchPage() {
        val previousQuery = smsSearchQuery
        smsSearchQuery = ""

        val pageBackground = if (isDarkMode) Color.BLACK else Color.WHITE
        val textColor = if (isDarkMode) Color.WHITE else Color.rgb(25, 25, 25)
        val secondaryColor = if (isDarkMode) Color.rgb(170, 170, 170) else Color.rgb(105, 105, 105)
        val capsuleColor = if (isDarkMode) Color.rgb(48, 48, 48) else Color.rgb(242, 242, 244)

        window.statusBarColor = pageBackground
        window.navigationBarColor = pageBackground
        window.decorView.systemUiVisibility =
            if (isDarkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackground)
            setPadding(dp(16), dp(8), dp(16), 0)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val backButton = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_back_arrow)
            imageTintList = android.content.res.ColorStateList.valueOf(textColor)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Back"
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setOnClickListener {
                smsSearchQuery = ""
                setContentView(createHomeScreen())
                loadMessages()
                window.statusBarColor = screenBackground
                window.navigationBarColor = screenBackground
                window.decorView.systemUiVisibility =
                    if (isDarkMode) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }

        topRow.addView(backButton, LinearLayout.LayoutParams(dp(40), dp(48)))

        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = solidDrawable(capsuleColor, dp(28).toFloat())
            setPadding(dp(16), 0, dp(14), 0)
        }

        val searchInput = EditText(this).apply {
            hint = "Search"
            textSize = 16f
            setTextColor(textColor)
            setHintTextColor(secondaryColor)
            setSingleLine(true)
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(0, 0, 0, 0)
        }

        searchBox.addView(
            searchInput,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        topRow.addView(
            searchBox,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                leftMargin = dp(4)
            }
        )

        page.addView(
            topRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            )
        )

        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(16))
        }

        val resultsScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(
                results,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        page.addView(
            resultsScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(page)

        fun addSearchResult(address: String, body: String, date: Long, subtitle: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                background = android.util.TypedValue().let {
                    val out = android.util.TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                    if (out.resourceId != 0) {
                        android.graphics.drawable.Drawable.createFromXml(
                            resources,
                            resources.getXml(out.resourceId)
                        )
                    } else null
                }
                setOnClickListener { openConversation(address) }
            }

            val name = getContactName(address)?.takeIf { it.isNotBlank() } ?: address
            row.addView(TextView(this).apply {
                text = name
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            row.addView(TextView(this).apply {
                text = body
                textSize = 14f
                setTextColor(secondaryColor)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })

            if (subtitle.isNotBlank()) {
                row.addView(TextView(this).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(secondaryColor)
                    setPadding(0, dp(4), 0, 0)
                })
            }

            results.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val divider = View(this).apply {
                setBackgroundColor(if (isDarkMode) Color.rgb(48, 48, 48) else Color.rgb(238, 238, 238))
            }
            results.addView(divider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ))
        }

        fun refreshSearchResults(queryText: String) {
            results.removeAllViews()
            val query = queryText.trim()
            if (query.isEmpty()) return

            val seen = mutableSetOf<String>()
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )

            cursor?.use { rows ->
                while (rows.moveToNext()) {
                    val address = rows.getString(
                        rows.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    ) ?: "Unknown"
                    val body = rows.getString(
                        rows.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    ) ?: ""
                    val date = rows.getLong(
                        rows.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    )
                    val name = getContactName(address).orEmpty()

                    val matchesQuery =
                        address.contains(query, ignoreCase = true) ||
                        name.contains(query, ignoreCase = true) ||
                        body.contains(query, ignoreCase = true)

                    if (matchesQuery) {
                        val key = "$address|$body|$date"
                        if (seen.add(key)) {
                            addSearchResult(
                                address,
                                body,
                                date,
                                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(date))
                            )
                        }
                    }
                }
            }

            MeshMessageStore.loadConversations(this).forEach { meshId ->
                val messages = MeshMessageStore.loadMessages(this, meshId)
                messages.asReversed().forEach { message ->
                    val name = meshId
                    if (
                        name.contains(query, ignoreCase = true) ||
                        message.body.contains(query, ignoreCase = true)
                    ) {
                        val key = "$meshId|${message.body}|${message.date}"
                        if (seen.add(key)) {
                            addSearchResult(
                                meshId,
                                message.body,
                                message.date,
                                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(message.date))
                            )
                        }
                    }
                }
            }

            if (results.childCount == 0) {
                results.addView(TextView(this).apply {
                    text = "No results"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(secondaryColor)
                    setPadding(0, dp(28), 0, dp(28))
                })
            }
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshSearchResults(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        searchInput.requestFocus()
        searchInput.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
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

        // گزینهٔ Mesh موقتاً از منو مخفی است.

        val settings = createMenuItem(
            title = "Settings",
            subtitle = "",
            iconRes = R.drawable.ic_settings
        )

        settings.setOnClickListener {
            meshPopup?.dismiss()
            showMeshIdSettings()
        }

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
            headerMenuButton ?: homeHeader,
            0,
            -dp(4),
            Gravity.END
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
        if (!MESH_ENABLED) return

        meshPopup?.dismiss()
        isMeshPanelVisible = true

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val myMeshId = prefs.getString(MESH_ID, "") ?: ""

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val videoFile = java.io.File(cacheDir, "mesh-background.mp4")
        try {
            val assetSize = assets.open("mesh-background.mp4").use { input ->
                var total = 0L
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                }
                total
            }

            if (!videoFile.exists() || videoFile.length() != assetSize) {
                assets.open("mesh-background.mp4").use { input ->
                    videoFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            logMesh("VIDEO | assetSize=$assetSize cacheSize=${videoFile.length()}")
        } catch (e: Exception) {
            logMesh("VIDEO | Could not prepare background: ${e.javaClass.simpleName}: ${e.message}")
        }

        val videoTexture = android.view.TextureView(this)

        videoTexture.surfaceTextureListener =
            object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surfaceTexture: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    if (!videoFile.exists() || videoFile.length() <= 0L) {
                        logMesh("VIDEO | background file is missing or empty")
                        return
                    }

                    try {
                        releaseMeshBackgroundVideo()
                        val surface = android.view.Surface(surfaceTexture)
                        meshBackgroundSurface = surface
                        val player = android.media.MediaPlayer()
                        meshBackgroundPlayer = player

                        player.setSurface(surface)
                        player.setDataSource(videoFile.absolutePath)
                        player.isLooping = true
                        player.setVolume(0f, 0f)

                        player.setOnPreparedListener {
                            logMesh("VIDEO | TextureView prepared duration=${it.duration} visible=$isMeshPanelVisible")
                            if (isMeshPanelVisible) {
                                it.start()
                                logMesh("VIDEO | TextureView start requested")
                            }
                        }

                        player.setOnErrorListener { _, what, extra ->
                            logMesh("VIDEO | TextureView playback error what=$what extra=$extra")
                            true
                        }

                        player.setOnInfoListener { _, what, extra ->
                            logMesh("VIDEO | TextureView info what=$what extra=$extra")
                            false
                        }

                        player.prepareAsync()
                    } catch (e: Exception) {
                        logMesh("VIDEO | TextureView setup failed: ${e.javaClass.simpleName}: ${e.message}")
                        releaseMeshBackgroundVideo()
                    }
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) = Unit

                override fun onSurfaceTextureDestroyed(
                    surfaceTexture: android.graphics.SurfaceTexture
                ): Boolean {
                    releaseMeshBackgroundVideo()
                    return true
                }

                override fun onSurfaceTextureUpdated(
                    surface: android.graphics.SurfaceTexture
                ) = Unit
            }

        root.addView(
            videoTexture,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val shade = View(this).apply {
            setBackgroundColor(Color.argb(72, 0, 0, 0))
        }
        root.addView(
            shade,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        meshFloatingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        val meshFloatingScroll =
            android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                clipChildren = false
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }

        meshFloatingScroll.addView(
            meshFloatingContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            meshFloatingScroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(82)
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
        )

        val close = TextView(this).apply {
            text = "×"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.argb(90, 20, 20, 24))
                cornerRadius = dp(24).toFloat()
            }
            setOnClickListener { returnToHome() }
        }
        root.addView(
            close,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(18)
                marginEnd = dp(18)
            }
        )

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val avatar = ImageView(this).apply {
            val colors = avatarGradientColors(myMeshId.ifBlank { "Mesh" })
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                colors
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(3), Color.argb(210, 255, 255, 255))
            }
            clipToOutline = true
            setImageResource(R.drawable.mesh_mobile)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(29), dp(29), dp(29), dp(29))
            contentDescription = "Mesh settings"
            isClickable = true
            isFocusable = true
        }
        center.addView(
            avatar,
            LinearLayout.LayoutParams(dp(104), dp(104)).apply {
                bottomMargin = dp(14)
            }
        )
        avatar.setOnClickListener {
            isMeshPanelVisible = false
            releaseMeshBackgroundVideo()
            showMeshIdSettings()
        }

        center.addView(
            TextView(this).apply {
                text = myMeshId
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(8f, 0f, 2f, Color.BLACK)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            center,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                bottomMargin = dp(75)
            }
        )

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.argb(190, 12, 12, 16))
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.argb(45, 255, 255, 255))
            }
        }

        meshStatusText = TextView(this).apply {
            text = "Mesh is ready"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }
        bottomPanel.addView(
            meshStatusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val searchInput = EditText(this).apply {
            hint = "Search id  •  MJ-XXXXXX"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply {
                setColor(Color.argb(45, 255, 255, 255))
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
        }
        bottomPanel.addView(
            searchInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        fun makeMeshButton(label: String, primary: Boolean = false): TextView {
            return TextView(this).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(
                        if (primary) Color.rgb(53, 112, 230)
                        else Color.argb(48, 255, 255, 255)
                    )
                    cornerRadius = dp(26).toFloat()
                    if (!primary) {
                        setStroke(dp(1), Color.argb(75, 255, 255, 255))
                    }
                }
                isClickable = true
                isFocusable = true
            }
        }

        val searchButton = makeMeshButton("Search id", true)
        bottomPanel.addView(
            searchButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                bottomMargin = dp(8)
            }
        )
        searchButton.setOnClickListener {
            val entered = searchInput.text.toString().trim()
                .uppercase(Locale.US)
            if (!entered.matches(Regex("MJ-[A-Z0-9]{6}"))) {
                searchInput.error = "Enter an ID like MJ-ABC123"
                return@setOnClickListener
            }
            if (entered == myMeshId.uppercase(Locale.US)) {
                Toast.makeText(this, "This is your own ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectedMeshId = entered
            saveMeshDevice(entered)
            meshManager?.setTargetMeshId(entered)
            if (hasNearbyPermissions()) {
                meshManager?.start()
                meshStatusText?.text = "Searching for $entered..."
            } else {
                ensureNearbyPermissions()
                meshStatusText?.text = "Nearby permissions are required"
            }
            Toast.makeText(
                this,
                "ID saved. The chat request must be accepted to start a conversation.",
                Toast.LENGTH_LONG
            ).show()
        }

        val autoSearchButton = makeMeshButton("Auto Search")
        bottomPanel.addView(
            autoSearchButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                bottomMargin = dp(8)
            }
        )
        autoSearchButton.setOnClickListener {
            meshManager?.setTargetMeshId(null)
            if (hasNearbyPermissions()) {
                meshManager?.start()
                meshStatusText?.text = "Searching for nearby devices..."
            } else {
                ensureNearbyPermissions()
            }
        }

        root.addView(
            bottomPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
                bottomMargin = dp(18)
            }
        )

        meshDevicesContainer = null
        setContentView(root)
        updateMeshStatus()
    }

    private fun returnToHome() {
        if (!isMeshPanelVisible) {
            return
        }

        isMeshPanelVisible = false
        releaseMeshBackgroundVideo()
        meshStatusText = null
        meshDevicesContainer = null
        meshFloatingContainer = null
        meshFloatingAvatars.clear()
        meshLogText = null
        if (MeshSession.activeChatId == null) {
            meshManager?.stop()
        }
        setContentView(createHomeScreen())
        updateMeshStatus()
    }

    private fun setArchiveScreen(show: Boolean) {
        if (showingArchivedChats == show) return

        showingArchivedChats = show
        setContentView(createHomeScreen())
        loadMessages()

        messagesScroll.post {
            messagesScroll.scrollTo(0, 0)
            messagesScroll.translationY = 0f
            messagesScroll.alpha = 1f
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (showingArchivedChats) {
            setArchiveScreen(false)
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
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        iconFrame.addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))

        archivedAvatarView = iconFrame
        archivedChatsRowView = row
        iconFrame.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(0, 129, 255))
        }
        row.addView(iconFrame, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            rightMargin = dp(12)
        })
        row.addView(TextView(this).apply {
            text = "Archived chats"
            textSize = 17f
            setTextColor(primaryText)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        row.setOnClickListener {
            setArchiveScreen(true)
        }
        messagesContainer.addView(row, 0, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(76)
        ))
    }

    private fun showEmptyState() {
        val empty = TextView(this).apply {
            text = if (showingArchivedChats) {
                "No archived chats"
            } else {
                "No Messages"
            }
            textSize = 20f
            setTextColor(primaryText)
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
                    setColor(
                        if (isDarkMode) Color.rgb(38, 38, 42)
                        else Color.rgb(240, 240, 240)
                    )
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
                            val hadArchivedConversations = archived.isNotEmpty()

                            if (showingArchivedChats) {
                                archived.remove(address)
                            } else {
                                archived.add(address)
                            }

                            val shouldEnableArchiveRow =
                                archived.isNotEmpty() &&
                                (
                                    !hadArchivedConversations ||
                                    prefs.getBoolean(
                                        "archived_chats_row_enabled",
                                        false
                                    )
                                )

                            prefs.edit()
                                .putStringSet("archived_conversations", archived)
                                .putBoolean(
                                    "archived_chats_row_enabled",
                                    shouldEnableArchiveRow
                                )
                                .apply()

                            archivedChatsRowHiddenByScroll =
                                archived.isNotEmpty() && !shouldEnableArchiveRow

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

        private fun createSelectionHeader(): View {
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
            setOnClickListener { exitSelectionMode() }
        }
        header.addView(cancel, LinearLayout.LayoutParams(dp(48), dp(48)))

        selectionSelectedText = TextView(this).apply {
            text = "1 Selected"
            textSize = 19f
            setTextColor(if (isDarkMode) Color.WHITE else Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            selectionSelectedText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dp(8)
            }
        )

        val hideButton = com.airbnb.lottie.LottieAnimationView(this).apply {
            setAnimation(R.raw.passcode_lock)
            repeatCount = 0
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Hide selected chats"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!isAnimating) {
                    setAnimation(R.raw.passcode_lock_close)
                    repeatCount = 0
                    progress = 0f
                    removeAllAnimatorListeners()
                    addAnimatorListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            removeAnimatorListener(this)
                            hideSelected()
                        }
                    })
                    playAnimation()
                }
            }
        }
        header.addView(hideButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        if (!isDarkMode) return header

        return header
    }

private fun createSelectionActionBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(if (isDarkMode) Color.TRANSPARENT else Color.WHITE)
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

    private fun hideSelected() {
        val smsAddresses = selectedAddresses.filterNot { it.startsWith("MJ-") }
        if (smsAddresses.isEmpty()) {
            Toast.makeText(this, "No SMS chats selected", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val hidden = prefs.getStringSet("hidden_conversations", emptySet())
            ?.toMutableSet() ?: mutableSetOf()
        hidden.addAll(smsAddresses)
        prefs.edit().putStringSet("hidden_conversations", hidden).apply()

        exitSelectionMode()
        loadMessages()
        Toast.makeText(this, "Chat hidden", Toast.LENGTH_SHORT).show()
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

        intent.putExtra("phone", address)
        startActivity(intent)
    }

    private class MessengerComposeIcon(
        context: android.content.Context
    ) : ImageView(context) {

        init {
            setImageResource(R.drawable.telegram_compose_plus)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "New message"
        }
    }

    private fun showMeshIdSettings() {
        meshPopup?.dismiss()
        isMeshPanelVisible = false
        isSettingsPageVisible = true
        isMeshLogPageVisible = false

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(screenBackground)
            setPadding(dp(18), dp(8), dp(18), dp(16))
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val safe = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                safe.left + dp(18),
                safe.top + dp(8),
                safe.right + dp(18),
                safe.bottom + dp(16)
            )
            insets
        }

        val header = android.widget.FrameLayout(this)

        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back_arrow)
            contentDescription = "Back"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = null
            setOnClickListener {
                isSettingsPageVisible = false
                setContentView(createHomeScreen())
                updateMeshStatus()
                loadMessages()
            }
        }

        header.addView(
            back,
            android.widget.FrameLayout.LayoutParams(dp(44), dp(44)).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        )

        header.addView(
            TextView(this).apply {
                text = "Settings"
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isDarkMode) Color.WHITE else Color.rgb(33, 33, 33))
                gravity = Gravity.CENTER
            },
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44),
                Gravity.TOP
            ).apply {
                topMargin = dp(48)
            }
        )

        page.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(100)
            )
        )

        fun createSettingsCard(): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = solidDrawable(if (isDarkMode) Color.rgb(22, 22, 22) else Color.WHITE, dp(20).toFloat())
            }
        }

        fun addCardTitle(card: LinearLayout, label: String) {
            card.addView(TextView(this).apply {
                text = label
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isDarkMode) Color.WHITE else Color.rgb(33, 33, 33))
            })
        }

        fun addCardSubtitle(card: LinearLayout, label: String) {
            card.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(if (isDarkMode) Color.rgb(175, 175, 185) else Color.rgb(110, 110, 115))
                setPadding(0, dp(5), 0, 0)
            })
        }

        val securityCard = createSettingsCard().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AppSecurity.showSettings(this@MainActivity)
            }
        }
        addCardTitle(securityCard, "Security")
        addCardSubtitle(
            securityCard,
            "Passcode Lock · Fingerprint · Auto-lock"
        )

        page.addView(
            securityCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        )

        val chatSettingsCard = createSettingsCard()
        addCardTitle(chatSettingsCard, "Chat Settings")

        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
            isClickable = true
            isFocusable = true
        }

        val themeLabels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        themeLabels.addView(TextView(this).apply {
            text = "System Default"
            textSize = 16f
            setTextColor(if (isDarkMode) Color.WHITE else Color.rgb(33, 33, 33))
        })

        themeLabels.addView(TextView(this).apply {
            text = "Follow device appearance"
            textSize = 13f
            setTextColor(if (isDarkMode) Color.rgb(175, 175, 185) else Color.rgb(110, 110, 115))
            setPadding(0, dp(4), 0, 0)
        })

        themeRow.addView(
            themeLabels,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        themeRow.addView(TextView(this).apply {
            text = "›"
            textSize = 26f
            setTextColor(if (isDarkMode) Color.rgb(175, 175, 185) else Color.rgb(110, 110, 115))
            gravity = Gravity.CENTER
        })

        themeRow.setOnClickListener {
            applyThemeModeWithAnimation("system")

            Toast.makeText(
                this,
                "Theme follows device appearance",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Promotional SMS filtering is opt-in and disabled by default.
        val spamFilterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)
        }

        val spamFilterLabels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        spamFilterLabels.addView(TextView(this).apply {
            text = "Filter promotional SMS"
            textSize = 16f
            setTextColor(if (isDarkMode) Color.WHITE else Color.rgb(33, 33, 33))
        })

        spamFilterLabels.addView(TextView(this).apply {
            text = "Automatically filter likely carrier ads; security and banking messages are exempt"
            textSize = 13f
            setTextColor(if (isDarkMode) Color.rgb(175, 175, 185) else Color.rgb(110, 110, 115))
            setPadding(0, dp(4), 0, 0)
        })

        spamFilterRow.addView(
            spamFilterLabels,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val spamFilterSwitch = android.widget.Switch(this).apply {
            isChecked = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("auto_filter_promotional_sms", false)

            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putBoolean("auto_filter_promotional_sms", enabled)
                    .apply()

                Toast.makeText(
                    this@MainActivity,
                    if (enabled) "Promotional SMS filtering enabled"
                    else "Promotional SMS filtering disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        spamFilterRow.addView(spamFilterSwitch)
        chatSettingsCard.addView(spamFilterRow)

        chatSettingsCard.addView(themeRow)

        page.addView(
            chatSettingsCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(page)
        androidx.core.view.ViewCompat.requestApplyInsets(page)
    }

    private fun showMeshLog() {
        isMeshPanelVisible = false
        isSettingsPageVisible = false
        isMeshLogPageVisible = true

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(screenBackground)
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
        isMainActivityResumed = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // درخواست‌های Mesh تا اطلاع ثانوی نادیده گرفته می‌شوند.
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
