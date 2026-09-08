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
import dev.autobridge.core.model.ScaleMode
import dev.autobridge.core.model.VehicleState
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.ui.AutoBridgePhoneTheme
import dev.autobridge.ui.PhoneControlCenter
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.display.MirrorOrientationController
import dev.autobridge.display.OrientationMonitor
import dev.autobridge.display.ScreenOffController
import dev.autobridge.display.StructuredLog
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

class MainActivity : Activity() {
    private companion object {
        const val REQUEST_CAPTURE = 2001
        const val REQUEST_NOTIFICATIONS = 2002

        const val COLOR_BACKGROUND = 0xff07131b.toInt()
        const val COLOR_SURFACE = 0xff0d2029.toInt()
        const val COLOR_SURFACE_ALT = 0xff122b37.toInt()
        const val COLOR_BORDER = 0xff1f3a47.toInt()
        const val COLOR_ACCENT = 0xff159cff.toInt()
        const val COLOR_ACCENT_DARK = 0xff0b5d96.toInt()
        const val COLOR_TEXT = 0xfff1f6fb.toInt()
        const val COLOR_MUTED = 0xff8ea5b5.toInt()
        const val COLOR_SUCCESS = 0xff2ee879.toInt()
        const val COLOR_WARNING = 0xffffbf5f.toInt()
    }

    private enum class PhoneScreen {
        HOME,
        APPS,
        PROFILES,
        PROFILE,
        MIRROR_SETTINGS,
        DEVELOPER,
        DEVICES
    }

    private lateinit var statusView: TextView
    private lateinit var mediaPlayback: MediaPlaybackClient
    private lateinit var screenContainer: FrameLayout
    private lateinit var bottomNav: LinearLayout
    private var homeConnectionView: TextView? = null
    private var developerLogView: TextView? = null
    private var developerMirrorEventsView: TextView? = null
    private var labSummaryView: TextView? = null
    private var pendingEntertainmentLaunch = false
    private var currentScreen = PhoneScreen.HOME
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
        setContentView(buildUi())
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
        mediaPlayback.disconnect()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
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
            setBackgroundColor(0xff091a23.toInt())
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

    private fun showPhoneScreen(requested: PhoneScreen) {
        val gatedRequested = if (
            requested == PhoneScreen.DEVELOPER &&
            !FeaturePolicy.app.isAvailable(Feature.DEVELOPER)
        ) {
            Toast.makeText(this, FeaturePolicy.app.denialMessage(Feature.DEVELOPER), Toast.LENGTH_SHORT).show()
            PhoneScreen.HOME
        } else {
            requested
        }
        val target = if (gatedRequested == PhoneScreen.PROFILE && selectedApp == null) {
            PhoneScreen.APPS
        } else {
            gatedRequested
        }
        currentScreen = target
        homeConnectionView = null
        developerLogView = null
        developerMirrorEventsView = null
        labSummaryView = null
        screenContainer.removeAllViews()

        val screen = when (target) {
            PhoneScreen.HOME -> buildHomeScreen()
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

        bottomNav.visibility = if (target == PhoneScreen.PROFILE || target == PhoneScreen.DEVELOPER) {
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
            Triple("▦", "Apps", PhoneScreen.APPS),
            Triple("▣", "Devices", PhoneScreen.DEVICES),
            Triple("⚙", "Settings", PhoneScreen.MIRROR_SETTINGS)
        ).forEach { (icon, title, destination) ->
            val active = when {
                destination == PhoneScreen.MIRROR_SETTINGS ->
                    currentScreen == PhoneScreen.MIRROR_SETTINGS
                destination == PhoneScreen.DEVICES -> currentScreen == PhoneScreen.DEVICES
                else -> currentScreen == destination
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

    private fun buildHomeScreen(): View {
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

        addCard(
            content,
            actionCard("▣   START MIRROR", primary = true) {
                if (FeaturePolicy.app.isAvailable(Feature.MIRROR)) {
                    requestScreenCapture()
                } else {
                    Toast.makeText(
                        this,
                        FeaturePolicy.app.denialMessage(Feature.MIRROR),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            top = 12
        )

        addLastSessionCard(content)
        content.addView(sectionLabel("CONTROL CENTER"))
        content.addView(
            composeControlCenter(),
            LinearLayout.LayoutParams(-1, dp(214)).apply { topMargin = dp(4) }
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
            mutedText(
                if (DevMode.isEnabled) {
                    "DEV_MODE is active. Rendering and input remain protected by the vehicle safety gate."
                } else {
                    "Safety first: rendering, video, input and app launching require a reported 0 speed."
                }
            ),
            top = 10
        )
        return screenScroll(content)
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
        content.addView(screenHeader("Applications", "APP LAUNCHER", back = { showPhoneScreen(PhoneScreen.HOME) }))

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
            val hasQuickApps = QuickAppsStore.enabledInstalledApps(this, installed).isNotEmpty()
            if (appsFavoritesOnly && !hasQuickApps) appsFavoritesOnly = false
            styleFilter(favorites, "Quick Apps", appsFavoritesOnly)
            styleFilter(allApps, "All apps", !appsFavoritesOnly)
        }
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
            mutedText("Per-app settings are applied only when the app is launched while the vehicle is parked."),
            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        )
        val installedApps = InstalledAppRepository.listLaunchableApps(this)
        QuickAppsStore.syncFavorites(this, installedApps)
        val apps = QuickAppsStore.enabledInstalledApps(this, installedApps)
        if (apps.isEmpty()) {
            addCard(content, mutedText("No enabled Quick Apps yet. Add one from Applications."))
            addCard(content, actionCard("BROWSE ALL APPS") { showPhoneScreen(PhoneScreen.APPS) })
        } else {
            apps.forEach { app ->
                addCard(content, appListRow(app), top = 8)
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
        addCard(content, actionCard("USE THIS PROFILE", primary = true) {
            when {
                !FeaturePolicy.app.isAvailable(Feature.QUICK_APPS) ->
                    Toast.makeText(this, FeaturePolicy.app.denialMessage(Feature.QUICK_APPS), Toast.LENGTH_SHORT).show()
                !QuickAppLauncher.launch(this, app.packageName, profileId) ->
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
                "Stored preference; direct AUTO_MIRROR currently renders FIT",
                profile.scaleMode.name,
                onClick = { cycleProfileScale(app.packageName, profileId) }
            ),
            top = 8
        )
        addCard(
            content,
            settingRow(
                "Resolution",
                "Stored preference; Android Auto surface remains authoritative",
                profile.preferredResolution?.name ?: ResolutionPreset.AUTO.name,
                onClick = { cycleProfileResolution(app.packageName, profileId) }
            ),
            top = 8
        )
        addCard(
            content,
            settingRow(
                "Frame rate",
                "Stored renderer target; current AUTO_MIRROR path uses the system rate",
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
                "Desired input backend: ${inputBackendLabel()} • actual touch remains parked-only",
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
                "Preference is applied only by a pipeline that owns the phone display",
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
        addCard(content, settingRow("Frame rate", "AUTO_MIRROR does not expose a fixed FPS control", "System"), top = 8)
        addCard(content, scaleModeOptions(), top = 8)
        addCard(
            content,
            settingSwitchRow(
                "Landscape mirror",
                "Apply a temporary global landscape lock during projection",
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
                if (SurfaceProfile.active.hardwareValidated) "Measured default profile" else "Manual placeholder; not hardware validated",
                SurfaceProfile.active.displayLabel,
                onClick = { toggleSurfaceProfile() }
            ),
            top = 4
        )
        addCard(
            content,
            settingRow(
                "Screen-off behavior",
                "The current AUTO_MIRROR pipeline follows phone display power",
                if (ScreenOffController.survivesScreenOff()) "Continues" else "Pauses"
            ),
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
        if (Shizuku.pingBinder()) {
            addCard(
                content,
                actionCard(
                    if (ShizukuInputBackend.isPermissionGranted) {
                        "CONNECT SHIZUKU TOUCH"
                    } else {
                        "REQUEST SHIZUKU PERMISSION"
                    }
                ) {
                    if (ShizukuInputBackend.isPermissionGranted) {
                        ShizukuInputBackend.bind(this)
                        refreshStatus()
                    } else {
                        ShizukuInputBackend.requestPermission()
                    }
                },
                top = 8
            )
        }

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
        addCard(
            content,
            settingSwitchRow(
                "Auto-launch last app",
                "Reopen the last explicitly launched app after mirror and car surface are ready",
                checked = MirrorSettings.autoLaunchLastApp,
                enabled = true
            ) { enabled ->
                SettingsStore.persistAutoLaunchLastApp(this, enabled)
                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
            },
            top = 8
        )
        addCard(
            content,
            settingSwitchRow(
                "Auto-start mirror",
                "Open the mirror screen when a consented projection already exists",
                checked = MirrorSettings.autoStartMirror,
                enabled = true
            ) { enabled ->
                SettingsStore.persistAutoStartMirror(this, enabled)
                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
            },
            top = 8
        )
        addCard(content, actionCard("STOP MIRROR", primary = false, destructive = true) {
            ProjectionService.stop(this)
            refreshStatus()
        }, top = 18)
        return screenScroll(content)
    }

    private fun buildDeveloperScreen(): View {
        val content = screenContent()
        content.addView(screenHeader("Developer", "STATUS & DIAGNOSTICS", back = { showPhoneScreen(PhoneScreen.HOME) }))

        content.addView(sectionLabel("LIVE STATUS"))
        statusView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_TEXT)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
        }
        content.addView(statusView, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(sectionLabel("STRUCTURED LOG"))
        val logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_MUTED)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(0xff0a1b23.toInt(), COLOR_BORDER)
        }
        developerLogView = logView
        content.addView(logView, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(sectionLabel("MIRROR EVENTS"))
        val mirrorEventsView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_MUTED)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(0xff0a1b23.toInt(), COLOR_BORDER)
        }
        developerMirrorEventsView = mirrorEventsView
        content.addView(mirrorEventsView, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        refreshDeveloperDiagnosticsViews()

        addCard(content, actionCard("CLEAR LOG") {
            StructuredLog.clear()
            MirrorDiagnostics.clearEvents()
            refreshDeveloperDiagnosticsViews()
            refreshStatus()
        }, top = 8)
        addCard(content, actionCard("REFRESH STATUS") { refreshStatus() }, top = 8)

        if (RuntimeContextStore.mode == AutoBridgeMode.LAB) {
            content.addView(sectionLabel("LAB SIMULATOR"))
            val labSummary = mutedText("")
            labSummaryView = labSummary
            content.addView(labSummary, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
            refreshLabSummary()
            addCard(content, sectionLabel("ENVIRONMENT"), top = 8)
            addCard(content, actionCard("DHU") { setLabEnvironment(Environment.DHU) }, top = 4)
            addCard(content, actionCard("EMULATOR") { setLabEnvironment(Environment.EMULATOR) }, top = 8)
            addCard(content, actionCard("TEST BENCH") { setLabEnvironment(Environment.TEST_BENCH) }, top = 8)
            addCard(content, actionCard("REAL CAR (SAFETY ENFORCED)") { setLabEnvironment(Environment.REAL_CAR) }, top = 8)
            addCard(content, sectionLabel("CONNECTION"), top = 8)
            addCard(content, actionCard("SIMULATE CONNECTED") { setLabConnection(true) }, top = 4)
            addCard(content, actionCard("SIMULATE DISCONNECTED") { setLabConnection(false) }, top = 8)
            addCard(content, sectionLabel("VEHICLE STATE"), top = 8)
            addCard(content, actionCard("SET PARKED") { setMockVehicleState(VehicleState.PARKED) }, top = 4)
            addCard(content, actionCard("SET MOVING") { setMockVehicleState(VehicleState.MOVING) }, top = 8)
            addCard(content, actionCard("SET UNKNOWN") { setMockVehicleState(VehicleState.UNKNOWN) }, top = 8)
        }
        return screenScroll(content)
    }

    private fun buildDevicesScreen(): View {
        val content = screenContent()
        content.addView(screenHeader("Devices", "ANDROID AUTO CONNECTION"))
        addCard(
            content,
            TextView(this).apply {
                text = "▣  Android Auto\n\n${if (MirrorCoordinator.isCarSurfaceReady) "●  Connected" else "○  Waiting for car surface"}\n${SurfaceProfile.active.displayLabel}  •  Wireless / DHU"
                textSize = 14f
                setTextColor(COLOR_TEXT)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
            },
            top = 4
        )
        addCard(content, settingRow("Car surface", "Projection target", if (MirrorCoordinator.isCarSurfaceReady) "Connected" else "Not connected"), top = 10)
        addCard(content, settingRow("Vehicle", "Fail-closed speed gate", vehicleLabel()), top = 8)
        addCard(content, settingRow("Reconnects", "This app session", ReconnectTracker.reconnectCount.toString()), top = 8)
        addCard(content, actionCard("OPEN MIRROR SETTINGS") { showPhoneScreen(PhoneScreen.MIRROR_SETTINGS) }, top = 14)
        addCard(content, actionCard("START MIRROR", primary = true) { requestScreenCapture() }, top = 8)
        return screenScroll(content)
    }

    private fun renderAppGrid(grid: LinearLayout) {
        val allApps = InstalledAppRepository.listLaunchableApps(this)
        QuickAppsStore.syncFavorites(this, allApps)
        val quickApps = QuickAppsStore.enabledInstalledApps(this, allApps)
        val base = if (appsFavoritesOnly) quickApps else allApps
        val apps = base.filter { item: InstalledApp ->
            appSearchQuery.isBlank() || item.label.contains(appSearchQuery, ignoreCase = true)
        }
        grid.removeAllViews()
        if (apps.isEmpty()) {
            val message = if (appsFavoritesOnly) {
                "No Quick Apps yet. Select All apps to add one."
            } else {
                "No apps match your search."
            }
            addCard(grid, mutedText(message), top = 14)
            return
        }
        apps.chunked(3).forEachIndexed { index, rowApps ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowApps.forEachIndexed { column, app ->
                row.addView(
                    appCard(app),
                    LinearLayout.LayoutParams(0, dp(126), 1f).apply {
                        setMargins(
                            if (column == 0) 0 else dp(4),
                            0,
                            if (column == rowApps.lastIndex) 0 else dp(4),
                            0
                        )
                    }
                )
            }
            repeat(3 - rowApps.size) {
                row.addView(Space(this), LinearLayout.LayoutParams(0, dp(126), 1f))
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, dp(126)).apply {
                topMargin = if (index == 0) dp(14) else dp(8)
            })
        }
    }

    private fun appCard(app: InstalledApp): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(10), dp(6), dp(8))
        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            selectedApp = app
            showPhoneScreen(PhoneScreen.PROFILE)
        }
        addView(appIconView(app, dp(48)), LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(context).apply {
            text = app.label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT)
            maxLines = 1
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(-1, dp(30)))
    }

    private fun appListRow(app: InstalledApp): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            selectedApp = app
            showPhoneScreen(PhoneScreen.PROFILE)
        }
        addView(appIconView(app, dp(44)), LinearLayout.LayoutParams(dp(44), dp(44)))
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        textColumn.addView(boldText(app.label, 15f))
        textColumn.addView(mutedText(if (PerAppProfileStore.isForceLandscape(context, app.packageName)) "Landscape profile" else "Default profile"))
        addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = "›"
            textSize = 24f
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(dp(22), -2))
    }

    private fun appShortcut(app: InstalledApp): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = "Launch ${app.label}"
        setOnClickListener {
            if (!QuickAppLauncher.launch(this@MainActivity, app.packageName)) {
                Toast.makeText(this@MainActivity, "Could not launch ${app.label}", Toast.LENGTH_SHORT).show()
            }
        }
        setOnLongClickListener {
            selectedApp = app
            showPhoneScreen(PhoneScreen.PROFILE)
            true
        }
        addView(appIconView(app, dp(42)), LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(TextView(context).apply {
            text = app.label
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT)
            maxLines = 1
        }, LinearLayout.LayoutParams(-1, dp(26)))
    }

    private fun appIconView(app: InstalledApp, size: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val icon: Drawable? = runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
        setImageDrawable(icon ?: getDrawable(android.R.drawable.sym_def_app_icon))
        contentDescription = app.label
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    private fun scaleModeOptions(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
        addView(boldText("Scale mode", 14f))
        addView(mutedText("FIT is the only mode implemented by the direct AUTO_MIRROR pipeline"), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(3)
        })
        val options = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        listOf("FIT" to true, "FILL" to false, "STRETCH" to false, "1:1" to false).forEachIndexed { index, (label, selected) ->
            val chip = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(if (selected) Color.WHITE else COLOR_MUTED)
                background = roundedBackground(if (selected) COLOR_ACCENT_DARK else COLOR_SURFACE_ALT, COLOR_BORDER)
                alpha = if (selected) 1f else 0.55f
                setOnClickListener {
                    if (!selected) {
                        Toast.makeText(this@MainActivity, "$label is not available in the current mirror pipeline", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            options.addView(chip, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                if (index > 0) marginStart = dp(5)
            })
        }
        addView(options)
    }

    private fun settingRow(
        title: String,
        subtitle: String,
        value: String,
        onClick: (() -> Unit)? = null
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(11), dp(14), dp(11))
        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        info.addView(boldText(title, 13f))
        info.addView(mutedText(subtitle), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = value
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (value == "Active" || value == "Enabled" || value == "Connected") COLOR_SUCCESS else COLOR_TEXT)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, -2))
    }

    private fun settingSwitchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        enabled: Boolean,
        onChanged: ((Boolean) -> Unit)? = null
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(10), dp(10))
        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        info.addView(boldText(title, 13f))
        info.addView(mutedText(subtitle), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(context).apply {
            isChecked = checked
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.5f
            setOnCheckedChangeListener { _, value -> onChanged?.invoke(value) }
        }
        addView(toggle, LinearLayout.LayoutParams(dp(54), dp(48)))
    }

    private fun screenHeader(
        title: String,
        subtitle: String? = null,
        back: (() -> Unit)? = null,
        action: Pair<String, () -> Unit>? = null
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(14))
        if (back != null) {
            addView(TextView(context).apply {
                text = "‹"
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(COLOR_TEXT)
                isClickable = true
                setOnClickListener { back() }
            }, LinearLayout.LayoutParams(dp(36), dp(48)))
        }
        val titleColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleColumn.addView(boldText(title, 20f))
        if (!subtitle.isNullOrBlank()) titleColumn.addView(mutedText(subtitle), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) {
            addView(TextView(context).apply {
                text = action.first
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(COLOR_ACCENT)
                isClickable = true
                setOnClickListener { action.second() }
            }, LinearLayout.LayoutParams(dp(42), dp(48)))
        }
    }

    private fun screenContent(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(12), dp(18), dp(24))
        setBackgroundColor(COLOR_BACKGROUND)
    }

    private fun screenScroll(content: View): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(COLOR_BACKGROUND)
        addView(content)
    }

    private fun sectionLabel(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 11f
        setTextColor(COLOR_ACCENT)
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(0, dp(18), 0, dp(7))
    }

    private fun dashboardTile(icon: String, title: String, action: () -> Unit): View = TextView(this).apply {
        text = "$icon\n$title"
        gravity = Gravity.CENTER
        textSize = 13f
        setLineSpacing(0f, 0.9f)
        setTextColor(COLOR_TEXT)
        background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun actionCard(
        label: String,
        primary: Boolean = false,
        destructive: Boolean = false,
        action: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = if (primary) 15f else 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(if (destructive) 0xffff8d8d.toInt() else Color.WHITE)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minHeight = dp(if (primary) 58 else 48)
        background = roundedBackground(
            when {
                primary -> COLOR_ACCENT
                destructive -> 0xff321b24.toInt()
                else -> COLOR_SURFACE_ALT
            },
            when {
                primary -> COLOR_ACCENT
                destructive -> 0xff77394a.toInt()
                else -> COLOR_BORDER
            }
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun mutedText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(COLOR_MUTED)
    }

    private fun boldText(value: String, size: Float): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(COLOR_TEXT)
        setTypeface(null, Typeface.BOLD)
    }

    private fun addCard(parent: LinearLayout, view: View, top: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        })
    }

    private fun weightedTile(start: Int = 0, end: Int = 0, top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, -1, 1f).apply {
            setMargins(dp(start), dp(top), dp(end), 0)
        }

    private fun styleFilter(view: TextView, label: String, selected: Boolean) {
        view.text = label
        view.gravity = Gravity.CENTER
        view.textSize = 12f
        view.setTextColor(if (selected) Color.WHITE else COLOR_MUTED)
        view.background = roundedBackground(if (selected) COLOR_ACCENT_DARK else COLOR_SURFACE, COLOR_BORDER)
    }

    private fun roundedBackground(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(11).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun openTouchSettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun inputBackendLabel(): String = when {
        ShizukuInputBackend.isPermissionGranted -> "Shizuku"
        AccessibilityInputBackend.isAvailable -> "Accessibility"
        else -> "Not connected"
    }

    private fun vehicleLabel(): String = when (RuntimeContextStore.vehicleState) {
        VehicleState.PARKED -> "PARKED"
        VehicleState.MOVING -> "MOVING"
        VehicleState.UNKNOWN -> "UNKNOWN"
    }

    private fun toggleLandscapeMirror() {
        if (!MirrorOrientationController.isEnabled &&
            !MirrorOrientationController.hasPermission(this)
        ) {
            Toast.makeText(
                this,
                "Allow Modify system settings to enable landscape mirror",
                Toast.LENGTH_LONG
            ).show()
            QuickAppLauncher.requestLandscapePermission(this)
            return
        }
        val enabled = !MirrorOrientationController.isEnabled
        if (!MirrorOrientationController.setEnabled(this, enabled)) {
            Toast.makeText(this, "Landscape mirror could not be changed", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                this,
                if (enabled) "Landscape mirror enabled" else "Landscape mirror disabled",
                Toast.LENGTH_SHORT
            ).show()
        }
        refreshStatus()
        if (currentScreen == PhoneScreen.MIRROR_SETTINGS) showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
    }

    private fun cycleAutoDimDelay() {
        val next = MirrorSettings.autoDimDelay.next()
        SettingsStore.persistAutoDimDelay(this, next)
        ProjectionService.refreshScreenPowerPolicy(this)
        Toast.makeText(this, "Auto dim: ${next.label}", Toast.LENGTH_SHORT).show()
        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
    }

    private fun toggleSurfaceProfile() {
        SurfaceProfile.active = if (SurfaceProfile.active == SurfaceProfile.DEFAULT) {
            SurfaceProfile.FORD_NEXT_GEN
        } else {
            SurfaceProfile.DEFAULT
        }
        SettingsStore.persistSurfaceProfile(this, SurfaceProfile.active)
        Toast.makeText(
            this,
            "Head unit profile: ${SurfaceProfile.active.displayLabel}",
            Toast.LENGTH_SHORT
        ).show()
        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
    }

    private fun cycleProfileScale(packageName: String, profileId: String = "default") {
        val current = PerAppProfileStore.profile(this, packageName, profileId)
        val next = when (current.scaleMode) {
            ScaleMode.FIT -> ScaleMode.FILL
            ScaleMode.FILL -> ScaleMode.STRETCH
            ScaleMode.STRETCH -> ScaleMode.ONE_TO_ONE
            ScaleMode.ONE_TO_ONE -> ScaleMode.FIT
        }
        PerAppProfileStore.save(this, current.copy(scaleMode = next))
        Toast.makeText(this, "Scale preference: $next", Toast.LENGTH_SHORT).show()
        showPhoneScreen(PhoneScreen.PROFILE)
    }

    private fun cycleProfileResolution(packageName: String, profileId: String = "default") {
        val current = PerAppProfileStore.profile(this, packageName, profileId)
        val next = when (current.preferredResolution) {
            null, ResolutionPreset.AUTO -> ResolutionPreset.HD_720P
            ResolutionPreset.HD_720P -> ResolutionPreset.FULL_HD_1080P
            ResolutionPreset.FULL_HD_1080P -> null
            ResolutionPreset.CUSTOM -> null
        }
        PerAppProfileStore.save(this, current.copy(preferredResolution = next))
        Toast.makeText(this, "Resolution preference: ${next?.name ?: "AUTO"}", Toast.LENGTH_SHORT).show()
        showPhoneScreen(PhoneScreen.PROFILE)
    }

    private fun cycleProfileFps(packageName: String, profileId: String = "default") {
        val current = PerAppProfileStore.profile(this, packageName, profileId)
        val next = when (current.preferredFps) {
            null -> 30
            30 -> 60
            else -> null
        }
        PerAppProfileStore.save(this, current.copy(preferredFps = next))
        Toast.makeText(this, "Frame rate preference: ${next?.let { "$it FPS" } ?: "System"}", Toast.LENGTH_SHORT).show()
        showPhoneScreen(PhoneScreen.PROFILE)
    }

    private fun cycleProfileAudio(packageName: String, profileId: String = "default") {
        val current = PerAppProfileStore.profile(this, packageName, profileId)
        val next = when (current.audioMode) {
            AudioMode.MEDIA -> AudioMode.MIRROR
            AudioMode.MIRROR -> AudioMode.OFF
            AudioMode.OFF -> AudioMode.MEDIA
        }
        PerAppProfileStore.save(this, current.copy(audioMode = next))
        Toast.makeText(this, "Audio preference: $next", Toast.LENGTH_SHORT).show()
        showPhoneScreen(PhoneScreen.PROFILE)
    }

    private fun addLastSessionCard(content: LinearLayout) {
        val snapshot = SessionRestoreStore.restore(this) ?: return
        val app = InstalledAppRepository.listLaunchableApps(this)
            .firstOrNull { it.packageName == snapshot.packageName }
        val launchable = runCatching {
            packageManager.getLaunchIntentForPackage(snapshot.packageName) != null
        }.getOrDefault(false)
        if (app == null || !launchable) {
            SessionRestoreStore.clear(this)
            return
        }

        content.addView(sectionLabel("LAST SESSION"))
        addCard(
            content,
            actionCard("↻   RESUME ${app.label}", primary = false) {
                if (!QuickAppLauncher.launch(this, snapshot.packageName, snapshot.profileId)) {
                    Toast.makeText(this, "Could not resume ${app.label}", Toast.LENGTH_SHORT).show()
                }
            },
            top = 4
        )
        addCard(
            content,
            settingRow(
                "Last app",
                "${snapshot.smartMode.name} • capture consent is never replayed",
                "Forget",
                onClick = {
                    SessionRestoreStore.clear(this)
                    showPhoneScreen(PhoneScreen.HOME)
                }
            ),
            top = 8
        )
    }


    private fun refreshDeveloperDiagnosticsViews() {
        developerLogView?.text = StructuredLog.format(limit = 20).ifEmpty { "No log entries yet" }
        developerMirrorEventsView?.text = MirrorDiagnostics.format(limit = 20).ifEmpty { "No mirror events yet" }
    }

    private fun refreshLabSummary() {
        labSummaryView?.text = buildString {
            appendLine("Environment: ${RuntimeContextStore.context.value.environment}")
            appendLine("Connection: ${if (RuntimeContextStore.context.value.connected) "CONNECTED" else "DISCONNECTED"}")
            append("Vehicle provider: ${if (DevMode.isEnabled) "LAB simulator" else "production CAR_SPEED / fail-closed"}")
        }
    }

    private fun setMockVehicleState(state: VehicleState) {
        if (!DevMode.isEnabled) {
            Toast.makeText(this, "LAB simulation requires a debug emulator and a non-real-car environment", Toast.LENGTH_SHORT).show()
            return
        }
        val legacyState = when (state) {
            VehicleState.PARKED -> ParkingStateStore.State.PARKED
            VehicleState.MOVING -> ParkingStateStore.State.MOVING
            VehicleState.UNKNOWN -> ParkingStateStore.State.UNKNOWN
        }
        MockVehicleStateProvider.setState(legacyState)
        RuntimeContextStore.setSimulatedVehicleState(state)
        Toast.makeText(this, "LAB vehicle state: $state", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun setLabEnvironment(environment: Environment) {
        if (RuntimeContextStore.context.value.connected || MirrorCoordinator.isCarSurfaceReady) {
            Toast.makeText(this, "Stop the active Android Auto session before changing LAB environment", Toast.LENGTH_SHORT).show()
            return
        }
        if (!RuntimeContextStore.setEnvironment(environment)) {
            Toast.makeText(this, "Environment selection is available only in LAB", Toast.LENGTH_SHORT).show()
            return
        }
        if (environment == Environment.REAL_CAR) {
            MockVehicleStateProvider.stop()
        } else if (DevMode.isEnabled) {
            MockVehicleStateProvider.start()
        } else {
            MockVehicleStateProvider.stop()
        }
        Toast.makeText(this, "LAB environment: $environment", Toast.LENGTH_SHORT).show()
        showPhoneScreen(PhoneScreen.DEVELOPER)
    }

    private fun setLabConnection(connected: Boolean) {
        if (MirrorCoordinator.isCarSurfaceReady) {
            Toast.makeText(this, "A real Android Auto surface is already connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (!RuntimeContextStore.setSimulatedConnection(connected)) {
            Toast.makeText(this, "Connection simulation is available only outside REAL_CAR", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, if (connected) "LAB connection simulated" else "LAB connection disconnected", Toast.LENGTH_SHORT).show()
        refreshLabSummary()
        refreshStatus()
    }

    private fun browserScreenIntent(): Intent =
        Intent(this, dev.autobridge.entertainment.EntertainmentActivity::class.java).apply {
            putExtra(dev.autobridge.entertainment.EntertainmentActivity.EXTRA_BROWSER_MODE, true)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }

    /**
     * Opens the embedded WebView after projection is ready, so AUTO_MIRROR sends this screen to
     * the car surface. The first use asks for MediaProjection consent and continues afterward.
     */
    private fun openBrowserOnCar() {
        if (!FeaturePolicy.app.isAvailable(Feature.BROWSER)) {
            Toast.makeText(this, FeaturePolicy.app.denialMessage(Feature.BROWSER), Toast.LENGTH_SHORT).show()
            return
        }
        if (MirrorCoordinator.isProjectionReady) {
            startActivity(browserScreenIntent())
            return
        }
        pendingEntertainmentLaunch = true
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        if (!FeaturePolicy.app.isAvailable(Feature.MIRROR)) {
            Toast.makeText(this, FeaturePolicy.app.denialMessage(Feature.MIRROR), Toast.LENGTH_SHORT).show()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            @Suppress("DEPRECATION")
            manager.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(captureIntent, REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated by Activity; retained to keep this starter dependency-light")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return

        if (resultCode == RESULT_OK && data != null) {
            val started = ProjectionService.start(this, resultCode, data)
            if (!started) {
                pendingEntertainmentLaunch = false
                Toast.makeText(
                    this,
                    "Could not start screen projection: ${ProjectionService.lastErrorMessage ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            } else if (pendingEntertainmentLaunch) {
                pendingEntertainmentLaunch = false
                startActivity(browserScreenIntent())
            }
        } else {
            pendingEntertainmentLaunch = false
            Toast.makeText(this, "Screen capture was not started", Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun refreshStatus() {
        val runtime = RuntimeContextStore.context.value
        val parking = when (runtime.vehicleState) {
            VehicleState.PARKED -> "PARKED"
            VehicleState.MOVING -> "MOVING (blocked)"
            VehicleState.UNKNOWN -> "UNKNOWN (blocked)"
        }
        val projection = when (ProjectionService.sessionState) {
            ProjectionService.SessionState.IDLE ->
                if (MirrorCoordinator.isProjectionReady) "ready" else "not started"
            ProjectionService.SessionState.STARTING -> "starting…"
            ProjectionService.SessionState.READY -> "ready"
            ProjectionService.SessionState.ERROR ->
                "ERROR — ${ProjectionService.lastErrorMessage ?: "unknown error"}"
        }
        val mirrorDecision = FeaturePolicy.app.decide(Feature.MIRROR, runtime)
        val contentWarning = MirrorContentPolicy.warningFor(runtime.currentPackageName)
        val nextStep = when {
            !mirrorDecision.allowed -> mirrorDecision.reason
            ProjectionService.sessionState == ProjectionService.SessionState.ERROR ->
                "Tap Start screen mirror and grant capture permission again."
            ProjectionService.sessionState == ProjectionService.SessionState.STARTING ->
                "Wait for the projection service to become ready."
            !MirrorCoordinator.isProjectionReady ->
                "Tap Start screen mirror and allow screen capture."
            !MirrorCoordinator.isCarSurfaceReady ->
                "Open AutoBridge in Android Auto / DHU."
            runtime.vehicleState == VehicleState.MOVING ->
                "Vehicle is moving; mirroring is blocked."
            runtime.vehicleState == VehicleState.UNKNOWN ->
                "Vehicle state is unknown; enable speed safety before mirroring."
            !MirrorCoordinator.isMirroring ->
                "Mirror prerequisites are ready; wait briefly or check Developer log."
            contentWarning != null -> contentWarning
            else ->
                "Mirror is active."
        }
        val shizuku = when {
            !FeaturePolicy.app.isAvailable(Feature.SHIZUKU) -> "disabled by mode"
            !Shizuku.pingBinder() -> "not running"
            !ShizukuInputBackend.isPermissionGranted -> "permission needed"
            else -> "ready"
        }
        val statusText = buildString {
            appendLine("Mode: ${runtime.mode}")
            appendLine("Environment: ${runtime.environment}")
            appendLine("Android Auto: ${if (runtime.connected || MirrorCoordinator.isCarSurfaceReady) "CONNECTED" else "DISCONNECTED"}")
            appendLine("Projection: $projection")
            appendLine("Car surface: ${if (MirrorCoordinator.isCarSurfaceReady) "connected" else "not connected"}")
            appendLine("Vehicle: $parking")
            appendLine("Mirroring: ${if (MirrorCoordinator.isMirroring) "ACTIVE" else "inactive"}")
            contentWarning?.let { appendLine("Content warning: $it") }
            if (runtime.mode == AutoBridgeMode.LAB) {
                appendLine("LAB simulator: vehicle input is ${if (runtime.environment == Environment.REAL_CAR) "disabled on REAL_CAR" else "available"}")
            }
            appendLine("Next: $nextStep")
            appendLine()
            appendLine("Shizuku touch: $shizuku")
            appendLine("Car reconnects (this session): ${ReconnectTracker.reconnectCount}")
            appendLine("Phone rotation: ${OrientationMonitor.label()}")
            val landscapeMirror = when {
                !MirrorOrientationController.isEnabled -> "off"
                !MirrorOrientationController.hasPermission(this@MainActivity) -> "ON (permission needed)"
                MirrorOrientationController.isApplied -> "ON (applied; restored on stop)"
                else -> "ON (applies next session)"
            }
            appendLine("Landscape mirror (Fermata): $landscapeMirror")
            appendLine("Head unit profile: ${SurfaceProfile.active.displayLabel}")
            appendLine("Force-landscape permission: ${if (QuickAppLauncher.hasLandscapePermission(this@MainActivity)) "granted" else "not granted"}")
            appendLine("Media playback: ${if (mediaPlayback.isPlaying) "playing" else "paused/stopped"}")
            appendLine("Screen-off: ${ScreenOffController.statusLabel()}")
            appendLine("Prevent sleep: ${if (MirrorSettings.preventScreenSleep) "on" else "off"}")
            appendLine("Auto dim: ${MirrorSettings.autoDimDelay.label}")
            appendLine("Stop on disconnect: ${if (MirrorSettings.stopOnDisconnect) "on" else "off"}")
            appendLine("Auto-launch last app: ${if (MirrorSettings.autoLaunchLastApp) "on" else "off"}")
            appendLine("Auto-start mirror: ${if (MirrorSettings.autoStartMirror) "on" else "off"}")
            appendLine("Developer log: ${StructuredLog.recent().size} entries")
            val uptime = MirrorDiagnostics.currentMirroringUptimeMs()
            if (uptime != null) appendLine("Mirroring uptime: ${uptime / 1000}s")
            val timeToActive = MirrorDiagnostics.latencyBetween("consent_received", "mirroring_active")
            append("Time to active (last session): ${timeToActive?.let { "${it}ms" } ?: "n/a"}")
        }
        refreshDeveloperDiagnosticsViews()
        refreshLabSummary()
        if (::statusView.isInitialized) statusView.text = statusText
        homeConnectionView?.text = buildString {
            appendLine("●  Android Auto    ${if (MirrorCoordinator.isCarSurfaceReady) "Connected" else "Waiting"}")
            appendLine("    ${SurfaceProfile.active.displayLabel}  •  ${runtime.environment}")
            append("    Vehicle  •  ${if (runtime.vehicleState == VehicleState.PARKED) "PARKED" else "SAFETY LOCKED"}")
        }
    }
}