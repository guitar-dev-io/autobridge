package dev.autobridge.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import dev.autobridge.entertainment.ContentKind
import dev.autobridge.entertainment.EntertainmentActivity
import dev.autobridge.entertainment.WebBookmarkStore
import dev.autobridge.iptv.IptvCatalog
import dev.autobridge.iptv.IptvCatalogData
import dev.autobridge.iptv.IptvDirectory
import dev.autobridge.iptv.IptvEntry
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.iptv.IptvKind
import dev.autobridge.iptv.IptvPlayback
import dev.autobridge.iptv.IptvSource
import dev.autobridge.iptv.IptvSourceStore
import dev.autobridge.iptv.IptvSourceType
import dev.autobridge.iptv.XtreamCredentials
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.MiniPlayer

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
    /** Which home tile opened this Activity. */
    enum class Section(val title: String, val accent: Int) {
        TV("TV", AutoBridgeDesign.ACCENT_TV),
        RADIO("Radio", AutoBridgeDesign.ACCENT_RADIO),
        FOLDERS("Folders", AutoBridgeDesign.ACCENT_FILES),
        PLAYLISTS("Playlists", AutoBridgeDesign.ACCENT_FILES),
        GALLERY("Gallery", AutoBridgeDesign.ACCENT_WEB),
        FAVORITES("Favorites", AutoBridgeDesign.ACCENT_FAVORITE),
        STREAMING("Streaming", AutoBridgeDesign.ACCENT_VIDEO)
    }

    companion object {
        const val EXTRA_SECTION = "dev.autobridge.extra.LIBRARY_SECTION"
        private const val REQUEST_MEDIA = 4711

        /** Below this a search field is clutter; above it, a long list is unusable without one. */
        private const val SEARCH_THRESHOLD = 12

        fun intent(context: android.content.Context, section: Section): Intent =
            Intent(context, LibraryActivity::class.java).putExtra(EXTRA_SECTION, section.name)
    }

    private val section by lazy {
        runCatching { Section.valueOf(intent.getStringExtra(EXTRA_SECTION).orEmpty()) }
            .getOrDefault(Section.TV)
    }
    private val accent get() = section.accent
    private val iptvKind get() = if (section == Section.RADIO) IptvKind.RADIO else IptvKind.TV

    private lateinit var playback: MediaPlaybackClient
    private var miniPlayer: MiniPlayer? = null
    private var resumedOnce = false

    /** A permission is asked for at most once per visit; after that the user is sent to settings. */
    private var permissionAsked = false

    /** Rendered pages, most recent last. Back pops one; popping the root finishes the Activity. */
    private val stack = ArrayDeque<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playback = MediaPlaybackClient(this)
        playback.connect()
        when (section) {
            Section.TV, Section.RADIO -> showSources()
            Section.FOLDERS -> showFolders()
            Section.PLAYLISTS -> showPlaylists()
            Section.GALLERY -> showGalleryAlbums()
            Section.FAVORITES -> showFavorites()
            Section.STREAMING -> showStreaming()
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
        miniPlayer?.stop()
        playback.disconnect()
    }

    override fun onBackPressed() {
        // The current page is the top of the stack, so dropping it and replaying the one beneath
        // restores the page the user came from without rebuilding the whole section. The previous
        // page stays on the stack: it is now the current one.
        stack.removeLastOrNull()
        val previous = stack.lastOrNull()
        if (previous == null) super.onBackPressed() else previous()
    }

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
     * body and the shared now-playing bar.
     */
    private fun render(
        title: String,
        subtitle: String?,
        rows: List<View>,
        empty: View? = null,
        onBack: () -> Unit = { onBackPressed() },
        actions: List<Pair<String, () -> Unit>> = emptyList(),
        search: Pair<String, (String) -> Unit>? = null
    ) {
        val body = AutoBridgeDesign.body(this)
        if (rows.isEmpty() && empty != null) body.addView(empty)
        else rows.forEach { body.stack(it) }

        val pinned = mutableListOf<View>()
        if (search != null) {
            pinned += AutoBridgeDesign.searchField(this, "Search", search.first, search.second)
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

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(this, title, subtitle, onBack = onBack),
                pinned = pinned,
                body = body,
                bottomBar = bottom
            )
        )
    }

    private fun openNowPlaying() {
        val playing = playback.currentTitle ?: return
        startActivity(
            PlayerActivity.intent(
                context = this,
                url = "",
                title = playing,
                subtitle = playback.currentArtist.orEmpty(),
                video = false
            )
        )
    }

    /** "1 entry" / "13 categories": a count the user reads, not a template with an s stuck on. */
    private fun plural(count: Int, singular: String, plural: String = singular + "s"): String =
        "$count " + if (count == 1) singular else plural

    // ----- IPTV: sources -> categories -> entries -----

    private fun showSources() = push {
        val sources = IptvSourceStore.list(this, iptvKind)
        val recent = IptvHistoryStore.recent(this, iptvKind)
        val rows = mutableListOf<View>()

        if (recent.isNotEmpty()) {
            rows += AutoBridgeDesign.contentRow(
                context = this,
                title = "Recently played",
                subtitle = "${recent.size} items",
                accent = accent,
                badgeText = "↺",
                trailing = "›",
                onClick = { showRecent() }
            )
        }
        sources.forEach { source ->
            rows += AutoBridgeDesign.contentRow(
                context = this,
                title = source.name,
                subtitle = sourceSubtitle(source),
                accent = accent,
                badgeText = if (source.type == IptvSourceType.XTREAM) "X" else "M",
                trailing = "⋯",
                onTrailing = { sourceMenu(source) },
                onClick = { openSource(source) }
            )
        }

        render(
            title = section.title,
            subtitle = if (sources.isEmpty()) "No source configured" else plural(sources.size, "source"),
            rows = rows,
            empty = AutoBridgeDesign.emptyState(
                context = this,
                title = "No ${section.title} source yet",
                message = "Pick a free public list, or add your own Xtream account or M3U playlist.",
                action = "Browse public lists" to { addFromDirectory() },
                accent = accent
            ),
            actions = listOf(
                "Public lists" to { addFromDirectory() },
                "+ Xtream" to { addSource(IptvSourceType.XTREAM) },
                "+ M3U" to { addSource(IptvSourceType.M3U) }
            )
        )
    }

    private fun sourceSubtitle(source: IptvSource): String {
        val type = if (source.type == IptvSourceType.XTREAM) "Xtream" else "M3U playlist"
        val cached = IptvCatalog.cached(source.id)
        return when {
            IptvCatalog.isLoading(source.id) -> "$type • loading…"
            cached != null -> "$type • " + plural(cached.entries.size, "entry", "entries")
            else -> "$type • ${hostOf(source.url)}"
        }
    }

    private fun openSource(source: IptvSource) {
        val cached = IptvCatalog.cached(source.id)
        if (cached != null) {
            showCategories(source, cached)
            return
        }
        val dialog = progressDialog("Loading ${source.name}…")
        IptvCatalog.load(this, source) { result ->
            dialog.dismiss()
            when (result) {
                is IptvCatalog.Result.Ready -> showCategories(source, result.data)
                is IptvCatalog.Result.Failed -> alert("Could not load ${source.name}", result.message)
            }
        }
    }

    private fun showCategories(source: IptvSource, data: IptvCatalogData) = push {
        val rows = data.categories.map { category ->
            AutoBridgeDesign.contentRow(
                context = this,
                title = category.name,
                subtitle = plural(category.count, "entry", "entries"),
                accent = accent,
                trailing = "›",
                onClick = { showEntries(source, data, category.id, category.name) }
            )
        }
        render(
            title = source.name,
            subtitle = plural(data.entries.size, "entry", "entries") + " • " +
                plural(data.categories.size, "category", "categories"),
            rows = rows,
            empty = AutoBridgeDesign.emptyState(
                this, "Nothing here", "This source returned no categories."
            ),
            actions = listOf("Refresh" to { refreshSource(source) })
        )
    }

    private fun refreshSource(source: IptvSource) {
        val dialog = progressDialog("Refreshing ${source.name}…")
        IptvCatalog.load(this, source, forceRefresh = true) { result ->
            dialog.dismiss()
            when (result) {
                is IptvCatalog.Result.Ready -> {
                    // Replace the stale categories page rather than stacking a second copy.
                    stack.removeLastOrNull()
                    showCategories(source, result.data)
                }
                is IptvCatalog.Result.Failed -> alert("Refresh failed", result.message)
            }
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
        // The page re-renders itself on every keystroke, so the filter lives outside the lambda.
        lateinit var draw: () -> Unit
        draw = {
            val filtered = if (query.isBlank()) all else {
                all.filter { it.title.contains(query, ignoreCase = true) }
            }
            val shown = filtered.take(MAX_VISIBLE_ENTRIES)
            val counted = if (query.isBlank()) plural(all.size, "entry", "entries")
            else "${filtered.size} of ${all.size} match \"$query\""
            render(
                title = categoryName,
                // A country-grouped public playlist puts thousands of channels in "All". Saying
                // "2080 entries" above 300 rows is a miscount the user has no way to notice.
                subtitle = if (shown.size < filtered.size) {
                    "$counted • showing first ${shown.size}, search to narrow"
                } else {
                    counted
                },
                rows = shown.map { entryRow(source, it, shown) },
                empty = AutoBridgeDesign.emptyState(
                    this, "No matches", "Nothing in this category matches that search."
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
        }
        push(draw)
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
        val row = AutoBridgeDesign.contentRow(
            context = this,
            title = entry.title,
            subtitle = listOfNotNull(
                entry.subtitle.takeIf { it.isNotBlank() },
                "Opens in browser".takeIf { entry.isWebPage },
                "Catch-up".takeIf { entry.supportsCatchup }
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

    /** Long-press menu for a TV entry: play here, or send it straight to the car's video screen. */
    private fun sendChannelMenu(source: IptvSource, entry: IptvEntry) {
        AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setItems(arrayOf("Play here", "Send to car")) { _, index ->
                when (index) {
                    0 -> openEntry(source, entry)
                    1 -> sendToCar(source, entry)
                }
            }
            .show()
    }

    /**
     * Sends a TV channel to the car's native video screen via the same command bus the Mobile
     * Remote uses, so it works whether Android Auto is connected right now or not — the router
     * reports NOT_CONNECTED instead of silently doing nothing.
     */
    private fun sendToCar(source: IptvSource, entry: IptvEntry) {
        IptvHistoryStore.recordPlayback(this, source, entry)
        dev.autobridge.remote.RemoteRuntime.ensureStarted(this)
        dev.autobridge.remote.AutoBridgeCommandBus.send(
            dev.autobridge.remote.AutoBridgeCommand(
                type = dev.autobridge.remote.CommandType.PLAY_VIDEO,
                payload = entry.url,
                source = dev.autobridge.remote.CommandSource.MOBILE,
                extras = mapOf("title" to entry.title)
            )
        )
        Toast.makeText(this, "Sending \"${entry.title}\" to the car…", Toast.LENGTH_SHORT).show()
    }

    private fun openEntry(
        source: IptvSource,
        entry: IptvEntry,
        siblings: List<IptvEntry> = emptyList()
    ) {
        if (entry.isSeriesFolder) {
            val dialog = progressDialog("Loading ${entry.title}…")
            IptvCatalog.loadEpisodes(source, entry) { episodes ->
                dialog.dismiss()
                if (episodes.isEmpty()) alert("No episodes", "The provider returned no episode list.")
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
            subtitle = "${episodes.size} episodes",
            rows = episodes.map { entryRow(source, it, episodes) },
            empty = AutoBridgeDesign.emptyState(this, "No episodes", "Nothing to play here.")
        )
    }

    private fun showRecent() = push {
        val items = IptvHistoryStore.recent(this, iptvKind)
        render(
            title = "Recently played",
            subtitle = "${items.size} items",
            rows = items.map { item ->
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = item.title,
                    subtitle = item.type.name.lowercase().replaceFirstChar { it.uppercase() },
                    accent = accent,
                    onClick = {
                        if (item.playback == IptvPlayback.WEB_PAGE) {
                            openWebChannel(item.url, item.title)
                        } else {
                            play(item.url, item.title, video = item.kind != IptvKind.RADIO)
                        }
                    }
                )
            },
            empty = AutoBridgeDesign.emptyState(this, "Nothing yet", "Channels you play show up here."),
            actions = listOf("Clear history" to {
                IptvHistoryStore.clearRecent(this)
                refresh()
            })
        )
    }

    // ----- Source editing -----

    private fun sourceMenu(source: IptvSource) {
        AlertDialog.Builder(this)
            .setTitle(source.name)
            .setItems(arrayOf("Refresh", "Edit", "Delete")) { _, index ->
                when (index) {
                    0 -> {
                        IptvCatalog.invalidate(source.id)
                        openSource(source)
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
            .setTitle("Remove ${source.name}?")
            .setMessage(
                if (IptvDirectory.list(iptvKind).any { it.url == source.url }) {
                    "This is one of the built-in public lists. It will not come back on its own, " +
                        "but you can add it again from \"Public lists\"."
                } else {
                    "The source is removed from this device. Channels you favourited stay."
                }
            )
            .setPositiveButton("Remove") { _, _ ->
                IptvSourceStore.remove(this, source.id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
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
            alert("No public lists", "There is no built-in list for this section.")
            return
        }
        val existing = IptvSourceStore.list(this).map { it.url }.toSet()
        val labels = offers.map { offer ->
            val suffix = if (offer.url in existing) " (already added)" else ""
            offer.name + "\n" + offer.note + suffix
        }
        AlertDialog.Builder(this)
            .setTitle("Free public lists")
            .setItems(labels.toTypedArray()) { _, index ->
                val offer = offers[index]
                if (offer.url in existing) {
                    alert(offer.name, "This list is already one of your sources.")
                    return@setItems
                }
                IptvSourceStore.save(this, IptvDirectory.toSource(offer, IptvSourceStore.newId()))
                refresh()
            }
            .setNegativeButton("Cancel", null)
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

        val name = field("Name", existing?.name.orEmpty())
        val url = field(
            if (xtream) "Portal URL or full player_api.php link" else "M3U playlist URL",
            existing?.url.orEmpty()
        )
        val username = if (xtream) field("Username", existing?.username.orEmpty()) else null
        val password = if (xtream) field("Password", existing?.password.orEmpty(), password = true) else null

        AlertDialog.Builder(this)
            .setTitle(
                if (existing == null) "Add ${if (xtream) "Xtream account" else "M3U playlist"}"
                else "Edit source"
            )
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val address = url.text.toString().trim()
                if (address.isEmpty()) {
                    alert("Missing URL", "Enter the provider address first.")
                    return@setPositiveButton
                }
                if (xtream) {
                    val credentials = XtreamCredentials.parse(
                        address, username?.text?.toString(), password?.text?.toString()
                    )
                    if (credentials == null) {
                        alert(
                            "Incomplete account",
                            "Enter a username and password, or paste a link that contains them."
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
                        alert("Invalid URL", "The playlist address must start with http:// or https://.")
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
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ----- Local library: folders, playlists, gallery -----

    private fun showFolders() = push {
        val types = setOf(LocalMediaRepository.MediaType.AUDIO, LocalMediaRepository.MediaType.VIDEO)
        if (!hasMediaPermission(types)) {
            renderPermissionNeeded("Folders", types)
            return@push
        }
        val folders = LocalMediaRepository.folders(this)
        render(
            title = "Folders",
            subtitle = "${folders.size} folders on this device",
            rows = folders.map { folder ->
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = folder.name,
                    subtitle = "${folder.count} files",
                    accent = accent,
                    badgeText = "▣",
                    trailing = "›",
                    onClick = { showFolderItems(folder) }
                )
            },
            empty = AutoBridgeDesign.emptyState(
                this, "No media folders", "No audio or video was found on this device."
            )
        )
    }

    private fun showFolderItems(folder: LocalMediaRepository.Group) = push {
        val items = LocalMediaRepository.folderItems(this, folder.id)
        val audio = items.filter { it.subtitle == "Audio" }
        render(
            title = folder.name,
            subtitle = "${items.size} files",
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
            empty = AutoBridgeDesign.emptyState(this, "Empty folder", "Nothing to play here.")
        )
    }

    private fun showPlaylists() = push {
        val types = setOf(LocalMediaRepository.MediaType.AUDIO)
        if (!hasMediaPermission(types)) {
            renderPermissionNeeded("Playlists", types)
            return@push
        }
        val playlists = LocalMediaRepository.playlists(this)
        val rows = mutableListOf<View>()
        rows += AutoBridgeDesign.contentRow(
            context = this,
            title = "All music",
            subtitle = "Every track on this device",
            accent = accent,
            badgeText = "♪",
            trailing = "›",
            onClick = { showTracks("All music", LocalMediaRepository.allAudio(this)) }
        )
        playlists.forEach { playlist ->
            rows += AutoBridgeDesign.contentRow(
                context = this,
                title = playlist.name,
                subtitle = "Playlist",
                accent = accent,
                badgeText = "≡",
                trailing = "›",
                onClick = { showTracks(playlist.name, LocalMediaRepository.playlistItems(this, playlist.id)) }
            )
        }
        render(
            title = "Playlists",
            subtitle = "${playlists.size} device playlists",
            rows = rows
        )
    }

    private fun showTracks(title: String, items: List<LocalMediaRepository.Item>) = push {
        render(
            title = title,
            subtitle = "${items.size} tracks",
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
                "No tracks",
                "Android 11 and later hide MediaStore playlists from apps, so this can be empty even when the playlist exists."
            )
        )
    }

    private fun showGalleryAlbums() = push {
        val types = setOf(LocalMediaRepository.MediaType.IMAGE, LocalMediaRepository.MediaType.VIDEO)
        if (!hasMediaPermission(types)) {
            renderPermissionNeeded("Gallery", types)
            return@push
        }
        val albums = LocalMediaRepository.galleryAlbums(this)
        render(
            title = "Gallery",
            subtitle = "${albums.size} albums",
            rows = albums.map { album ->
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = album.name,
                    subtitle = "${album.count} items",
                    accent = accent,
                    badgeText = "◱",
                    trailing = "›",
                    onClick = { showGalleryItems(album) }
                )
            },
            empty = AutoBridgeDesign.emptyState(
                this, "No albums", "No photos or videos were found on this device."
            )
        )
    }

    private fun showGalleryItems(album: LocalMediaRepository.Group) = push {
        val items = LocalMediaRepository.galleryItems(this, album.id)
        render(
            title = album.name,
            subtitle = "${items.size} items",
            rows = items.map { item ->
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = item.title,
                    subtitle = if (item.durationMs > 0) durationText(item.durationMs) else "Photo",
                    accent = accent,
                    artworkUrl = item.uri,
                    badgeText = if (item.durationMs > 0) "▶" else "◱",
                    onClick = { openGalleryItem(item) }
                )
            },
            empty = AutoBridgeDesign.emptyState(this, "Empty album", "Nothing in this album.")
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
        if (!opened) alert("No viewer", "No app on this phone can open that image.")
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
                subtitle = if (item.kind == IptvKind.RADIO) "Radio" else "TV",
                accent = if (item.kind == IptvKind.RADIO) {
                    AutoBridgeDesign.ACCENT_RADIO
                } else {
                    AutoBridgeDesign.ACCENT_TV
                },
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
            title = "Favorites",
            subtitle = "${channels.size} channels • ${bookmarks.size} pages",
            rows = rows,
            empty = AutoBridgeDesign.emptyState(
                this,
                "Nothing saved yet",
                "Star a channel in TV or Radio, or save a page in the browser, and it appears here."
            )
        )
    }

    // ----- Streaming -----

    /** The shared [StreamingLinks] catalog, grouped; each row opens the site in the browser. */
    private fun showStreaming() = push {
        val rows = mutableListOf<View>()
        StreamingLinks.grouped().forEach { (group, links) ->
            rows += AutoBridgeDesign.sectionLabel(this, group.title)
            links.forEach { link ->
                rows += AutoBridgeDesign.contentRow(
                    context = this,
                    title = link.title,
                    subtitle = hostOf(link.url),
                    accent = accent,
                    onClick = {
                        // Same intent as the YouTube home tile, so the browser is reused if open.
                        startActivity(
                            Intent(this, dev.autobridge.browser.BrowserActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                .setData(Uri.parse(link.url))
                        )
                    }
                )
            }
        }
        render(title = "Streaming", subtitle = "${StreamingLinks.all.size} sites", rows = rows)
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
            alert("Nothing to play", "This entry has no stream address.")
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
            subtitle = "Media access required",
            rows = emptyList(),
            empty = AutoBridgeDesign.emptyState(
                context = this,
                title = "AutoBridge needs media access",
                message = "Allow access to this device's audio, video and photos to browse $title. " +
                    "If the system no longer asks, grant it in Android settings.",
                action = "Allow access" to { requestMediaPermission(types) },
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
        }.onFailure { alert("Settings unavailable", "Grant media access from Android settings.") }
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
            .setPositiveButton("OK", null).show()
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
