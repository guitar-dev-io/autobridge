package dev.autobridge

import android.Manifest
import android.app.Activity
import android.content.Context
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
import dev.autobridge.core.consent.PermissionDisclosure
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
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgePhoneTheme
import dev.autobridge.remote.RemoteRuntime
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.display.MirrorOrientationController
import dev.autobridge.display.OrientationMonitor
import dev.autobridge.display.ScreenOffController
import dev.autobridge.display.ScreenPowerController
import dev.autobridge.logging.StructuredLog
import dev.autobridge.diagnostics.CrashReportStore
import dev.autobridge.display.SurfaceProfile
import dev.autobridge.entertainment.BrowserLauncher
import dev.autobridge.input.AccessibilityInputBackend
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.input.TouchRouter
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.mirror.ReconnectTracker
import dev.autobridge.safety.DevMode
import dev.autobridge.safety.MockVehicleStateProvider
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.settings.MirrorSettings
import dev.autobridge.settings.SettingsStore
import dev.autobridge.ui.PhoneNav
import dev.autobridge.update.UpdateChecker
import rikka.shizuku.Shizuku

/** Phone destinations are defined (and unit-tested) in [PhoneNav]. */
private typealias PhoneScreen = PhoneNav.Route

/**
 * How another Activity (the browser's "Now playing" / "Agent" actions, say) opens a running or
 * fresh [MainActivity] straight to one of its screens instead of landing on Home.
 *
 * [Intent.FLAG_ACTIVITY_REORDER_TO_FRONT] reuses the existing instance when MainActivity is still
 * in the task's back stack (the common case: Home opened the caller in the first place), which is
 * what makes [MainActivity.onNewIntent] run rather than a second instance being created.
 */
object MainActivityScreens {
    const val EXTRA_OPEN_SCREEN = "dev.autobridge.extra.OPEN_SCREEN"

    fun intent(context: android.content.Context, route: PhoneNav.Route): android.content.Intent =
        android.content.Intent(context, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(EXTRA_OPEN_SCREEN, route.name)
}

class MainActivity : androidx.activity.ComponentActivity() {
    private companion object {
        const val REQUEST_CAPTURE = 2001
        const val REQUEST_NOTIFICATIONS = 2002
        const val REQUEST_VOICE_SEARCH = 2003

        const val SUPPORT_URL = "https://buymeacoffee.com/guitar.story"
        const val GITHUB_URL = "https://github.com/guitar-dev-io/autobridge"
        const val FACEBOOK_URL = "https://www.facebook.com/share/19mw1X5Lou/"

        /** Home checks for a newer release once per app process; see [maybeCheckUpdateFromHome]. */
        var homeUpdateChecked = false

        /** A newer release found by that check, shown as Home's update card until dismissed. */
        var homeUpdate: dev.autobridge.update.UpdateChecker.Result.Available? = null

        /** A Home check is skipped when the last answer was "up to date" and is this fresh. */
        const val HOME_UPDATE_RECHECK_MS = 3 * 60 * 60 * 1000L
        const val PREFS_HOME_UPDATE = "home_update"
        const val KEY_DISMISSED_VERSION = "dismissed_version"

        const val STATE_SCREEN = "phone_screen"
        const val STATE_BACK_STACK = "phone_back_stack"
        const val STATE_APPS_FAVORITES = "apps_favorites_only"
        const val STATE_APPS_QUERY = "apps_query"

        // Matches AutoBridgeDesign / ComposeTokens (docs/UI_REDESIGN_TASKS.md "Design tokens").
        const val COLOR_BACKGROUND = 0xff12151a.toInt()
        const val COLOR_SURFACE = 0xff1c2027.toInt()
        const val COLOR_SURFACE_ALT = 0xff232831.toInt()
        const val COLOR_BORDER = 0xff2e343e.toInt()
        const val COLOR_ACCENT = 0xff4da3ff.toInt()
        const val COLOR_ACCENT_DARK = 0xff0b5d96.toInt()
        const val COLOR_TEXT = 0xfff3f5f7.toInt()
        const val COLOR_MUTED = 0xffa9b0ba.toInt()
        const val COLOR_SUCCESS = 0xff2ee879.toInt()
        const val COLOR_WARNING = 0xffffbf5f.toInt()
    }

    /** Back stack of the phone shell; see [PhoneNav.BackStack]. */
    private var backStack = PhoneNav.BackStack()


    /** Restyles the Applications filter chips; set while that screen is built, null otherwise. */
    private var appsFilterRefresh: (() -> Unit)? = null

    private lateinit var statusView: TextView
    private lateinit var mediaPlayback: MediaPlaybackClient
    private lateinit var screenContainer: FrameLayout
    /** Live internal-state text on Advanced > Debug; null when that page is not showing. */
    private var debugStateView: TextView? = null
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
            if (::statusView.isInitialized || debugStateView != null) refreshStatus()
            statusHandler.postDelayed(this, 1_000L)
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        runOnUiThread {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                getString(
                    if (granted) R.string.input_shizuku_enabled
                    else R.string.input_shizuku_denied
                ),
                Toast.LENGTH_SHORT
            ).show()
            if (granted) ShizukuInputBackend.bind(this)
            refreshStatus()
        }
    }

    /**
     * Applies the Settings &gt; Language choice before any view or string is resolved. A no-op on
     * API 33+, where the platform has already picked the locale; see [AppLocale.rebase].
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.restore(this)
        // What maintenance is due, as a notification (once a day at most; nothing without items).
        runCatching { dev.autobridge.maintenance.MaintenanceReminder.check(this) }
        // Which screens Android sees (a head unit's rear screen may or may not be one of them).
        dev.autobridge.display.DisplayInventory.log(this)
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
            if (::statusView.isInitialized || debugStateView != null) refreshStatus()
        })
        // Start the Mobile Remote command runtime and share the already-connected media client so
        // media commands reuse the same MediaSession (no duplicate connection).
        RemoteRuntime.ensureStarted(this)
        RemoteRuntime.attachMediaClient(mediaPlayback)
        // Rotation and process recreation return to the same page and drill-down path instead of
        // dropping the user on Home. Unknown or legacy names (REMOTE, DEVICES) are mapped by
        // PhoneNav.parse, so an old saved state never crashes the restore. An explicit deep-link
        // target (see MainActivityScreens) wins over the saved one: it is a fresh, deliberate
        // request from outside, not process-death restoration.
        val openScreen = intent.getStringExtra(MainActivityScreens.EXTRA_OPEN_SCREEN)?.let(PhoneNav::parse)
        val restoredScreen = openScreen ?: PhoneNav.parse(savedInstanceState?.getString(STATE_SCREEN))
        backStack = PhoneNav.BackStack(
            savedInstanceState?.getStringArrayList(STATE_BACK_STACK).orEmpty().map(PhoneNav::parse)
        )
        appsFavoritesOnly = savedInstanceState?.getBoolean(STATE_APPS_FAVORITES, true) ?: true
        appSearchQuery = savedInstanceState?.getString(STATE_APPS_QUERY).orEmpty()
        setContentView(buildUi(restoredScreen))
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBack()) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        refreshStatus()
    }

    /**
     * Reused-instance half of [MainActivityScreens]: when MainActivity is still in the back stack
     * (brought forward instead of recreated), onCreate does not run again, so the deep-link target
     * is applied here instead.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(MainActivityScreens.EXTRA_OPEN_SCREEN)?.let { showPhoneScreen(PhoneNav.parse(it)) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SCREEN, currentScreen.name)
        outState.putStringArrayList(STATE_BACK_STACK, ArrayList(backStack.entries.map { it.name }))
        outState.putBoolean(STATE_APPS_FAVORITES, appsFavoritesOnly)
        outState.putString(STATE_APPS_QUERY, appSearchQuery)
    }

    /**
     * One step back through the phone shell (header ‹ and the system Back both land here).
     * Returns false when there is nowhere left to go, so the system can leave the app.
     */
    private fun goBack(): Boolean {
        val target = backStack.back(currentScreen) ?: return false
        showPhoneScreen(target, fromBack = true)
        return true
    }

    /** Update, What's-new and Send-log flows; see [PhoneMaintenance]. */
    private val maintenance by lazy {
        PhoneMaintenance(this, openUrl = ::openExternalUrl, fallbackShare = ::shareDiagnostics)
    }

    /**
     * The Home microphone with offline Whisper ([dev.autobridge.voice.PhoneVoiceSession]). Its
     * commands land on the same entry points the screens use: the browser, Home, Back, and the
     * Agent parser for anything outside the voice grammar.
     */
    private val voiceSession by lazy {
        dev.autobridge.voice.PhoneVoiceSession(this, object : dev.autobridge.voice.PhoneVoiceSession.Actions {
            override fun openUrl(url: String) {
                startActivity(browserScreenIntent().setData(android.net.Uri.parse(url)))
            }
            override fun openBrowser() = openBrowserOnCar()
            override fun goHome() = showPhoneScreen(PhoneScreen.HOME)
            override fun goBack() { this@MainActivity.goBack() }
            override fun openSection(section: dev.autobridge.library.HomeSection) = openHomeSection(section)
            override fun runAgentCommand(text: String) = runPhoneVoiceCommand(text)
        })
    }

    override fun onResume() {
        super.onResume()
        statusHandler.removeCallbacks(refreshStatusRunnable)
        refreshStatusRunnable.run()
        maintenance.runPostUpdateChecks()
    }

    override fun onPause() {
        statusHandler.removeCallbacks(refreshStatusRunnable)
        super.onPause()
    }

    override fun onStop() {
        // Going to the background mid-sentence releases the microphone.
        voiceSession.stop()
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        voiceSession.onPermissionResult(
            requestCode,
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        )
    }

    override fun onDestroy() {
        voiceSession.destroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        homeMiniPlayer?.stop()
        mediaPlayback.disconnect()
        super.onDestroy()
    }

    private fun buildUi(initial: PhoneScreen = PhoneScreen.HOME): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // No bottom tab bar: Home is the hub and every other destination is a child reached from
        // it, directly or through Settings (see PhoneNav).
        screenContainer = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
        }
        root.addView(
            screenContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        showPhoneScreen(initial, fromBack = true)
        return root
    }

    private fun showPhoneScreen(requested: PhoneScreen, force: Boolean = false, fromBack: Boolean = false) {
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
        if (!fromBack) backStack.onNavigate(if (screenRendered) currentScreen else null, target)
        screenRendered = true
        currentScreen = target
        debugStateView = null
        developerLogView = null
        developerMirrorEventsView = null
        labSummaryView = null
        screenContainer.removeAllViews()

        val screen = when (target) {
            PhoneScreen.HOME -> buildHomeScreen()
            PhoneScreen.SETTINGS -> buildSettingsMenu()
            PhoneScreen.CONTROL -> buildControlScreen()
            PhoneScreen.CONTROL_HISTORY -> buildCommandHistoryScreen()
            PhoneScreen.APPS -> buildAppsScreen()
            PhoneScreen.PROFILE -> buildProfileScreen()
            PhoneScreen.MIRROR_SETTINGS -> buildMirrorSettingsScreen()
            PhoneScreen.INPUT_TOUCH -> buildInputTouchScreen()
            PhoneScreen.ADVANCED -> buildAdvancedScreen()
            PhoneScreen.DEVELOPER -> buildDeveloperScreen()
            PhoneScreen.DEBUG -> buildDebugScreen()
            PhoneScreen.CAR_CONNECTION -> buildCarConnectionScreen()
            PhoneScreen.AGENT_COMMANDS -> buildAgentCommandsScreen()
            PhoneScreen.ABOUT -> buildAboutScreen()
            // Two-column cards rather than a long list: More is a handful of places to go, each
            // with its own colour, so they read as destinations instead of settings rows.
            PhoneScreen.HOME_MORE -> buildHomeGroupScreen(
                getString(R.string.home_tile_more),
                getString(R.string.home_more_subtitle),
                dev.autobridge.ui.PhoneHomeLayout.moreSections,
                grid = true
            )
        }
        screenContainer.addView(
            screen,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        refreshStatus()
    }

    /**
     * Home: a launcher/dashboard rather than a grid of every feature. Android Auto status, Send
     * to Car with its recent sends, then six Quick Launch tiles ([dev.autobridge.ui.PhoneHomeLayout]). Every
     * section the old grid showed is still reachable: directly, through Music / TV / Radio, or
     * behind More. The car's own grid is unchanged.
     */
    private fun buildHomeScreen(): View {
        maybeCheckUpdateFromHome()
        val layout = dev.autobridge.ui.PhoneHomeLayout
        val design = dev.autobridge.ui.AutoBridgeDesign
        val tiles = dev.autobridge.ui.PhoneHomeLayout.Tile.entries.map { tile ->
            when (tile) {
                dev.autobridge.ui.PhoneHomeLayout.Tile.BROWSER, dev.autobridge.ui.PhoneHomeLayout.Tile.YOUTUBE -> {
                    val section = layout.directSection.getValue(tile)
                    dev.autobridge.ui.HomeTileUi(getString(tile.titleRes), tileIcon(section), section.accent) { openHomeSection(section) }
                }
                // Music, TV / Radio and Favorites now open the Library screen directly, with its
                // chip row pre-selected, instead of the old chooser pages — Library itself is the
                // chooser now (phase 3). Favorite apps stay reachable from Settings > Apps & profiles.
                dev.autobridge.ui.PhoneHomeLayout.Tile.MUSIC -> dev.autobridge.ui.HomeTileUi(
                    getString(tile.titleRes), R.drawable.ic_tile_youtube_music, design.ACCENT_FILES
                ) {
                    startActivity(
                        dev.autobridge.library.LibraryActivity.intent(
                            this, dev.autobridge.library.LibraryActivity.Section.MUSIC
                        )
                    )
                }
                dev.autobridge.ui.PhoneHomeLayout.Tile.TV_RADIO -> dev.autobridge.ui.HomeTileUi(
                    getString(tile.titleRes), R.drawable.ic_tile_tv, design.ACCENT_TV
                ) {
                    startActivity(
                        dev.autobridge.library.LibraryActivity.intent(
                            this, dev.autobridge.library.LibraryActivity.Section.TV
                        )
                    )
                }
                dev.autobridge.ui.PhoneHomeLayout.Tile.FAVORITES -> dev.autobridge.ui.HomeTileUi(
                    getString(tile.titleRes), R.drawable.ic_tile_favorite, design.ACCENT_FAVORITE
                ) {
                    startActivity(
                        dev.autobridge.library.LibraryActivity.intent(
                            this, dev.autobridge.library.LibraryActivity.Section.FAVORITES
                        )
                    )
                }
                dev.autobridge.ui.PhoneHomeLayout.Tile.MORE -> dev.autobridge.ui.HomeTileUi(
                    getString(tile.titleRes), R.drawable.ic_tile_apps, design.ACCENT_SYSTEM
                ) { showPhoneScreen(PhoneScreen.HOME_MORE) }
            }
        }

        val dashboard = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AutoBridgePhoneTheme {
                    dev.autobridge.ui.HomeDashboard(
                        tiles = tiles,
                        onOpenConnection = { showPhoneScreen(PhoneScreen.CAR_CONNECTION) },
                        onEditQuickLaunch = { appsFavoritesOnly = true; showPhoneScreen(PhoneScreen.APPS) },
                        onOpenController = { showPhoneScreen(PhoneScreen.CONTROL) },
                        onMirror = { requestScreenCapture() },
                        onBridgeDuo = duoScreenIntent()?.let { intent -> { startActivity(intent) } },
                        updateVersion = homeUpdate?.release?.versionName,
                        onOpenUpdate = { homeUpdate?.let(::showUpdateDialog) },
                        onDismissUpdate = ::dismissHomeUpdate
                    )
                }
            }
        }
        val body = design.body(this).apply {
            addView(dashboard, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Same rule as the About page: an off-store payment prompt is for the sideload flavors only.
            if (BuildConfig.AUTOBRIDGE_MODE != "SAFE") {
                addView(
                    design.pill(this@MainActivity, getString(R.string.home_donate), accent = design.ACCENT_FAVORITE) { showDonateChoices() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(12)
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                    }
                )
            }
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

        return design.page(
            context = this,
            header = design.header(
                context = this,
                title = getString(R.string.app_name),
                // The installed version, so which build is on the phone can be read at a glance
                // when a fix is being checked on the car.
                subtitle = "v${BuildConfig.VERSION_NAME}",
                logo = R.mipmap.ic_launcher_round,
                // Outdoor temperature when a Weather place is saved; the connection itself is
                // the status card right below, so the chip no longer repeats it.
                chip = homeStatusChip(),
                actions = listOf(
                    dev.autobridge.ui.AutoBridgeDesign.HeaderAction("\uD83C\uDF99", ::homeVoiceSearch, filled = true),
                    // Home is the hub now that the bottom tab bar is gone, so this is the only way
                    // into Settings from here.
                    dev.autobridge.ui.AutoBridgeDesign.HeaderAction("\u2699", { showPhoneScreen(PhoneScreen.SETTINGS) })
                )
            ),
            body = body,
            bottomBar = bottom,
            applyInsets = false
        )
    }

    /** What the break-reminder row says: off, or how often. */
    private fun breakReminderCaption(): String {
        val hours = dev.autobridge.breakreminder.BreakReminder.hours(this)
        return if (hours == 0) getString(R.string.break_reminder_off) else resources.getQuantityString(R.plurals.break_reminder_every, hours, hours)
    }

    /** Off, or every 1 to 4 hours of driving with the car connected. */
    private fun showBreakReminderChoice() {
        val options = dev.autobridge.breakreminder.BreakReminderOptions.HOURS
        val labels = (listOf(getString(R.string.break_reminder_off)) +
            options.map { resources.getQuantityString(R.plurals.break_reminder_every, it, it) }).toTypedArray()
        val current = dev.autobridge.breakreminder.BreakReminder.hours(this)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.break_reminder_title))
            .setMessage(getString(R.string.break_reminder_explain))
            .setSingleChoiceItems(labels, if (current == 0) 0 else options.indexOf(current) + 1) { dialog, which ->
                dev.autobridge.breakreminder.BreakReminder.setHours(this, if (which == 0) 0 else options[which - 1])
                dialog.dismiss()
                showPhoneScreen(PhoneScreen.SETTINGS, force = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The coffee button on Home: the same two ways to say thanks as the About page. */
    private fun showDonateChoices() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_promptpay_title))
            .setItems(arrayOf(getString(R.string.about_support_caption), getString(R.string.about_promptpay))) { _, which ->
                if (which == 0) openInAppUrl(SUPPORT_URL) else showPromptPayDialog()
            }
            .show()
    }

    /** Home > Music / TV / Radio / More: a short list of the sections grouped behind one tile. */
    private fun buildHomeGroupScreen(
        title: String,
        subtitle: String,
        sections: List<dev.autobridge.library.HomeSection>,
        grid: Boolean = false
    ): View =
        dev.autobridge.ui.PhoneLauncherUi.screen(
            context = this,
            title = title,
            subtitle = subtitle,
            entries = sections.map { section ->
                dev.autobridge.ui.PhoneLauncherUi.Entry(
                    title = section.title(this),
                    icon = tileIcon(section),
                    caption = section.caption(this),
                    accent = section.accent,
                    open = { openHomeSection(section) }
                )
            },
            grid = grid,
            home = { goBack() },
            applyInsets = false
        )

    private fun tileIcon(section: dev.autobridge.library.HomeSection): Int =
        when (section) {
            dev.autobridge.library.HomeSection.TV -> R.drawable.ic_tile_tv
            dev.autobridge.library.HomeSection.RADIO -> R.drawable.ic_tile_radio
            dev.autobridge.library.HomeSection.WEB -> R.drawable.ic_tile_web
            dev.autobridge.library.HomeSection.YOUTUBE -> R.drawable.ic_tile_youtube
            dev.autobridge.library.HomeSection.YOUTUBE_MUSIC -> R.drawable.ic_tile_youtube_music
            dev.autobridge.library.HomeSection.STREAMING -> R.drawable.ic_tile_streaming
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
            section == dev.autobridge.library.HomeSection.REMOTE -> showPhoneScreen(PhoneScreen.CONTROL)
            else -> showPhoneScreen(PhoneScreen.SETTINGS)
        }
    }

    /**
     * Outdoor temperature for the user's saved Weather place when available, otherwise connection
     * + vehicle state — kept to a couple of short words so the chip never wraps. Reads only the
     * [dev.autobridge.weather.WeatherRepository] cache; [refreshHomeWeatherIfNeeded] is what kicks
     * off (and redraws Home after) an actual fetch.
     */
    private fun homeStatusChip(): String? {
        refreshHomeWeatherIfNeeded()
        val place = dev.autobridge.weather.WeatherLocationStore.place(this)
        val snapshot = dev.autobridge.weather.WeatherRepository.cachedSnapshot()
        if (place != null && snapshot != null && snapshot.place == place) {
            return "${Math.round(snapshot.temperatureC)}°C"
        }
        // No weather: no chip. Connection state is the Android Auto card under the header.
        return null
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
     * Header microphone: dictate a command or search. With Settings > Voice Recognition on (the
     * default) and a 64-bit phone, speech is transcribed offline by Whisper and parsed by
     * [dev.autobridge.voice.VoiceCommandParser]; otherwise the phone's own speech service is used and
     * the phrase goes through [runPhoneVoiceCommand], as before. Falls back to the browser's own
     * search entry when the device has no speech recognizer.
     */
    private fun homeVoiceSearch() {
        if (dev.autobridge.voice.VoiceSettings.enabled(this) && dev.autobridge.voice.VoiceRuntime.isSupported(this)) {
            voiceSession.start()
            return
        }
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                android.speech.RecognizerIntent.EXTRA_PROMPT,
                getString(R.string.voice_prompt_say_command)
            )
        }
        val started = runCatching { startActivityForResult(intent, REQUEST_VOICE_SEARCH) }.isSuccess
        if (!started) {
            Toast.makeText(this, getString(R.string.voice_no_recognizer), Toast.LENGTH_SHORT)
                .show()
            openBrowserOnCar()
        }
    }

    /**
     * Runs a dictated phrase through the same parser the car Agent uses, then maps each action
     * onto the phone's own entry points (the car router needs a car Screen, so it is not reused
     * here). Anything that is not a recognised command still ends up as a browser search.
     */
    private fun runPhoneVoiceCommand(spoken: String) {
        val command = dev.autobridge.agent.AgentCommandRouter.parse(spoken) ?: return
        val action = command.action
        fun openUrl(url: String) =
            startActivity(browserScreenIntent().setData(android.net.Uri.parse(url)))
        fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        when (action) {
            dev.autobridge.agent.AgentCommandRouter.AgentAction.OPEN_URL -> {
                val url = dev.autobridge.entertainment.ContentAddress.https(command.argument.orEmpty())
                if (url != null) openUrl(url)
                else openUrl(dev.autobridge.entertainment.ContentAddress.webSearch(spoken))
            }
            dev.autobridge.agent.AgentCommandRouter.AgentAction.OPEN_BROWSER,
            dev.autobridge.agent.AgentCommandRouter.AgentAction.ENTER_FULLSCREEN,
            dev.autobridge.agent.AgentCommandRouter.AgentAction.EXIT_FULLSCREEN -> openBrowserOnCar()
            dev.autobridge.agent.AgentCommandRouter.AgentAction.OPEN_MIRROR -> requestScreenCapture()
            dev.autobridge.agent.AgentCommandRouter.AgentAction.OPEN_MEDIA ->
                startActivity(
                    dev.autobridge.library.LibraryActivity.intent(
                        this, dev.autobridge.library.LibraryActivity.Section.PLAYLISTS
                    )
                )
            dev.autobridge.agent.AgentCommandRouter.AgentAction.RESUME_MEDIA -> {
                mediaPlayback.resume()
                toast(getString(R.string.agent_toast_resuming_playback))
            }
            dev.autobridge.agent.AgentCommandRouter.AgentAction.LOG_FUEL ->
                toast(dev.autobridge.fuel.FuelVoiceLogger.log(this, command.argument.orEmpty()).message)
            dev.autobridge.agent.AgentCommandRouter.AgentAction.OPEN_RECENT ->
                toast(getString(R.string.car_agent_recent_on_car))
            dev.autobridge.agent.AgentCommandRouter.AgentAction.ENABLE_DESKTOP -> {
                dev.autobridge.browser.BrowserUserAgentStore.select(
                    this, dev.autobridge.browser.BrowserUserAgentMode.DESKTOP
                )
                toast(getString(R.string.agent_toast_desktop_on))
            }
            dev.autobridge.agent.AgentCommandRouter.AgentAction.DISABLE_DESKTOP -> {
                dev.autobridge.browser.BrowserUserAgentStore.select(
                    this, dev.autobridge.browser.BrowserUserAgentMode.MOBILE
                )
                toast(getString(R.string.agent_toast_desktop_off))
            }
        }
    }

    /**
     * Opens the language choice.
     *
     * Android 13+ has a per-app language screen of its own, populated from
     * `res/xml/locales_config.xml`, and that is where users look for it — it also scales to any
     * number of languages for free. Below that the platform offers nothing, so the row cycles
     * [AppLocale.OPTIONS] in place and restarts the shell to redraw it, which is the same
     * tap-to-cycle shape the rest of these settings use.
     */
    private fun chooseLanguage() {
        if (AppLocale.openSystemPicker(this)) return
        val next = AppLocale.nextOption(this)
        if (AppLocale.select(this, next.tag)) recreate() else showPhoneScreen(PhoneScreen.SETTINGS)
    }

    /** One titled group of rows on a settings-style list page. */
    private class SettingsGroup(
        val label: String,
        val rows: List<SettingsRow> = emptyList(),
        /** Pre-built views that render inside the group card after [rows]; e.g. the Safety switch. */
        val customRows: List<View> = emptyList()
    )

    /**
     * One row on a settings list. [value], when set, draws the row as a value row (no icon badge,
     * the value right-aligned) matching the GENERAL rows in design 07; otherwise it is a normal
     * icon-badge row. [icon] is only used when [value] is null.
     */
    private class SettingsRow(
        val title: String,
        val caption: String,
        val icon: Int,
        val accent: Int,
        val value: String? = null,
        val open: () -> Unit
    )

    private fun settingsEntry(
        title: String,
        caption: String,
        icon: Int,
        accent: Int = dev.autobridge.ui.AutoBridgeDesign.ACCENT_SYSTEM,
        action: () -> Unit
    ) = SettingsRow(
        title = title,
        caption = caption,
        icon = icon,
        accent = accent,
        open = action
    )

    /**
     * A GENERAL-style row: no icon badge, the current [value] shown right-aligned before the
     * chevron (design 07 "Agent & Commands → Quick actions, history", "Language → ไทย").
     */
    private fun settingsValueEntry(title: String, value: String, action: () -> Unit) = SettingsRow(
        title = title,
        caption = "",
        icon = 0,
        accent = dev.autobridge.ui.AutoBridgeDesign.ACCENT_SYSTEM,
        value = value,
        open = action
    )

    /**
     * A grouped list page in the launcher's visual language: header, then each group as a quiet
     * section label over one rounded card whose rows are divided by hairlines (design 07). [back]
     * is null for the Settings root tab.
     */
    private fun settingsListPage(
        title: String,
        subtitle: String?,
        groups: List<SettingsGroup>,
        back: (() -> Unit)?,
        searchHint: String? = null
    ): View {
        val design = dev.autobridge.ui.AutoBridgeDesign
        val body = design.body(this)
        // With a search hint the page gets a filter box on top: typing narrows every group to the
        // rows whose title or caption matches, so a setting can be found without knowing its group.
        val groupsHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (searchHint != null) {
            body.addView(
                design.searchField(this, searchHint, "") { query -> renderSettingsGroups(groupsHolder, groups, query) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            )
        }
        body.addView(groupsHolder, LinearLayout.LayoutParams(-1, -2))
        renderSettingsGroups(groupsHolder, groups, "")
        return design.page(
            context = this,
            header = design.header(context = this, title = title, subtitle = subtitle, onBack = back),
            body = body,
            applyInsets = false
        )
    }

    /** Draws [groups] into [holder], keeping only rows that match [query] (all rows when blank). */
    private fun renderSettingsGroups(holder: LinearLayout, groups: List<SettingsGroup>, query: String) {
        val ui = dev.autobridge.ui.SettingsUi
        val needle = query.trim()
        holder.removeAllViews()
        groups.forEach { group ->
            val matching = if (needle.isEmpty()) group.rows else group.rows.filter {
                it.title.contains(needle, ignoreCase = true) || it.caption.contains(needle, ignoreCase = true) ||
                    group.label.contains(needle, ignoreCase = true)
            }
            // Custom rows are pre-built views with no searchable text; they show with the full list.
            val custom = if (needle.isEmpty()) group.customRows else emptyList()
            if (matching.isEmpty() && custom.isEmpty()) return@forEach
            custom.forEach { (it.parent as? ViewGroup)?.removeView(it) }
            val cardRows = buildList {
                matching.forEach { entry ->
                    add(
                        if (entry.value != null) ui.valueRow(
                            context = this@MainActivity,
                            title = entry.title,
                            value = entry.value,
                            onClick = entry.open
                        ) else ui.row(
                            context = this@MainActivity,
                            title = entry.title,
                            caption = entry.caption,
                            icon = entry.icon,
                            accent = entry.accent,
                            onClick = entry.open
                        )
                    )
                }
                addAll(custom)
            }
            holder.addView(
                ui.group(this, group.label, cardRows),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            )
        }
    }

    /**
     * Settings root. Four groups matching design 07_PhoneSettings.png: CAR & APPS (connection,
     * duo screen, apps, display, input), PLAYBACK & WEB (video, youtube, browser), SAFETY (bypass
     * switch), GENERAL (agent, language, advanced, about).
     */
    private fun buildSettingsMenu(): View {
        val design = dev.autobridge.ui.AutoBridgeDesign
        val ui = dev.autobridge.ui.SettingsUi
        val bypassOn = dev.autobridge.safety.BypassPolicyStore.enabled
        // Safety bypass: an accent-tinted toggle on a card inside the SAFETY group (design 07).
        // The row toggles; turning ON still goes through the confirm dialog, turning OFF is
        // immediate — the exact behaviour (and persistent notification via toggleBypass) as before.
        val bypassRow = ui.switchRow(
            context = this,
            title = getString(R.string.bypass_setting_title),
            caption = getString(
                if (bypassOn) R.string.bypass_setting_summary_on
                else R.string.bypass_setting_summary_off
            ),
            icon = R.drawable.ic_tile_settings,
            accent = design.ACCENT_RADIO,
            checked = bypassOn,
            onToggle = {
                if (!dev.autobridge.safety.BypassPolicyStore.enabled) {
                    // Turning ON: show the same confirmation dialog as before.
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.bypass_confirm_title)
                        .setMessage(R.string.bypass_confirm_message)
                        .setNegativeButton(R.string.action_cancel) { _, _ ->
                            // Nothing changed; rebuild so the toggle reflects the still-off state.
                            showPhoneScreen(PhoneScreen.SETTINGS, force = true)
                        }
                        .setOnCancelListener {
                            showPhoneScreen(PhoneScreen.SETTINGS, force = true)
                        }
                        .setPositiveButton(R.string.bypass_notification_action_turn_on) { _, _ -> toggleBypass() }
                        .show()
                } else {
                    // Turning OFF: disable immediately.
                    toggleBypass()
                }
            }
        )

        return settingsListPage(
            title = getString(R.string.settings_title),
            subtitle = null,
            back = { goBack() },
            searchHint = getString(R.string.settings_search_hint),
            groups = listOf(
                SettingsGroup(getString(R.string.settings_group_car_apps), buildList {
                    add(settingsEntry(
                        getString(R.string.settings_car_connection),
                        getString(R.string.settings_car_connection_caption),
                        R.drawable.ic_tile_car,
                        accent = design.ACCENT
                    ) {
                        showPhoneScreen(PhoneScreen.CAR_CONNECTION)
                    })
                    addAll(duoScreenEntry())
                    addAll(projectionSetupEntry())
                    add(settingsEntry(
                        getString(R.string.settings_app_profiles),
                        getString(R.string.settings_app_profiles_caption_full),
                        R.drawable.ic_tile_apps,
                        accent = design.ACCENT_SYSTEM
                    ) {
                        appsFavoritesOnly = true
                        showPhoneScreen(PhoneScreen.APPS)
                    })
                    add(settingsEntry(
                        getString(R.string.settings_display_mirror),
                        getString(R.string.settings_display_mirror_caption),
                        R.drawable.ic_tile_mirror,
                        accent = design.ACCENT
                    ) {
                        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
                    })
                    add(settingsEntry(
                        getString(R.string.settings_input_touch),
                        getString(R.string.settings_input_touch_caption),
                        R.drawable.ic_tile_touch,
                        accent = design.ACCENT
                    ) {
                        showPhoneScreen(PhoneScreen.INPUT_TOUCH)
                    })
                }),
                // The driver's own paperwork on the car: what it burns, what it is due for, and a copy of both.
                SettingsGroup(getString(R.string.settings_group_utilities), buildList {
                    add(settingsEntry(
                        getString(R.string.fuel_title),
                        getString(R.string.fuel_settings_caption),
                        R.drawable.ic_tile_car,
                        accent = design.ACCENT_RADIO
                    ) { startActivity(dev.autobridge.fuel.FuelLogActivity.intent(this@MainActivity)) })
                    add(settingsEntry(
                        getString(R.string.maint_title),
                        getString(R.string.maint_settings_caption),
                        R.drawable.ic_tile_settings,
                        accent = design.ACCENT_RADIO
                    ) { startActivity(dev.autobridge.maintenance.MaintenanceActivity.intent(this@MainActivity)) })
                    add(settingsEntry(
                        getString(R.string.break_reminder_title),
                        breakReminderCaption(),
                        R.drawable.ic_tile_remote,
                        accent = design.ACCENT_FAVORITE
                    ) { showBreakReminderChoice() })
                    add(settingsEntry(
                        getString(R.string.costs_title),
                        getString(R.string.costs_settings_caption),
                        R.drawable.ic_tile_playlist,
                        accent = design.ACCENT_FILES
                    ) { startActivity(dev.autobridge.expense.CostsActivity.intent(this@MainActivity)) })
                    add(settingsEntry(
                        getString(R.string.parking_title),
                        getString(R.string.parking_settings_caption),
                        R.drawable.ic_tile_car,
                        accent = design.ACCENT_TV
                    ) { startActivity(dev.autobridge.parking.ParkingActivity.intent(this@MainActivity)) })
                    add(settingsEntry(
                        getString(R.string.emergency_title),
                        getString(R.string.emergency_settings_caption),
                        R.drawable.ic_tile_touch,
                        accent = design.ACCENT_VIDEO
                    ) { startActivity(dev.autobridge.emergency.EmergencyActivity.intent(this@MainActivity)) })
                    add(settingsEntry(
                        getString(R.string.backup_title),
                        getString(R.string.backup_settings_caption),
                        R.drawable.ic_tile_folder,
                        accent = design.ACCENT_SYSTEM
                    ) { startActivity(dev.autobridge.backup.BackupActivity.intent(this@MainActivity)) })
                }),
                SettingsGroup(getString(R.string.settings_group_playback_web), listOf(
                    settingsEntry(
                        getString(R.string.settings_video),
                        getString(R.string.settings_video_caption_full),
                        R.drawable.ic_tile_tv,
                        accent = design.ACCENT_VIDEO
                    ) {
                        startActivity(dev.autobridge.library.VideoSettingsActivity.intent(this))
                    },
                    settingsEntry(
                        getString(R.string.settings_youtube),
                        getString(R.string.settings_youtube_caption_full),
                        R.drawable.ic_tile_youtube,
                        accent = design.ACCENT_FAVORITE
                    ) {
                        startActivity(dev.autobridge.youtube.YouTubeSettingsActivity.intent(this))
                    },
                    settingsEntry(
                        getString(R.string.settings_browser),
                        getString(R.string.settings_browser_caption),
                        R.drawable.ic_tile_web,
                        accent = design.ACCENT_WEB
                    ) {
                        openBrowserSettings()
                    }
                )),
                SettingsGroup(
                    label = getString(R.string.settings_group_safety),
                    customRows = listOf(bypassRow)
                ),
                SettingsGroup(getString(R.string.settings_group_general), listOf(
                    settingsValueEntry(
                        getString(R.string.agent_screen_title),
                        getString(R.string.agent_screen_subtitle)
                    ) {
                        showPhoneScreen(PhoneScreen.AGENT_COMMANDS)
                    },
                    settingsEntry(
                        getString(R.string.voice_settings_title),
                        getString(R.string.voice_settings_caption),
                        R.drawable.ic_car_mic
                    ) {
                        startActivity(dev.autobridge.voice.VoiceSettingsActivity.intent(this))
                    },
                    settingsValueEntry(
                        getString(R.string.settings_language),
                        getString(AppLocale.selectedOption(this).labelRes)
                    ) { chooseLanguage() },
                    settingsEntry(
                        getString(R.string.settings_advanced),
                        getString(R.string.settings_advanced_caption),
                        R.drawable.ic_tile_debug
                    ) {
                        showPhoneScreen(PhoneScreen.ADVANCED)
                    },
                    settingsEntry(
                        getString(R.string.settings_send_log),
                        getString(R.string.settings_send_log_caption),
                        R.drawable.ic_tile_debug
                    ) { maintenance.sendLogReport() },
                    settingsEntry(
                        getString(R.string.settings_about),
                        getString(R.string.settings_about_caption),
                        R.drawable.ic_tile_settings
                    ) {
                        showPhoneScreen(PhoneScreen.ABOUT)
                    }
                ))
            )
        )
    }

    /**
     * The Duo Screen row, present only in the sideload flavors that actually ship the feature.
     *
     * Resolved by component name rather than referenced directly: the activity and its strings live
     * in a flavor-specific source set, so the safe (Play) build has neither, and a direct reference
     * would not compile there. Uses explicit string resources from the shared strings.xml so the
     * text matches the design.
     */
    private fun duoScreenEntry(): List<SettingsRow> {
        val intent = duoScreenIntent() ?: return emptyList()
        return listOf(
            settingsEntry(
                getString(R.string.settings_duo_screen),
                getString(R.string.settings_duo_screen_caption),
                R.drawable.ic_tile_settings,
                accent = dev.autobridge.ui.AutoBridgeDesign.ACCENT_SYSTEM
            ) { startActivity(intent) }
        )
    }

    /**
     * Bridge Web / Bridge Mirror on Android Auto: re-running the install-source step after an
     * update, and listing or unlisting Bridge Mirror. Resolved by name like [duoScreenIntent], and
     * absent from the safe build, which has no projection route.
     */
    private fun projectionSetupEntry(): List<SettingsRow> {
        val intent = Intent().setClassName(this, "dev.autobridge.projection.ProjectionSetupActivity")
            .takeIf { packageManager.resolveActivity(it, 0) != null } ?: return emptyList()
        return listOf(
            settingsEntry(
                getString(R.string.settings_projection_setup),
                getString(R.string.settings_projection_setup_caption),
                R.drawable.ic_tile_settings,
                accent = dev.autobridge.ui.AutoBridgeDesign.ACCENT_SYSTEM
            ) { startActivity(intent) }
        )
    }

    /**
     * Resolved by component name rather than referenced directly: the activity lives in a
     * flavor-specific source set, so the safe (Play) build does not have it and a direct
     * reference would not compile there. Null when this build/device has no Duo Screen.
     */
    private fun duoScreenIntent(): Intent? {
        val intent = Intent().setClassName(this, "dev.autobridge.projection.DuoScreenSettingsActivity")
        return intent.takeIf { packageManager.resolveActivity(it, 0) != null }
    }

    /** Settings > Advanced: the developer tools, kept out of the main Settings list. */
    private fun buildAdvancedScreen(): View = settingsListPage(
        title = getString(R.string.settings_advanced),
        subtitle = getString(R.string.advanced_subtitle),
        back = { goBack() },
        groups = listOf(
            SettingsGroup("", listOf(
                settingsEntry(
                    getString(R.string.advanced_diagnostics),
                    getString(R.string.advanced_diagnostics_caption),
                    R.drawable.ic_tile_debug
                ) {
                    showPhoneScreen(PhoneScreen.DEVELOPER)
                },
                settingsEntry(
                    getString(R.string.advanced_debug),
                    getString(R.string.advanced_debug_caption),
                    R.drawable.ic_tile_settings
                ) {
                    showPhoneScreen(PhoneScreen.DEBUG)
                },
                settingsEntry(
                    getString(R.string.advanced_storage),
                    getString(R.string.advanced_storage_caption),
                    R.drawable.ic_tile_settings
                ) {
                    startActivity(dev.autobridge.library.StorageSettingsActivity.intent(this))
                }
            ))
        )
    )

    /** Flips the safety bypass and rebuilds Settings so the switch row reflects the new state. */
    private fun toggleBypass() {
        val nowOn = dev.autobridge.safety.BypassPolicyStore.toggle()
        Toast.makeText(
            this,
            getString(if (nowOn) R.string.bypass_toast_on else R.string.bypass_toast_off),
            Toast.LENGTH_SHORT
        ).show()
        showPhoneScreen(PhoneScreen.SETTINGS, force = true)
    }

    /**
     * Settings > About, in three groups rather than one list of eight rows.
     *
     * The rows answer three different questions - how to say thanks, what this build is, and whose
     * work it stands on - and reading them as one column meant a donation row sat between the
     * licenses and the version number. Grouping also puts "Developed by" next to the two support
     * rows, where a person who has just decided to buy the coffee looks for who they are buying it
     * for.
     */
    private fun buildAboutScreen(): View = settingsListPage(
        title = getString(R.string.settings_about),
        subtitle = "AutoBridge ${BuildConfig.VERSION_NAME}",
        back = { goBack() },
        groups = listOf(
            SettingsGroup(getString(R.string.about_group_support), buildList {
                add(settingsEntry(
                    getString(R.string.about_support),
                    getString(R.string.about_support_caption),
                    R.drawable.ic_tile_favorite
                ) {
                    openInAppUrl(SUPPORT_URL)
                })
                // PromptPay donate QR. Off-store payment prompts breach Google Play policy, so the
                // row only exists on the sideload flavors (personal/lab), never on `safe`.
                if (BuildConfig.AUTOBRIDGE_MODE != "SAFE") {
                    add(settingsEntry(
                        getString(R.string.about_promptpay),
                        getString(R.string.about_promptpay_caption),
                        R.drawable.ic_tile_favorite
                    ) {
                        showPromptPayDialog()
                    })
                }
                add(settingsEntry(
                    getString(R.string.about_developer),
                    getString(R.string.about_developer_caption),
                    R.drawable.ic_tile_favorite
                ) {
                    Toast.makeText(this@MainActivity, getString(R.string.about_developer_caption), Toast.LENGTH_SHORT).show()
                })
            }),
            SettingsGroup(getString(R.string.about_group_app), listOf(
                settingsEntry(
                    getString(R.string.about_check_update),
                    updateCheckCaption(),
                    R.drawable.ic_tile_remote
                ) {
                    checkForUpdates()
                },
                settingsEntry(
                    getString(R.string.about_github),
                    "github.com/guitar-dev-io/autobridge",
                    R.drawable.ic_tile_web
                ) {
                    openInAppUrl(GITHUB_URL)
                },
                settingsEntry(
                    getString(R.string.about_facebook),
                    getString(R.string.about_facebook_caption),
                    R.drawable.ic_tile_web
                ) {
                    openInAppUrl(FACEBOOK_URL)
                }
            )),
            SettingsGroup(getString(R.string.about_group_credits), listOf(
                settingsEntry(
                    getString(R.string.about_credits),
                    getString(R.string.about_credits_caption),
                    R.drawable.ic_tile_folder
                ) {
                    showCreditsDialog()
                },
                settingsEntry(
                    getString(R.string.about_licenses),
                    getString(R.string.about_licenses_caption),
                    R.drawable.ic_tile_folder
                ) {
                    showOpenSourceLicenses()
                }
            ))
        )
    )

    /** Settings &gt; About: full attribution for the open-source and third-party sources. */
    private fun showCreditsDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_credits_title))
            .setMessage(getString(R.string.about_credits_body))
            .setPositiveButton(getString(R.string.about_credits_close), null)
            .show()
    }

    /**
     * PromptPay donate QR. The image is a bundled drawable (never fetched over the network, so it
     * cannot be swapped out from under the user). Only reachable on the sideload flavors — the
     * About row that opens it is gated out of `safe` to stay within Google Play's payment policy.
     */
    private fun showPromptPayDialog() {
        val image = ImageView(this).apply {
            setImageResource(R.drawable.promptpay_qr)
            adjustViewBounds = true
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            contentDescription = getString(R.string.about_promptpay_title)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_promptpay_title))
            .setMessage(getString(R.string.about_promptpay_message))
            .setView(image)
            .setPositiveButton(getString(R.string.about_promptpay_close), null)
            .show()
    }

    /**
     * Opens [url] in AutoBridge's own browser, so a link from the app (About, support, the
     * release page) does not throw the user out to another app. Falls back to the phone's
     * browser only when it is not a web page this browser loads.
     */
    private fun openInAppUrl(url: String) {
        val page = dev.autobridge.entertainment.ContentAddress.https(url) ?: return openExternalUrl(url)
        val opened = runCatching {
            startActivity(browserScreenIntent().setData(android.net.Uri.parse(page)))
            true
        }.getOrDefault(false)
        if (!opened) openExternalUrl(url)
    }

    /** The phone's own browser: for what this app's browser should not handle (an APK download). */
    private fun openExternalUrl(url: String) {
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            true
        }.getOrDefault(false)
        if (!opened) {
            Toast.makeText(this, getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * What the "Check for updates" row says before it is tapped: the installed version plus the
     * previous check's answer, or an invitation when there has not been one. This row is the only
     * place the installed version is shown in About, so it carries both jobs rather than repeating
     * the number in a separate row. [UpdateChecker] never runs on its own, so until the user asks
     * there is nothing to report beyond the version itself.
     */
    private fun updateCheckCaption(): String {
        if (updateCheckRunning) return getString(R.string.about_check_update_checking)
        val current = BuildConfig.VERSION_NAME
        val last = UpdateChecker.lastCheck(this)
            ?: return getString(R.string.about_check_update_caption, current)
        val checkedAt = android.text.format.DateUtils.getRelativeTimeSpanString(
            last.checkedAtEpochMillis,
            System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS
        )
        return if (last.updateAvailable) {
            getString(R.string.about_check_update_caption_available, last.latestVersion, current, checkedAt)
        } else {
            getString(R.string.about_check_update_caption_latest, last.latestVersion, checkedAt)
        }
    }

    /** True while About's "Check for updates" is waiting on GitHub, so a second tap does not stack. */
    private var updateCheckRunning = false

    private fun checkForUpdates() {
        if (updateCheckRunning) return
        updateCheckRunning = true
        // A visible "loading" while GitHub answers: the check can take a few seconds on a slow
        // connection, and a single toast left no sign that anything was still happening.
        val progress = updateProgressDialog()
        if (currentScreen == PhoneScreen.ABOUT) showPhoneScreen(PhoneScreen.ABOUT, force = true)
        UpdateChecker.checkAsync(this) { result ->
            updateCheckRunning = false
            // The check outlives a back press or a rotation, so the activity may be gone by now.
            if (isFinishing || isDestroyed) return@checkAsync
            runCatching { progress.dismiss() }
            if (result is UpdateChecker.Result.Available) homeUpdate = result
            when (result) {
                is UpdateChecker.Result.Available -> showUpdateDialog(result)
                is UpdateChecker.Result.UpToDate -> Toast.makeText(
                    this,
                    getString(R.string.about_check_update_up_to_date, result.latestVersion),
                    Toast.LENGTH_SHORT
                ).show()
                is UpdateChecker.Result.Failed -> Toast.makeText(
                    this,
                    getString(R.string.about_check_update_failed, result.reason),
                    Toast.LENGTH_LONG
                ).show()
            }
            // Redraw so the row caption carries what was just learned.
            if (currentScreen == PhoneScreen.ABOUT) showPhoneScreen(PhoneScreen.ABOUT, force = true)
        }
    }

    /** A small non-blocking dialog with a spinner and "Checking for updates…". */
    private fun updateProgressDialog(): android.app.AlertDialog {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(android.widget.ProgressBar(this@MainActivity).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.about_check_update_checking)
                textSize = 16f
                setPadding(dp(16), 0, 0, 0)
            })
        }
        return android.app.AlertDialog.Builder(this).setView(row).setCancelable(true).show()
    }

    /**
     * Home's own update check: once per app process, sideload builds only (the Play build is
     * updated by the store), and skipped when the last answer was "up to date" and recent. A newer
     * release becomes the card at the top of Home rather than a dialog over it.
     */
    private fun maybeCheckUpdateFromHome() {
        if (BuildConfig.AUTOBRIDGE_MODE == "SAFE" || homeUpdateChecked) return
        homeUpdateChecked = true
        val last = UpdateChecker.lastCheck(this)
        if (last != null && !last.updateAvailable &&
            System.currentTimeMillis() - last.checkedAtEpochMillis < HOME_UPDATE_RECHECK_MS
        ) return
        UpdateChecker.checkAsync(this) { result ->
            if (isFinishing || isDestroyed) return@checkAsync
            if (result !is UpdateChecker.Result.Available) return@checkAsync
            val dismissed = getSharedPreferences(PREFS_HOME_UPDATE, MODE_PRIVATE).getString(KEY_DISMISSED_VERSION, null)
            if (dismissed == result.release.versionName) return@checkAsync
            homeUpdate = result
            if (currentScreen == PhoneScreen.HOME) showPhoneScreen(PhoneScreen.HOME, force = true)
        }
    }

    /** Hides Home's update card for this release; a later release shows it again. */
    private fun dismissHomeUpdate() {
        homeUpdate?.let { update ->
            getSharedPreferences(PREFS_HOME_UPDATE, MODE_PRIVATE).edit()
                .putString(KEY_DISMISSED_VERSION, update.release.versionName).apply()
        }
        homeUpdate = null
        if (currentScreen == PhoneScreen.HOME) showPhoneScreen(PhoneScreen.HOME, force = true)
    }

    /**
     * Offers the newer release without installing anything itself: the APK is downloaded and
     * handed to the installer the user picks, which is where a decision to replace this app belongs.
     */
    private fun showUpdateDialog(update: UpdateChecker.Result.Available) {
        val release = update.release
        val notes = release.notes.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(12)
            .joinToString("\n")
            .take(600)
        val message = buildString {
            append(getString(R.string.about_update_message, release.versionName, update.currentVersion))
            if (notes.isNotEmpty()) append("\n\n").append(notes)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_update_title))
            .setMessage(message)
            .setNeutralButton(getString(R.string.about_update_release_page)) { _, _ ->
                openInAppUrl(release.pageUrl)
            }
            .setNegativeButton(getString(R.string.about_update_later), null)
            .setPositiveButton(getString(R.string.about_update_download)) { _, _ ->
                val apkUrl = release.apkUrl
                if (apkUrl == null) openInAppUrl(release.pageUrl) else maintenance.downloadAndOfferInstall(apkUrl)
            }
            .show()
    }

    /**
     * Opens the generated Play-services license list when present, otherwise the LICENSE on
     * GitHub, so the row is never a dead end.
     */
    private fun showOpenSourceLicenses() {
        val opened = runCatching {
            startActivity(Intent(this, Class.forName("com.google.android.gms.oss.licenses.OssLicensesMenuActivity")))
            true
        }.getOrDefault(false)
        if (!opened) openInAppUrl("$GITHUB_URL/blob/main/LICENSE")
    }

    /**
     * Settings > Advanced > Debug: the internal values (mode, environment such as REAL_CAR/DHU,
     * raw vehicle state, renderer) that user-facing screens deliberately do not show. Refreshed
     * by the 1s status tick.
     */
    private fun buildDebugScreen(): View {
        val content = screenContent()
        content.addView(
            screenHeader(
                getString(R.string.advanced_debug),
                getString(R.string.advanced_debug_subtitle),
                back = { goBack() }
            )
        )
        val state = TextView(this).apply {
            debugStateView = this
            textSize = 13f
            setTextColor(COLOR_TEXT)
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
            text = debugStateText()
        }
        addCard(content, state, top = 4)
        addCard(content, actionCard(getString(R.string.diag_copy)) { copyDiagnosticsToClipboard() }, top = 12)
        return screenScroll(content)
    }

    private fun debugStateText(): String {
        val runtime = RuntimeContextStore.context.value
        return buildString {
            appendLine("Mode:          ${runtime.mode.name}")
            appendLine("Environment:   ${runtime.environment.name}")
            appendLine("Connected:     ${runtime.connected}")
            appendLine("Vehicle state: ${runtime.vehicleState.name}")
            appendLine("Vehicle:       ${runtime.vehicleProfile?.name ?: "—"}")
            appendLine("Feature:       ${runtime.currentFeature?.name ?: "—"}")
            appendLine("Pipeline:      ${ScreenOffController.pipelineMode.name}")
            appendLine("Mirroring:     ${MirrorCoordinator.isMirroring}")
            append("Dev mode:      ${DevMode.isEnabled}")
        }
    }

    private fun composeScreen(content: @androidx.compose.runtime.Composable () -> Unit): View =
        ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { AutoBridgePhoneTheme { content() } }
        }

    /**
     * Control: on-the-car transport, one Open/search-or-Type input, Quick Actions and Queue /
     * Recent / Favorites — the former Control tab, Bridge controller and History button, merged.
     */
    private fun buildControlScreen(): View = composeScreen {
        dev.autobridge.ui.ControlScreen(
            context = this@MainActivity,
            onBack = { goBack() },
            onOpenHistory = { showPhoneScreen(PhoneScreen.CONTROL_HISTORY) },
            onOpenConnection = { showPhoneScreen(PhoneScreen.CAR_CONNECTION) }
        )
    }

    private fun buildCommandHistoryScreen(): View = composeScreen {
        dev.autobridge.ui.CommandHistoryScreen(context = this@MainActivity, onBack = { goBack() })
    }

    private fun buildAgentCommandsScreen(): View = composeScreen {
        dev.autobridge.ui.AgentCommandsScreen(
            context = this@MainActivity,
            onBack = { goBack() },
            onOpenHistory = { showPhoneScreen(PhoneScreen.CONTROL_HISTORY) }
        )
    }

    /**
     * Settings > Input & Touch. The touch rows used to sit inside Mirror settings; they are the
     * same rows and handlers, only grouped on their own page.
     */
    private fun buildInputTouchScreen(): View {
        val content = screenContent()
        content.addView(
            screenHeader(
                getString(R.string.settings_input_touch),
                getString(R.string.input_subtitle),
                back = { goBack() }
            )
        )
        content.addView(sectionLabel(getString(R.string.input_section_touch)))
        addCard(
            content,
            settingRow(
                getString(R.string.input_touch_control),
                getString(R.string.input_touch_control_caption),
                getString(R.string.input_open),
                onClick = { openTouchSettings() }
            ),
            top = 4
        )
        addCard(
            content,
            settingRow(
                getString(R.string.input_backend),
                getString(R.string.input_backend_caption),
                inputBackendLabel(),
                onClick = { openTouchSettings() }
            ),
            top = 8
        )
        addCard(
            content,
            settingSwitchRow(
                getString(R.string.input_real_touch),
                getString(R.string.input_real_touch_caption),
                checked = MirrorSettings.realTouchEnabled,
                enabled = ShizukuInputBackend.isRealTouchAvailable || MirrorSettings.realTouchEnabled
            ) { enabled -> onRealTouchToggled(enabled) },
            top = 8
        )
        shizukuTouchCard()?.let { addCard(content, it, top = 8) }
        return screenScroll(content)
    }

    private fun buildAppsScreen(): View {
        val content = screenContent()
        // "App launcher" was misleading: tapping a row opens that app's profile, it does not launch
        // it. The star on each row is what adds it to Favorites (stored in QuickAppsStore, the one favorites list).
        content.addView(
            // Apps & profiles: reached from Settings now, no longer a bottom tab.
            screenHeader(getString(R.string.apps_title), getString(R.string.apps_subtitle), back = { goBack() })
        )

        val search = EditText(this).apply {
            hint = getString(R.string.apps_search_hint)
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
            styleFilter(favorites, getString(R.string.apps_filter_favorites, quickCount), appsFavoritesOnly)
            styleFilter(
                allApps,
                getString(R.string.apps_filter_all, installed.size),
                !appsFavoritesOnly
            )
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
                back = { goBack() },
                action = (if (isQuickApp) "★" else "☆") to {
                    QuickAppsStore.setEnabled(this, app, !isQuickApp, profileId)
                    Toast.makeText(
                        this,
                        getString(
                            if (isQuickApp) R.string.profiles_removed_from_favorites
                            else R.string.profiles_added_to_favorites
                        ),
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

        addCard(
            content,
            settingRow(
                getString(R.string.profiles_smart_mode),
                decision.reason,
                decision.mode.name
            ),
            top = 8
        )
        
        // ปลดล็อกการเรียกเปิดแอปโดยตรง
        addCard(content, actionCard(getString(R.string.profiles_use_profile), primary = true) {
            if (!QuickAppLauncher.launch(this, app.packageName, profileId)) {
                Toast.makeText(
                    this,
                    getString(R.string.profiles_launch_failed, app.label),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, top = 12)

        content.addView(sectionLabel(getString(R.string.profiles_section_launch)))
        addCard(
            content,
            settingSwitchRow(
                getString(R.string.profiles_auto_mirror),
                getString(R.string.profiles_auto_mirror_caption),
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
                getString(R.string.profiles_auto_fullscreen),
                getString(R.string.profiles_auto_fullscreen_caption),
                checked = profile.autoFullscreen,
                enabled = true
            ) { enabled ->
                PerAppProfileStore.save(this, profile.copy(autoFullscreen = enabled))
                showPhoneScreen(PhoneScreen.PROFILE)
            },
            top = 8
        )

        content.addView(sectionLabel(getString(R.string.mirror_section_display)))
        addCard(
            content,
            settingSwitchRow(
                getString(R.string.profiles_force_landscape),
                getString(R.string.profiles_force_landscape_caption),
                checked = profile.rotationMode == RotationMode.LANDSCAPE,
                enabled = true
            ) { enabled ->
                PerAppProfileStore.setForceLandscape(this, app.packageName, enabled, profileId)
                if (enabled && !QuickAppLauncher.hasLandscapePermission(this)) {
                    Toast.makeText(
                        this,
                        getString(R.string.profiles_needs_write_settings),
                        Toast.LENGTH_LONG
                    ).show()
                    QuickAppLauncher.requestLandscapePermission(this)
                }
            },
            top = 4
        )
        addCard(
            content,
            settingRow(
                getString(R.string.profiles_scale),
                getString(R.string.profiles_stored_preference),
                profile.scaleMode.name,
                onClick = { cycleProfileScale(app.packageName, profileId) }
            ),
            top = 8
        )
        addCard(
            content,
            settingRow(
                getString(R.string.profiles_resolution),
                getString(R.string.profiles_stored_preference),
                profile.preferredResolution?.name ?: ResolutionPreset.AUTO.name,
                onClick = { cycleProfileResolution(app.packageName, profileId) }
            ),
            top = 8
        )
        addCard(
            content,
            settingRow(
                getString(R.string.profiles_frame_rate),
                getString(R.string.profiles_stored_renderer_target),
                profile.preferredFps?.let { "$it FPS" } ?: getString(R.string.profiles_fps_system),
                onClick = { cycleProfileFps(app.packageName, profileId) }
            ),
            top = 8
        )

        content.addView(sectionLabel(getString(R.string.profiles_section_touch_audio)))
        addCard(
            content,
            settingSwitchRow(
                getString(R.string.input_touch_control),
                getString(R.string.profiles_touch_backend_caption, inputBackendLabel()),
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
                getString(R.string.profiles_audio_output),
                getString(R.string.profiles_audio_output_caption),
                profile.audioMode.name,
                onClick = { cycleProfileAudio(app.packageName, profileId) }
            ),
            top = 8
        )
        addCard(
            content,
            settingSwitchRow(
                getString(R.string.profiles_keep_screen_on),
                getString(R.string.profiles_keep_screen_on_caption),
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
            actionCard(getString(R.string.profiles_reset), destructive = true) {
                PerAppProfileStore.reset(this, app.packageName, profileId)
                Toast.makeText(
                    this,
                    getString(R.string.profiles_reset_done),
                    Toast.LENGTH_SHORT
                ).show()
                showPhoneScreen(PhoneScreen.PROFILE)
            },
            top = 16
        )
        return screenScroll(content)
    }

    private fun buildMirrorSettingsScreen(): View {
        val content = screenContent()
        content.addView(
            screenHeader(
                getString(R.string.settings_display_mirror),
                getString(R.string.mirror_subtitle),
                back = { goBack() }
            )
        )

        // Phone-side projection start (needs the MediaProjection consent prompt on this phone).
        // It used to be the Control Center's START MIRROR button.
        addCard(
            content,
            actionCard(
                getString(
                    if (MirrorCoordinator.isMirroring) R.string.mirror_mirroring
                    else R.string.mirror_start
                ),
                primary = true
            ) {
                requestScreenCapture()
            },
            top = 4
        )

        content.addView(sectionLabel(getString(R.string.mirror_section_display)))
        addCard(
            content,
            settingRow(
                getString(R.string.mirror_resolution),
                getString(R.string.mirror_resolution_caption),
                getString(R.string.mirror_value_auto)
            ),
            top = 4
        )
        addCard(
            content,
            settingRow(getString(R.string.mirror_frame_rate), frameRateSubtitle(), frameRateValue()),
            top = 8
        )
        addCard(
            content,
            settingRow(
                getString(R.string.mirror_renderer),
                getString(R.string.mirror_renderer_caption),
                ScreenOffController.pipelineMode.name,
                onClick = { cyclePipelineMode() }
            ),
            top = 8
        )
        addCard(content, scaleModeOptions(), top = 8)
        addCard(
            content,
            settingRow(
                getString(R.string.mirror_rotation),
                mirrorRotationSubtitle(),
                mirrorRotationValue(),
                onClick = { cycleMirrorRotation() }
            ),
            top = 8
        )
        addCard(
            content,
            settingSwitchRow(
                getString(R.string.mirror_landscape_global),
                getString(R.string.mirror_landscape_global_caption),
                checked = MirrorOrientationController.isEnabled,
                enabled = true
            ) { toggleLandscapeMirror() },
            top = 8
        )
        addCard(
            content,
            settingRow(
                getString(R.string.mirror_crop),
                getString(R.string.mirror_crop_caption),
                getString(R.string.mirror_value_auto)
            ),
            top = 8
        )

        content.addView(sectionLabel(getString(R.string.mirror_section_advanced)))
        addCard(
            content,
            settingRow(
                getString(R.string.mirror_head_unit_profile),
                headUnitProfileSubtitle(),
                SurfaceProfile.active.displayLabel,
                onClick = { toggleSurfaceProfile() }
            ),
            top = 4
        )
        addCard(
            content,
            settingRow(
                getString(R.string.mirror_screen_off),
                ScreenOffController.statusLabel(),
                screenOffBehaviorValue()
            ),
            top = 8
        )
        addCard(
            content,
            settingSwitchRow(
                getString(R.string.mirror_panel_off),
                getString(R.string.mirror_panel_off_caption),
                checked = MirrorSettings.screenOffOnAutoDim,
                enabled = ScreenOffController.panelOffAvailable() || MirrorSettings.screenOffOnAutoDim
            ) { enabled -> onPanelOffToggled(enabled) },
            top = 8
        )
        addCard(content, settingRow(
            getString(R.string.mirror_dim_now),
            ScreenPowerController.statusLabel(),
            getString(R.string.mirror_apply),
            onClick = {
                val applied = ScreenPowerController.dimNow()
                Toast.makeText(
                    this,
                    if (applied) ScreenPowerController.statusLabel()
                    else getString(R.string.mirror_start_first),
                    Toast.LENGTH_SHORT
                ).show()
                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
            }
        ), top = 8)
        addCard(content, settingRow(
            getString(R.string.mirror_restore_screen),
            getString(R.string.mirror_restore_screen_caption),
            getString(R.string.mirror_restore),
            onClick = {
                val restored = ScreenPowerController.restorePhoneScreen()
                Toast.makeText(
                    this,
                    if (restored) getString(R.string.mirror_display_restored)
                    else ScreenPowerController.statusLabel(),
                    Toast.LENGTH_SHORT
                ).show()
                showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
            }
        ), top = 8)
        // Real touch injection, Input backend and the Shizuku card moved to Settings > Input & Touch.

        content.addView(sectionLabel(getString(R.string.mirror_section_automation)))
        addCard(
            content,
            settingSwitchRow(
                getString(R.string.mirror_prevent_sleep),
                getString(R.string.mirror_prevent_sleep_caption),
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
                getString(R.string.mirror_auto_dim),
                getString(R.string.mirror_auto_dim_caption),
                MirrorSettings.autoDimDelay.label,
                onClick = { cycleAutoDimDelay() }
            ),
            top = 8
        )
        addCard(
            content,
            settingSwitchRow(
                getString(R.string.mirror_stop_on_disconnect),
                getString(R.string.mirror_stop_on_disconnect_caption),
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
        content.addView(
            screenHeader(
                getString(R.string.advanced_diagnostics),
                getString(R.string.diag_subtitle),
                back = { goBack() }
            )
        )

        content.addView(sectionLabel(getString(R.string.diag_section_log)))
        val logView = TextView(this).apply {
            developerLogView = this
            textSize = 12f
            setTextColor(COLOR_TEXT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
            text = StructuredLog.format(limit = 30).ifEmpty { getString(R.string.diag_no_log) }
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
            actionCard("SHARE") { maintenance.sendLogReport() },
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

        content.addView(sectionLabel(getString(R.string.diag_section_mirror_events)))
        val mirrorEventsView = TextView(this).apply {
            developerMirrorEventsView = this
            textSize = 12f
            setTextColor(COLOR_TEXT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(COLOR_SURFACE, COLOR_BORDER)
            text = MirrorDiagnostics.format(limit = 30).ifEmpty { getString(R.string.diag_no_mirror_events) }
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
        addCard(content, mutedText(getString(R.string.drm_diagnostics_disclaimer)), top = 4)
        return screenScroll(content)
    }

    /**
     * Settings > Car & Connection (the old Devices tab). The vehicle card is live from
     * RuntimeContextStore; the rows reuse the existing Car setup activity and diagnostics page.
     */
    private fun buildCarConnectionScreen(): View = composeScreen {
        dev.autobridge.ui.CarConnectionScreen(
            onBack = { goBack() },
            links = listOf(
                dev.autobridge.ui.SettingsLink(R.drawable.ic_tile_car, "Connection", "Permissions and Android Auto readiness") {
                    startActivity(dev.autobridge.mirror.MirrorSetupActivity.intent(this))
                },
                dev.autobridge.ui.SettingsLink(R.drawable.ic_tile_remote, "Startup", "Start the media service when the car pairs over Bluetooth") {
                    startActivity(dev.autobridge.mirror.MirrorSetupActivity.intent(this))
                },
                dev.autobridge.ui.SettingsLink(R.drawable.ic_tile_debug, "Diagnostics", "Connection log, crash reports and mirror events") {
                    showPhoneScreen(PhoneScreen.DEVELOPER)
                }
            )
        )
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
                    text = getString(R.string.favorite)
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
                contentDescription = if (isQuickApp) "Remove from Favorites" else "Add to Favorites"
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
                        getString(
                            if (isQuickApp) R.string.profiles_removed_from_favorites
                            else R.string.profiles_added_to_favorites
                        ),
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

    /** Launches the phone browser and immediately opens its settings sheet. */
    private fun openBrowserSettings() {
        startActivity(
            browserScreenIntent().putExtra(
                dev.autobridge.browser.BrowserActivity.EXTRA_OPEN_SETTINGS, true
            )
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VOICE_SEARCH) {
            val spoken = data?.getStringArrayListExtra(
                android.speech.RecognizerIntent.EXTRA_RESULTS
            )?.firstOrNull()?.trim().orEmpty()
            if (resultCode == Activity.RESULT_OK && spoken.isNotEmpty()) {
                runPhoneVoiceCommand(spoken)
            }
            return
        }
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ProjectionService.start(this, resultCode, data)
                Toast.makeText(this, getString(R.string.mirror_started), Toast.LENGTH_SHORT).show()
                if (pendingEntertainmentLaunch) {
                    pendingEntertainmentLaunch = false
                    startActivity(browserScreenIntent())
                }
            } else {
                pendingEntertainmentLaunch = false
                Toast.makeText(this, getString(R.string.mirror_capture_denied), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun refreshStatus() {
        debugStateView?.text = debugStateText()
        developerLogView?.text = StructuredLog.format(limit = 30).ifEmpty { getString(R.string.diag_no_log) }
        developerMirrorEventsView?.text = MirrorDiagnostics.format(limit = 30).ifEmpty { getString(R.string.diag_no_mirror_events) }
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
            .onFailure {
                Toast.makeText(this, getString(R.string.diag_no_share_target), Toast.LENGTH_SHORT)
                    .show()
            }
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
            appendLine(StructuredLog.format(limit = 60).ifEmpty { getString(R.string.diag_no_log) })
            appendLine()
            appendLine("--- Mirror events ---")
            appendLine(MirrorDiagnostics.format(limit = 60).ifEmpty { getString(R.string.diag_no_mirror_events) })
        }
        val manager = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (manager == null) {
            Toast.makeText(this, getString(R.string.diag_clipboard_unavailable), Toast.LENGTH_SHORT)
                .show()
            return
        }
        manager.setPrimaryClip(android.content.ClipData.newPlainText("AutoBridge diagnostics", text))
        Toast.makeText(this, getString(R.string.diag_copied), Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
        }
    }

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
            Toast.makeText(
                this,
                getString(R.string.mirror_stop_before_renderer),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val next = when (ScreenOffController.pipelineMode) {
            ScreenOffController.PipelineMode.AUTO_MIRROR -> ScreenOffController.PipelineMode.SELF_DRAWN
            ScreenOffController.PipelineMode.SELF_DRAWN,
            ScreenOffController.PipelineMode.OWN_CONTENT -> ScreenOffController.PipelineMode.AUTO_MIRROR
        }
        if (!MirrorCoordinator.setPipelineMode(next)) {
            Toast.makeText(
                this,
                getString(R.string.mirror_renderer_unavailable),
                Toast.LENGTH_SHORT
            ).show()
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
                getString(R.string.mirror_needs_self_drawn),
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
        row.addView(boldText(getString(R.string.mirror_scale_mode), 14f))
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
                getString(R.string.mirror_needs_write_settings),
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
            Toast.makeText(
                this,
                getString(R.string.mirror_landscape_failed),
                Toast.LENGTH_LONG
            ).show()
        } else if (enable) {
            Toast.makeText(
                this,
                getString(R.string.mirror_global_lock_warning),
                Toast.LENGTH_LONG
            ).show()
        }
        showPhoneScreen(PhoneScreen.MIRROR_SETTINGS)
    }
    private fun headUnitProfileSubtitle() = "Display profile"
    private fun toggleSurfaceProfile() {}
    private fun screenOffBehaviorValue() = "Auto"
    private fun onPanelOffToggled(enabled: Boolean) {}
    /**
     * Turns the privileged pointer sink on or off, and says why when it cannot be turned on.
     *
     * The switch is drawn enabled while the setting is already on, so a user who turned it on when
     * Shizuku was connected can always turn it off again; that is the one case where this runs with
     * the backend unavailable, and turning OFF must still be allowed to go through.
     */
    private fun onRealTouchToggled(enabled: Boolean) {
        if (enabled && !ShizukuInputBackend.isRealTouchAvailable) {
            Toast.makeText(this, getString(R.string.input_real_touch_unavailable), Toast.LENGTH_LONG).show()
            // Redraw so the switch snaps back to off rather than sitting on a state nothing stored.
            showPhoneScreen(PhoneScreen.INPUT_TOUCH, force = true)
            return
        }
        SettingsStore.persistRealTouchEnabled(this, enabled)
        showPhoneScreen(PhoneScreen.INPUT_TOUCH, force = true)
    }

    /** What is actually carrying touches right now - Shizuku, the accessibility service, neither. */
    private fun inputBackendLabel() = TouchRouter.activeBackendLabel(this)
    /**
     * The accessibility service is how a tap on the car display becomes a tap on this phone, and
     * Play treats that use of the API as one the user must understand before granting. So the
     * explanation comes first and Android's accessibility settings open only if the user goes on.
     */
    private fun openTouchSettings() {
        PermissionDisclosure.show(
            this,
            R.string.a11y_disclosure_title,
            R.string.a11y_disclosure_body
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
    private fun shizukuTouchCard(): View? = null
    private fun cycleAutoDimDelay() {}
}
