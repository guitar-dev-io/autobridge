package dev.autobridge.library

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import dev.autobridge.R
import dev.autobridge.entertainment.ContentKind
import dev.autobridge.entertainment.EntertainmentActivity
import dev.autobridge.entertainment.WebBookmarkStore
import dev.autobridge.i18n.AppLocale
import dev.autobridge.iptv.IptvCatalog
import dev.autobridge.iptv.IptvCatalogData
import dev.autobridge.iptv.IptvCategory
import dev.autobridge.iptv.IptvDirectory
import dev.autobridge.iptv.IptvEntry
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
import dev.autobridge.iptv.IptvPinnedCategoryStore
import dev.autobridge.iptv.IptvPlayback
import dev.autobridge.iptv.IptvSource
import dev.autobridge.iptv.IptvSourceStore
import dev.autobridge.iptv.IptvSourceType
import dev.autobridge.iptv.StreamPing
import dev.autobridge.iptv.XtreamCredentials
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.MiniPlayer
import dev.autobridge.ui.SystemBack

/**
 * Phone host for the home grid's content sections: TV, Radio, Folders, Playlists, Gallery and
 * Favorites.
 *
 * One Activity with an in-memory page stack keeps navigation shallow and matches the rest of this
 * codebase's programmatic-view style. Every page is drawn with [AutoBridgeDesign], so the section's
 * accent colour carries from the home card through its header, rows and player.
 *
 * Playback is never implemented here: it goes to [PlayerActivity], which owns the MediaSession
 * attachment and the parked-state gate.
 */
class LibraryActivity : Activity() {
    /**
     * Which library content is showing. One Activity now covers all of them — see [chips] — so
     * this is "which chip is selected" as much as "which home tile opened this Activity".
     *
     * [FOLDERS], [PLAYLISTS] and [GALLERY] have no chip of their own; they are reached through
     * [FILES], but stay as their own [Section] so an external launch (the voice agent opens
     * [PLAYLISTS] directly) still lands on the right page with [FILES] highlighted.
     */
    enum class Section(@StringRes val titleRes: Int, val accent: Int) {
        TV(R.string.section_tv, AutoBridgeDesign.ACCENT_TV),
        RADIO(R.string.section_radio, AutoBridgeDesign.ACCENT_RADIO),
        MUSIC(R.string.home_tile_music, AutoBridgeDesign.ACCENT_VIDEO),
        STREAMING(R.string.section_streaming, AutoBridgeDesign.ACCENT_VIDEO),
        FILES(R.string.library_section_files, AutoBridgeDesign.ACCENT_FILES),
        FAVORITES(R.string.section_favorites, AutoBridgeDesign.ACCENT_FAVORITE),
        FOLDERS(R.string.section_folders, AutoBridgeDesign.ACCENT_FILES),
        PLAYLISTS(R.string.section_playlists, AutoBridgeDesign.ACCENT_FILES),
        GALLERY(R.string.section_gallery, AutoBridgeDesign.ACCENT_WEB);

        /** The chip / page name in the current UI language. */
        fun title(context: Context): String = context.getString(titleRes)
    }

    /** The chip row, in order. [FOLDERS]/[PLAYLISTS]/[GALLERY] fall under [Section.FILES]. */
    private val chips = listOf(
        Section.TV, Section.RADIO, Section.MUSIC, Section.STREAMING, Section.FILES
    )

    /** Which chip reads as selected for [section] — the three Files sub-pages included. */
    private fun chipFor(target: Section): Section = when (target) {
        Section.FOLDERS, Section.PLAYLISTS, Section.GALLERY -> Section.FILES
        else -> target
    }

    companion object {
        const val EXTRA_SECTION = "dev.autobridge.extra.LIBRARY_SECTION"
        private const val REQUEST_MEDIA = 4711

        /** The Streaming grid: icons to a row, and the size they are drawn at. */
        private const val STREAM_COLUMNS = 4
        private const val STREAM_ICON_PX = 168

        /** Below this a search field is clutter; above it, a long list is unusable without one. */
        private const val SEARCH_THRESHOLD = 12

        /** Entries a global category-page search matches, across the whole source. */
        private const val MAX_SEARCH_MATCHES = 120

        fun intent(context: android.content.Context, section: Section): Intent =
            Intent(context, LibraryActivity::class.java).putExtra(EXTRA_SECTION, section.name)
    }

    private val initialSection by lazy {
        runCatching { Section.valueOf(intent.getStringExtra(EXTRA_SECTION).orEmpty()) }
            .getOrDefault(Section.TV)
    }

    /** The chip/page on screen right now. Switched in place by [showSection] — no re-launch. */
    private var section: Section = Section.TV
    private val accent get() = section.accent
    private val iptvKind get() = if (section == Section.RADIO) IptvKind.RADIO else IptvKind.TV

    /** A url just sent to the car's video screen, so its tile can read "ON CAR" until another is sent. */
    private var lastSentToCarUrl: String? = null

    private lateinit var playback: MediaPlaybackClient
    private var miniPlayer: MiniPlayer? = null
    private var resumedOnce = false

    /** A permission is asked for at most once per visit; after that the user is sent to settings. */
    private var permissionAsked = false

    /** Rendered pages, most recent last. Back pops one; popping the root finishes the Activity. */
    private val stack = ArrayDeque<() -> Unit>()

    /** Undoes [SystemBack.register]; see that object for why Back needs registering at all. */
    private var releaseBack: () -> Unit = {}

    /**
     * The tiles of the entry page currently drawn, by stream URL, so a check result can be written
     * into the page instead of rebuilding it. Rebuilt whenever that page draws; a stale view left
     * in here after navigating away is only ever written to, never read from.
     */
    private val pingTiles = mutableMapOf<String, View>()

    /**
     * The entries page's header signal icon, so a check result landing can recolour it without
     * re-rendering the page. Null on any page that has no signal icon, which `render` leaves it as.
     */
    private var signalIndicator: TextView? = null

    /** Applies the Settings &gt; Language choice; see [AppLocale.rebase]. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        releaseBack = SystemBack.register(this) { goBack() }
        playback = MediaPlaybackClient(this)
        playback.connect()
        showSection(initialSection)
    }

    /**
     * Switches the chip row to [target] and draws its root page, clearing whatever page stack the
     * previous chip had built up — the same as if a fresh Activity had been launched for it, which
     * is what this replaces: tapping a chip no longer re-launches the Activity.
     */
    private fun showSection(target: Section) {
        section = target
        stack.clear()
        when (target) {
            Section.TV, Section.RADIO -> showLibraryHome()
            Section.MUSIC -> showMusic()
            Section.FILES -> showFiles()
            Section.FAVORITES -> showFavorites()
            Section.STREAMING -> showStreaming()
            Section.FOLDERS -> showFolders()
            Section.PLAYLISTS -> showPlaylists()
            Section.GALLERY -> showGalleryAlbums()
        }
    }

    override fun onResume() {
        super.onResume()
        miniPlayer?.start()
        // Coming back from the player should reflect a new "recently played" entry, but the very
        // first resume follows onCreate's own render, so re-drawing then is pure waste.
        if (resumedOnce) refresh() else resumedOnce = true
    }

    override fun onPause() {
        super.onPause()
        miniPlayer?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseBack()
        miniPlayer?.stop()
        playback.disconnect()
    }

    /**
     * Back goes up one page, not out of the section.
     *
     * The current page is the top of the stack, so dropping it and replaying the one beneath
     * restores the page the user came from without rebuilding the whole section. The previous page
     * stays on the stack: it is now the current one. The root page has nothing beneath it, so Back
     * there leaves the way the system would have.
     */
    private fun goBack() {
        stack.removeLastOrNull()
        val previous = stack.lastOrNull()
        if (previous == null) SystemBack.finishFromBack(this) else previous()
    }

    // Pre-33 devices only; everything newer comes through [SystemBack]. See its table.
    @Deprecated("Back is handled by SystemBack on API 33+", ReplaceWith("goBack()"))
    @Suppress("DEPRECATION")
    // The lint check wants this gone, but it is still the only Back a pre-33 device delivers;
    // SystemBack carries the versions that no longer call it.
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() = goBack()

    /** Renders [page] and records it so Back can replay the page beneath it. */
    private fun push(page: () -> Unit) {
        stack.addLast(page)
        page()
    }

    /** Re-renders the current page in place, e.g. after a source is added or a favourite toggled. */
    private fun refresh() {
        stack.lastOrNull()?.invoke()
    }

    /**
     * Assembles a page in the house style: header, optional pinned search/action row, a scrolling
     * body and the shared now-playing bar. Returns the built page so a caller that passed
     * [overlay] can look up the scroll view afterwards (see [AutoBridgeDesign.pageScroll]) and wire
     * up what the overlay does.
     */
    private fun render(
        title: String,
        subtitle: String?,
        rows: List<View>,
        empty: View? = null,
        onBack: () -> Unit = { goBack() },
        actions: List<Pair<String, () -> Unit>> = emptyList(),
        headerActions: List<AutoBridgeDesign.HeaderAction> = emptyList(),
        search: Pair<String, (String) -> Unit>? = null,
        extraPinned: List<View> = emptyList(),
        overlay: View? = null
    ): View {
        val body = AutoBridgeDesign.body(this)
        if (rows.isEmpty() && empty != null) body.addView(empty)
        else rows.forEach { body.stack(it) }

        val pinned = mutableListOf<View>()
        pinned += extraPinned
        if (search != null) {
            pinned += AutoBridgeDesign.searchField(this, getString(R.string.action_search), search.first, search.second)
        }
        if (actions.isNotEmpty()) {
            val row = LinearLayout(this)
            actions.forEachIndexed { index, (label, handler) ->
                row.addView(
                    AutoBridgeDesign.pill(this, label, primary = index == 0, accent = accent, onClick = handler),
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginStart = if (index == 0) 0 else dp(8)
                    }
                )
            }
            pinned += row
        }

        val bar = miniPlayer ?: MiniPlayer(this, playback) { openNowPlaying() }.also {
            miniPlayer = it
            it.start()
        }
        // The bar is reused across pages, so detach it from the page it was last attached to.
        (bar.view.parent as? android.view.ViewGroup)?.removeView(bar.view)
        val bottom = LinearLayout(this).apply {
            setPadding(dp(16), 0, dp(16), dp(12))
            addView(bar.view, LinearLayout.LayoutParams(-1, -2))
        }

        val header = AutoBridgeDesign.header(this, title, subtitle, onBack = onBack, actions = headerActions)
        signalIndicator = header.findViewWithTag(SIGNAL_ACTION_TAG)
        val page = AutoBridgeDesign.page(
            context = this,
            header = header,
            pinned = pinned,
            body = body,
            bottomBar = bottom,
            overlay = overlay
        )
        setContentView(page)
        return page
    }

    private fun openNowPlaying() {
        val playing = playback.currentTitle ?: return
        startActivity(
            PlayerActivity.intent(
                context = this,
                url = "",
                title = playing,
                subtitle = playback.currentArtist.orEmpty(),
                // Open as video when the session is actually on a video channel. Hardcoding false
                // forced the audio-only path, so tapping the now-playing bar on a TV channel kept
                // the sound but never reattached the video surface - picture gone, audio only.
                video = playback.hasVideo
            )
        )
    }

    /** "1 entry" / "13 categories": a count the user reads, not a template with an s stuck on. */
    private fun plural(@androidx.annotation.PluralsRes id: Int, count: Int): String =
        resources.getQuantityString(id, count, count)

    // ----- IPTV: sources -> categories -> entries -----

    /**
     * Root of the TV/Radio chips: the current source's categories, loaded straight away so there
     * is no extra "pick a source" step when one already exists — switching is the source card's
     * job now (see [sourceCard]). Re-runs in full on [refresh], so adding the first source from
     * the empty state hands off to the loaded page without a second tap.
     */
    private fun showLibraryHome() = push {
        val sources = IptvSourceStore.list(this, iptvKind)
        if (sources.isEmpty()) {
            render(
                title = getString(R.string.library_title),
                subtitle = getString(R.string.library_no_source, section.title(this)),
                rows = emptyList(),
                empty = AutoBridgeDesign.emptyState(
                    context = this,
                    title = getString(R.string.library_no_source, section.title(this)),
                    message = getString(R.string.library_no_source_message),
                    action = getString(R.string.library_browse_public_lists) to { addFromDirectory() },
                    accent = accent
                ),
                headerActions = listOf(AutoBridgeDesign.HeaderAction("+", onClick = { addSourceChooser() })),
                actions = listOf(
                    getString(R.string.library_public_lists) to { addFromDirectory() },
                    "+ Xtream" to { addSource(IptvSourceType.XTREAM) },
                    "+ M3U" to { addSource(IptvSourceType.M3U) }
                ),
                extraPinned = listOf(chipsRow())
            )
        } else {
            // A source now exists (e.g. just added from the empty state above) — hand off to the
            // loaded categories page and drop this page from the stack, so Back does not return
            // to a stale empty check sitting behind the one that actually loaded.
            stack.removeLastOrNull()
            openLibrarySource(sources.first(), sources)
        }
    }

    private fun openLibrarySource(source: IptvSource, allSources: List<IptvSource>) {
        val cached = IptvCatalog.cached(source.id)
        if (cached != null) {
            showLibraryCategories(source, allSources, cached)
            return
        }
        val dialog = progressDialog(getString(R.string.library_loading, source.name))
        IptvCatalog.load(this, source) { result ->
            dialog.dismiss()
            when (result) {
                is IptvCatalog.Result.Ready -> showLibraryCategories(source, allSources, result.data)
                is IptvCatalog.Result.Failed -> alert(getString(R.string.library_load_failed, source.name), result.message)
            }
        }
    }

    /**
     * Source card, search across the whole source, pinned categories + Last watched, and the full
     * category list grouped A–Z with a right-edge letter index. A non-blank search replaces the
     * A–Z list with flat channel and category matches instead of narrowing it in place — the two
     * read as different modes (browse vs. find), which is what the categories page and the old
     * entries-page search already did separately.
     */
    private fun showLibraryCategories(source: IptvSource, allSources: List<IptvSource>, data: IptvCatalogData) {
        var query = ""
        var sortAscending = true
        lateinit var draw: () -> Unit
        draw = {
            val q = query.trim()
            val rows = mutableListOf<View>()
            var overlay: View? = null
            var scrollRef: ScrollView? = null

            if (q.isBlank()) {
                val pinnedIds = IptvPinnedCategoryStore.pinned(this, source.id)
                val pinnedCategories = data.categories.filter { it.id in pinnedIds }
                val hasRecent = IptvHistoryStore.recent(this, iptvKind).isNotEmpty()
                if (pinnedCategories.isNotEmpty() || hasRecent) {
                    rows += pinnedHeader(source, data)
                    rows += pinnedRow(source, data, pinnedCategories, hasRecent)
                }

                val grouped = data.categories
                    .groupBy { categoryLetter(it.name) }
                    .toSortedMap(
                        if (sortAscending) compareBy { categoryLetterSortKey(it) }
                        else compareByDescending { categoryLetterSortKey(it) }
                    )
                if (grouped.isNotEmpty()) rows += allCategoriesHeader(data.categories.size, sortAscending) {
                    sortAscending = !sortAscending
                    draw()
                }
                val anchors = mutableMapOf<String, View>()
                grouped.forEach { (letter, categories) ->
                    // Letter headers reuse the muted sectionLabel but override to accent so the
                    // A–Z spine reads as navigation, not just another quiet divider.
                    val header = AutoBridgeDesign.sectionLabel(this, letter)
                        .also { it.setTextColor(AutoBridgeDesign.ACCENT) }
                    anchors[letter] = header
                    rows += header
                    categories.forEach { category -> rows += categoryRow(source, data, category) }
                }
                // A short list fits on screen without help; the index would just be clutter.
                if (grouped.size > 3) {
                    overlay = letterIndex(grouped.keys.toList()) { letter ->
                        val target = anchors[letter] ?: return@letterIndex
                        scrollRef?.post { scrollRef?.smoothScrollTo(0, target.top) }
                    }
                }
            } else {
                val matchingEntries = data.entries
                    .filter { it.title.contains(q, ignoreCase = true) }
                    .take(MAX_SEARCH_MATCHES)
                val matchingCategories = data.categories.filter { it.name.contains(q, ignoreCase = true) }
                if (matchingEntries.isNotEmpty()) {
                    rows += AutoBridgeDesign.sectionLabel(this, getString(R.string.car_favorites_channels))
                    rows += matchingEntries.map { entryRow(source, it, matchingEntries) }
                }
                if (matchingCategories.isNotEmpty()) {
                    rows += AutoBridgeDesign.sectionLabel(this, getString(R.string.library_categories))
                    rows += matchingCategories.map { categoryRow(source, data, it) }
                }
            }

            val page = render(
                title = getString(R.string.library_title),
                subtitle = null,
                rows = rows,
                empty = if (q.isNotBlank()) {
                    AutoBridgeDesign.emptyState(
                        this, getString(R.string.car_find_no_matches), getString(R.string.library_no_matches_query, q)
                    )
                } else {
                    AutoBridgeDesign.emptyState(
                        this, getString(R.string.library_nothing_here), getString(R.string.library_no_categories)
                    )
                },
                headerActions = listOf(AutoBridgeDesign.HeaderAction("+", onClick = { addSourceChooser() })),
                search = query to { value -> query = value; draw() },
                extraPinned = listOf(chipsRow(), sourceCard(source, allSources)),
                overlay = overlay
            )
            scrollRef = AutoBridgeDesign.pageScroll(page)
        }
        push(draw)
    }

    /** Every chip, the currently selected one highlighted; tapping a different one switches to it. */
    private fun chipsRow(): View {
        val selected = chipFor(section)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chips.forEach { chip ->
            val isSelected = chip == selected
            row.addView(
                TextView(this).apply {
                    text = chip.title(this@LibraryActivity)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(if (isSelected) AutoBridgeDesign.INK else AutoBridgeDesign.TEXT)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                    background = AutoBridgeDesign.surface(
                        this@LibraryActivity,
                        if (isSelected) chip.accent else AutoBridgeDesign.SURFACE,
                        20,
                        if (isSelected) chip.accent else AutoBridgeDesign.HAIRLINE
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { if (!isSelected) showSection(chip) }
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
            )
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(-2, -2))
        }
    }

    /** Current source, its size, and a tap to switch; the "⋯" keeps Refresh/Edit/Delete reachable. */
    private fun sourceCard(source: IptvSource, allSources: List<IptvSource>): View {
        val type = if (source.type == IptvSourceType.XTREAM) "Xtream" else "M3U"
        val cached = IptvCatalog.cached(source.id)
        val subtitle = if (cached != null) {
            "$type • " + plural(R.plurals.library_categories_count, cached.categories.size) + " • " +
                plural(R.plurals.library_channels_count, cached.entries.size)
        } else {
            "$type • ${hostOf(source.url)}"
        }
        return AutoBridgeDesign.contentRow(
            context = this,
            title = source.name,
            subtitle = subtitle,
            accent = accent,
            badgeText = "📁",
            trailing = "▾",
            onTrailing = { sourceMenu(source) },
            onClick = { switchSource(source, allSources) }
        )
    }

    private fun switchSource(current: IptvSource, allSources: List<IptvSource>) {
        if (allSources.size <= 1) {
            addSourceChooser()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.library_switch_source)
            .setItems(allSources.map { it.name }.toTypedArray()) { _, index ->
                val chosen = allSources[index]
                if (chosen.id != current.id) {
                    // Replace the current categories page rather than stacking a second copy.
                    stack.removeLastOrNull()
                    openLibrarySource(chosen, allSources)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun addSourceChooser() {
        AlertDialog.Builder(this)
            .setTitle(R.string.library_add_source)
            .setItems(
                arrayOf(
                    getString(R.string.library_browse_public_lists),
                    getString(R.string.library_add_xtream),
                    getString(R.string.library_add_m3u)
                )
            ) { _, index ->
                when (index) {
                    0 -> addFromDirectory()
                    1 -> addSource(IptvSourceType.XTREAM)
                    else -> addSource(IptvSourceType.M3U)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** One category row: name, entry count, and a pin star — the main way to pin and unpin. */
    private fun categoryRow(source: IptvSource, data: IptvCatalogData, category: IptvCategory): View {
        val pinned = IptvPinnedCategoryStore.isPinned(this, source.id, category.id)
        return AutoBridgeDesign.contentRow(
            context = this,
            title = category.name,
            subtitle = plural(R.plurals.library_entries_count, category.count),
            accent = accent,
            trailing = if (pinned) "★" else "☆",
            onTrailing = {
                IptvPinnedCategoryStore.toggle(this, source.id, category.id)
                refresh()
            },
            onClick = { showEntries(source, data, category.id, category.name) }
        )
    }

    /**
     * The "ALL CATEGORIES · N" divider with a tappable A–Z / Z–A sort affordance on the right.
     * The label keeps its muted sectionLabel look; only the sort control reads as accent.
     */
    private fun allCategoriesHeader(count: Int, ascending: Boolean, onToggleSort: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                AutoBridgeDesign.sectionLabel(this@LibraryActivity, getString(R.string.library_all_categories_count, count)),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            addView(TextView(this@LibraryActivity).apply {
                text = getString(if (ascending) R.string.library_sort_az else R.string.library_sort_za)
                textSize = 12f
                setTextColor(AutoBridgeDesign.ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(10), dp(6), dp(6), dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener { onToggleSort() }
            }, LinearLayout.LayoutParams(-2, -2))
        }

    /**
     * The "PINNED" divider with an "Edit" link that opens a checkbox list for managing which
     * categories are pinned for this source. Pins are read and toggled through
     * [IptvPinnedCategoryStore]; the page refreshes when the dialog is dismissed.
     */
    private fun pinnedHeader(source: IptvSource, data: IptvCatalogData): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                AutoBridgeDesign.sectionLabel(this@LibraryActivity, getString(R.string.library_pinned)),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            addView(TextView(this@LibraryActivity).apply {
                text = getString(R.string.library_edit_pinned)
                textSize = 12f
                setTextColor(AutoBridgeDesign.ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(10), dp(6), dp(6), dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener { editPinnedCategories(source, data) }
            }, LinearLayout.LayoutParams(-2, -2))
        }

    /** Checkbox list of every category, toggling each one's pinned state for [source]. */
    private fun editPinnedCategories(source: IptvSource, data: IptvCatalogData) {
        val categories = data.categories
        if (categories.isEmpty()) return
        val names = categories.map { it.name }.toTypedArray()
        val checked = BooleanArray(categories.size) {
            IptvPinnedCategoryStore.isPinned(this, source.id, categories[it].id)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.library_pinned_categories)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                if (isChecked != IptvPinnedCategoryStore.isPinned(this, source.id, categories[which].id)) {
                    IptvPinnedCategoryStore.toggle(this, source.id, categories[which].id)
                }
            }
            .setOnDismissListener { refresh() }
            .setPositiveButton(R.string.control_quick_actions_done, null)
            .show()
    }

    /** Pinned categories, then a "Last watched" shortcut into the full recently-played list. */
    private fun pinnedRow(
        source: IptvSource,
        data: IptvCatalogData,
        pinnedCategories: List<IptvCategory>,
        hasRecent: Boolean
    ): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pinnedCategories.forEach { category ->
            row.addView(
                chip("★ ${category.name} · ${category.count}", AutoBridgeDesign.ACCENT_FAVORITE) {
                    showEntries(source, data, category.id, category.name)
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
            )
        }
        if (hasRecent) {
            row.addView(
                chip(getString(R.string.library_last_watched), AutoBridgeDesign.ACCENT_FILES) { showRecent() },
                LinearLayout.LayoutParams(-2, -2)
            )
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(-2, -2))
        }
    }

    private fun chip(label: String, color: Int, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = AutoBridgeDesign.surface(
            this@LibraryActivity, AutoBridgeDesign.tint(color, 0.16f), 18, AutoBridgeDesign.tint(color, 0.32f)
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** The right-edge jump index: one small glyph per [letters] entry, tapping scrolls to its header. */
    private fun letterIndex(letters: List<String>, onJump: (String) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(4), dp(6), dp(4))
            letters.forEach { letter ->
                addView(TextView(this@LibraryActivity).apply {
                    text = letter
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(AutoBridgeDesign.ACCENT)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                    setOnClickListener { onJump(letter) }
                })
            }
        }

    /** A–Z section letter for a category name: Latin and Thai keep their own letter, everything
     *  else (digits, symbols) groups under "#". */
    private fun categoryLetter(name: String): String {
        val c = name.trim().firstOrNull()?.uppercaseChar() ?: return "#"
        return when {
            c in 'A'..'Z' -> c.toString()
            c.code in 0x0E01..0x0E5B -> c.toString()
            else -> "#"
        }
    }

    /** Sort key for [categoryLetter]: A–Z, then Thai in code-point order, then "#" last. */
    private fun categoryLetterSortKey(letter: String): Int {
        val c = letter.firstOrNull() ?: return Int.MAX_VALUE
        return when {
            letter == "#" -> Int.MAX_VALUE
            c in 'A'..'Z' -> c.code
            c.code in 0x0E01..0x0E5B -> 1000 + c.code
            else -> Int.MAX_VALUE
        }
    }

    private fun showEntries(
        source: IptvSource,
        data: IptvCatalogData,
        categoryId: String,
        categoryName: String
    ) {
        val all = data.entriesIn(categoryId)
        var query = ""
        var hideOffline = false
        var gridView = true
        // Checked once per visit, not once per keystroke: draw() runs again on every letter typed.
        var autoChecked = false
        // The page re-renders itself on every keystroke, so the filter lives outside the lambda.
        lateinit var draw: () -> Unit
        draw = {
            val filtered = if (query.isBlank()) all else {
                all.filter { it.title.contains(query, ignoreCase = true) }
            }
            val checkedResults = filtered.mapNotNull { e -> e.url.takeIf { it.isNotBlank() }?.let(StreamPing::cached) }
            val offlineCount = checkedResults.count { StreamPing.tone(it) == StreamPing.Tone.BAD }
            val onlineCount = checkedResults.size - offlineCount
            val visible = if (hideOffline) {
                filtered.filter { e ->
                    e.url.isBlank() || StreamPing.cached(e.url)?.let { StreamPing.tone(it) != StreamPing.Tone.BAD } ?: true
                }
            } else {
                filtered
            }
            val shown = visible.take(MAX_VISIBLE_ENTRIES)
            val counted = if (query.isBlank()) getString(R.string.library_entries_counted, all.size, source.name)
            else getString(R.string.library_entries_matched, filtered.size, all.size, query)
            // The tiles about to be built are the ones a landing result writes into.
            pingTiles.clear()
            val rows = mutableListOf<View>()
            if (checkedResults.isNotEmpty()) rows += checkSummaryRow(
                online = onlineCount,
                offline = offlineCount,
                hideOffline = hideOffline,
                onCheck = { recheckEntries(shown) },
                onToggleHide = {
                    hideOffline = !hideOffline
                    draw()
                }
            )
            rows += if (gridView) {
                // Channels and films carry a logo worth seeing, so the grid is the default; two
                // columns is what fits a phone at a glance. List trades the logo for density.
                AutoBridgeDesign.grid(this, shown.map { entryTile(source, it, shown) }, columns = 3)
            } else {
                shown.map { entryRow(source, it, shown) }
            }
            render(
                title = categoryName,
                // A country-grouped public playlist puts thousands of channels in "All". Saying
                // "2080 entries" above 300 rows is a miscount the user has no way to notice.
                subtitle = if (shown.size < filtered.size) {
                    getString(R.string.library_entries_truncated, all.size, source.name, shown.size)
                } else {
                    counted
                },
                rows = rows,
                empty = AutoBridgeDesign.emptyState(
                    this, getString(R.string.car_find_no_matches), getString(R.string.library_no_matches_category)
                ),
                // A filled star to pin this category, then the grid/list toggle. The channel
                // check moved to a button on the status summary row (see checkSummaryRow).
                headerActions = listOf(
                    run {
                        val isPinned = IptvPinnedCategoryStore.isPinned(this@LibraryActivity, source.id, categoryId)
                        AutoBridgeDesign.HeaderAction(
                            glyph = if (isPinned) "★" else "☆",
                            filled = isPinned,
                            tint = if (isPinned) AutoBridgeDesign.ACCENT_FAVORITE else null,
                            onClick = {
                                IptvPinnedCategoryStore.toggle(this@LibraryActivity, source.id, categoryId)
                                draw()
                            }
                        )
                    },
                    AutoBridgeDesign.HeaderAction(
                        glyph = if (gridView) "▦" else "≡",
                        onClick = { gridView = !gridView; draw() }
                    )
                ),
                search = if (all.size >= SEARCH_THRESHOLD) {
                    query to { value: String ->
                        query = value
                        draw()
                    }
                } else {
                    null
                }
            )
            if (!autoChecked) {
                autoChecked = true
                pingEntries(shown.take(AUTO_PING_ENTRIES), quiet = true)
            }
        }
        push(draw)
    }

    /** Online/offline counts from the last check, a recheck button, and the Hide offline toggle. */
    private fun checkSummaryRow(
        online: Int,
        offline: Int,
        hideOffline: Boolean,
        onCheck: () -> Unit,
        onToggleHide: () -> Unit
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The leading dot of each count carries its own colour (green online, red offline)
            // while the counts themselves stay muted, so the summary reads at a glance.
            val summary = android.text.SpannableStringBuilder()
            val onlineStart = summary.length
            summary.append("●")
            summary.setSpan(
                android.text.style.ForegroundColorSpan(AutoBridgeDesign.ACCENT_ONLINE),
                onlineStart, summary.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            summary.append(" ").append(getString(R.string.library_online_count, online)).append("   ")
            val offlineStart = summary.length
            summary.append("●")
            summary.setSpan(
                android.text.style.ForegroundColorSpan(AutoBridgeDesign.DANGER),
                offlineStart, summary.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            summary.append(" ").append(getString(R.string.library_offline_count, offline))
            addView(TextView(this@LibraryActivity).apply {
                text = summary
                textSize = 12f
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(
                AutoBridgeDesign.pill(
                    this@LibraryActivity,
                    getString(R.string.library_check_button),
                    primary = false,
                    accent = accent,
                    onClick = onCheck
                ),
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
            )
            addView(
                AutoBridgeDesign.pill(
                    this@LibraryActivity,
                    getString(if (hideOffline) R.string.library_show_all else R.string.library_hide_offline),
                    primary = hideOffline,
                    accent = accent,
                    onClick = onToggleHide
                )
            )
        }

    /**
     * One entry as a grid tile: its logo, its title, and its last check result in colour.
     *
     * [siblings] is the list this tile is shown in. It becomes the player's queue, which is what
     * Next/Previous, the channel gesture and "Auto next channel" walk; without it a channel opened
     * from a category would be the only thing the player knows about.
     */
    private fun entryTile(
        source: IptvSource,
        entry: IptvEntry,
        siblings: List<IptvEntry> = emptyList()
    ): View {
        val favorite = entry.url.isNotBlank() && IptvHistoryStore.isFavorite(this, entry.url)
        val onCar = entry.url.isNotBlank() && entry.url == lastSentToCarUrl
        val tile = AutoBridgeDesign.contentTile(
            context = this,
            title = entry.title,
            subtitle = listOfNotNull(
                entry.subtitle.takeIf { it.isNotBlank() },
                getString(R.string.car_iptv_opens_in_browser).takeIf { entry.isWebPage },
                getString(R.string.car_iptv_catchup).takeIf { entry.supportsCatchup }
            ).joinToString(" • "),
            accent = accent,
            artworkUrl = entry.logo,
            status = StreamPing.cached(entry.url)?.let { pingStatus(it) },
            corner = if (entry.isSeriesFolder) {
                "›" to { openEntry(source, entry, siblings) }
            } else {
                (if (favorite) "★" else "☆") to {
                    IptvHistoryStore.toggleFavorite(this, source, entry)
                    refresh()
                }
            },
            // TV video streams (not web pages, not folders) can be sent straight to the car's
            // video screen without opening the phone player first.
            onLongClick = if (!entry.isSeriesFolder && !entry.isWebPage && source.kind != IptvKind.RADIO) {
                { sendChannelMenu(source, entry) }
            } else {
                null
            },
            onClick = { openEntry(source, entry, siblings) }
        )
        if (entry.url.isNotBlank()) pingTiles[entry.url] = tile
        return if (onCar) onCarBadge(tile) else tile
    }

    /** Wraps an entry tile with the accent border and "ON CAR" pill the currently-sent channel gets. */
    private fun onCarBadge(tile: View): View = FrameLayout(this).apply {
        background = AutoBridgeDesign.surface(this@LibraryActivity, android.graphics.Color.TRANSPARENT, 18, accent)
        addView(tile, FrameLayout.LayoutParams(-1, -1).apply {
            val inset = dp(2)
            setMargins(inset, inset, inset, inset)
        })
        addView(
            TextView(this@LibraryActivity).apply {
                text = getString(R.string.library_on_car)
                textSize = 10f
                setTextColor(AutoBridgeDesign.INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = AutoBridgeDesign.surface(this@LibraryActivity, accent, 10)
            },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(10)
                topMargin = dp(10)
            }
        )
    }

    /** A check result as text and the colour it reads in. */
    private fun pingStatus(result: StreamPing.Result): Pair<String, Int> =
        StreamPing.describe(result) to when (StreamPing.tone(result)) {
            StreamPing.Tone.GOOD -> AutoBridgeDesign.SIGNAL_GOOD
            StreamPing.Tone.SLOW -> AutoBridgeDesign.SIGNAL_SLOW
            StreamPing.Tone.BAD -> AutoBridgeDesign.DANGER
        }

    /**
     * Writes the results that have landed into the tiles already on screen.
     *
     * Re-rendering the page instead would scroll it back to the top every time a batch of answers
     * arrives, which is an unusable page while a check runs - so nothing is rebuilt here.
     */
    private fun updatePingTiles() {
        pingTiles.forEach { (url, tile) ->
            val result = StreamPing.cached(url) ?: return@forEach
            val (text, color) = pingStatus(result)
            AutoBridgeDesign.setStatus(tile, text, color)
        }
        signalTint()?.let { signalIndicator?.setTextColor(it) }
    }

    /**
     * The header signal icon's colour: the worst tone among [pingTiles]' cached results, or null
     * while nothing on this page has been checked yet, which leaves the icon in its default colour.
     *
     * [pingTiles] is read rather than taking an entries list, because that is exactly the set this
     * page's icon is answering for - and it stays correct as results land without re-rendering.
     */
    private fun signalTint(): Int? {
        val tones = pingTiles.keys.mapNotNull { StreamPing.cached(it) }.map { StreamPing.tone(it) }
        val worst = when {
            tones.isEmpty() -> return null
            tones.contains(StreamPing.Tone.BAD) -> StreamPing.Tone.BAD
            tones.contains(StreamPing.Tone.SLOW) -> StreamPing.Tone.SLOW
            else -> StreamPing.Tone.GOOD
        }
        return when (worst) {
            StreamPing.Tone.GOOD -> AutoBridgeDesign.SIGNAL_GOOD
            StreamPing.Tone.SLOW -> AutoBridgeDesign.SIGNAL_SLOW
            StreamPing.Tone.BAD -> AutoBridgeDesign.DANGER
        }
    }

    /**
     * [siblings] is the list this row is shown in. It becomes the player's queue, which is what
     * Next/Previous, the channel gesture and "Auto next channel" walk; without it a channel opened
     * from a category would be the only thing the player knows about.
     */
    private fun entryRow(
        source: IptvSource,
        entry: IptvEntry,
        siblings: List<IptvEntry> = emptyList()
    ): View {
        val favorite = entry.url.isNotBlank() && IptvHistoryStore.isFavorite(this, entry.url)
        val onCar = entry.url.isNotBlank() && entry.url == lastSentToCarUrl
        val row = AutoBridgeDesign.contentRow(
            context = this,
            title = entry.title,
            subtitle = listOfNotNull(
                getString(R.string.library_on_car).takeIf { onCar },
                entry.subtitle.takeIf { it.isNotBlank() },
                getString(R.string.car_iptv_opens_in_browser).takeIf { entry.isWebPage },
                getString(R.string.car_iptv_catchup).takeIf { entry.supportsCatchup },
                // Whatever the last check said about this address, for as long as it stays true;
                // the row says nothing extra until something has actually been checked.
                StreamPing.cached(entry.url)?.let { StreamPing.describe(it) }
            ).joinToString(" • "),
            accent = accent,
            artworkUrl = entry.logo,
            trailing = if (entry.isSeriesFolder) "›" else if (favorite) "★" else "☆",
            onTrailing = if (entry.isSeriesFolder) null else {
                {
                    IptvHistoryStore.toggleFavorite(this, source, entry)
                    refresh()
                }
            },
            onClick = { openEntry(source, entry, siblings) }
        )
        // TV video streams (not web pages, not folders) can be sent straight to the car's video
        // screen without opening the phone player first — a long press keeps the tap target simple.
        if (!entry.isSeriesFolder && !entry.isWebPage && source.kind != IptvKind.RADIO) {
            row.setOnLongClickListener { sendChannelMenu(source, entry); true }
        }
        return row
    }

    /** Long-press menu for a TV entry: play here, send it to the car, or check that it answers. */
    private fun sendChannelMenu(source: IptvSource, entry: IptvEntry) {
        AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setItems(
                arrayOf(
                    getString(R.string.library_play_here),
                    getString(R.string.drawer_send_to_car),
                    getString(R.string.library_ping)
                )
            ) { _, index ->
                when (index) {
                    0 -> openEntry(source, entry)
                    1 -> sendToCar(source, entry)
                    else -> pingEntry(entry)
                }
            }
            .show()
    }

    /**
     * Checks the channels on this page, so a dead or geo-blocked one reads as such in its row
     * instead of being found out by a player that spins.
     *
     * Only what is on screen is checked, and only up to [MAX_PING_ENTRIES] of it: a public list
     * holds thousands of addresses and probing them all would be a port scan of a dozen CDNs. The
     * rows re-read [StreamPing]'s cache as results land, which is why this re-renders the page
     * rather than tracking anything itself.
     */
    private fun pingEntries(entries: List<IptvEntry>, quiet: Boolean = false) {
        val urls = checkableUrls(entries)
        if (urls.isEmpty()) {
            if (!quiet) Toast.makeText(this, getString(R.string.library_nothing_to_check), Toast.LENGTH_SHORT).show()
            return
        }
        StreamPing.checkAll(urls) { progress ->
            if (isFinishing || isDestroyed) return@checkAll
            updatePingTiles()
            if (progress.done && !quiet) {
                Toast.makeText(
                    this,
                    getString(R.string.library_channels_answered, progress.alive, progress.total),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        // The automatic pass says nothing at all: it is background work the user did not ask for,
        // and its whole output is the colour on the tiles.
        if (quiet) return
        Toast.makeText(this, getString(R.string.library_checking_channels, urls.size), Toast.LENGTH_SHORT).show()
    }

    /**
     * The explicit recheck, triggered by tapping the header's signal icon: the same sweep, but the
     * remembered answers are dropped first.
     *
     * Without that, a tap inside the five-minute freshness window would hand back exactly what is
     * already on screen and look like a button that does nothing.
     */
    private fun recheckEntries(entries: List<IptvEntry>) {
        StreamPing.forget(checkableUrls(entries))
        pingEntries(entries)
    }

    /** The addresses on a page worth probing: a folder and a watch page are not streams. */
    private fun checkableUrls(entries: List<IptvEntry>): List<String> = entries
        .filter { !it.isSeriesFolder && !it.isWebPage && it.url.isNotBlank() }
        .map { it.url }
        .distinct()
        .take(MAX_PING_ENTRIES)

    /** One channel, checked from its long-press menu and reported in full rather than in a row. */
    private fun pingEntry(entry: IptvEntry) {
        val dialog = progressDialog(getString(R.string.library_checking, entry.title))
        StreamPing.check(entry.url) { result ->
            dialog.dismiss()
            if (isFinishing || isDestroyed) return@check
            updatePingTiles()
            alert(entry.title, pingSentence(result))
        }
    }

    private fun pingSentence(result: StreamPing.Result): String = when (result) {
        is StreamPing.Result.Alive -> getString(R.string.library_ping_alive, result.millis)
        is StreamPing.Result.Refused -> getString(R.string.library_ping_refused, result.status)
        is StreamPing.Result.Unreachable ->
            getString(R.string.library_ping_unreachable, result.reason.lowercase())
        StreamPing.Result.Unsupported -> getString(R.string.library_ping_unsupported)
    }

    /**
     * Sends a TV channel to the car's native video screen via the same command bus the Mobile
     * Remote uses, so it works whether Android Auto is connected right now or not — the router
     * reports NOT_CONNECTED instead of silently doing nothing.
     */
    private fun sendToCar(source: IptvSource, entry: IptvEntry) {
        IptvHistoryStore.recordPlayback(this, source, entry)
        lastSentToCarUrl = entry.url
        refresh()
        dev.autobridge.remote.RemoteRuntime.ensureStarted(this)
        dev.autobridge.remote.AutoBridgeCommandBus.send(
            dev.autobridge.remote.AutoBridgeCommand(
                type = dev.autobridge.remote.CommandType.PLAY_VIDEO,
                payload = entry.url,
                source = dev.autobridge.remote.CommandSource.MOBILE,
                extras = mapOf("title" to entry.title)
            )
        )
        Toast.makeText(this, getString(R.string.library_sending_to_car, entry.title), Toast.LENGTH_SHORT).show()
    }

    private fun openEntry(
        source: IptvSource,
        entry: IptvEntry,
        siblings: List<IptvEntry> = emptyList()
    ) {
        if (entry.isSeriesFolder) {
            val dialog = progressDialog(getString(R.string.library_loading, entry.title))
            IptvCatalog.loadEpisodes(source, entry) { episodes ->
                dialog.dismiss()
                if (episodes.isEmpty()) alert(getString(R.string.library_no_episodes), getString(R.string.library_no_episode_list))
                else showEpisodes(source, entry, episodes)
            }
            return
        }
        IptvHistoryStore.recordPlayback(this, source, entry)
        if (entry.isWebPage) {
            openWebChannel(entry.url, entry.title)
            return
        }
        // Only entries the player can actually open belong in the queue: a folder or a web page
        // in it would make Next land on something that cannot play.
        val playable = siblings.filter { !it.isSeriesFolder && !it.isWebPage && it.url.isNotBlank() }
        play(
            url = entry.url,
            title = entry.title,
            subtitle = entry.subtitle,
            artwork = entry.logo,
            video = source.kind != IptvKind.RADIO,
            queue = playable.map { it.url },
            queueTitles = playable.map { it.title },
            queueIndex = playable.indexOfFirst { it.url == entry.url }.coerceAtLeast(0)
        )
    }

    /**
     * A channel whose playlist entry points at a YouTube or Twitch page, not a stream. The player
     * can only fail on those, so the page is handed to the browser surface, which applies its own
     * parked/browser policy gate.
     */
    private fun openWebChannel(url: String, title: String) {
        startActivity(
            Intent(this, EntertainmentActivity::class.java)
                .putExtra(EntertainmentActivity.EXTRA_SOURCE_URL, url)
                .putExtra(EntertainmentActivity.EXTRA_SOURCE_KIND, ContentKind.WEB.name)
                .putExtra(EntertainmentActivity.EXTRA_SOURCE_TITLE, title)
        )
    }

    private fun showEpisodes(source: IptvSource, series: IptvEntry, episodes: List<IptvEntry>) {
        // Seasons are the natural grouping; a single season skips the extra tap.
        val seasons = episodes.map { it.subtitle }.distinct()
        if (seasons.size <= 1) {
            showEpisodeList(source, series.title, episodes)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(series.title)
            .setItems(seasons.toTypedArray()) { _, index ->
                val season = seasons[index]
                showEpisodeList(
                    source, "${series.title} • $season", episodes.filter { it.subtitle == season }
                )
            }
            .show()
    }

    private fun showEpisodeList(source: IptvSource, title: String, episodes: List<IptvEntry>) = push {
        render(
            title = title,
            subtitle = getString(R.string.library_episodes_count, episodes.size),
            rows = episodes.map { entryRow(source, it, episodes) },
            empty = AutoBridgeDesign.emptyState(
                this, getString(R.string.library_no_episodes), getString(R.string.library_nothing_to_play_here)
            )
        )
    }

    private fun showRecent() = push {
        val items = IptvHistoryStore.recent(this, iptvKind)
        render(
            title = getString(R.string.car_iptv_recent),
            subtitle = getString(R.string.library_items_count, items.size),
            rows = items.map { item ->
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = item.title,
                    subtitle = item.type.name.lowercase().replaceFirstChar { it.uppercase() },
                    accent = accent,
                    artworkUrl = item.logo,
                    onClick = {
                        if (item.playback == IptvPlayback.WEB_PAGE) {
                            openWebChannel(item.url, item.title)
                        } else {
                            play(item.url, item.title, video = item.kind != IptvKind.RADIO)
                        }
                    }
                )
            },
            empty = AutoBridgeDesign.emptyState(
                this, getString(R.string.library_nothing_yet), getString(R.string.library_recent_empty)
            ),
            actions = listOf(getString(R.string.history_clear) to {
                IptvHistoryStore.clearRecent(this)
                refresh()
            })
        )
    }

    // ----- Source editing -----

    private fun sourceMenu(source: IptvSource) {
        AlertDialog.Builder(this)
            .setTitle(source.name)
            .setItems(
                arrayOf(
                    getString(R.string.car_diag_refresh),
                    getString(R.string.library_edit_pinned),
                    getString(R.string.action_delete)
                )
            ) { _, index ->
                when (index) {
                    0 -> {
                        // Replace the stale categories page rather than stacking a second copy.
                        stack.removeLastOrNull()
                        IptvCatalog.invalidate(source.id)
                        openLibrarySource(source, IptvSourceStore.list(this, iptvKind))
                    }
                    1 -> addSource(source.type, source)
                    else -> confirmDelete(source)
                }
            }
            .show()
    }

    /**
     * Deleting a source is permanent — a built-in default that is removed stays removed — so it is
     * confirmed once. Nothing is lost for good: every public list is still in the picker.
     */
    private fun confirmDelete(source: IptvSource) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.library_remove_source_title, source.name))
            .setMessage(
                if (IptvDirectory.list(iptvKind).any { it.url == source.url }) {
                    getString(R.string.library_remove_builtin_message)
                } else {
                    getString(R.string.library_remove_message)
                }
            )
            .setPositiveButton(R.string.bridge_controller_remove) { _, _ ->
                IptvSourceStore.remove(this, source.id)
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * One-tap add for the public lists in [IptvDirectory].
     *
     * The picker only fills in the address the user would otherwise type: the source is an
     * ordinary M3U entry afterwards, editable and removable like any other. A list already added
     * is shown as such rather than added twice.
     */
    private fun addFromDirectory() {
        val offers = IptvDirectory.list(iptvKind)
        if (offers.isEmpty()) {
            alert(getString(R.string.library_no_public_lists), getString(R.string.library_no_public_lists_message))
            return
        }
        val existing = IptvSourceStore.list(this).map { it.url }.toSet()
        val labels = offers.map { offer ->
            val note = if (offer.url in existing) getString(R.string.library_already_added, offer.note) else offer.note
            offer.name + "\n" + note
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.library_free_public_lists)
            .setItems(labels.toTypedArray()) { _, index ->
                val offer = offers[index]
                if (offer.url in existing) {
                    alert(offer.name, getString(R.string.library_already_source))
                    return@setItems
                }
                IptvSourceStore.save(this, IptvDirectory.toSource(offer, IptvSourceStore.newId()))
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Add/edit form. For Xtream the URL field accepts a bare portal or a full
     * `player_api.php`/`get.php` link; credentials found in a pasted link fill the empty fields,
     * which is how providers usually hand accounts over.
     */
    private fun addSource(type: IptvSourceType, existing: IptvSource? = null) {
        val xtream = type == IptvSourceType.XTREAM
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        fun field(hint: String, value: String, password: Boolean = false): EditText =
            EditText(this).apply {
                this.hint = hint
                setText(value)
                setSingleLine()
                if (password) inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                container.addView(this)
            }

        val name = field(getString(R.string.maint_field_name), existing?.name.orEmpty())
        val url = field(
            getString(if (xtream) R.string.library_xtream_url_hint else R.string.library_m3u_url_hint),
            existing?.url.orEmpty()
        )
        val username = if (xtream) field(getString(R.string.library_username), existing?.username.orEmpty()) else null
        val password = if (xtream) field(getString(R.string.library_password), existing?.password.orEmpty(), password = true) else null

        AlertDialog.Builder(this)
            .setTitle(
                if (existing == null) getString(if (xtream) R.string.library_add_xtream else R.string.library_add_m3u)
                else getString(R.string.library_edit_source)
            )
            .setView(container)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val address = url.text.toString().trim()
                if (address.isEmpty()) {
                    alert(getString(R.string.library_missing_url), getString(R.string.library_missing_url_message))
                    return@setPositiveButton
                }
                if (xtream) {
                    val credentials = XtreamCredentials.parse(
                        address, username?.text?.toString(), password?.text?.toString()
                    )
                    if (credentials == null) {
                        alert(
                            getString(R.string.library_incomplete_account),
                            getString(R.string.library_incomplete_account_message)
                        )
                        return@setPositiveButton
                    }
                    IptvSourceStore.save(
                        this,
                        IptvSource(
                            id = existing?.id ?: IptvSourceStore.newId(),
                            name = name.text.toString().trim().ifEmpty { hostOf(credentials.portal) },
                            kind = iptvKind,
                            type = IptvSourceType.XTREAM,
                            url = credentials.portal,
                            username = credentials.username,
                            password = credentials.password
                        )
                    )
                } else {
                    if (!address.startsWith("http://", true) && !address.startsWith("https://", true)) {
                        alert(getString(R.string.library_invalid_url), getString(R.string.library_invalid_url_message))
                        return@setPositiveButton
                    }
                    IptvSourceStore.save(
                        this,
                        IptvSource(
                            id = existing?.id ?: IptvSourceStore.newId(),
                            name = name.text.toString().trim().ifEmpty { hostOf(address) },
                            kind = iptvKind,
                            type = IptvSourceType.M3U,
                            url = address
                        )
                    )
                }
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ----- Local library: folders, playlists, gallery -----

    private fun showFolders() = push {
        val types = setOf(LocalMediaRepository.MediaType.AUDIO, LocalMediaRepository.MediaType.VIDEO)
        if (!hasMediaPermission(types)) {
            renderPermissionNeeded(getString(R.string.section_folders), types)
            return@push
        }
        val folders = LocalMediaRepository.folders(this)
        render(
            title = getString(R.string.section_folders),
            subtitle = getString(R.string.library_folders_count, folders.size),
            rows = folders.map { folder ->
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = folder.name,
                    subtitle = getString(R.string.library_files_count, folder.count),
                    accent = accent,
                    badgeText = "▣",
                    trailing = "›",
                    onClick = { showFolderItems(folder) }
                )
            },
            empty = AutoBridgeDesign.emptyState(
                this, getString(R.string.library_no_media_folders), getString(R.string.library_no_media_folders_message)
            )
        )
    }

    private fun showFolderItems(folder: LocalMediaRepository.Group) = push {
        val items = LocalMediaRepository.folderItems(this, folder.id)
        val audio = items.filter { it.subtitle == "Audio" }
        render(
            title = folder.name,
            subtitle = getString(R.string.library_files_count, items.size),
            rows = items.map { item ->
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = item.title,
                    subtitle = durationText(item.durationMs).ifBlank { item.subtitle },
                    accent = accent,
                    badgeText = if (item.subtitle == "Audio") "♪" else "▶",
                    onClick = {
                        val isAudio = item.subtitle == "Audio"
                        play(
                            url = item.uri,
                            title = item.title,
                            video = !isAudio,
                            // Audio queues the folder so Next/Previous walks it.
                            queue = if (isAudio) audio.map { it.uri } else emptyList(),
                            queueIndex = if (isAudio) audio.indexOfFirst { it.uri == item.uri } else 0
                        )
                    }
                )
            },
            empty = AutoBridgeDesign.emptyState(
                this, getString(R.string.library_empty_folder), getString(R.string.library_nothing_to_play_here)
            )
        )
    }

    private fun showPlaylists() = push {
        val types = setOf(LocalMediaRepository.MediaType.AUDIO)
        if (!hasMediaPermission(types)) {
            renderPermissionNeeded(getString(R.string.section_playlists), types)
            return@push
        }
        val playlists = LocalMediaRepository.playlists(this)
        val rows = mutableListOf<View>()
        rows += AutoBridgeDesign.contentRow(
            context = this,
            title = getString(R.string.car_all_music),
            subtitle = getString(R.string.library_every_track),
            accent = accent,
            badgeText = "♪",
            trailing = "›",
            onClick = { showTracks(getString(R.string.car_all_music), LocalMediaRepository.allAudio(this)) }
        )
        playlists.forEach { playlist ->
            rows += AutoBridgeDesign.contentRow(
                context = this,
                title = playlist.name,
                subtitle = getString(R.string.library_playlist),
                accent = accent,
                badgeText = "≡",
                trailing = "›",
                onClick = { showTracks(playlist.name, LocalMediaRepository.playlistItems(this, playlist.id)) }
            )
        }
        render(
            title = getString(R.string.section_playlists),
            subtitle = getString(R.string.library_device_playlists_count, playlists.size),
            rows = rows
        )
    }

    private fun showTracks(title: String, items: List<LocalMediaRepository.Item>) = push {
        render(
            title = title,
            subtitle = getString(R.string.library_tracks_count, items.size),
            rows = items.mapIndexed { index, item ->
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = item.title,
                    subtitle = listOf(item.subtitle, durationText(item.durationMs))
                        .filter { it.isNotBlank() }.joinToString(" • "),
                    accent = accent,
                    badgeText = "♪",
                    onClick = {
                        play(
                            url = item.uri,
                            title = item.title,
                            subtitle = item.subtitle,
                            video = false,
                            queue = items.map { it.uri },
                            queueIndex = index
                        )
                    }
                )
            },
            empty = AutoBridgeDesign.emptyState(
                this,
                getString(R.string.library_no_tracks),
                getString(R.string.library_no_tracks_message)
            )
        )
    }

    private fun showGalleryAlbums() = push {
        val types = setOf(LocalMediaRepository.MediaType.IMAGE, LocalMediaRepository.MediaType.VIDEO)
        if (!hasMediaPermission(types)) {
            renderPermissionNeeded(getString(R.string.section_gallery), types)
            return@push
        }
        val albums = LocalMediaRepository.galleryAlbums(this)
        render(
            title = getString(R.string.section_gallery),
            subtitle = getString(R.string.library_albums_count, albums.size),
            rows = albums.map { album ->
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = album.name,
                    subtitle = getString(R.string.library_items_count, album.count),
                    accent = accent,
                    badgeText = "◱",
                    trailing = "›",
                    onClick = { showGalleryItems(album) }
                )
            },
            empty = AutoBridgeDesign.emptyState(
                this, getString(R.string.library_no_albums), getString(R.string.library_no_albums_message)
            )
        )
    }

    private fun showGalleryItems(album: LocalMediaRepository.Group) = push {
        val items = LocalMediaRepository.galleryItems(this, album.id)
        render(
            title = album.name,
            subtitle = getString(R.string.library_items_count, items.size),
            rows = items.map { item ->
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = item.title,
                    subtitle = if (item.durationMs > 0) durationText(item.durationMs) else getString(R.string.media_kind_photo),
                    accent = accent,
                    artworkUrl = item.uri,
                    badgeText = if (item.durationMs > 0) "▶" else "◱",
                    onClick = { openGalleryItem(item) }
                )
            },
            empty = AutoBridgeDesign.emptyState(
                this, getString(R.string.library_empty_album), getString(R.string.library_empty_album_message)
            )
        )
    }

    /** Videos play in the app; photos go to whatever viewer the user already has. */
    private fun openGalleryItem(item: LocalMediaRepository.Item) {
        if (item.durationMs > 0) {
            play(item.uri, item.title, video = true)
            return
        }
        val opened = runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(item.uri), "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.isSuccess
        if (!opened) alert(getString(R.string.library_no_viewer), getString(R.string.library_no_viewer_message))
    }

    // ----- Favorites -----

    private fun showFavorites() = push {
        val channels = IptvHistoryStore.favorites(this)
        val bookmarks = WebBookmarkStore.list(this)
        val rows = mutableListOf<View>()

        channels.forEach { item ->
            rows += AutoBridgeDesign.contentRow(
                context = this,
                title = item.title,
                subtitle = getString(if (item.kind == IptvKind.RADIO) R.string.section_radio else R.string.section_tv),
                accent = if (item.kind == IptvKind.RADIO) {
                    AutoBridgeDesign.ACCENT_RADIO
                } else {
                    AutoBridgeDesign.ACCENT_TV
                },
                artworkUrl = item.logo,
                trailing = "✕",
                onTrailing = {
                    IptvHistoryStore.removeFavorite(this, item.url)
                    refresh()
                },
                onClick = {
                    // Favorites of the same kind are a channel list in their own right, so the
                    // player gets them as its queue.
                    val peers = channels.filter { it.kind == item.kind && it.url.isNotBlank() }
                    play(
                        url = item.url,
                        title = item.title,
                        video = item.kind != IptvKind.RADIO,
                        queue = peers.map { it.url },
                        queueTitles = peers.map { it.title },
                        queueIndex = peers.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
                    )
                }
            )
        }
        bookmarks.forEach { bookmark ->
            rows += AutoBridgeDesign.contentRow(
                context = this,
                title = bookmark.title,
                subtitle = hostOf(bookmark.url),
                accent = AutoBridgeDesign.ACCENT_WEB,
                badgeText = "@",
                onClick = {
                    startActivity(
                        Intent(this, dev.autobridge.browser.BrowserActivity::class.java)
                            .setData(Uri.parse(bookmark.url))
                    )
                }
            )
        }

        render(
            title = getString(R.string.section_favorites),
            subtitle = getString(R.string.library_favorites_count, channels.size, bookmarks.size),
            rows = rows,
            empty = AutoBridgeDesign.emptyState(
                this,
                getString(R.string.library_nothing_saved),
                getString(R.string.library_nothing_saved_message)
            ),
            extraPinned = listOf(chipsRow())
        )
    }

    // ----- Music -----

    /** YouTube Music (a web shortcut, same as Home's Music tile) and this device's audio playlists. */
    private fun showMusic() = push {
        val rows = listOf(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.section_youtube_music),
                subtitle = "music.youtube.com",
                accent = accent,
                badgeText = "▶",
                trailing = "›",
                onClick = {
                    startActivity(
                        Intent(this, dev.autobridge.browser.BrowserActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            .setData(Uri.parse("https://music.youtube.com"))
                    )
                }
            ),
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.section_playlists),
                subtitle = getString(R.string.library_audio_on_device),
                accent = accent,
                badgeText = "♪",
                trailing = "›",
                onClick = { showPlaylists() }
            )
        )
        render(title = getString(R.string.home_tile_music), subtitle = null, rows = rows, extraPinned = listOf(chipsRow()))
    }

    // ----- Files -----

    /** Folders, Playlists and Gallery, merged behind one chip. */
    private fun showFiles() = push {
        val rows = listOf(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.section_folders),
                subtitle = getString(R.string.library_audio_video_on_device),
                accent = accent,
                badgeText = "▣",
                trailing = "›",
                onClick = { showFolders() }
            ),
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.section_playlists),
                subtitle = getString(R.string.library_audio_on_device),
                accent = accent,
                badgeText = "♪",
                trailing = "›",
                onClick = { showPlaylists() }
            ),
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.section_gallery),
                subtitle = getString(R.string.library_photos_videos_on_device),
                accent = accent,
                badgeText = "◱",
                trailing = "›",
                onClick = { showGalleryAlbums() }
            )
        )
        render(title = getString(R.string.library_section_files), subtitle = null, rows = rows, extraPinned = listOf(chipsRow()))
    }

    // ----- Streaming -----

    /** The shared [StreamingLinks] catalog, grouped; each row opens the site in the browser. */
    private fun showStreaming() = push {
        val rows = mutableListOf<View>()
        // Same intent as the YouTube home tile, so the browser is reused if it is open.
        fun openSite(url: String) = startActivity(
            Intent(this, dev.autobridge.browser.BrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .setData(Uri.parse(url))
        )

        // The driver's own favourites on top, then the catalog, as an icon grid like the car's. A tap
        // opens; a long press stars or unstars (favourites are starred, the + tile adds any address).
        fun tile(title: String, url: String, starred: Boolean, caption: String? = null) = StreamingTile(
            title = title,
            icon = StreamingIcons.bitmap(StreamingIcons.styleFor(title, url), STREAM_ICON_PX, starred),
            caption = caption,
            onClick = { openSite(url) },
            onLongClick = {
                if (starred) StreamingFavoritesStore.remove(this, url) else StreamingFavoritesStore.add(this, title, url)
                refresh()
            }
        )
        val favorites = StreamingFavoritesStore.list(this)
        val favoriteUrls = favorites.map { it.url }.toSet()
        rows += AutoBridgeDesign.sectionLabel(this, getString(R.string.streaming_favorites))
        rows += streamingGrid(
            favorites.map { tile(it.title, it.url, starred = true) } + StreamingTile(
                title = getString(R.string.streaming_add_favorite),
                icon = StreamingIcons.bitmap(StreamingIcons.ADD, STREAM_ICON_PX),
                caption = null,
                onClick = { showAddFavoriteDialog() },
                onLongClick = {}
            )
        )
        rows += TextView(this).apply {
            text = getString(R.string.streaming_long_press_hint)
            textSize = 12f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        StreamingLinks.grouped().forEach { (group, links) ->
            rows += AutoBridgeDesign.sectionLabel(this, group.title)
            rows += streamingGrid(
                links.map { link ->
                    tile(
                        link.title, link.url, starred = link.url in favoriteUrls,
                        caption = getString(R.string.streaming_may_not_play).takeIf { StreamingLinks.mayNotPlay(link) }
                    )
                }
            )
        }
        render(
            title = getString(R.string.section_streaming),
            subtitle = getString(R.string.library_sites_count, StreamingLinks.all.size),
            rows = rows,
            extraPinned = listOf(chipsRow())
        )
    }

    /** One icon in the Streaming grid. */
    private class StreamingTile(
        val title: String,
        val icon: android.graphics.Bitmap,
        val caption: String?,
        val onClick: () -> Unit,
        val onLongClick: () -> Unit,
    )

    /** [tiles] as rows of icons with their names under them, [STREAM_COLUMNS] to a row. */
    private fun streamingGrid(tiles: List<StreamingTile>): View {
        val density = resources.displayMetrics.density
        val grid = android.widget.GridLayout(this).apply { columnCount = STREAM_COLUMNS }
        tiles.forEach { tile ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding((4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt())
                isClickable = true
                isLongClickable = true
                contentDescription = tile.title
                setOnClickListener { tile.onClick() }
                setOnLongClickListener { tile.onLongClick(); true }
                addView(
                    android.widget.ImageView(this@LibraryActivity).apply { setImageBitmap(tile.icon) },
                    LinearLayout.LayoutParams((56 * density).toInt(), (56 * density).toInt())
                )
                addView(TextView(this@LibraryActivity).apply {
                    text = tile.title
                    textSize = 12f
                    maxLines = 2
                    gravity = android.view.Gravity.CENTER
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(AutoBridgeDesign.TEXT)
                    setPadding(0, (4 * density).toInt(), 0, 0)
                })
                tile.caption?.let { caption ->
                    addView(TextView(this@LibraryActivity).apply {
                        text = caption
                        textSize = 10f
                        maxLines = 2
                        gravity = android.view.Gravity.CENTER
                        setTextColor(AutoBridgeDesign.TEXT_MUTED)
                    })
                }
            }
            grid.addView(
                cell,
                android.widget.GridLayout.LayoutParams(
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
                    android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                ).apply { width = 0 }
            )
        }
        return grid
    }

    /** Adds any website to the favourites: a name (optional) and an address. */
    private fun showAddFavoriteDialog() {
        val name = EditText(this).apply { hint = getString(R.string.streaming_field_name) }
        val address = EditText(this).apply {
            hint = getString(R.string.streaming_field_address)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(name)
            addView(address)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.streaming_add_favorite))
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.fuel_save)) { _, _ ->
                if (StreamingFavoritesStore.add(this, name.text.toString(), address.text.toString())) {
                    refresh()
                } else {
                    android.widget.Toast.makeText(this, getString(R.string.streaming_invalid_address), android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    // ----- Shared helpers -----

    /** Opens [PlayerActivity], which owns the MediaSession attachment and the parked-state gate. */
    private fun play(
        url: String,
        title: String,
        subtitle: String = "",
        artwork: String = "",
        video: Boolean,
        queue: List<String> = emptyList(),
        queueTitles: List<String> = emptyList(),
        queueIndex: Int = 0
    ) {
        if (url.isBlank()) {
            alert(getString(R.string.library_nothing_to_play), getString(R.string.library_no_stream_address))
            return
        }
        startActivity(
            PlayerActivity.intent(
                context = this,
                url = url,
                title = title,
                subtitle = subtitle,
                artwork = artwork,
                video = video,
                queue = queue,
                queueTitles = queueTitles,
                queueIndex = queueIndex
            )
        )
    }

    /**
     * True when [types] are all granted. When they are not, the caller renders
     * [renderPermissionNeeded] instead.
     *
     * Rendering deliberately never requests a permission itself: `requestPermissions` can deliver
     * its result synchronously (a permission the user has permanently denied, or an OEM policy),
     * and a render-time request would then re-enter the same page until the stack overflowed. The
     * request only happens from an explicit tap.
     */
    private fun hasMediaPermission(types: Set<LocalMediaRepository.MediaType>): Boolean =
        LocalMediaRepository.hasPermission(this, types)

    private fun renderPermissionNeeded(title: String, types: Set<LocalMediaRepository.MediaType>) {
        render(
            title = title,
            subtitle = getString(R.string.library_media_access_required),
            rows = emptyList(),
            empty = AutoBridgeDesign.emptyState(
                context = this,
                title = getString(R.string.library_needs_media_access),
                message = getString(R.string.library_media_access_message, title),
                action = getString(R.string.library_allow_access) to { requestMediaPermission(types) },
                accent = accent
            )
        )
    }

    /** Asks for [types]; the page is re-rendered from [onRequestPermissionsResult] either way. */
    private fun requestMediaPermission(types: Set<LocalMediaRepository.MediaType>) {
        val missing = LocalMediaRepository.missingPermissions(this, types)
        if (missing.isEmpty()) {
            refresh()
            return
        }
        if (permissionAsked) {
            // The system will not prompt again, so send the user where they can still change it.
            openAppSettings()
            return
        }
        permissionAsked = true
        requestPermissions(missing.toTypedArray(), REQUEST_MEDIA)
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }.onFailure { alert(getString(R.string.library_settings_unavailable), getString(R.string.library_grant_media_access)) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MEDIA) return
        // Re-render either way: a denial should show the permission state, not hang on a spinner.
        refresh()
    }

    private fun progressDialog(message: String): AlertDialog =
        AlertDialog.Builder(this).setMessage(message).setCancelable(false).show()

    private fun alert(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun durationText(durationMs: Long): String {
        if (durationMs <= 0L) return ""
        val totalSeconds = durationMs / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
}

/** Cap on rows drawn at once: these pages are plain view stacks, not recycling lists. */
private const val MAX_VISIBLE_ENTRIES = 300

/**
 * Cap on channels one explicit check probes. A page can show 300 tiles; probing them all would
 * hammer a handful of providers for answers the user did not ask about.
 */
private const val MAX_PING_ENTRIES = 60

/**
 * Cap on the automatic check a page runs when it opens — roughly the first two screenfuls of a
 * two-column grid. The automatic pass is traffic the user did not ask for, so it stays smaller
 * than what tapping the signal icon will do on request.
 */
private const val AUTO_PING_ENTRIES = 24

/** Tag on the entries page's header signal icon, so a landed check result can find it again. */
private const val SIGNAL_ACTION_TAG = "iptv-signal"
