//package dev.autobridge

//import android.Manifest
//import android.app.Activity
//import android.content.Intent
//import android.content.pm.PackageManager
//import android.graphics.Color
//import android.graphics.Typeface
//import android.graphics.drawable.Drawable
//import android.graphics.drawable.GradientDrawable
//import android.media.projection.MediaProjectionConfig
//import android.media.projection.MediaProjectionManager
//import android.os.Build
//import android.os.Bundle
//import android.os.Handler
//import android.os.Looper
//import android.provider.Settings
//import android.text.Editable
//import android.text.TextWatcher
//import android.view.Gravity
//import android.view.View
//import android.view.ViewGroup
//import android.widget.EditText
//import android.widget.FrameLayout
//import android.widget.HorizontalScrollView
//import android.widget.ImageView
//import android.widget.LinearLayout
//import android.widget.ScrollView
//import android.widget.Space
//import android.widget.Switch
//import android.widget.TextView
//import android.widget.Toast
//import androidx.compose.ui.platform.ComposeView
//import androidx.compose.ui.platform.ViewCompositionStrategy
//import dev.autobridge.apps.InstalledApp
//import dev.autobridge.apps.AppListFilter
//import dev.autobridge.apps.InstalledAppRepository
//import dev.autobridge.apps.MirrorContentPolicy
//import dev.autobridge.apps.PerAppProfileStore
//import dev.autobridge.apps.QuickAppLauncher
//import dev.autobridge.apps.QuickAppsStore
//import dev.autobridge.apps.SmartModeResolver
//import dev.autobridge.core.datastore.SessionRestoreStore
//import dev.autobridge.core.model.AudioMode
//import dev.autobridge.core.model.AutoBridgeMode
//import dev.autobridge.core.model.Environment
//import dev.autobridge.core.model.Feature
//import dev.autobridge.core.model.ResolutionPreset
//import dev.autobridge.core.model.RotationMode
//import dev.autobridge.core.model.RuntimeContext
//import dev.autobridge.core.model.ScaleMode
//import dev.autobridge.core.model.VehicleState
//import dev.autobridge.core.policy.FeaturePolicy
//import dev.autobridge.core.state.RuntimeContextStore
//import dev.autobridge.ui.AutoBridgePhoneTheme
//import dev.autobridge.ui.PhoneControlCenter
//import dev.autobridge.display.MirrorDiagnostics
//import dev.autobridge.display.MirrorOrientationController
//import dev.autobridge.display.OrientationMonitor
//import dev.autobridge.display.ScreenOffController
//import dev.autobridge.display.ScreenPowerController
//import dev.autobridge.display.StructuredLog
//import dev.autobridge.display.SurfaceProfile
//import dev.autobridge.entertainment.BrowserLauncher
//import dev.autobridge.input.AccessibilityInputBackend
//import dev.autobridge.input.ShizukuInputBackend
//import dev.autobridge.media.MediaPlaybackClient
//import dev.autobridge.mirror.MirrorCoordinator
//import dev.autobridge.mirror.ProjectionService
//import dev.autobridge.mirror.ReconnectTracker
//import dev.autobridge.safety.DevMode
//import dev.autobridge.safety.MockVehicleStateProvider
//import dev.autobridge.safety.ParkingStateStore
//import dev.autobridge.settings.MirrorSettings
//import dev.autobridge.settings.SettingsStore
//import rikka.shizuku.Shizuku

//class MainActivity : androidx.activity.ComponentActivity() {
//    private companion object {
//        const val REQUEST_CAPTURE = 2001
//        const val REQUEST_NOTIFICATIONS = 2002

//        const val COLOR_BACKGROUND = 0xff07131b.toInt()
//        const val COLOR_SURFACE = 0xff0d2029.toInt()
//        const val COLOR_SURFACE_ALT = 0xff122b37.toInt()
//        const val COLOR_BORDER = 0xff1f3a47.toInt()
//        const val COLOR_ACCENT = 0xff159cff.toInt()
//        const val COLOR_ACCENT_DARK = 0xff0b5d96.toInt()
//        const val COLOR_TEXT = 0xffeaf0fa.toInt()
//        const val COLOR_MUTED = 0xff8494b0.toInt()
//        const val COLOR_SUCCESS = 0xff2ee879.toInt()
//        const val COLOR_WARNING = 0xffffbf5f.toInt()
//    }

//    private enum class PhoneScreen {
//        HOME,
//        APPS,
//        PROFILES,
//        PROFILE,
//        MIRROR_SETTINGS,
//        DEVELOPER,
//        DEVICES
//    }

//    private lateinit var statusView: TextView
//    private lateinit var mediaPlayback: MediaPlaybackClient
//    private lateinit var screenContainer: FrameLayout
//    private lateinit var bottomNav: LinearLayout
//    private var homeConnectionView: TextView? = null
//    private var developerLogView: TextView? = null
//    private var developerMirrorEventsView: TextView? = null
//    private var labSummaryView: TextView? = null
//    private var pendingEntertainmentLaunch = false
//    private var currentScreen = PhoneScreen.HOME
//    private var selectedApp: InstalledApp? = null
//    private var appsFavoritesOnly = true
//    private var appSearchQuery = ""

//    private val statusHandler = Handler(Looper.getMainLooper())
//    private val refreshStatusRunnable = object : Runnable {
//        override fun run() {
//            if (::statusView.isInitialized || homeConnectionView != null) refreshStatus()
//            statusHandler.postDelayed(this, 1_000L)
//        }
//    }

//    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
//        runOnUiThread {
//            val granted = grantResult == PackageManager.PERMISSION_GRANTED
//            Toast.makeText(
//                this,
//                if (granted) "Shizuku touch enabled" else "Shizuku permission denied",
//                Toast.LENGTH_SHORT
//            ).show()
//            if (granted) ShizukuInputBackend.bind(this)
//            refreshStatus()
//        }
//    }

//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        SettingsStore.restore(this)
//        SessionRestoreStore.restore(this)?.let { snapshot ->
//            RuntimeContextStore.setCurrentFeature(snapshot.feature, snapshot.packageName)
//            RuntimeContextStore.setDisplayPreferences(
//                scaleMode = snapshot.scaleMode,
//                rotationMode = snapshot.rotationMode,
//                audioMode = snapshot.audioMode,
//                fullscreen = snapshot.fullscreen
//            )
//        }
//        if (DevMode.isEnabled) {
//            MockVehicleStateProvider.start()
//        }
//        requestNotificationPermissionIfNeeded()
//        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
//        mediaPlayback = MediaPlaybackClient(this)
//        mediaPlayback.connect(onConnected = {
//            if (::statusView.isInitialized || homeConnectionView != null) refreshStatus()
//        })
//        setContentView(buildUi())
//        refreshStatus()
//    }

//    override fun onResume() {
//        super.onResume()
//        statusHandler.removeCallbacks(refreshStatusRunnable)
//        refreshStatusRunnable.run()
//    }

//    override fun onPause() {
//        statusHandler.removeCallbacks(refreshStatusRunnable)
//        super.onPause()
//    }

//    override fun onDestroy() {
//        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
//        mediaPlayback.disconnect()
//        super.onDestroy()
//    }

//    private fun buildUi(): View {
//        val root = LinearLayout(this).apply {
//            orientation = LinearLayout.VERTICAL
//            setBackgroundColor(COLOR_BACKGROUND)
//        }

//        screenContainer = FrameLayout(this).apply {
//            setBackgroundColor(COLOR_BACKGROUND)
//        }
//        root.addView(
//            screenContainer,
//            LinearLayout.LayoutParams(
//                ViewGroup.LayoutParams.MATCH_PARENT,
//                0,
//                1f
//            )
//        )

//        bottomNav = LinearLayout(this).apply {
//            orientation = LinearLayout.HORIZONTAL
//            gravity = Gravity.CENTER
//            setPadding(dp(6), dp(5), dp(6), dp(7))
//            setBackgroundColor(0xff091a23.toInt())
//        }
//        root.addView(
//            bottomNav,
//            LinearLayout.LayoutParams(
//                ViewGroup.LayoutParams.MATCH_PARENT,
//                dp(64)
//            )
//        )

//        showPhoneScreen(PhoneScreen.HOME)
//        return root
//    }

//    private fun showPhoneScreen(requested: PhoneScreen) {
//        val gatedRequested = if (
//            requested == PhoneScreen.DEVELOPER &&
//            !FeaturePolicy.app.isAvailable(Feature.DEVELOPER)
//        ) {
//            Toast.makeText(this, FeaturePolicy.app.denialMessage(Feature.DEVELOPER), Toast.LENGTH_SHORT).show()
//            PhoneScreen.HOME
//        } else {
//            requested
//        }
//        val target = if (gatedRequested == PhoneScreen.PROFILE && selectedApp == null) {
//            PhoneScreen.APPS
//        } else {
//            gatedRequested
//        }
//        currentScreen = target
//        homeConnectionView = null
//        developerLogView = null
//        developerMirrorEventsView = null
//        labSummaryView = null
//        screenContainer.removeAllViews()

//        val screen = when (target) {
//            PhoneScreen.HOME -> buildHomeScreen()
//            PhoneScreen.APPS -> buildAppsScreen()
//            PhoneScreen.PROFILES -> buildProfilesScreen()
//            PhoneScreen.PROFILE -> buildProfileScreen()
//            PhoneScreen.MIRROR_SETTINGS -> buildMirrorSettingsScreen()
//            PhoneScreen.DEVELOPER -> buildDeveloperScreen()
//            PhoneScreen.DEVICES -> buildDevicesScreen()
//        }
//        screenContainer.addView(
//            screen,
//            FrameLayout.LayoutParams(
//                ViewGroup.LayoutParams.MATCH_PARENT,
//                ViewGroup.LayoutParams.MATCH_PARENT
//            )
//        )

//        bottomNav.visibility = if (target == PhoneScreen.PROFILE || target == PhoneScreen.DEVELOPER) {
//            View.GONE
//        } else {
//            View.VISIBLE
//        }
//        renderBottomNavigation()
//        refreshStatus()
//    }

//    private fun renderBottomNavigation() {
//        if (!::bottomNav.isInitialized || bottomNav.visibility != View.VISIBLE) return
//        bottomNav.removeAllViews()
//        listOf(
//            Triple("⌂", "Home", PhoneScreen.HOME),
//            Triple("▦", "Apps", PhoneScreen.APPS),
//            Triple("▣", "Devices", PhoneScreen.DEVICES),
//            Triple("⚙", "Settings", PhoneScreen.MIRROR_SETTINGS)
//        ).forEach { (icon, title, destination) ->
//            val active = when {
//                destination == PhoneScreen.MIRROR_SETTINGS ->
//                    currentScreen == PhoneScreen.MIRROR_SETTINGS
//                destination == PhoneScreen.DEVICES -> currentScreen == PhoneScreen.DEVICES
//                else -> currentScreen == destination
//            }
//            val item = TextView(this).apply {
//                text = "$icon\n$title"
//                gravity = Gravity.CENTER
//                textSize = 11f
//                setLineSpacing(0f, 0.88f)
//                setTextColor(if (active) COLOR_ACCENT else COLOR_MUTED)
//                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
//                isClickable = true
//                isFocusable = true
//                setPadding(0, dp(3), 0, 0)
//                setOnClickListener { showPhoneScreen(destination) }
//            }
//            bottomNav.addView(
//                item,
//                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
//            )
//        }
//    }

//    private fun buildHomeScreen(): View {
//        val content = screenContent()
//        content.addView(
//            screenHeader(
//                title = "AutoBridge",
//                subtitle = "PHONE CONTROL CENTER",
//                action = "⚙" to { showPhoneScreen(PhoneScreen.MIRROR_SETTINGS) }
//            )
//        )

//        val connection = TextView(this).apply {
//            homeConnectionView = this
//            textSize = 13f
//            setTextColor(COLOR_TEXT)
//            setPadding(dp(16), dp(14), dp(16), dp(14))
//            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
//            minHeight = dp(88)
//        }
//        addCard(content, connection, top = 4)

//        addCard(
//            content,
//            actionCard("▣   START MIRROR", primary = true) {
//                if (FeaturePolicy.app.isAvailable(Feature.MIRROR)) {
//                    requestScreenCapture()
//                } else {
//                    Toast.makeText(
//                        this,
//                        FeaturePolicy.app.denialMessage(Feature.MIRROR),
//                        Toast.LENGTH_SHORT
//                    ).show()
//                }
//            },
//            top = 12
//        )

//        addLastSessionCard(content)
//        content.addView(sectionLabel("CONTROL CENTER"))
//        content.addView(
//            composeControlCenter(),
//            LinearLayout.LayoutParams(-1, dp(214)).apply { topMargin = dp(4) }
//        )

//        val quickHeader = LinearLayout(this).apply {
//            orientation = LinearLayout.HORIZONTAL
//            gravity = Gravity.CENTER_VERTICAL
//        }
//        quickHeader.addView(sectionLabel("QUICK APPS"), LinearLayout.LayoutParams(0, dp(40), 1f))
//        val edit = TextView(this).apply {
//            text = "Edit"
//            textSize = 12f
//            setTextColor(COLOR_ACCENT)
//            setPadding(dp(8), 0, 0, 0)
//            setOnClickListener { showPhoneScreen(PhoneScreen.APPS) }
//        }
//        quickHeader.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
//        content.addView(quickHeader)

//        val installedApps = InstalledAppRepository.listLaunchableApps(this)
//        QuickAppsStore.syncFavorites(this, installedApps)
//        val quickApps = QuickAppsStore.enabledInstalledApps(this, installedApps).take(6)
//        val quickRow = LinearLayout(this).apply {
//            orientation = LinearLayout.HORIZONTAL
//            gravity = Gravity.CENTER_VERTICAL
//        }
//        if (quickApps.isEmpty()) {
//            quickRow.addView(
//                mutedText("No Quick Apps configured. Add them from Applications."),
//                LinearLayout.LayoutParams(-1, dp(72))
//            )
//        } else {
//            quickApps.forEach { app ->
//                quickRow.addView(
//                    appShortcut(app),
//                    LinearLayout.LayoutParams(dp(72), dp(82)).apply {
//                        marginEnd = dp(8)
//                    }
//                )
//            }
//        }
//        val quickScroll = HorizontalScrollView(this).apply {
//            isHorizontalScrollBarEnabled = false
//            addView(quickRow)
//        }
//        content.addView(quickScroll, LinearLayout.LayoutParams(-1, dp(86)))

//        addCard(
//            content,
//            mutedText(
//                if (DevMode.isEnabled) {
//                    "DEV_MODE is active. Rendering and input remain protected by the vehicle safety gate."
//                } else {
//                    "Safety first: rendering, video, input and app launching require a reported 0 speed."
//                }
//            ),
//            top = 10
//        )
//        return screenScroll(content)
//    }

//    private fun composeControlCenter(): View = ComposeView(this).apply {
//        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
//        setContent {
//            AutoBridgePhoneTheme {
//                PhoneControlCenter(
//                    onStartMirror = { requestScreenCapture() },
//                    onOpenApps = { showPhoneScreen(PhoneScreen.APPS) },
//                    onOpenProfiles = { showPhoneScreen(PhoneScreen.PROFILES) },
//                    onOpenTouch = { openTouchSettings() },
//                    onOpenDeveloper = { showPhoneScreen(PhoneScreen.DEVELOPER) }
//                )
//            }
//        }
//    }

//    private fun buildAppsScreen(): View {
//        val content = screenContent()
//        content.addView(screenHeader("Applications", "APP LAUNCHER", back = { showPhoneScreen(PhoneScreen.HOME) }))

//        val search = EditText(this).apply {
//            hint = "Search apps…"
//            textSize = 14f
//            isSingleLine = true
//            setTextColor(COLOR_TEXT)
//            setHintTextColor(COLOR_MUTED)
//            setPadding(dp(14), 0, dp(14), 0)
//            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
//            setText(appSearchQuery)
//        }
//        content.addView(search, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(10) })

//        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
//        val favorites = TextView(this)
//        val allApps = TextView(this)
//        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
//        fun refreshAppFilter() {
//            val installed = InstalledAppRepository.listLaunchableApps(this)
//            QuickAppsStore.syncFavorites(this, installed)
//            val hasQuickApps = QuickAppsStore.enabledInstalledApps(this, installed).isNotEmpty()
//            if (appsFavoritesOnly && !hasQuickApps) appsFavoritesOnly = false
//            styleFilter(favorites, "Quick Apps", appsFavoritesOnly)
//            styleFilter(allApps, "All apps", !appsFavoritesOnly)
//        }
//        favorites.setOnClickListener {
//            appsFavoritesOnly = true
//            refreshAppFilter()
//            renderAppGrid(grid)
//        }
//        allApps.setOnClickListener {
//            appsFavoritesOnly = false
//            refreshAppFilter()
//            renderAppGrid(grid)
//        }
//        filters.addView(favorites, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(5) })
//        filters.addView(allApps, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(5) })
//        content.addView(filters)
//        content.addView(grid, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
//        search.addTextChangedListener(object : TextWatcher {
//            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
//            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
//                appSearchQuery = s?.toString().orEmpty()
//                renderAppGrid(grid)
//            }
//            override fun afterTextChanged(s: Editable?) = Unit
//        })
//        refreshAppFilter()
//        renderAppGrid(grid)
//        return screenScroll(content)
//    }

//    private fun buildProfilesScreen(): View {
//        val content = screenContent()
//        content.addView(screenHeader("Profiles", "APP PROFILES", back = { showPhoneScreen(PhoneScreen.HOME) }))
//        content.addView(
//            mutedText("Per-app settings are applied only when the app is launched while the vehicle is parked."),
//            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
//        )
//        val installedApps = InstalledAppRepository.listLaunchableApps(this)
//        QuickAppsStore.syncFavorites(this, installedApps)
//        val apps = QuickAppsStore.enabledInstalledApps(this, installedApps)
//        if (apps.isEmpty()) {
//            addCard(content, mutedText("No enabled Quick Apps yet. Add one from Applications."))
//            addCard(content, actionCard("BROWSE ALL APPS") { showPhoneScreen(PhoneScreen.APPS) })
//        } else {
//            apps.forEach { app ->
//                addCard(content, appListRow(app), top = 8)
//            }
//        }
//        return screenScroll(content)
//    }

//    private fun buildProfileScreen(): View {
//        val app = selectedApp ?: return buildAppsScreen()
//        val content = screenContent()
//        val quickApp = QuickAppsStore.list(this).firstOrNull { it.packageName == app.packageName }
//        val profileId = quickApp?.profileId ?: "default"
//        val profile = PerAppProfileStore.profile(this, app.packageName, profileId)
//        val decision = SmartModeResolver.resolve(app.packageName, profile)
//        val isQuickApp = quickApp?.enabled == true
//        content.addView(
//            screenHeader(
//                title = app.label,
//                subtitle = "APP PROFILE • $profileId",
//                back = { showPhoneScreen(PhoneScreen.APPS) },
//                action = (if (isQuickApp) "★" else "☆") to {
//                    QuickAppsStore.setEnabled(this, app, !isQuickApp, profileId)
//                    Toast.makeText(
//                        this,
//                        if (isQuickApp) "Removed from Quick Apps" else "Added to Quick Apps",
//                        Toast.LENGTH_SHORT
//                    ).show()
//                    showPhoneScreen(PhoneScreen.PROFILE)
//                }
//            )
//        )

//        val identity = LinearLayout(this).apply {
//            orientation = LinearLayout.HORIZONTAL
//            gravity = Gravity.CENTER_VERTICAL
//            setPadding(dp(16), dp(14), dp(16), dp(14))
//            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
//        }
//        identity.addView(appIconView(app, dp(52)), LinearLayout.LayoutParams(dp(52), dp(52)))
//        val identityText = LinearLayout(this).apply {
//            orientation = LinearLayout.VERTICAL
//            setPadding(dp(14), 0, 0, 0)
//        }
//        identityText.addView(boldText(app.label, 17f))
//        identityText.addView(mutedText(app.packageName))
//        identity.addView(identityText, LinearLayout.LayoutParams(0, -2, 1f))
//        addCard(content, identity, top = 4)

//        addCard(content, settingRow("Smart mode", decision.reason, decision.mode.name), top = 8)
//        addCard(content, actionCard("USE THIS PROFILE", primary = true) {
//            when {
//                !FeaturePolicy.app.isAvailable(Feature.QUICK_APPS) ->
//                    Toast.makeText(this, FeaturePolicy.app.denialMessage(Feature.QUICK_APPS), Toast.LENGTH_SHORT).show()
//                !QuickAppLauncher.launch(this, app.packageName, profileId) ->
//                    Toast.makeText(this, "Could not launch ${app.label}", Toast.LENGTH_SHORT).show()
//            }
//        }, top = 12)

//        content.addView(sectionLabel("LAUNCH & MIRROR"))
//        addCard(
//            content,
//            settingSwitchRow(
//                "Auto mirror",
//                "Store the mirror intent; capture consent is still required",
//                checked = profile.autoMirror,
//                enabled = true
//            ) { enabled ->
//                PerAppProfileStore.save(this, profile.copy(autoMirror = enabled))
//                showPhoneScreen(PhoneScreen.PROFILE)
//            },
//            top = 4
//        )
//        addCard(
//            content,
//            settingSwitchRow(
//                "Auto fullscreen",
//                "Persist the preference; the target app controls its own system bars",
//                checked = profile.autoFullscreen,
//                enabled = true
//            ) { enabled ->
//                PerAppProfileStore.save(this, profile.copy(autoFullscreen = enabled))
//                showPhoneScreen(PhoneScreen.PROFILE)
//            },
//            top = 8
//        )

//        content.addView(sectionLabel("DISPLAY"))
//        addCard(
//            content,
//            settingSwitchRow(
//                "Force landscape",
//                "Lock the phone to landscape while this profile is active",
//                checked = profile.rotationMode == RotationMode.LANDSCAPE,
//                enabled = true
//            ) { enabled ->
//                PerAppProfileStore.setForceLandscape(this, app.packageName, enabled, profileId)
//                if (enabled && !QuickAppLauncher.hasLandscapePermission(this)) {
//                    Toast.makeText(this, "Grant Modify system settings to apply landscape", Toast.LENGTH_LONG).show()
//                    QuickAppLauncher.requestLandscapePermission(this)
//                }
//            },
//            top = 4
//        )
//        addCard(
//            content,
//            settingRow(
//                "Scale",
//                "Stored preference; direct AUTO_MIRROR currently renders FIT",
//                profile.scaleMode.name,
//                onClick = { cycleProfileScale(app.packageName, profileId) }
//            ),
//            top = 8
//        )
//        addCard(
//            content,
//            settingRow(
//                "Resolution",
//                "Stored preference; Android Auto surface remains authoritative",
//                profile.preferredResolution?.name ?: ResolutionPreset.AUTO.name,
//                onClick = { cycleProfileResolution(app.packageName, profileId) }
//            ),
//            top = 8
//        )
//        addCard(
//            content,
//            settingRow(
//                "Frame rate",
//                "Stored renderer target; current AUTO_MIRROR path uses the system rate",
//                profile.preferredFps?.let { "${it} FPS" } ?: "System",
//                onClick = { cycleProfileFps(app.packageName, profileId) }
//            ),
//            top = 8
//        )

//        content.addView(sectionLabel("TOUCH & AUDIO"))
//        addCard(
//            content,
//            settingSwitchRow(
//                "Touch control",
//                "Desired input backend: ${inputBackendLabel()} • actual touch remains parked-only",
//                checked = profile.touchEnabled,
//                enabled = true
//            ) { enabled ->
//                PerAppProfileStore.save(this, profile.copy(touchEnabled = enabled))
//                showPhoneScreen(PhoneScreen.PROFILE)
//            },
//            top = 4
//        )
//        addCard(
//            content,
//            settingRow(
//                "Audio output",
//                "Stored Smart Mode preference",
//                profile.audioMode.name,
//                onClick = { cycleProfileAudio(app.packageName, profileId) }
//            ),
//            top = 8
//        )
//        addCard(
//            content,
//            settingSwitchRow(
//                "Keep screen on",
//                "Preference is applied only by a pipeline that owns the phone display",
//                checked = profile.keepPhoneScreenOn,
//                enabled = true
//            ) { enabled ->
//                PerAppProfileStore.save(this, profile.copy(keepPhoneScreenOn = enabled))
//                showPhoneScreen(PhoneScreen.PROFILE)
//            },
//            top = 8
//        )
//        addCard(
//            content,
//            actionCard("RESET PROFILE", destructive = true) {
//                PerAppProfileStore.reset(this, app.packageName, profileId)
//                Toast.makeText(this, "Profile reset to defaults", Toast.LENGTH_SHORT).show()
//                showPhoneScreen(PhoneScreen.PROFILE)
//            },
//            top = 16
//        )
//        return screenScroll(content)
//    }

//    private fun buildMirrorSettingsScreen(): View {
//        val content = screenContent()
//        content.addView(
//            screenHeader("Mirror Settings", "PROJECTION", back = { showPhoneScreen(PhoneScreen.HOME) })
//        )

//        content.addView(sectionLabel("DISPLAY"))
//        addCard(content, settingRow("Resolution", "The connected car surface chooses the size", "Auto"), top = 4)
//        addCard(
//            content,
//            settingRow("Frame rate", frameRateSubtitle(), frameRateValue()),
//            top = 8
//        )
//        addCard(
//            content,
//            settingRow(
//                "Renderer pipeline",
//                "SELF_DRAWN enables app-controlled transform and frame accounting",
//                ScreenOffController.pipelineMode.name,
//                onClick = { cyclePipelineMode() }
//            ),
//            top = 8
//        )
//        addCard(content, scaleModeOptions(), top = 8)
//        addCard(
//            content,
//            settingSwitchRow(
//                "Landscape mirror",
//                "Apply a temporary global landscape lock during projection",
//                checked = MirrorOrientationController.isEnabled,
//                enabled = true
//            ) { toggleLandscapeMirror() },
//            top = 8
//        )
//        addCard(content, settingRow("Crop", "Aspect-preserving output", "Auto"), top = 8)

//        content.addView(sectionLabel("ADVANCED"))
//        addCard(
//            content,
//            settingRow(
//                "Head-unit profile",
//                headUnitProfileSubtitle(),
//                SurfaceProfile.active.displayLabel,
//                onClick = { toggleSurfaceProfile() }
//            ),
//            top = 4
//        )
//        addCard(
//            content,
//            settingRow(
//                "Screen-off behavior",
//                ScreenOffController.statusLabel(),
//                screenOffBehaviorValue()
//            ),
//            top = 8
//        )
//        addCard(
//            content,
//            settingSwitchRow(
//                "Panel off on auto-dim",
//                "ScreenOnAuto-style panel-only power-off; requires Shizuku/root and falls back to dim",
//                checked = MirrorSettings.screenOffOnAutoDim,
//                enabled = ScreenOffController.panelOffAvailable() || MirrorSettings.screenOffOnAutoDim
//            ) { enabled -> onPanelOffToggled(enabled) },
//            top = 8
//        )
//        addCard(content, settingRow(
//            "Dim phone now", ScreenPowerController.statusLabel(), "Apply",
//            onClick = {
//                val applied = ScreenPowerController.dimNow()
//                Toast.makeText(this,
//                    if (applied) ScreenPowerController.statusLabel() else "Start mirroring while parked first",
//                    Toast.LENGTH_SHORT).show()
//                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//            }
//        ), top = 8)
//        addCard(content, settingRow(
//            "Restore phone screen", "Restore display and restart the auto-dim timer", "Restore",
//            onClick = {
//                val restored = ScreenPowerController.restorePhoneScreen()
//                Toast.makeText(this,
//                    if (restored) "Phone display restored to configured policy" else ScreenPowerController.statusLabel(),
//                    Toast.LENGTH_SHORT).show()
//                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//            }
//        ), top = 8)
//        addCard(
//            content,
//            settingSwitchRow(
//                "Real touch injection",
//                "Privileged pointer sink; Android Auto must provide a raw pointer stream",
//                checked = MirrorSettings.realTouchEnabled,
//                enabled = ShizukuInputBackend.isRealTouchAvailable || MirrorSettings.realTouchEnabled
//            ) { enabled -> onRealTouchToggled(enabled) },
//            top = 8
//        )
//        addCard(
//            content,
//            settingRow(
//                "Input backend",
//                "Accessibility preferred, Shizuku optional",
//                inputBackendLabel(),
//                onClick = { openTouchSettings() }
//            ),
//            top = 8
//        )
//        shizukuTouchCard()?.let { addCard(content, it, top = 8) }

//        content.addView(sectionLabel("AUTOMATION"))
//        addCard(
//            content,
//            settingSwitchRow(
//                "Prevent sleep",
//                "Keep the phone panel awake while projection is active",
//                checked = MirrorSettings.preventScreenSleep,
//                enabled = true
//            ) { enabled ->
//                SettingsStore.persistPreventScreenSleep(this, enabled)
//                ProjectionService.refreshScreenPowerPolicy(this)
//                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//            },
//            top = 4
//        )
//        addCard(
//            content,
//            settingRow(
//                "Auto dim",
//                "Dim after inactivity; car touch starts the timer again",
//                MirrorSettings.autoDimDelay.label,
//                onClick = { cycleAutoDimDelay() }
//            ),
//            top = 8
//        )
//        addCard(
//            content,
//            settingSwitchRow(
//                "Stop on disconnect",
//                "Stop projection after Android Auto stays disconnected for 2 seconds",
//                checked = MirrorSettings.stopOnDisconnect,
//                enabled = true
//            ) { enabled ->
//                SettingsStore.persistStopOnDisconnect(this, enabled)
//                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//            },
//            top = 8
//        )
//        addCard(
//            content,
//            settingSwitchRow(
//                "Auto-launch last app",
//                "Reopen the last explicitly launched app after mirror and car surface are ready",
//                checked = MirrorSettings.autoLaunchLastApp,
//                enabled = true
//            ) { enabled ->
//                SettingsStore.persistAutoLaunchLastApp(this, enabled)
//                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//            },
//            top = 8
//        )
//        addCard(
//            content,
//            settingSwitchRow(
//                "Auto-start mirror",
//                "Open the mirror screen when a consented projection already exists",
//                checked = MirrorSettings.autoStartMirror,
//                enabled = true
//            ) { enabled ->
//                SettingsStore.persistAutoStartMirror(this, enabled)
//                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//            },
//            top = 8
//        )
//        addCard(content, actionCard("STOP MIRROR", primary = false, destructive = true) {
//            ProjectionService.stop(this)
//            refreshStatus()
//        }, top = 18)
//        return screenScroll(content)
//    }

//    private fun frameRateSubtitle(): String =
//        if (ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.SELF_DRAWN) {
//            "Self-drawn renderer target; measured after frames arrive"
//        } else {
//            "AUTO_MIRROR does not expose a fixed FPS control"
//        }

//    private fun frameRateValue(): String {
//        val frameStats = MirrorDiagnostics.frameStats()
//        val selfDrawn = ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.SELF_DRAWN
//        return if (selfDrawn && frameStats.rendered > 0) {
//            "%.1f FPS".format(frameStats.measuredFps)
//        } else {
//            "System"
//        }
//    }

//    private fun headUnitProfileSubtitle(): String =
//        if (SurfaceProfile.active.hardwareValidated) {
//            "Measured default profile"
//        } else {
//            "Manual placeholder; not hardware validated"
//        }

//    private fun screenOffBehaviorValue(): String = when {
//        ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.OWN_CONTENT -> "Unavailable"
//        MirrorSettings.screenOffOnAutoDim && ScreenOffController.panelOffAvailable() -> "Panel off on auto-dim"
//        ScreenOffController.survivesScreenOff() -> "Continues"
//        else -> "Not guaranteed"
//    }

//    private fun onPanelOffToggled(enabled: Boolean) {
//        if (enabled && !ScreenOffController.panelOffAvailable()) {
//            Toast.makeText(this, "Privileged panel-off is not available", Toast.LENGTH_LONG).show()
//            return
//        }
//        SettingsStore.persistScreenOffOnAutoDim(this, enabled)
//        ProjectionService.refreshScreenPowerPolicy(this)
//        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//    }

//    private fun onRealTouchToggled(enabled: Boolean) {
//        if (enabled && !ShizukuInputBackend.isRealTouchAvailable) {
//            Toast.makeText(this, "Real-touch backend is not available", Toast.LENGTH_LONG).show()
//            return
//        }
//        SettingsStore.persistRealTouchEnabled(this, enabled)
//        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//    }

//    private fun shizukuTouchCard(): View? {
//        if (!Shizuku.pingBinder()) return null
//        val label = if (ShizukuInputBackend.isPermissionGranted) {
//            "CONNECT SHIZUKU TOUCH"
//        } else {
//            "REQUEST SHIZUKU PERMISSION"
//        }
//        return actionCard(label) { onShizukuTouchCardClicked() }
//    }

//    private fun onShizukuTouchCardClicked() {
//        if (ShizukuInputBackend.isPermissionGranted) {
//            ShizukuInputBackend.bind(this)
//            refreshStatus()
//        } else {
//            ShizukuInputBackend.requestPermission()
//        }
//    }

//    private fun buildDeveloperScreen(): View {
//        val content = screenContent()
//        content.addView(screenHeader("Developer", "STATUS & DIAGNOSTICS", back = { showPhoneScreen(PhoneScreen.HOME) }))

//        content.addView(sectionLabel("LIVE STATUS"))
//        statusView = TextView(this).apply {
//            textSize = 12f
//            typeface = Typeface.MONOSPACE
//            setTextColor(COLOR_TEXT)
//            setPadding(dp(14), dp(14), dp(14), dp(14))
//            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
//        }
//        content.addView(statusView, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

//        content.addView(sectionLabel("STRUCTURED LOG"))
//        val logView = TextView(this).apply {
//            textSize = 11f
//            typeface = Typeface.MONOSPACE
//            setTextColor(COLOR_MUTED)
//            setPadding(dp(14), dp(14), dp(14), dp(14))
//            background = roundedBackground(0xff0a1b23.toInt(), COLOR_BORDER)
//        }
//        developerLogView = logView
//        content.addView(logView, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

//        content.addView(sectionLabel("MIRROR EVENTS"))
//        val mirrorEventsView = TextView(this).apply {
//            textSize = 11f
//            typeface = Typeface.MONOSPACE
//            setTextColor(COLOR_MUTED)
//            setPadding(dp(14), dp(14), dp(14), dp(14))
//            background = roundedBackground(0xff0a1b23.toInt(), COLOR_BORDER)
//        }
//        developerMirrorEventsView = mirrorEventsView
//        content.addView(mirrorEventsView, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
//        refreshDeveloperDiagnosticsViews()

//        addCard(content, actionCard("CLEAR LOG") {
//            StructuredLog.clear()
//            MirrorDiagnostics.clearEvents()
//            refreshDeveloperDiagnosticsViews()
//            refreshStatus()
//        }, top = 8)
//        addCard(content, actionCard("REFRESH STATUS") { refreshStatus() }, top = 8)

//        if (RuntimeContextStore.mode == AutoBridgeMode.LAB) {
//            content.addView(sectionLabel("LAB SIMULATOR"))
//            val labSummary = mutedText("")
//            labSummaryView = labSummary
//            content.addView(labSummary, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
//                topMargin = dp(4)
//            })
//            refreshLabSummary()
//            addCard(content, sectionLabel("ENVIRONMENT"), top = 8)
//            addCard(content, actionCard("DHU") { setLabEnvironment(Environment.DHU) }, top = 4)
//            addCard(content, actionCard("EMULATOR") { setLabEnvironment(Environment.EMULATOR) }, top = 8)
//            addCard(content, actionCard("TEST BENCH") { setLabEnvironment(Environment.TEST_BENCH) }, top = 8)
//            addCard(content, actionCard("REAL CAR (SAFETY ENFORCED)") { setLabEnvironment(Environment.REAL_CAR) }, top = 8)
//            addCard(content, sectionLabel("CONNECTION"), top = 8)
//            addCard(content, actionCard("SIMULATE CONNECTED") { setLabConnection(true) }, top = 4)
//            addCard(content, actionCard("SIMULATE DISCONNECTED") { setLabConnection(false) }, top = 8)
//            addCard(content, sectionLabel("VEHICLE STATE"), top = 8)
//            addCard(content, actionCard("SET PARKED") { setMockVehicleState(VehicleState.PARKED) }, top = 4)
//            addCard(content, actionCard("SET MOVING") { setMockVehicleState(VehicleState.MOVING) }, top = 8)
//            addCard(content, actionCard("SET UNKNOWN") { setMockVehicleState(VehicleState.UNKNOWN) }, top = 8)
//        }
//        return screenScroll(content)
//    }

//    private fun buildDevicesScreen(): View {
//        val content = screenContent()
//        content.addView(screenHeader("Devices", "ANDROID AUTO CONNECTION"))
//        addCard(
//            content,
//            TextView(this).apply {
//                text = "▣  Android Auto\n\n${if (MirrorCoordinator.isCarSurfaceReady) "●  Connected" else "○  Waiting for car surface"}\n${SurfaceProfile.active.displayLabel}  •  Wireless / DHU"
//                textSize = 14f
//                setTextColor(COLOR_TEXT)
//                setPadding(dp(16), dp(16), dp(16), dp(16))
//                background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
//            },
//            top = 4
//        )
//        addCard(content, settingRow("Car surface", "Projection target", if (MirrorCoordinator.isCarSurfaceReady) "Connected" else "Not connected"), top = 10)
//        addCard(content, settingRow("Vehicle", "Fail-closed speed gate", vehicleLabel()), top = 8)
//        addCard(content, settingRow("Reconnects", "This app session", ReconnectTracker.reconnectCount.toString()), top = 8)
//        addCard(content, actionCard("OPEN MIRROR SETTINGS") { showPhoneScreen(PhoneScreen.MIRROR_SETTINGS) }, top = 14)
//        addCard(content, actionCard("START MIRROR", primary = true) { requestScreenCapture() }, top = 8)
//        return screenScroll(content)
//    }

//    private fun renderAppGrid(grid: LinearLayout) {
//        val allApps = InstalledAppRepository.listLaunchableApps(this)
//        QuickAppsStore.syncFavorites(this, allApps)
//        val quickApps = QuickAppsStore.enabledInstalledApps(this, allApps)
//        val base = if (appsFavoritesOnly) quickApps else allApps
//        val apps = base.filter { item: InstalledApp ->
//            appSearchQuery.isBlank() || item.label.contains(appSearchQuery, ignoreCase = true)
//        }
//        grid.removeAllViews()
//        if (apps.isEmpty()) {
//            addCard(grid, mutedText(appGridEmptyMessage()), top = 14)
//            return
//        }
//        apps.chunked(3).forEachIndexed { index, rowApps ->
//            grid.addView(
//                appGridRow(rowApps),
//                LinearLayout.LayoutParams(-1, dp(126)).apply {
//                    topMargin = if (index == 0) dp(14) else dp(8)
//                }
//            )
//        }
//    }

//    private fun appGridEmptyMessage(): String =
//        if (appsFavoritesOnly) {
//            "No Quick Apps yet. Select All apps to add one."
//        } else {
//            "No apps match your search."
//        }

//    private fun appGridRow(rowApps: List<InstalledApp>): View {
//        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
//        rowApps.forEachIndexed { column, app ->
//            row.addView(
//                appCard(app),
//                LinearLayout.LayoutParams(0, dp(126), 1f).apply {
//                    val startMargin = if (column == 0) 0 else dp(4)
//                    val endMargin = if (column == rowApps.lastIndex) 0 else dp(4)
//                    setMargins(startMargin, 0, endMargin, 0)
//                }
//            )
//        }
//        repeat(3 - rowApps.size) {
//            row.addView(Space(this), LinearLayout.LayoutParams(0, dp(126), 1f))
//        }
//        return row
//    }

//    private fun appCard(app: InstalledApp): View = LinearLayout(this).apply {
//        orientation = LinearLayout.VERTICAL
//        gravity = Gravity.CENTER
//        setPadding(dp(6), dp(10), dp(6), dp(8))
//        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
//        isClickable = true
//        isFocusable = true
//        setOnClickListener {
//            selectedApp = app
//            showPhoneScreen(PhoneScreen.PROFILE)
//        }
//        addView(appIconView(app, dp(48)), LinearLayout.LayoutParams(dp(48), dp(48)))
//        addView(TextView(context).apply {
//            text = app.label
//            textSize = 11f
//            gravity = Gravity.CENTER
//            setTextColor(COLOR_TEXT)
//            maxLines = 1
//            setPadding(0, dp(6), 0, 0)
//        }, LinearLayout.LayoutParams(-1, dp(30)))
//    }

//    private fun appListRow(app: InstalledApp): View = LinearLayout(this).apply {
//        orientation = LinearLayout.HORIZONTAL
//        gravity = Gravity.CENTER_VERTICAL
//        setPadding(dp(14), dp(12), dp(14), dp(12))
//        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
//        isClickable = true
//        isFocusable = true
//        setOnClickListener {
//            selectedApp = app
//            showPhoneScreen(PhoneScreen.PROFILE)
//        }
//        addView(appIconView(app, dp(44)), LinearLayout.LayoutParams(dp(44), dp(44)))
//        val textColumn = LinearLayout(context).apply {
//            orientation = LinearLayout.VERTICAL
//            setPadding(dp(12), 0, 0, 0)
//        }
//        textColumn.addView(boldText(app.label, 15f))
//        textColumn.addView(mutedText(if (PerAppProfileStore.isForceLandscape(context, app.packageName)) "Landscape profile" else "Default profile"))
//        addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
//        addView(TextView(context).apply {
//            text = "›"
//            textSize = 24f
//            setTextColor(COLOR_MUTED)
//        }, LinearLayout.LayoutParams(dp(22), -2))
//    }

//    private fun appShortcut(app: InstalledApp): View = LinearLayout(this).apply {
//        orientation = LinearLayout.VERTICAL
//        gravity = Gravity.CENTER
//        isClickable = true
//        isFocusable = true
//        contentDescription = "Launch ${app.label}"
//        setOnClickListener {
//            if (!QuickAppLauncher.launch(this@MainActivity, app.packageName)) {
//                Toast.makeText(this@MainActivity, "Could not launch ${app.label}", Toast.LENGTH_SHORT).show()
//            }
//        }
//        setOnLongClickListener {
//            selectedApp = app
//            showPhoneScreen(PhoneScreen.PROFILE)
//            true
//        }
//        addView(appIconView(app, dp(42)), LinearLayout.LayoutParams(dp(42), dp(42)))
//        addView(TextView(context).apply {
//            text = app.label
//            textSize = 10f
//            gravity = Gravity.CENTER
//            setTextColor(COLOR_TEXT)
//            maxLines = 1
//        }, LinearLayout.LayoutParams(-1, dp(26)))
//    }

//    private fun appIconView(app: InstalledApp, size: Int): ImageView = ImageView(this).apply {
//        scaleType = ImageView.ScaleType.CENTER_INSIDE
//        val icon: Drawable? = runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
//        setImageDrawable(icon ?: getDrawable(android.R.drawable.sym_def_app_icon))
//        contentDescription = app.label
//        layoutParams = LinearLayout.LayoutParams(size, size)
//    }

//    private fun scaleModeOptions(): View = LinearLayout(this).apply {
//        orientation = LinearLayout.VERTICAL
//        setPadding(dp(14), dp(12), dp(14), dp(12))
//        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
//        val selfDrawn = ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.SELF_DRAWN
//        addView(boldText("Scale mode", 14f))
//        addView(
//            mutedText(scaleModeDescription(selfDrawn)),
//            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) }
//        )
//        val options = LinearLayout(context).apply {
//            orientation = LinearLayout.HORIZONTAL
//            setPadding(0, dp(10), 0, 0)
//        }
//        scaleModeChoices().forEachIndexed { index, (mode, label) ->
//            options.addView(
//                scaleModeChip(mode, label, selfDrawn),
//                LinearLayout.LayoutParams(0, dp(34), 1f).apply {
//                    if (index > 0) marginStart = dp(5)
//                }
//            )
//        }
//        addView(options)
//    }

//    private fun scaleModeDescription(selfDrawn: Boolean): String =
//        if (selfDrawn) {
//            "SELF_DRAWN applies the selected transform to car pixels and touch mapping"
//        } else {
//            "FIT is the only mode implemented by the direct AUTO_MIRROR pipeline"
//        }

//    private fun scaleModeChoices(): List<Pair<ScaleMode, String>> = listOf(
//        ScaleMode.FIT to "FIT",
//        ScaleMode.FILL to "FILL",
//        ScaleMode.STRETCH to "STRETCH",
//        ScaleMode.ONE_TO_ONE to "1:1"
//    )

//    private fun scaleModeChip(mode: ScaleMode, label: String, selfDrawn: Boolean): View {
//        val selected = MirrorCoordinator.requestedScale == mode
//        return TextView(this).apply {
//            text = label
//            gravity = Gravity.CENTER
//            textSize = 11f
//            setTextColor(if (selected) Color.WHITE else COLOR_MUTED)
//            background = roundedBackground(
//                if (selected) COLOR_ACCENT_DARK else COLOR_SURFACE_ALT,
//                COLOR_BORDER
//            )
//            alpha = if (selected || selfDrawn) 1f else 0.55f
//            setOnClickListener { onScaleModeChipClicked(mode, label, selfDrawn) }
//        }
//    }

//    private fun onScaleModeChipClicked(mode: ScaleMode, label: String, selfDrawn: Boolean) {
//        if (!selfDrawn) {
//            Toast.makeText(this, "$label requires SELF_DRAWN pipeline", Toast.LENGTH_SHORT).show()
//            return
//        }
//        MirrorCoordinator.setScaleMode(mode)
//        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//    }

//    private fun settingRow(
//        title: String,
//        subtitle: String,
//        value: String,
//        onClick: (() -> Unit)? = null
//    ): View = LinearLayout(this).apply {
//        orientation = LinearLayout.HORIZONTAL
//        gravity = Gravity.CENTER_VERTICAL
//        setPadding(dp(14), dp(11), dp(14), dp(11))
//        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
//        if (onClick != null) {
//            isClickable = true
//            isFocusable = true
//            setOnClickListener { onClick() }
//        }
//        val info = LinearLayout(context).apply {
//            orientation = LinearLayout.VERTICAL
//        }
//        info.addView(boldText(title, 13f))
//        info.addView(mutedText(subtitle), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
//        addView(info, LinearLayout.LayoutParams(0, -2, 1f))
//        addView(TextView(context).apply {
//            text = value
//            textSize = 12f
//            gravity = Gravity.CENTER
//            setTextColor(if (value == "Active" || value == "Enabled" || value == "Connected") COLOR_SUCCESS else COLOR_TEXT)
//            setPadding(dp(8), 0, 0, 0)
//        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, -2))
//    }

//    private fun settingSwitchRow(
//        title: String,
//        subtitle: String,
//        checked: Boolean,
//        enabled: Boolean,
//        onChanged: ((Boolean) -> Unit)? = null
//    ): View = LinearLayout(this).apply {
//        orientation = LinearLayout.HORIZONTAL
//        gravity = Gravity.CENTER_VERTICAL
//        setPadding(dp(14), dp(10), dp(10), dp(10))
//        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
//        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
//        info.addView(boldText(title, 13f))
//        info.addView(mutedText(subtitle), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
//        addView(info, LinearLayout.LayoutParams(0, -2, 1f))
//        val toggle = Switch(context).apply {
//            isChecked = checked
//            isEnabled = enabled
//            alpha = if (enabled) 1f else 0.5f
//            setOnCheckedChangeListener { _, value -> onChanged?.invoke(value) }
//        }
//        addView(toggle, LinearLayout.LayoutParams(dp(54), dp(48)))
//    }

//    private fun screenHeader(
//        title: String,
//        subtitle: String? = null,
//        back: (() -> Unit)? = null,
//        action: Pair<String, () -> Unit>? = null
//    ): View = LinearLayout(this).apply {
//        orientation = LinearLayout.HORIZONTAL
//        gravity = Gravity.CENTER_VERTICAL
//        setPadding(0, dp(8), 0, dp(14))
//        if (back != null) {
//            addView(TextView(context).apply {
//                text = "‹"
//                textSize = 30f
//                gravity = Gravity.CENTER
//                setTextColor(COLOR_TEXT)
//                isClickable = true
//                setOnClickListener { back() }
//            }, LinearLayout.LayoutParams(dp(36), dp(48)))
//        }
//        val titleColumn = LinearLayout(context).apply {
//            orientation = LinearLayout.VERTICAL
//        }
//        titleColumn.addView(boldText(title, 20f))
//        if (!subtitle.isNullOrBlank()) titleColumn.addView(mutedText(subtitle), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
//        addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
//        if (action != null) {
//            addView(TextView(context).apply {
//                text = action.first
//                textSize = 22f
//                gravity = Gravity.CENTER
//                setTextColor(COLOR_ACCENT)
//                isClickable = true
//                setOnClickListener { action.second() }
//            }, LinearLayout.LayoutParams(dp(42), dp(48)))
//        }
//    }

//    private fun screenContent(): LinearLayout = LinearLayout(this).apply {
//        orientation = LinearLayout.VERTICAL
//        setPadding(dp(18), dp(12), dp(18), dp(24))
//        setBackgroundColor(COLOR_BACKGROUND)
//    }

//    private fun screenScroll(content: View): ScrollView = ScrollView(this).apply {
//        isFillViewport = true
//        setBackgroundColor(COLOR_BACKGROUND)
//        addView(content)
//    }

//    private fun sectionLabel(title: String): TextView = TextView(this).apply {
//        text = title
//        textSize = 11f
//        setTextColor(COLOR_ACCENT)
//        setTypeface(null, Typeface.BOLD)
//        letterSpacing = 0.08f
//        setPadding(0, dp(18), 0, dp(7))
//    }

//    private fun dashboardTile(icon: String, title: String, action: () -> Unit): View = TextView(this).apply {
//        text = "$icon\n$title"
//        gravity = Gravity.CENTER
//        textSize = 13f
//        setLineSpacing(0f, 0.9f)
//        setTextColor(COLOR_TEXT)
//        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
//        isClickable = true
//        isFocusable = true
//        setOnClickListener { action() }
//    }

//    private fun actionCard(
//        label: String,
//        primary: Boolean = false,
//        destructive: Boolean = false,
//        action: () -> Unit
//    ): TextView = TextView(this).apply {
//        text = label
//        gravity = Gravity.CENTER
//        textSize = if (primary) 15f else 13f
//        setTypeface(null, Typeface.BOLD)
//        setTextColor(if (destructive) 0xffff8d8d.toInt() else Color.WHITE)
//        setPadding(dp(14), dp(12), dp(14), dp(12))
//        minHeight = dp(if (primary) 58 else 48)
//        background = roundedBackground(
//            when {
//                primary -> COLOR_ACCENT
//                destructive -> 0xff321b24.toInt()
//                else -> COLOR_SURFACE_ALT
//            },
//            when {
//                primary -> COLOR_ACCENT
//                destructive -> 0xff77394a.toInt()
//                else -> COLOR_BORDER
//            }
//        )
//        isClickable = true
//        isFocusable = true
//        setOnClickListener { action() }
//    }

//    private fun mutedText(value: String): TextView = TextView(this).apply {
//        text = value
//        textSize = 12f
//        setTextColor(COLOR_MUTED)
//    }

//    private fun boldText(value: String, size: Float): TextView = TextView(this).apply {
//        text = value
//        textSize = size
//        setTextColor(COLOR_TEXT)
//        setTypeface(null, Typeface.BOLD)
//    }

//    private fun addCard(parent: LinearLayout, view: View, top: Int = 0) {
//        parent.addView(view, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
//            topMargin = dp(top)
//        })
//    }

//    private fun weightedTile(start: Int = 0, end: Int = 0, top: Int = 0): LinearLayout.LayoutParams =
//        LinearLayout.LayoutParams(0, -1, 1f).apply {
//            setMargins(dp(start), dp(top), dp(end), 0)
//        }

//    private fun styleFilter(view: TextView, label: String, selected: Boolean) {
//        view.text = label
//        view.gravity = Gravity.CENTER
//        view.textSize = 12f
//        view.setTextColor(if (selected) Color.WHITE else COLOR_MUTED)
//        view.background = roundedBackground(if (selected) COLOR_ACCENT_DARK else COLOR_SURFACE, COLOR_BORDER)
//    }

//    private fun roundedBackground(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
//        setColor(fill)
//        cornerRadius = dp(11).toFloat()
//        setStroke(dp(1), stroke)
//    }

//    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

//    private fun openTouchSettings() {
//        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
//    }

//    private fun inputBackendLabel(): String = when {
//        ShizukuInputBackend.isRealTouchAvailable -> "Shizuku • real-touch ready"
//        ShizukuInputBackend.isPermissionGranted -> "Shizuku"
//        AccessibilityInputBackend.isAvailable -> "Accessibility"
//        else -> "Not connected"
//    }

//    private fun vehicleLabel(): String = when (RuntimeContextStore.vehicleState) {
//        VehicleState.PARKED -> "PARKED"
//        VehicleState.MOVING -> "MOVING"
//        VehicleState.UNKNOWN -> "UNKNOWN"
//    }

//    private fun toggleLandscapeMirror() {
//        if (!MirrorOrientationController.isEnabled &&
//            !MirrorOrientationController.hasPermission(this)
//        ) {
//            Toast.makeText(
//                this,
//                "Allow Modify system settings to enable landscape mirror",
//                Toast.LENGTH_LONG
//            ).show()
//            QuickAppLauncher.requestLandscapePermission(this)
//            return
//        }
//        val enabled = !MirrorOrientationController.isEnabled
//        if (!MirrorOrientationController.setEnabled(this, enabled)) {
//            Toast.makeText(this, "Landscape mirror could not be changed", Toast.LENGTH_LONG).show()
//        } else {
//            Toast.makeText(
//                this,
//                if (enabled) "Landscape mirror enabled" else "Landscape mirror disabled",
//                Toast.LENGTH_SHORT
//            ).show()
//        }
//        refreshStatus()
//        if (currentScreen == PhoneScreen.MIRROR_SETTINGS) showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//    }

//    private fun cyclePipelineMode() {
//        if (MirrorCoordinator.isProjectionReady) {
//            Toast.makeText(this, "Stop projection before changing the renderer", Toast.LENGTH_SHORT).show()
//            return
//        }
//        val next = when (ScreenOffController.pipelineMode) {
//            ScreenOffController.PipelineMode.AUTO_MIRROR -> ScreenOffController.PipelineMode.SELF_DRAWN
//            ScreenOffController.PipelineMode.SELF_DRAWN,
//            ScreenOffController.PipelineMode.OWN_CONTENT -> ScreenOffController.PipelineMode.AUTO_MIRROR
//        }
//        if (!MirrorCoordinator.setPipelineMode(next)) {
//            Toast.makeText(this, "Renderer pipeline is not available", Toast.LENGTH_SHORT).show()
//            return
//        }
//        SettingsStore.persistScreenOffMode(this, next)
//        Toast.makeText(this, "Pipeline: ${next.name}", Toast.LENGTH_SHORT).show()
//        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//    }

//    private fun cycleAutoDimDelay() {
//        val next = MirrorSettings.autoDimDelay.next()
//        SettingsStore.persistAutoDimDelay(this, next)
//        ProjectionService.refreshScreenPowerPolicy(this)
//        Toast.makeText(this, "Auto dim: ${next.label}", Toast.LENGTH_SHORT).show()
//        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//    }

//    private fun toggleSurfaceProfile() {
//        SurfaceProfile.active = if (SurfaceProfile.active == SurfaceProfile.DEFAULT) {
//            SurfaceProfile.FORD_NEXT_GEN
//        } else {
//            SurfaceProfile.DEFAULT
//        }
//        SettingsStore.persistSurfaceProfile(this, SurfaceProfile.active)
//        Toast.makeText(
//            this,
//            "Head unit profile: ${SurfaceProfile.active.displayLabel}",
//            Toast.LENGTH_SHORT
//        ).show()
//        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
//    }

//    private fun cycleProfileScale(packageName: String, profileId: String = "default") {
//        val current = PerAppProfileStore.profile(this, packageName, profileId)
//        val next = when (current.scaleMode) {
//            ScaleMode.FIT -> ScaleMode.FILL
//            ScaleMode.FILL -> ScaleMode.STRETCH
//            ScaleMode.STRETCH -> ScaleMode.ONE_TO_ONE
//            ScaleMode.ONE_TO_ONE -> ScaleMode.FIT
//        }
//        PerAppProfileStore.save(this, current.copy(scaleMode = next))
//        Toast.makeText(this, "Scale preference: $next", Toast.LENGTH_SHORT).show()
//        showPhoneScreen(PhoneScreen.PROFILE)
//    }

//    private fun cycleProfileResolution(packageName: String, profileId: String = "default") {
//        val current = PerAppProfileStore.profile(this, packageName, profileId)
//        val next = when (current.preferredResolution) {
//            null, ResolutionPreset.AUTO -> ResolutionPreset.HD_720P
//            ResolutionPreset.HD_720P -> ResolutionPreset.FULL_HD_1080P
//            ResolutionPreset.FULL_HD_1080P -> null
//            ResolutionPreset.CUSTOM -> null
//        }
//        PerAppProfileStore.save(this, current.copy(preferredResolution = next))
//        Toast.makeText(this, "Resolution preference: ${next?.name ?: "AUTO"}", Toast.LENGTH_SHORT).show()
//        showPhoneScreen(PhoneScreen.PROFILE)
//    }

//    private fun cycleProfileFps(packageName: String, profileId: String = "default") {
//        val current = PerAppProfileStore.profile(this, packageName, profileId)
//        val next = when (current.preferredFps) {
//            null -> 30
//            30 -> 60
//            else -> null
//        }
//        PerAppProfileStore.save(this, current.copy(preferredFps = next))
//        if (RuntimeContextStore.context.value.currentPackageName == packageName) {
//            MirrorSettings.preferredFps = next
//        }
//        Toast.makeText(this, "Frame rate preference: ${next?.let { "$it FPS" } ?: "System"}", Toast.LENGTH_SHORT).show()
//        showPhoneScreen(PhoneScreen.PROFILE)
//    }

//    private fun cycleProfileAudio(packageName: String, profileId: String = "default") {
//        val current = PerAppProfileStore.profile(this, packageName, profileId)
//        val next = when (current.audioMode) {
//            AudioMode.MEDIA -> AudioMode.MIRROR
//            AudioMode.MIRROR -> AudioMode.OFF
//            AudioMode.OFF -> AudioMode.MEDIA
//        }
//        PerAppProfileStore.save(this, current.copy(audioMode = next))
//        Toast.makeText(this, "Audio preference: $next", Toast.LENGTH_SHORT).show()
//        showPhoneScreen(PhoneScreen.PROFILE)
//    }

//    private fun addLastSessionCard(content: LinearLayout) {
//        val snapshot = SessionRestoreStore.restore(this) ?: return
//        val app = InstalledAppRepository.listLaunchableApps(this)
//            .firstOrNull { it.packageName == snapshot.packageName }
//        val launchable = runCatching {
//            packageManager.getLaunchIntentForPackage(snapshot.packageName) != null
//        }.getOrDefault(false)
//        if (app == null || !launchable) {
//            SessionRestoreStore.clear(this)
//            return
//        }

//        content.addView(sectionLabel("LAST SESSION"))
//        addCard(
//            content,
//            actionCard("↻   RESUME ${app.label}", primary = false) {
//                if (!QuickAppLauncher.launch(this, snapshot.packageName, snapshot.profileId)) {
//                    Toast.makeText(this, "Could not resume ${app.label}", Toast.LENGTH_SHORT).show()
//                }
//            },
//            top = 4
//        )
//        addCard(
//            content,
//            settingRow(
//                "Last app",
//                "${snapshot.smartMode.name} • capture consent is never replayed",
//                "Forget",
//                onClick = {
//                    SessionRestoreStore.clear(this)
//                    showPhoneScreen(PhoneScreen.HOME)
//                }
//            ),
//            top = 8
//        )
//    }


//    private fun refreshDeveloperDiagnosticsViews() {
//        developerLogView?.text = StructuredLog.format(limit = 20).ifEmpty { "No log entries yet" }
//        developerMirrorEventsView?.text = MirrorDiagnostics.format(limit = 20).ifEmpty { "No mirror events yet" }
//    }

//    private fun refreshLabSummary() {
//        labSummaryView?.text = buildString {
//            appendLine("Environment: ${RuntimeContextStore.context.value.environment}")
//            appendLine("Connection: ${if (RuntimeContextStore.context.value.connected) "CONNECTED" else "DISCONNECTED"}")
//            append("Vehicle provider: ${if (DevMode.isEnabled) "LAB simulator" else "production CAR_SPEED / fail-closed"}")
//        }
//    }

//    private fun setMockVehicleState(state: VehicleState) {
//        if (!DevMode.isEnabled) {
//            Toast.makeText(this, "LAB simulation requires a debug emulator and a non-real-car environment", Toast.LENGTH_SHORT).show()
//            return
//        }
//        val legacyState = when (state) {
//            VehicleState.PARKED -> ParkingStateStore.State.PARKED
//            VehicleState.MOVING -> ParkingStateStore.State.MOVING
//            VehicleState.UNKNOWN -> ParkingStateStore.State.UNKNOWN
//        }
//        MockVehicleStateProvider.setState(legacyState)
//        RuntimeContextStore.setSimulatedVehicleState(state)
//        Toast.makeText(this, "LAB vehicle state: $state", Toast.LENGTH_SHORT).show()
//        refreshStatus()
//    }

//    private fun setLabEnvironment(environment: Environment) {
//        if (RuntimeContextStore.context.value.connected || MirrorCoordinator.isCarSurfaceReady) {
//            Toast.makeText(this, "Stop the active Android Auto session before changing LAB environment", Toast.LENGTH_SHORT).show()
//            return
//        }
//        if (!RuntimeContextStore.setEnvironment(environment)) {
//            Toast.makeText(this, "Environment selection is available only in LAB", Toast.LENGTH_SHORT).show()
//            return
//        }
//        if (environment == Environment.REAL_CAR) {
//            MockVehicleStateProvider.stop()
//        } else if (DevMode.isEnabled) {
//            MockVehicleStateProvider.start()
//        } else {
//            MockVehicleStateProvider.stop()
//        }
//        Toast.makeText(this, "LAB environment: $environment", Toast.LENGTH_SHORT).show()
//        showPhoneScreen(PhoneScreen.DEVELOPER)
//    }

//    private fun setLabConnection(connected: Boolean) {
//        if (MirrorCoordinator.isCarSurfaceReady) {
//            Toast.makeText(this, "A real Android Auto surface is already connected", Toast.LENGTH_SHORT).show()
//            return
//        }
//        if (!RuntimeContextStore.setSimulatedConnection(connected)) {
//            Toast.makeText(this, "Connection simulation is available only outside REAL_CAR", Toast.LENGTH_SHORT).show()
//            return
//        }
//        Toast.makeText(this, if (connected) "LAB connection simulated" else "LAB connection disconnected", Toast.LENGTH_SHORT).show()
//        refreshLabSummary()
//        refreshStatus()
//    }

//    private fun browserScreenIntent(): Intent =
//        Intent(this, dev.autobridge.entertainment.EntertainmentActivity::class.java).apply {
//            putExtra(dev.autobridge.entertainment.EntertainmentActivity.EXTRA_BROWSER_MODE, true)
//            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
//        }

//    /**
//     * Opens the embedded WebView after projection is ready, so AUTO_MIRROR sends this screen to
//     * the car surface. The first use asks for MediaProjection consent and continues afterward.
//     */
//    private fun openBrowserOnCar() {
//        if (!FeaturePolicy.app.isAvailable(Feature.BROWSER)) {
//            Toast.makeText(this, FeaturePolicy.app.denialMessage(Feature.BROWSER), Toast.LENGTH_SHORT).show()
//            return
//        }
//        if (MirrorCoordinator.isProjectionReady) {
//            startActivity(browserScreenIntent())
//            return
//        }
//        pendingEntertainmentLaunch = true
//        requestScreenCapture()
//    }

//    private fun requestScreenCapture() {
//        if (!FeaturePolicy.app.isAvailable(Feature.MIRROR)) {
//            Toast.makeText(this, FeaturePolicy.app.denialMessage(Feature.MIRROR), Toast.LENGTH_SHORT).show()
//            return
//        }
//        val manager = getSystemService(MediaProjectionManager::class.java)
//        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
//            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
//        } else {
//            @Suppress("DEPRECATION")
//            manager.createScreenCaptureIntent()
//        }
//        @Suppress("DEPRECATION")
//        startActivityForResult(captureIntent, REQUEST_CAPTURE)
//    }

//    @Deprecated("Deprecated by Activity; retained to keep this starter dependency-light")
//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
//        if (requestCode != REQUEST_CAPTURE) return

//        if (resultCode == RESULT_OK && data != null) {
//            val started = ProjectionService.start(this, resultCode, data)
//            if (!started) {
//                pendingEntertainmentLaunch = false
//                Toast.makeText(
//                    this,
//                    "Could not start screen projection: ${ProjectionService.lastErrorMessage ?: "unknown error"}",
//                    Toast.LENGTH_LONG
//                ).show()
//            } else if (pendingEntertainmentLaunch) {
//                pendingEntertainmentLaunch = false
//                startActivity(browserScreenIntent())
//            }
//        } else {
//            pendingEntertainmentLaunch = false
//            Toast.makeText(this, "Screen capture was not started", Toast.LENGTH_SHORT).show()
//        }
//        refreshStatus()
//    }

//    private fun requestNotificationPermissionIfNeeded() {
//        if (Build.VERSION.SDK_INT >= 33 &&
//            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
//        ) {
//            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
//        }
//    }

//    private fun refreshStatus() {
//        val runtime = RuntimeContextStore.context.value
//        val contentWarning = MirrorContentPolicy.warningFor(runtime.currentPackageName)
//        val statusText = buildStatusText(runtime, contentWarning)

//        refreshDeveloperDiagnosticsViews()
//        refreshLabSummary()
//        if (::statusView.isInitialized) statusView.text = statusText
//        homeConnectionView?.text = buildHomeConnectionText(runtime)
//    }

//    private fun buildStatusText(runtime: RuntimeContext, contentWarning: String?): String =
//        buildString {
//            appendLine("Mode: ${runtime.mode}")
//            appendLine("Environment: ${runtime.environment}")
//            appendLine("Android Auto: ${androidAutoLabel(runtime)}")
//            appendLine("Projection: ${projectionStatusLabel()}")
//            appendLine("Car surface: ${boolLabel(MirrorCoordinator.isCarSurfaceReady, "connected", "not connected")}")
//            appendLine("Vehicle: ${parkingLabel(runtime.vehicleState)}")
//            appendLine("Mirroring: ${boolLabel(MirrorCoordinator.isMirroring, "ACTIVE", "inactive")}")
//            contentWarning?.let { appendLine("Content warning: $it") }
//            labSimulatorLine(runtime)?.let { appendLine(it) }
//            appendLine("Next: ${nextStepMessage(runtime, contentWarning)}")
//            appendLine()
//            appendLine("Shizuku touch: ${shizukuStatusLabel()}")
//            appendLine("Car reconnects (this session): ${ReconnectTracker.reconnectCount}")
//            appendLine("Phone rotation: ${OrientationMonitor.label()}")
//            appendLine("Landscape mirror (Fermata): ${landscapeMirrorLabel()}")
//            appendLine("Head unit profile: ${SurfaceProfile.active.displayLabel}")
//            appendLine("Force-landscape permission: ${boolLabel(QuickAppLauncher.hasLandscapePermission(this@MainActivity), "granted", "not granted")}")
//            appendLine("Media playback: ${boolLabel(mediaPlayback.isPlaying, "playing", "paused/stopped")}")
//            appendLine("Screen-off: ${ScreenOffController.statusLabel()}")
//            appendLine("Phone display: ${ScreenPowerController.statusLabel()}")
//            appendLine("Renderer pipeline: ${ScreenOffController.pipelineMode.name}")
//            appendLine(rendererFrameLine())
//            appendLine("Prevent sleep: ${onOff(MirrorSettings.preventScreenSleep)}")
//            appendLine("Auto dim: ${MirrorSettings.autoDimDelay.label}")
//            appendLine("Stop on disconnect: ${onOff(MirrorSettings.stopOnDisconnect)}")
//            appendLine("Auto-launch last app: ${onOff(MirrorSettings.autoLaunchLastApp)}")
//            appendLine("Auto-start mirror: ${onOff(MirrorSettings.autoStartMirror)}")
//            appendLine("Developer log: ${StructuredLog.recent().size} entries")
//            MirrorDiagnostics.currentMirroringUptimeMs()?.let { appendLine("Mirroring uptime: ${it / 1000}s") }
//            val timeToActive = MirrorDiagnostics.latencyBetween("consent_received", "mirroring_active")
//            append("Time to active (last session): ${timeToActive?.let { "${it}ms" } ?: "n/a"}")
//        }

//    private fun buildHomeConnectionText(runtime: RuntimeContext): String =
//        buildString {
//            appendLine("●  Android Auto    ${if (MirrorCoordinator.isCarSurfaceReady) "Connected" else "Waiting"}")
//            appendLine("    ${SurfaceProfile.active.displayLabel}  •  ${runtime.environment}")
//            append("    Vehicle  •  ${if (runtime.vehicleState == VehicleState.PARKED) "PARKED" else "SAFETY LOCKED"}")
//        }

//    private fun onOff(value: Boolean): String = if (value) "on" else "off"

//    private fun boolLabel(value: Boolean, ifTrue: String, ifFalse: String): String =
//        if (value) ifTrue else ifFalse

//    private fun androidAutoLabel(runtime: RuntimeContext): String =
//        boolLabel(runtime.connected || MirrorCoordinator.isCarSurfaceReady, "CONNECTED", "DISCONNECTED")

//    private fun labSimulatorLine(runtime: RuntimeContext): String? {
//        if (runtime.mode != AutoBridgeMode.LAB) return null
//        val inputState = boolLabel(
//            runtime.environment == Environment.REAL_CAR,
//            "disabled on REAL_CAR",
//            "available"
//        )
//        return "LAB simulator: vehicle input is $inputState"
//    }

//    private fun parkingLabel(state: VehicleState): String = when (state) {
//        VehicleState.PARKED -> "PARKED"
//        VehicleState.MOVING -> "MOVING (blocked)"
//        VehicleState.UNKNOWN -> "UNKNOWN (blocked)"
//    }

//    private fun projectionStatusLabel(): String = when (ProjectionService.sessionState) {
//        ProjectionService.SessionState.IDLE ->
//            if (MirrorCoordinator.isProjectionReady) "ready" else "not started"
//        ProjectionService.SessionState.STARTING -> "starting…"
//        ProjectionService.SessionState.READY -> "ready"
//        ProjectionService.SessionState.ERROR ->
//            "ERROR — ${ProjectionService.lastErrorMessage ?: "unknown error"}"
//    }

//    private fun nextStepMessage(runtime: RuntimeContext, contentWarning: String?): String {
//        val mirrorDecision = FeaturePolicy.app.decide(Feature.MIRROR, runtime)
//        return when {
//            !mirrorDecision.allowed -> mirrorDecision.reason
//            ProjectionService.sessionState == ProjectionService.SessionState.ERROR ->
//                "Tap Start screen mirror and grant capture permission again."
//            ProjectionService.sessionState == ProjectionService.SessionState.STARTING ->
//                "Wait for the projection service to become ready."
//            !MirrorCoordinator.isProjectionReady ->
//                "Tap Start screen mirror and allow screen capture."
//            !MirrorCoordinator.isCarSurfaceReady ->
//                "Open AutoBridge in Android Auto / DHU."
//            runtime.vehicleState == VehicleState.MOVING ->
//                "Vehicle is moving; mirroring is blocked."
//            runtime.vehicleState == VehicleState.UNKNOWN ->
//                "Vehicle state is unknown; enable speed safety before mirroring."
//            !MirrorCoordinator.isMirroring ->
//                "Mirror prerequisites are ready; wait briefly or check Developer log."
//            contentWarning != null -> contentWarning
//            else -> "Mirror is active."
//        }
//    }

//    private fun shizukuStatusLabel(): String = when {
//        !FeaturePolicy.app.isAvailable(Feature.SHIZUKU) -> "disabled by mode"
//        !Shizuku.pingBinder() -> "not running"
//        !ShizukuInputBackend.isPermissionGranted -> "permission needed"
//        else -> "ready"
//    }

//    private fun landscapeMirrorLabel(): String = when {
//        !MirrorOrientationController.isEnabled -> "off"
//        !MirrorOrientationController.hasPermission(this@MainActivity) -> "ON (permission needed)"
//        MirrorOrientationController.isApplied -> "ON (applied; restored on stop)"
//        else -> "ON (applies next session)"
//    }

//    private fun rendererFrameLine(): String {
//        if (ScreenOffController.pipelineMode != ScreenOffController.PipelineMode.SELF_DRAWN) {
//            return "Frame stats: n/a (OS-owned AUTO_MIRROR)"
//        }
//        val frameStats = MirrorDiagnostics.frameStats()
//        return "Self-drawn frames: captured=${frameStats.captured}, dropped=${frameStats.dropped}, " +
//            "rendered=${frameStats.rendered}, fps=${"%.1f".format(frameStats.measuredFps)}, " +
//            "latency=${frameStats.lastLatencyMs?.let { "${it}ms" } ?: "n/a"}"
//    }
//}

package dev.autobridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.autobridge.apps.AppListFilter
import dev.autobridge.apps.InstalledApp
import dev.autobridge.apps.InstalledAppRepository
import dev.autobridge.apps.MirrorContentPolicy
import dev.autobridge.apps.PerAppProfileStore
import dev.autobridge.apps.QuickAppLauncher
import dev.autobridge.apps.QuickAppsStore
import dev.autobridge.apps.SmartModeResolver
import dev.autobridge.core.datastore.SessionRestoreStore
import dev.autobridge.core.model.AudioMode
import dev.autobridge.core.model.AutoBridgeMode
import dev.autobridge.core.model.Environment
import dev.autobridge.core.model.Feature
import dev.autobridge.core.model.ResolutionPreset
import dev.autobridge.core.model.RotationMode
import dev.autobridge.core.model.RuntimeContext
import dev.autobridge.core.model.ScaleMode
import dev.autobridge.core.model.VehicleState
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.ui.AutoBridgePhoneTheme
import dev.autobridge.ui.PhoneControlCenter
import dev.autobridge.ui.MobileRemoteScreen
import dev.autobridge.ui.RemoteTab
import dev.autobridge.remote.RemoteRuntime
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.display.MirrorOrientationController
import dev.autobridge.display.OrientationMonitor
import dev.autobridge.display.ScreenOffController
import dev.autobridge.display.ScreenPowerController
import dev.autobridge.display.StructuredLog
import dev.autobridge.diagnostics.CrashReportStore
import dev.autobridge.display.SurfaceProfile
import dev.autobridge.entertainment.BrowserLauncher
import dev.autobridge.input.AccessibilityInputBackend
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.mirror.ReconnectTracker
import dev.autobridge.safety.DevMode
import dev.autobridge.safety.MockVehicleStateProvider
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.settings.MirrorSettings
import dev.autobridge.settings.SettingsStore
import rikka.shizuku.Shizuku

class MainActivity : androidx.activity.ComponentActivity() {
    private companion object {
        const val REQUEST_CAPTURE = 2001
        const val REQUEST_NOTIFICATIONS = 2002
        const val REQUEST_VOICE_SEARCH = 2003

        const val COLOR_BACKGROUND = 0xff0b0e14.toInt()
        const val COLOR_SURFACE = 0xff141924.toInt()
        const val COLOR_SURFACE_ALT = 0xff1c2331.toInt()
        const val COLOR_BORDER = 0xff26304a.toInt()
        const val COLOR_ACCENT = 0xff4c7df0.toInt()
        const val COLOR_ACCENT_DARK = 0xff0b5d96.toInt()
        const val COLOR_TEXT = 0xffeaf0fa.toInt()
        const val COLOR_MUTED = 0xff8494b0.toInt()
        const val COLOR_SUCCESS = 0xff2ee879.toInt()
        const val COLOR_WARNING = 0xffffbf5f.toInt()
    }

    private enum class PhoneScreen {
        HOME,
        SETTINGS,
        CONTROL_CENTER,
        REMOTE,
        APPS,
        PROFILES,
        PROFILE,
        MIRROR_SETTINGS,
        DEVELOPER,
        DEVICES
    }

    /** Selected sub-tab of the Mobile Remote (Home/Remote/Commands/Settings). */
    private var remoteTab = RemoteTab.REMOTE

    /** Restyles the Applications filter chips; set while that screen is built, null otherwise. */
    private var appsFilterRefresh: (() -> Unit)? = null

    private lateinit var statusView: TextView
    private lateinit var mediaPlayback: MediaPlaybackClient
    private lateinit var screenContainer: FrameLayout
    private lateinit var bottomNav: LinearLayout
    private var homeConnectionView: TextView? = null
    private var developerLogView: TextView? = null
    private var crashReportView: TextView? = null
    private var developerMirrorEventsView: TextView? = null
    private var labSummaryView: TextView? = null
    private var pendingEntertainmentLaunch = false

    /** The home screen's now-playing bar, kept across re-renders so its ticker is not restarted. */
    private var homeMiniPlayer: dev.autobridge.ui.MiniPlayer? = null
    private var currentScreen = PhoneScreen.HOME

    /**
     * False until the first screen has actually been built. Without it the re-entry guard below
     * would swallow the initial render, because currentScreen already starts at HOME.
     */
    private var screenRendered = false
    private var selectedApp: InstalledApp? = null
    private var appsFavoritesOnly = true
    private var appSearchQuery = ""

    private val statusHandler = Handler(Looper.getMainLooper())
    private val refreshStatusRunnable = object : Runnable {
        override fun run() {
            if (::statusView.isInitialized || homeConnectionView != null) refreshStatus()
            statusHandler.postDelayed(this, 1_000L)
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        runOnUiThread {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (granted) "Shizuku touch enabled" else "Shizuku permission denied",
                Toast.LENGTH_SHORT
            ).show()
            if (granted) ShizukuInputBackend.bind(this)
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.restore(this)
        SessionRestoreStore.restore(this)?.let { snapshot ->
            RuntimeContextStore.setCurrentFeature(snapshot.feature, snapshot.packageName)
            RuntimeContextStore.setDisplayPreferences(
                scaleMode = snapshot.scaleMode,
                rotationMode = snapshot.rotationMode,
                audioMode = snapshot.audioMode,
                fullscreen = snapshot.fullscreen
            )
        }
        if (DevMode.isEnabled) {
            MockVehicleStateProvider.start()
        }
        requestNotificationPermissionIfNeeded()
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        mediaPlayback = MediaPlaybackClient(this)
        mediaPlayback.connect(onConnected = {
            if (::statusView.isInitialized || homeConnectionView != null) refreshStatus()
        })
        // Start the Mobile Remote command runtime and share the already-connected media client so
        // media commands reuse the same MediaSession (no duplicate connection).
        RemoteRuntime.ensureStarted(this)
        RemoteRuntime.attachMediaClient(mediaPlayback)
        setContentView(buildUi())
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentScreen != PhoneScreen.HOME) showPhoneScreen(PhoneScreen.HOME)
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        statusHandler.removeCallbacks(refreshStatusRunnable)
        refreshStatusRunnable.run()
    }

    override fun onPause() {
        statusHandler.removeCallbacks(refreshStatusRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        homeMiniPlayer?.stop()
        mediaPlayback.disconnect()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        screenContainer = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
        }
        root.addView(
            screenContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(7))
            setBackgroundColor(COLOR_SURFACE_ALT)
        }
        root.addView(
            bottomNav,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64)
            )
        )

        showPhoneScreen(PhoneScreen.HOME)
        return root
    }

    private fun showPhoneScreen(requested: PhoneScreen, force: Boolean = false) {
        val target = if (requested == PhoneScreen.PROFILE && selectedApp == null) {
            PhoneScreen.APPS
        } else {
            requested
        }
        // Re-selecting the destination already on screen is a no-op rather than a rebuild: tapping
        // the active bottom-bar tab used to discard the Applications search term and the scroll
        // position and hand back an identical screen. PROFILE is exempt because which app it shows
        // is state, so the same destination can still need a rebuild. [force] overrides this for
        // callers that need the same screen rebuilt with new data (e.g. the weather chip landing).
        if (!force && screenRendered && target == currentScreen && target != PhoneScreen.PROFILE) return
        screenRendered = true
        currentScreen = target
        homeConnectionView = null
        developerLogView = null
        developerMirrorEventsView = null
        labSummaryView = null
        screenContainer.removeAllViews()

        val screen = when (target) {
            PhoneScreen.HOME -> buildHomeScreen()
            PhoneScreen.SETTINGS -> buildSettingsMenu()
            PhoneScreen.CONTROL_CENTER -> buildControlCenterScreen()
            PhoneScreen.REMOTE -> buildRemoteScreen()
            PhoneScreen.APPS -> buildAppsScreen()
            PhoneScreen.PROFILES -> buildProfilesScreen()
            PhoneScreen.PROFILE -> buildProfileScreen()
            PhoneScreen.MIRROR_SETTINGS -> buildMirrorSettingsScreen()
            PhoneScreen.DEVELOPER -> buildDeveloperScreen()
            PhoneScreen.DEVICES -> buildDevicesScreen()
        }
        screenContainer.addView(
            screen,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // The bar belongs to every top-level destination, including the ones it used to hide on.
        // Hiding it on Home, Settings and Remote meant Home had no navigation at all (its tiles were
        // the only way out) and that opening Settings from the Apps tab dropped the bar exactly when
        // switching tabs was the likely next move. Only per-item detail screens hide it now, so Back
        // is unambiguous there.
        bottomNav.visibility = if (
            target == PhoneScreen.PROFILE ||
            target == PhoneScreen.DEVELOPER
        ) {
            View.GONE
        } else {
            View.VISIBLE
        }
        renderBottomNavigation()
        refreshStatus()
    }

    private fun renderBottomNavigation() {
        if (!::bottomNav.isInitialized || bottomNav.visibility != View.VISIBLE) return
        bottomNav.removeAllViews()
        listOf(
            Triple("⌂", "Home", PhoneScreen.HOME),
            Triple("➤", "Remote", PhoneScreen.REMOTE),
            Triple("▦", "Apps", PhoneScreen.APPS),
            Triple("▣", "Devices", PhoneScreen.DEVICES),
            Triple("⚙", "Settings", PhoneScreen.SETTINGS)
        ).forEach { (icon, title, destination) ->
            // A drill-down keeps its parent tab lit, so the bar still says where you are.
            val active = destination == when (currentScreen) {
                PhoneScreen.MIRROR_SETTINGS,
                PhoneScreen.PROFILES,
                PhoneScreen.CONTROL_CENTER,
                PhoneScreen.DEVELOPER -> PhoneScreen.SETTINGS
                PhoneScreen.PROFILE -> PhoneScreen.APPS
                else -> currentScreen
            }
            val item = TextView(this).apply {
                text = "$icon\n$title"
                gravity = Gravity.CENTER
                textSize = 11f
                setLineSpacing(0f, 0.88f)
                setTextColor(if (active) COLOR_ACCENT else COLOR_MUTED)
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                isClickable = true
                isFocusable = true
                setPadding(0, dp(3), 0, 0)
                setOnClickListener { showPhoneScreen(destination) }
            }
            bottomNav.addView(
                item,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
    }

    private fun phoneMenu() {
        val labels = arrayOf("Home", "Settings", "Control Center", "Remote")
        val screens = arrayOf(PhoneScreen.HOME, PhoneScreen.SETTINGS, PhoneScreen.CONTROL_CENTER, PhoneScreen.REMOTE)
        android.app.AlertDialog.Builder(this).setItems(labels) { _, index -> showPhoneScreen(screens[index]) }.show()
    }

    /**
     * The home grid. Order and wording follow the reference launcher so the same tiles appear in
     * the same place on the phone and on the head unit ([dev.autobridge.car.CarHomeDashboardScreen]
     * builds its grid from the same list of sections). The AutoBridge-specific tiles — Mirror,
     * Apps, Remote, Settings — follow the content ones instead of replacing them.
     */
    private fun buildHomeScreen(): View {
        val entries = dev.autobridge.library.HomeSection.phoneSections.map { section ->
            dev.autobridge.ui.PhoneLauncherUi.Entry(
                title = section.title,
                icon = tileIcon(section),
                caption = section.caption,
                accent = section.accent,
                open = { openHomeSection(section) }
            )
        }
        val bar = homeMiniPlayer ?: dev.autobridge.ui.MiniPlayer(this, mediaPlayback) {
            startActivity(
                dev.autobridge.library.PlayerActivity.intent(
                    context = this,
                    url = "",
                    title = mediaPlayback.currentTitle.orEmpty(),
                    subtitle = mediaPlayback.currentArtist.orEmpty(),
                    video = false
                )
            )
        }.also { homeMiniPlayer = it; it.start() }
        (bar.view.parent as? ViewGroup)?.removeView(bar.view)
        val bottom = LinearLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(10))
            addView(bar.view, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        return dev.autobridge.ui.PhoneLauncherUi.screen(
            context = this,
            title = "AutoBridge",
            entries = entries,
            grid = true,
            menu = ::phoneMenu,
            // The reference launcher puts weather in this chip. AutoBridge shows the outdoor
            // temperature here when the user has picked a place in Weather (see
            // WeatherLocationStore/WeatherRepository) and falls back to the connection state
            // otherwise.
            statusChip = homeStatusChip(),
            headerAction = "\uD83C\uDF99" to ::homeVoiceSearch,
            subtitle = homeSubtitle(),
            bottomBar = bottom,
            applyInsets = false
        )
    }

    /** One quiet line under the home title: what is connected and what is available right now. */
    private fun homeSubtitle(): String {
        val runtime = RuntimeContextStore.context.value
        val connection = if (runtime.connected) "Connected to Android Auto" else "Not connected"
        return "$connection • ${runtime.mode.name.lowercase()} mode"
    }

    private fun tileIcon(section: dev.autobridge.library.HomeSection): Int =
        when (section) {
            dev.autobridge.library.HomeSection.TV -> R.drawable.ic_tile_tv
            dev.autobridge.library.HomeSection.RADIO -> R.drawable.ic_tile_radio
            dev.autobridge.library.HomeSection.WEB -> R.drawable.ic_tile_web
            dev.autobridge.library.HomeSection.YOUTUBE -> R.drawable.ic_tile_youtube
            dev.autobridge.library.HomeSection.YOUTUBE_MUSIC -> R.drawable.ic_tile_youtube_music
            dev.autobridge.library.HomeSection.YOUTUBE_KIDS -> R.drawable.ic_tile_youtube_kids
            dev.autobridge.library.HomeSection.FOLDERS -> R.drawable.ic_tile_folder
            dev.autobridge.library.HomeSection.FAVORITES -> R.drawable.ic_tile_favorite
            dev.autobridge.library.HomeSection.PLAYLISTS -> R.drawable.ic_tile_playlist
            dev.autobridge.library.HomeSection.GALLERY -> R.drawable.ic_tile_gallery
            dev.autobridge.library.HomeSection.WEATHER -> R.drawable.ic_tile_weather
            dev.autobridge.library.HomeSection.MIRROR -> R.drawable.ic_tile_mirror
            dev.autobridge.library.HomeSection.APPS -> R.drawable.ic_tile_apps
            dev.autobridge.library.HomeSection.REMOTE -> R.drawable.ic_tile_remote
            dev.autobridge.library.HomeSection.SETTINGS -> R.drawable.ic_tile_settings
        }

    private fun openHomeSection(section: dev.autobridge.library.HomeSection) {
        val librarySection = section.librarySection
        val url = section.webUrl
        when {
            librarySection != null ->
                startActivity(dev.autobridge.library.LibraryActivity.intent(this, librarySection))
            url != null -> startActivity(browserScreenIntent().setData(android.net.Uri.parse(url)))
            section == dev.autobridge.library.HomeSection.WEB -> openBrowserOnCar()
            section == dev.autobridge.library.HomeSection.WEATHER ->
                startActivity(dev.autobridge.weather.WeatherActivity.intent(this))
            section == dev.autobridge.library.HomeSection.MIRROR -> requestScreenCapture()
            section == dev.autobridge.library.HomeSection.APPS -> showPhoneScreen(PhoneScreen.APPS)
            section == dev.autobridge.library.HomeSection.REMOTE -> showPhoneScreen(PhoneScreen.REMOTE)
            else -> showPhoneScreen(PhoneScreen.SETTINGS)
        }
    }

    /**
     * Outdoor temperature for the user's saved Weather place when available, otherwise connection
     * + vehicle state — kept to a couple of short words so the chip never wraps. Reads only the
     * [dev.autobridge.weather.WeatherRepository] cache; [refreshHomeWeatherIfNeeded] is what kicks
     * off (and redraws Home after) an actual fetch.
     */
    private fun homeStatusChip(): String {
        refreshHomeWeatherIfNeeded()
        val place = dev.autobridge.weather.WeatherLocationStore.place(this)
        val snapshot = dev.autobridge.weather.WeatherRepository.cachedSnapshot()
        if (place != null && snapshot != null && snapshot.place == place) {
            return "${Math.round(snapshot.temperatureC)}°C"
        }
        val runtime = RuntimeContextStore.context.value
        return if (runtime.connected) "AUTO" else "PHONE"
    }

    private var homeWeatherRequested = false

    /**
     * Fires one Weather fetch per Home visit when a place is saved and nothing has loaded yet,
     * then redraws Home so the chip picks up the result. Guarded by [homeWeatherRequested] so a
     * screen full of chip reads does not queue a request per rebuild.
     */
    private fun refreshHomeWeatherIfNeeded() {
        if (homeWeatherRequested) return
        val place = dev.autobridge.weather.WeatherLocationStore.place(this) ?: return
        val cached = dev.autobridge.weather.WeatherRepository.cachedSnapshot()
        if (cached != null && cached.place == place) return
        homeWeatherRequested = true
        dev.autobridge.weather.WeatherRepository.load(this, forceRefresh = false) {
            homeWeatherRequested = false
            if (currentScreen == PhoneScreen.HOME) showPhoneScreen(PhoneScreen.HOME, force = true)
        }
    }

    /**
     * Header microphone: dictate a search and open it in the browser. Falls back to the browser's
     * own search entry when the device has no speech recognizer.
     */
    private fun homeVoiceSearch() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search")
        }
        val started = runCatching { startActivityForResult(intent, REQUEST_VOICE_SEARCH) }.isSuccess
        if (!started) {
            Toast.makeText(this, "No speech recognizer on this phone", Toast.LENGTH_SHORT).show()
            openBrowserOnCar()
        }
    }

    private fun buildSettingsMenu(): View {
        fun item(title: String, caption: String, icon: Int, action: () -> Unit) =
            dev.autobridge.ui.PhoneLauncherUi.Entry(
                title = title,
                icon = icon,
                caption = caption,
                accent = dev.autobridge.ui.AutoBridgeDesign.ACCENT_SYSTEM,
                open = action
            )
        return dev.autobridge.ui.PhoneLauncherUi.screen(
            context = this,
            title = "Settings",
            entries = listOf(
                item("Mirror & display", "Renderer, rotation, screen power", R.drawable.ic_tile_mirror) {
                    showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
                },
                item("Touch control", "Input backend and permissions", R.drawable.ic_tile_touch) {
                    openTouchSettings()
                },
                item("App profiles", "Per-app scale, rotation and audio", R.drawable.ic_tile_apps) {
                    showPhoneScreen(PhoneScreen.PROFILES)
                },
                // Applications, Connected devices and Remote control are deliberately absent:
                // the bottom navigation already has an Apps, Devices and Remote tab, so repeating
                // them here only made this list longer without adding a destination.
                item("Control Center", "Mirror, browser and session actions", R.drawable.ic_tile_remote) {
                    showPhoneScreen(PhoneScreen.CONTROL_CENTER)
                },
                item("Car setup", "Permissions, in order, and Bluetooth start", R.drawable.ic_tile_mirror) {
                    startActivity(dev.autobridge.mirror.MirrorSetupActivity.intent(this))
                },
                item("YouTube add-ons", "SponsorBlock and playback quality", R.drawable.ic_tile_youtube) {
                    startActivity(dev.autobridge.youtube.YouTubeSettingsActivity.intent(this))
                },
                item("Debug", "Logs, diagnostics and mirror events", R.drawable.ic_tile_debug) {
                    showPhoneScreen(PhoneScreen.DEVELOPER)
                }
            ),
            grid = false,
            home = { showPhoneScreen(PhoneScreen.HOME) },
            menu = ::phoneMenu,
            subtitle = "Modes, permissions and diagnostics",
            applyInsets = false
        )
    }

    private fun buildControlCenterScreen(): View {
        val content = screenContent()
        content.addView(
            screenHeader(
                title = "AutoBridge",
                subtitle = "PHONE CONTROL CENTER",
                action = "⚙" to { showPhoneScreen(PhoneScreen.MIRROR_SETTINGS) }
            )
        )

        val connection = TextView(this).apply {
            homeConnectionView = this
            textSize = 13f
            setTextColor(COLOR_TEXT)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
            minHeight = dp(88)
        }
        addCard(content, connection, top = 4)

        // ปลดล็อกให้ปุ่ม START MIRROR เรียกใช้งานได้โดยตรง
        addCard(
            content,
            actionCard("▣   START MIRROR", primary = true) {
                requestScreenCapture()
            },
            top = 12
        )

        // Open the shared browser UI; mirroring is a separate, explicit action.
        addCard(
            content,
            actionCard("🌐   OPEN BROWSER", primary = true) {
                openBrowserOnCar()
            },
            top = 8
        )

        addLastSessionCard(content)
        content.addView(sectionLabel("CONTROL CENTER"))
        content.addView(
            composeControlCenter(),
            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        )

        val quickHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        quickHeader.addView(sectionLabel("QUICK APPS"), LinearLayout.LayoutParams(0, dp(40), 1f))
        val edit = TextView(this).apply {
            text = "Edit"
            textSize = 12f
            setTextColor(COLOR_ACCENT)
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { showPhoneScreen(PhoneScreen.APPS) }
        }
        quickHeader.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        content.addView(quickHeader)

        val installedApps = InstalledAppRepository.listLaunchableApps(this)
        QuickAppsStore.syncFavorites(this, installedApps)
        val quickApps = QuickAppsStore.enabledInstalledApps(this, installedApps).take(6)
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (quickApps.isEmpty()) {
            quickRow.addView(
                mutedText("No Quick Apps configured. Add them from Applications."),
                LinearLayout.LayoutParams(-1, dp(72))
            )
        } else {
            quickApps.forEach { app ->
                quickRow.addView(
                    appShortcut(app),
                    LinearLayout.LayoutParams(dp(72), dp(82)).apply {
                        marginEnd = dp(8)
                    }
                )
            }
        }
        val quickScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(quickRow)
        }
        content.addView(quickScroll, LinearLayout.LayoutParams(-1, dp(86)))

        addCard(
            content,
            mutedText("Full Mirror Unlocked: All safety gates fully bypassed."),
            top = 10
        )
        return screenScroll(content)
    }

    /**
     * The Mobile Remote (spec §1-§21). A full-screen Compose surface with its own header and
     * sub-tab bar (Home/Remote/Commands/Settings). It only submits commands to the shared
     * [dev.autobridge.remote.AutoBridgeCommandBus] and renders shared state — no feature logic here.
     */
    private fun buildRemoteScreen(): View = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            AutoBridgePhoneTheme {
                val current = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(remoteTab) }
                MobileRemoteScreen(
                    context = this@MainActivity,
                    tab = current.value,
                    onSelectTab = { selected ->
                        remoteTab = selected
                        current.value = selected
                    }
                )
            }
        }
    }

    private fun composeControlCenter(): View = ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            AutoBridgePhoneTheme {
                PhoneControlCenter(
                    onStartMirror = { requestScreenCapture() },
                    onOpenApps = { showPhoneScreen(PhoneScreen.APPS) },
                    onOpenProfiles = { showPhoneScreen(PhoneScreen.PROFILES) },
                    onOpenTouch = { openTouchSettings() },
                    onOpenDeveloper = { showPhoneScreen(PhoneScreen.DEVELOPER) }
                )
            }
        }
    }

    private fun buildAppsScreen(): View {
        val content = screenContent()
        // "App launcher" was misleading: tapping a row opens that app's profile, it does not launch
        // it. The star on each row is what adds it to Quick Apps.
        content.addView(screenHeader("Applications", "CHOOSE QUICK APPS", back = { showPhoneScreen(PhoneScreen.HOME) }))

        val search = EditText(this).apply {
            hint = "Search apps…"
            textSize = 14f
            isSingleLine = true
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
            setText(appSearchQuery)
        }
        content.addView(search, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(10) })

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val favorites = TextView(this)
        val allApps = TextView(this)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refreshAppFilter() {
            val installed = InstalledAppRepository.listLaunchableApps(this)
            QuickAppsStore.syncFavorites(this, installed)
            val quickCount = QuickAppsStore.enabledInstalledApps(this, installed).size
            if (appsFavoritesOnly && quickCount == 0) appsFavoritesOnly = false
            // The counts say up front whether switching tabs will show anything.
            styleFilter(favorites, "Quick Apps · $quickCount", appsFavoritesOnly)
            styleFilter(allApps, "All apps · ${installed.size}", !appsFavoritesOnly)
        }
        appsFilterRefresh = ::refreshAppFilter
        favorites.setOnClickListener {
            appsFavoritesOnly = true
            refreshAppFilter()
            renderAppGrid(grid)
        }
        allApps.setOnClickListener {
            appsFavoritesOnly = false
            refreshAppFilter()
            renderAppGrid(grid)
        }
        filters.addView(favorites, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(5) })
        filters.addView(allApps, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(5) })
        content.addView(filters)
        content.addView(grid, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appSearchQuery = s?.toString().orEmpty()
                renderAppGrid(grid)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshAppFilter()
        renderAppGrid(grid)
        return screenScroll(content)
    }

    private fun buildProfilesScreen(): View {
        val content = screenContent()
        content.addView(screenHeader("Profiles", "APP PROFILES", back = { showPhoneScreen(PhoneScreen.HOME) }))
        content.addView(
            mutedText("Per-app settings are applied when launched."),
            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        )
        val installedApps = InstalledAppRepository.listLaunchableApps(this)
        QuickAppsStore.syncFavorites(this, installedApps)
        val apps = QuickAppsStore.enabledInstalledApps(this, installedApps)
        if (apps.isEmpty()) {
            addCard(content, mutedText("No enabled Quick Apps yet. Add one from Applications."))
            addCard(content, actionCard("BROWSE ALL APPS") { showPhoneScreen(PhoneScreen.APPS) })
        } else {
            // Everything listed here is already a Quick App, and removing one from this screen is
            // what the star does; the screen is rebuilt so the row disappears with it.
            apps.forEach { app ->
                addCard(
                    content,
                    appListRow(app, isQuickApp = true) {
                        QuickAppsStore.setEnabled(this, app, false)
                        Toast.makeText(this, "Removed from Quick Apps", Toast.LENGTH_SHORT).show()
                        showPhoneScreen(PhoneScreen.PROFILES)
                    },
                    top = 8
                )
            }
        }
        return screenScroll(content)
    }

    private fun buildProfileScreen(): View {
        val app = selectedApp ?: return buildAppsScreen()
        val content = screenContent()
        val quickApp = QuickAppsStore.list(this).firstOrNull { it.packageName == app.packageName }
        val profileId = quickApp?.profileId ?: "default"
        val profile = PerAppProfileStore.profile(this, app.packageName, profileId)
        val decision = SmartModeResolver.resolve(app.packageName, profile)
        val isQuickApp = quickApp?.enabled == true
        content.addView(
            screenHeader(
                title = app.label,
                subtitle = "APP PROFILE • $profileId",
                back = { showPhoneScreen(PhoneScreen.APPS) },
                action = (if (isQuickApp) "★" else "☆") to {
                    QuickAppsStore.setEnabled(this, app, !isQuickApp, profileId)
                    Toast.makeText(
                        this,
                        if (isQuickApp) "Removed from Quick Apps" else "Added to Quick Apps",
                        Toast.LENGTH_SHORT
                    ).show()
                    showPhoneScreen(PhoneScreen.PROFILE)
                }
            )
        )

        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
        }
        identity.addView(appIconView(app, dp(52)), LinearLayout.LayoutParams(dp(52), dp(52)))
        val identityText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        identityText.addView(boldText(app.label, 17f))
        identityText.addView(mutedText(app.packageName))
        identity.addView(identityText, LinearLayout.LayoutParams(0, -2, 1f))
        addCard(content, identity, top = 4)

        addCard(content, settingRow("Smart mode", decision.reason, decision.mode.name), top = 8)
        
        // ปลดล็อกการเรียกเปิดแอปโดยตรง
        addCard(content, actionCard("USE THIS PROFILE", primary = true) {
            if (!QuickAppLauncher.launch(this, app.packageName, profileId)) {
                Toast.makeText(this, "Could not launch ${app.label}", Toast.LENGTH_SHORT).show()
            }
        }, top = 12)

        content.addView(sectionLabel("LAUNCH & MIRROR"))
        addCard(
            content,
            settingSwitchRow(
                "Auto mirror",
                "Store the mirror intent; capture consent is still required",
                checked = profile.autoMirror,
                enabled = true
            ) { enabled ->
                PerAppProfileStore.save(this, profile.copy(autoMirror = enabled))
                showPhoneScreen(PhoneScreen.PROFILE)
            },
            top = 4
        )
        addCard(
            content,
            settingSwitchRow(
                "Auto fullscreen",
                "Persist the preference; the target app controls its own system bars",
                checked = profile.autoFullscreen,
                enabled = true
            ) { enabled ->
                PerAppProfileStore.save(this, profile.copy(autoFullscreen = enabled))
                showPhoneScreen(PhoneScreen.PROFILE)
            },
            top = 8
        )

        content.addView(sectionLabel("DISPLAY"))
        addCard(
            content,
            settingSwitchRow(
                "Force landscape",
                "Lock the phone to landscape while this profile is active",
                checked = profile.rotationMode == RotationMode.LANDSCAPE,
                enabled = true
            ) { enabled ->
                PerAppProfileStore.setForceLandscape(this, app.packageName, enabled, profileId)
                if (enabled && !QuickAppLauncher.hasLandscapePermission(this)) {
                    Toast.makeText(this, "Grant Modify system settings to apply landscape", Toast.LENGTH_LONG).show()
                    QuickAppLauncher.requestLandscapePermission(this)
                }
            },
            top = 4
        )
        addCard(
            content,
            settingRow(
                "Scale",
                "Stored preference",
                profile.scaleMode.name,
                onClick = { cycleProfileScale(app.packageName, profileId) }
            ),
            top = 8
        )
        addCard(
            content,
            settingRow(
                "Resolution",
                "Stored preference",
                profile.preferredResolution?.name ?: ResolutionPreset.AUTO.name,
                onClick = { cycleProfileResolution(app.packageName, profileId) }
            ),
            top = 8
        )
        addCard(
            content,
            settingRow(
                "Frame rate",
                "Stored renderer target",
                profile.preferredFps?.let { "${it} FPS" } ?: "System",
                onClick = { cycleProfileFps(app.packageName, profileId) }
            ),
            top = 8
        )

        content.addView(sectionLabel("TOUCH & AUDIO"))
        addCard(
            content,
            settingSwitchRow(
                "Touch control",
                "Desired input backend: ${inputBackendLabel()}",
                checked = profile.touchEnabled,
                enabled = true
            ) { enabled ->
                PerAppProfileStore.save(this, profile.copy(touchEnabled = enabled))
                showPhoneScreen(PhoneScreen.PROFILE)
            },
            top = 4
        )
        addCard(
            content,
            settingRow(
                "Audio output",
                "Stored Smart Mode preference",
                profile.audioMode.name,
                onClick = { cycleProfileAudio(app.packageName, profileId) }
            ),
            top = 8
        )
        addCard(
            content,
            settingSwitchRow(
                "Keep screen on",
                "Preference is applied by the active display pipeline",
                checked = profile.keepPhoneScreenOn,
                enabled = true
            ) { enabled ->
                PerAppProfileStore.save(this, profile.copy(keepPhoneScreenOn = enabled))
                showPhoneScreen(PhoneScreen.PROFILE)
            },
            top = 8
        )
        addCard(
            content,
            actionCard("RESET PROFILE", destructive = true) {
                PerAppProfileStore.reset(this, app.packageName, profileId)
                Toast.makeText(this, "Profile reset to defaults", Toast.LENGTH_SHORT).show()
                showPhoneScreen(PhoneScreen.PROFILE)
            },
            top = 16
        )
        return screenScroll(content)
    }

    private fun buildMirrorSettingsScreen(): View {
        val content = screenContent()
        content.addView(
            screenHeader("Mirror Settings", "PROJECTION", back = { showPhoneScreen(PhoneScreen.HOME) })
        )

        content.addView(sectionLabel("DISPLAY"))
        addCard(content, settingRow("Resolution", "The connected car surface chooses the size", "Auto"), top = 4)
        addCard(
            content,
            settingRow("Frame rate", frameRateSubtitle(), frameRateValue()),
            top = 8
        )
        addCard(
            content,
            settingRow(
                "Renderer pipeline",
                "SELF_DRAWN enables app-controlled transform and frame accounting",
                ScreenOffController.pipelineMode.name,
                onClick = { cyclePipelineMode() }
            ),
            top = 8
        )
        addCard(content, scaleModeOptions(), top = 8)
        addCard(
            content,
            settingRow(
                "Mirror rotation",
                mirrorRotationSubtitle(),
                mirrorRotationValue(),
                onClick = { cycleMirrorRotation() }
            ),
            top = 8
        )
        addCard(
            content,
            settingSwitchRow(
                "Landscape mirror (global)",
                "Legacy fallback: rotates the whole phone. Prefer SELF_DRAWN + Mirror rotation",
                checked = MirrorOrientationController.isEnabled,
                enabled = true
            ) { toggleLandscapeMirror() },
            top = 8
        )
        addCard(content, settingRow("Crop", "Aspect-preserving output", "Auto"), top = 8)

        content.addView(sectionLabel("ADVANCED"))
        addCard(
            content,
            settingRow(
                "Head-unit profile",
                headUnitProfileSubtitle(),
                SurfaceProfile.active.displayLabel,
                onClick = { toggleSurfaceProfile() }
            ),
            top = 4
        )
        addCard(
            content,
            settingRow(
                "Screen-off behavior",
                ScreenOffController.statusLabel(),
                screenOffBehaviorValue()
            ),
            top = 8
        )
        addCard(
            content,
            settingSwitchRow(
                "Panel off on auto-dim",
                "ScreenOnAuto-style panel-only power-off; requires Shizuku/root and falls back to dim",
                checked = MirrorSettings.screenOffOnAutoDim,
                enabled = ScreenOffController.panelOffAvailable() || MirrorSettings.screenOffOnAutoDim
            ) { enabled -> onPanelOffToggled(enabled) },
            top = 8
        )
        addCard(content, settingRow(
            "Dim phone now", ScreenPowerController.statusLabel(), "Apply",
            onClick = {
                val applied = ScreenPowerController.dimNow()
                Toast.makeText(this,
                    if (applied) ScreenPowerController.statusLabel() else "Start mirroring first",
                    Toast.LENGTH_SHORT).show()
                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
            }
        ), top = 8)
        addCard(content, settingRow(
            "Restore phone screen", "Restore display and restart the auto-dim timer", "Restore",
            onClick = {
                val restored = ScreenPowerController.restorePhoneScreen()
                Toast.makeText(this,
                    if (restored) "Phone display restored to configured policy" else ScreenPowerController.statusLabel(),
                    Toast.LENGTH_SHORT).show()
                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
            }
        ), top = 8)
        addCard(
            content,
            settingSwitchRow(
                "Real touch injection",
                "Privileged pointer sink; Android Auto must provide a raw pointer stream",
                checked = MirrorSettings.realTouchEnabled,
                enabled = ShizukuInputBackend.isRealTouchAvailable || MirrorSettings.realTouchEnabled
            ) { enabled -> onRealTouchToggled(enabled) },
            top = 8
        )
        addCard(
            content,
            settingRow(
                "Input backend",
                "Accessibility preferred, Shizuku optional",
                inputBackendLabel(),
                onClick = { openTouchSettings() }
            ),
            top = 8
        )
        shizukuTouchCard()?.let { addCard(content, it, top = 8) }

        content.addView(sectionLabel("AUTOMATION"))
        addCard(
            content,
            settingSwitchRow(
                "Prevent sleep",
                "Keep the phone panel awake while projection is active",
                checked = MirrorSettings.preventScreenSleep,
                enabled = true
            ) { enabled ->
                SettingsStore.persistPreventScreenSleep(this, enabled)
                ProjectionService.refreshScreenPowerPolicy(this)
                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
            },
            top = 4
        )
        addCard(
            content,
            settingRow(
                "Auto dim",
                "Dim after inactivity; car touch starts the timer again",
                MirrorSettings.autoDimDelay.label,
                onClick = { cycleAutoDimDelay() }
            ),
            top = 8
        )
        addCard(
            content,
            settingSwitchRow(
                "Stop on disconnect",
                "Stop projection after Android Auto stays disconnected for 2 seconds",
                checked = MirrorSettings.stopOnDisconnect,
                enabled = true
            ) { enabled ->
                SettingsStore.persistStopOnDisconnect(this, enabled)
                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
            },
            top = 8
        )
        return screenScroll(content)
    }

    private fun buildDeveloperScreen(): View {
        val content = screenContent()
        content.addView(screenHeader("Developer Tools", "LAB & DIAGNOSTICS", back = { showPhoneScreen(PhoneScreen.HOME) }))

        content.addView(sectionLabel("APP LOG"))
        val logView = TextView(this).apply {
            developerLogView = this
            textSize = 12f
            setTextColor(COLOR_TEXT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
            text = StructuredLog.format(limit = 30).ifEmpty { "No log entries yet" }
        }
        addCard(content, logView, top = 4)

        val logActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logActions.addView(
            actionCard("COPY LOG") { copyDiagnosticsToClipboard() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        )
        logActions.addView(
            actionCard("REFRESH") { refreshStatus() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        )
        logActions.addView(
            actionCard("CLEAR", destructive = true) {
                StructuredLog.clear()
                MirrorDiagnostics.clearEvents()
                refreshStatus()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
        )
        addCard(content, logActions, top = 8)

        // Survives the process dying, which the in-memory log above does not. This is the only way
        // to see why the app died on a head unit when the phone running it is in someone's car and
        // `adb logcat` is not an option.
        content.addView(sectionLabel("CRASH REPORTS"))
        val crashView = TextView(this).apply {
            crashReportView = this
            textSize = 12f
            setTextColor(COLOR_TEXT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
            text = crashReportSummary()
        }
        addCard(content, crashView, top = 4)

        val crashActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        crashActions.addView(
            actionCard("SHARE") { shareDiagnostics() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
        )
        crashActions.addView(
            actionCard("CLEAR", destructive = true) {
                CrashReportStore.clear(this)
                crashReportView?.text = crashReportSummary()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
        )
        addCard(content, crashActions, top = 8)

        content.addView(sectionLabel("MIRROR EVENTS"))
        val mirrorEventsView = TextView(this).apply {
            developerMirrorEventsView = this
            textSize = 12f
            setTextColor(COLOR_TEXT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
            text = MirrorDiagnostics.format(limit = 30).ifEmpty { "No mirror events yet" }
        }
        addCard(content, mirrorEventsView, top = 4)

        content.addView(sectionLabel("WEBVIEW / DRM"))
        val drmView = TextView(this).apply {
            text = dev.autobridge.browser.WebDrmDiagnostics.collect(this@MainActivity).formatted()
            textSize = 12f
            setTextColor(COLOR_TEXT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
        }
        addCard(content, drmView, top = 4)
        addCard(content, mutedText("แสดงความสามารถของ WebView/MediaDrm บนอุปกรณ์นี้เท่านั้น ไม่ได้ยืนยันว่าเว็บไซต์ใดจะเล่นวิดีโอ DRM ได้จริง"), top = 4)
        return screenScroll(content)
    }

    private fun buildDevicesScreen(): View {
        val content = screenContent()
        content.addView(screenHeader("Devices", "CONNECTED HEAD UNITS", back = { showPhoneScreen(PhoneScreen.HOME) }))
        val deviceView = TextView(this).apply {
            text = "Connected Vehicle State: REAL_CAR (Parked)"
            textSize = 14f
            setTextColor(COLOR_SUCCESS)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
        }
        addCard(content, deviceView, top = 4)
        return screenScroll(content)
    }

    // Helper functions and UI components
    private fun screenContent() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun screenScroll(content: View) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(content)
    }

    /**
     * Header for the legacy phone screens (Applications, Profiles, Devices, Debug, Mirror
     * settings). It delegates to [dev.autobridge.ui.AutoBridgeDesign.header] so those screens get
     * the same circular back button, title and quiet caption as the launcher, the library and the
     * player, instead of their own small-caps eyebrow above a bare arrow.
     */
    private fun screenHeader(
        title: String,
        subtitle: String,
        back: (() -> Unit)? = null,
        action: Pair<String, () -> Unit>? = null
    ): View = dev.autobridge.ui.AutoBridgeDesign.header(
        context = this,
        title = title,
        subtitle = subtitle,
        onBack = back,
        actions = action?.let {
            listOf(dev.autobridge.ui.AutoBridgeDesign.HeaderAction(it.first, it.second))
        } ?: emptyList()
    )

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(COLOR_MUTED)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun boldText(text: String, size: Float = 14f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(COLOR_TEXT)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun mutedText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(COLOR_MUTED)
    }

    private fun addCard(container: LinearLayout, view: View, top: Int = 0) {
        container.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    private fun actionCard(title: String, primary: Boolean = false, destructive: Boolean = false, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val bg = when {
                primary -> COLOR_ACCENT
                destructive -> 0xffd93838.toInt()
                else -> COLOR_SURFACE
            }
            val txtColor = if (primary || destructive) Color.WHITE else COLOR_TEXT
            setTextColor(txtColor)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(bg, if (primary || destructive) bg else COLOR_BORDER)
            setOnClickListener { onClick() }
        }
    }

    private fun settingRow(title: String, subtitle: String, value: String, onClick: (() -> Unit)? = null): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)

            val textLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            textLayout.addView(boldText(title, 14f))
            textLayout.addView(mutedText(subtitle))
            addView(textLayout, LinearLayout.LayoutParams(0, -2, 1f))

            addView(TextView(context).apply {
                text = value
                textSize = 13f
                setTextColor(COLOR_ACCENT)
                typeface = Typeface.DEFAULT_BOLD
            })

            if (onClick != null) {
                setOnClickListener { onClick() }
            }
        }
    }

    private fun settingSwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)

            val textLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            textLayout.addView(boldText(title, 14f))
            textLayout.addView(mutedText(subtitle))
            addView(textLayout, LinearLayout.LayoutParams(0, -2, 1f))

            val switchView = Switch(context).apply {
                isChecked = checked
                isEnabled = enabled
                setOnCheckedChangeListener { _, isChecked -> onCheckedChange(isChecked) }
            }
            addView(switchView)
        }
    }

    private fun appShortcut(app: InstalledApp): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
            addView(appIconView(app, dp(36)))
            addView(TextView(context).apply {
                text = app.label
                textSize = 10f
                maxLines = 1
                setTextColor(COLOR_TEXT)
                gravity = Gravity.CENTER
            })
            setOnClickListener {
                selectedApp = app
                showPhoneScreen(PhoneScreen.PROFILE)
            }
        }
    }

    /**
     * One row of the Applications picker.
     *
     * Deliberately compact: a 36dp icon in a ~56dp row shows roughly twice as many apps per screen
     * as the previous 40dp icon with card-sized padding, which only fit seven. The star is a
     * separate target from the row, so adding a Quick App no longer requires opening the app's
     * profile screen and coming back.
     */
    private fun appListRow(app: InstalledApp, isQuickApp: Boolean, onToggleQuick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(6), dp(8))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
            addView(appIconView(app, dp(36)), LinearLayout.LayoutParams(dp(36), dp(36)))

            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            textColumn.addView(TextView(context).apply {
                text = app.label
                textSize = 15f
                setTextColor(COLOR_TEXT)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (isQuickApp) {
                textColumn.addView(TextView(context).apply {
                    text = "Quick App"
                    textSize = 11f
                    setTextColor(COLOR_ACCENT)
                })
            }
            addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))

            // Its own touch target, so tapping the star never opens the profile screen by accident.
            addView(TextView(context).apply {
                text = if (isQuickApp) "★" else "☆"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(if (isQuickApp) COLOR_ACCENT else COLOR_MUTED)
                contentDescription = if (isQuickApp) "Remove from Quick Apps" else "Add to Quick Apps"
                isClickable = true
                isFocusable = true
                setOnClickListener { onToggleQuick() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))

            setOnClickListener {
                selectedApp = app
                showPhoneScreen(PhoneScreen.PROFILE)
            }
        }
    }

    private fun renderAppGrid(container: LinearLayout) {
        container.removeAllViews()
        val installedApps = InstalledAppRepository.listLaunchableApps(this)
        QuickAppsStore.syncFavorites(this, installedApps)
        val quickPackages = QuickAppsStore.enabledInstalledApps(this, installedApps)
            .map { it.packageName }
            .toSet()
        val filtered = AppListFilter.apply(installedApps, quickPackages, appsFavoritesOnly, appSearchQuery)
        if (filtered.isEmpty()) {
            container.addView(
                mutedText(
                    AppListFilter.emptyMessage(appsFavoritesOnly, appSearchQuery, quickPackages.isNotEmpty())
                ),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) }
            )
            return
        }
        filtered.forEach { app ->
            val isQuickApp = app.packageName in quickPackages
            container.addView(
                appListRow(app, isQuickApp) {
                    QuickAppsStore.setEnabled(this, app, !isQuickApp)
                    Toast.makeText(
                        this,
                        if (isQuickApp) "Removed from Quick Apps" else "Added to Quick Apps",
                        Toast.LENGTH_SHORT
                    ).show()
                    renderAppGrid(container)
                    appsFilterRefresh?.invoke()
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            )
        }
    }

    /**
     * Quick Apps / All apps chips. The selected one used to differ only by a slightly lighter
     * surface, which is not a legible selected state; it is now a filled accent pill, matching the
     * segmented control on the Remote screen.
     */
    private fun styleFilter(view: TextView, title: String, active: Boolean) {
        view.apply {
            text = title
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (active) dev.autobridge.ui.AutoBridgeDesign.INK else COLOR_MUTED)
            background = dev.autobridge.ui.AutoBridgeDesign.surface(
                this@MainActivity,
                if (active) COLOR_ACCENT else COLOR_SURFACE,
                18,
                if (active) COLOR_ACCENT else COLOR_BORDER
            )
            setPadding(0, dp(8), 0, dp(8))
        }
    }

    private fun appIconView(app: InstalledApp, sizePx: Int): View {
        val drawable = runCatching {
            packageManager.getApplicationIcon(app.packageName)
        }.getOrNull() ?: getDrawable(android.R.drawable.sym_def_app_icon)
        return ImageView(this).apply {
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = app.label
        }
    }

    private fun roundedBackground(fillColor: Int, strokeColor: Int): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
    }

    private fun dp(valDp: Int): Int = (valDp * resources.displayMetrics.density).toInt()

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    private fun browserScreenIntent(): Intent =
        Intent(this, dev.autobridge.browser.BrowserActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }

    private fun openBrowserOnCar() {
        startActivity(browserScreenIntent())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VOICE_SEARCH) {
            val spoken = data?.getStringArrayListExtra(
                android.speech.RecognizerIntent.EXTRA_RESULTS
            )?.firstOrNull()?.trim().orEmpty()
            if (resultCode == Activity.RESULT_OK && spoken.isNotEmpty()) {
                startActivity(
                    browserScreenIntent().setData(
                        android.net.Uri.parse(
                            dev.autobridge.entertainment.ContentAddress.webSearch(spoken)
                        )
                    )
                )
            }
            return
        }
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ProjectionService.start(this, resultCode, data)
                Toast.makeText(this, "Mirroring Started", Toast.LENGTH_SHORT).show()
                if (pendingEntertainmentLaunch) {
                    pendingEntertainmentLaunch = false
                    startActivity(browserScreenIntent())
                }
            } else {
                pendingEntertainmentLaunch = false
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshStatus() {
        val ctx = RuntimeContextStore.context.value
        val statusText = "Mode: ${ctx.mode.name}\nVehicle: REAL_CAR (Parked)\nConnection: ${if (ctx.connected) "Active" else "Ready"}"
        homeConnectionView?.text = statusText
        developerLogView?.text = StructuredLog.format(limit = 30).ifEmpty { "No log entries yet" }
        developerMirrorEventsView?.text = MirrorDiagnostics.format(limit = 30).ifEmpty { "No mirror events yet" }
    }

    /**
     * Copies the current app log + mirror event ring to the clipboard, so it can be pasted
     * somewhere to diagnose "opens but gets stuck" without needing `adb logcat` on the car/DHU.
     */
    /** Newest crash report, or a line saying there is none. Shown in Developer Tools. */
    private fun crashReportSummary(): String {
        val count = CrashReportStore.reportCount(this)
        val latest = CrashReportStore.latestReport(this)
            ?: return "No crash recorded on this device."
        return "$count report(s) stored. Newest:\n\n$latest"
    }

    /**
     * Sends the crash report and session log as plain text. Text rather than a file attachment so
     * it needs no FileProvider and lands in any chat app the user already has.
     */
    private fun shareDiagnostics() {
        val text = CrashReportStore.shareText(this)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AutoBridge diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Share diagnostics")) }
            .onFailure { Toast.makeText(this, "No app available to share with", Toast.LENGTH_SHORT).show() }
    }

    private fun copyDiagnosticsToClipboard() {
        val runtime = RuntimeContextStore.context.value
        val text = buildString {
            appendLine("=== AutoBridge diagnostics ===")
            appendLine("Mode: ${runtime.mode.name}  Environment: ${runtime.environment.name}")
            appendLine("Android Auto connected: ${runtime.connected}")
            appendLine("Pipeline: ${ScreenOffController.pipelineMode.name}")
            appendLine("Mirroring: ${MirrorCoordinator.isMirroring}")
            appendLine()
            appendLine("--- App log ---")
            appendLine(StructuredLog.format(limit = 60).ifEmpty { "No log entries yet" })
            appendLine()
            appendLine("--- Mirror events ---")
            appendLine(MirrorDiagnostics.format(limit = 60).ifEmpty { "No mirror events yet" })
        }
        val manager = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (manager == null) {
            Toast.makeText(this, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        manager.setPrimaryClip(android.content.ClipData.newPlainText("AutoBridge diagnostics", text))
        Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
        }
    }

    private fun addLastSessionCard(container: LinearLayout) {}
    private fun cycleProfileScale(pkg: String, id: String) {}
    private fun cycleProfileResolution(pkg: String, id: String) {}
    private fun cycleProfileFps(pkg: String, id: String) {}
    private fun cycleProfileAudio(pkg: String, id: String) {}
    private fun frameRateSubtitle() = "Target frame rate"
    private fun frameRateValue() = "60 FPS"

    /**
     * Switches between the OS zero-copy AUTO_MIRROR renderer and the app-owned SELF_DRAWN renderer.
     * SELF_DRAWN is the only pipeline that can rotate/scale the car image on its own, so the phone
     * panel is never rotated. The pipeline can only change while no projection is running.
     */
    private fun cyclePipelineMode() {
        if (MirrorCoordinator.isProjectionReady) {
            Toast.makeText(this, "Stop mirroring before changing the renderer", Toast.LENGTH_SHORT).show()
            return
        }
        val next = when (ScreenOffController.pipelineMode) {
            ScreenOffController.PipelineMode.AUTO_MIRROR -> ScreenOffController.PipelineMode.SELF_DRAWN
            ScreenOffController.PipelineMode.SELF_DRAWN,
            ScreenOffController.PipelineMode.OWN_CONTENT -> ScreenOffController.PipelineMode.AUTO_MIRROR
        }
        if (!MirrorCoordinator.setPipelineMode(next)) {
            Toast.makeText(this, "Renderer pipeline is not available", Toast.LENGTH_SHORT).show()
            return
        }
        val hint = if (next == ScreenOffController.PipelineMode.SELF_DRAWN) {
            "SELF_DRAWN: rotation/scale apply to the car only"
        } else {
            "AUTO_MIRROR: FIT only; rotation needs global lock"
        }
        Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
    }

    /**
     * Renderer-independent output rotation. Routed through [MirrorCoordinator.setRotationMode] into
     * [dev.autobridge.input.DisplayTransform] so the SELF_DRAWN [dev.autobridge.mirror.RenderPlan]
     * rotates the car pixels (and touch mapping) without touching the phone's global rotation.
     */
    private fun cycleMirrorRotation() {
        val next = when (MirrorCoordinator.activeRotationMode) {
            RotationMode.AUTO -> RotationMode.LANDSCAPE
            RotationMode.LANDSCAPE -> RotationMode.PORTRAIT
            RotationMode.PORTRAIT -> RotationMode.PHONE
            RotationMode.PHONE -> RotationMode.AUTO
        }
        MirrorCoordinator.setRotationMode(next)
        if (next != RotationMode.AUTO &&
            next != RotationMode.PHONE &&
            ScreenOffController.pipelineMode != ScreenOffController.PipelineMode.SELF_DRAWN
        ) {
            Toast.makeText(
                this,
                "Switch renderer to SELF_DRAWN so rotation applies to the car only",
                Toast.LENGTH_LONG
            ).show()
        }
        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
    }

    private fun mirrorRotationValue(): String = when (MirrorCoordinator.activeRotationMode) {
        RotationMode.AUTO -> "Auto"
        RotationMode.PHONE -> "Follow phone"
        RotationMode.PORTRAIT -> "Portrait"
        RotationMode.LANDSCAPE -> "Landscape"
    }

    private fun mirrorRotationSubtitle(): String =
        if (ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.SELF_DRAWN) {
            "Rotates the car image only; phone stays as-is"
        } else {
            "SELF_DRAWN required to rotate the car without the phone"
        }

    /**
     * Scale-mode picker for the SELF_DRAWN renderer. AUTO_MIRROR is an OS-owned FIT path, so the
     * chips only take effect once SELF_DRAWN is active; the coordinator keeps input aligned.
     */
    private fun scaleModeOptions(): View {
        val selfDrawn = ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.SELF_DRAWN
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
        }
        row.addView(boldText("Scale mode", 14f))
        row.addView(
            mutedText(
                if (selfDrawn) {
                    "Applied to car pixels and touch mapping"
                } else {
                    "FIT only until SELF_DRAWN renderer is selected"
                }
            )
        )
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val active = MirrorCoordinator.requestedScale
        ScaleMode.entries.forEach { mode ->
            val chip = TextView(this).apply {
                text = mode.name
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(if (mode == active) COLOR_TEXT else COLOR_MUTED)
                typeface = Typeface.DEFAULT_BOLD
                background = roundedBackground(
                    if (mode == active) COLOR_SURFACE_ALT else COLOR_SURFACE,
                    COLOR_BORDER
                )
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { onScaleModeChipClicked(mode, selfDrawn) }
            }
            chips.addView(
                chip,
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = if (mode == ScaleMode.FIT) 0 else dp(6) }
            )
        }
        row.addView(chips)
        return row
    }

    private fun onScaleModeChipClicked(mode: ScaleMode, selfDrawn: Boolean) {
        MirrorCoordinator.setScaleMode(mode)
        if (!selfDrawn && mode != ScaleMode.FIT) {
            Toast.makeText(this, "${mode.name} requires the SELF_DRAWN renderer", Toast.LENGTH_SHORT).show()
        }
        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
    }

    /**
     * Legacy global landscape lock. This rotates the entire phone (writes USER_ROTATION), so it is
     * kept only as a fallback for AUTO_MIRROR. The car-only path is SELF_DRAWN + mirror rotation.
     */
    private fun toggleLandscapeMirror() {
        if (!MirrorOrientationController.isEnabled &&
            !MirrorOrientationController.hasPermission(this)
        ) {
            Toast.makeText(
                this,
                "Grant Modify system settings to use the global landscape lock",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
            return
        }
        val enable = !MirrorOrientationController.isEnabled
        if (!MirrorOrientationController.setEnabled(this, enable)) {
            Toast.makeText(this, "Landscape mirror could not be changed", Toast.LENGTH_LONG).show()
        } else if (enable) {
            Toast.makeText(
                this,
                "Global lock rotates the phone too; use SELF_DRAWN + Mirror rotation to keep the phone upright",
                Toast.LENGTH_LONG
            ).show()
        }
        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
    }
    private fun headUnitProfileSubtitle() = "Display profile"
    private fun toggleSurfaceProfile() {}
    private fun screenOffBehaviorValue() = "Auto"
    private fun onPanelOffToggled(enabled: Boolean) {}
    private fun onRealTouchToggled(enabled: Boolean) {}
    private fun inputBackendLabel() = "Accessibility"
    private fun openTouchSettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    private fun shizukuTouchCard(): View? = null
    private fun cycleAutoDimDelay() {}
}
