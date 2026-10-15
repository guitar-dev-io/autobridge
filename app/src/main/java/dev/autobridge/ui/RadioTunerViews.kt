package dev.autobridge.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.radio.RadioSourceType
import dev.autobridge.radio.RadioStation
import dev.autobridge.radio.RadioUiState
import dev.autobridge.ui.AutoBridgeDesign.dp
import kotlin.math.abs

/**
 * The phone's portrait Radio tuner, drawn in the same programmatic View/Canvas style as the rest
 * of [AutoBridgeDesign] — no Compose.
 *
 * The page is a single scrollable column: a source selector (Internet Radio enabled, FM shown but
 * disabled with its honest unavailable reason, offline hidden entirely), a [RadioTunerDialView]
 * acting as the station selector, a now-playing block, a large transport row, and six presets.
 * Everything the user can press is at least a 44dp target, the active control is electric-blue
 * [AutoBridgeDesign.ACCENT], and the amber [AutoBridgeDesign.ACCENT_RADIO] is only ever the Radio
 * section's identity colour.
 *
 * This object builds views; it holds no state. The hosting activity owns [RadioUiState] and the
 * callbacks, re-reads the stores on resume, and rebuilds by calling [buildBody] again.
 */
object RadioTunerViews {

    /** Everything the tuner can ask its host to do. The host wires these to the radio domain. */
    class Callbacks(
        /** Switch the user's selected source (Internet Radio / FM). */
        val onSelectSource: (RadioSourceType) -> Unit,
        /** Play/pause the shared session. */
        val onPlayPause: () -> Unit,
        /** Step to the previous / next station in the live queue. */
        val onPrevious: () -> Unit,
        val onNext: () -> Unit,
        /** Toggle the currently-selected station as a favourite. */
        val onToggleFavorite: () -> Unit,
        /** Toggle a specific station as a favourite (used by favourite/recent rows). */
        val onToggleStationFavorite: (RadioStation) -> Unit,
        /** Recall a saved preset (1..6); null station means an empty slot was tapped. */
        val onRecallPreset: (slot: Int, station: RadioStation?) -> Unit,
        /** Long-press a preset slot to save the current station into it. */
        val onSavePreset: (slot: Int) -> Unit,
        /** Open the station search. */
        val onSearch: () -> Unit,
        /** Play a specific station (a favourite or a recent tapped in its row). */
        val onPlayStation: (RadioStation) -> Unit,
        /** Open the full recently-played list. */
        val onOpenRecents: () -> Unit,
    )

    /**
     * Builds the tuner body as a list of rows the host stacks into [AutoBridgeDesign.body].
     *
     * [capDial] caps the dial height for a landscape/compact fallback; portrait passes false so the
     * dial takes its designed size.
     */
    fun buildBody(
        context: Context,
        state: RadioUiState,
        callbacks: Callbacks,
        capDial: Boolean = false,
    ): List<View> {
        val rows = mutableListOf<View>()

        rows += sourceSelector(context, state, callbacks)
        rows += dialCard(context, state, callbacks, capDial)
        rows += nowPlaying(context, state, callbacks)
        rows += transportRow(context, state, callbacks)
        rows += AutoBridgeDesign.sectionLabel(context, context.getString(R.string.radio_presets))
        rows += presetsGrid(context, state, callbacks)

        if (state.favorites.isNotEmpty()) {
            rows += AutoBridgeDesign.sectionLabel(context, context.getString(R.string.section_favorites))
            state.favorites.take(6).forEach { station ->
                rows += stationRow(context, station, state, callbacks)
            }
        }
        if (state.recents.isNotEmpty()) {
            rows += recentsHeader(context, callbacks)
            state.recents.take(6).forEach { station ->
                rows += stationRow(context, station, state, callbacks)
            }
        }
        return rows
    }

    // ----- Source selector (Internet enabled, FM disabled, offline hidden) -----

    private fun sourceSelector(context: Context, state: RadioUiState, callbacks: Callbacks): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val segments = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE, 18)
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
        }
        // Internet Radio: always enabled, the only source that can play here.
        segments.addView(
            segment(
                context,
                label = context.getString(R.string.radio_source_internet),
                selected = state.selectedSource == RadioSourceType.INTERNET_RADIO,
                enabled = true,
            ) { callbacks.onSelectSource(RadioSourceType.INTERNET_RADIO) },
            LinearLayout.LayoutParams(0, context.dp(44), 1f),
        )
        // Hardware FM: shown so the user knows it exists, but disabled — this device cannot tune it.
        segments.addView(
            segment(
                context,
                label = context.getString(R.string.radio_source_fm),
                selected = state.selectedSource == RadioSourceType.HARDWARE_FM,
                enabled = false,
            ) { callbacks.onSelectSource(RadioSourceType.HARDWARE_FM) },
            LinearLayout.LayoutParams(0, context.dp(44), 1f).apply { marginStart = context.dp(4) },
        )
        // The OFFLINE_AUDIO segment is intentionally not added: offline is unwired this pass and a
        // disabled third segment would only be noise.
        container.addView(segments, LinearLayout.LayoutParams(-1, -2))

        // When FM is the selection, say plainly why nothing is playing from it.
        if (state.selectedSource == RadioSourceType.HARDWARE_FM) {
            val reason = state.fmUnavailableReason ?: context.getString(R.string.radio_fm_unavailable)
            container.addView(TextView(context).apply {
                text = reason
                textSize = 12f
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
                setPadding(context.dp(6), context.dp(6), context.dp(6), 0)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        return container
    }

    private fun segment(
        context: Context,
        label: String,
        selected: Boolean,
        enabled: Boolean,
        onClick: () -> Unit,
    ): View = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // The active segment uses the electric-blue accent; a disabled FM segment reads muted.
        setTextColor(
            when {
                selected && enabled -> AutoBridgeDesign.INK
                enabled -> AutoBridgeDesign.TEXT
                else -> AutoBridgeDesign.TEXT_MUTED
            }
        )
        background = when {
            selected && enabled -> AutoBridgeDesign.surface(context, AutoBridgeDesign.ACCENT, 14, AutoBridgeDesign.ACCENT)
            selected -> AutoBridgeDesign.surface(context, AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT_RADIO, 0.18f), 14, AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT_RADIO, 0.4f))
            else -> null
        }
        isClickable = true
        isFocusable = true
        contentDescription = label
        // A disabled segment is still tappable so the FM reason/panel can appear; it simply never
        // becomes active in the coordinator (the host records the selection and keeps internet live).
        setOnClickListener { onClick() }
    }

    // ----- The dial / station selector -----

    private fun dialCard(context: Context, state: RadioUiState, callbacks: Callbacks, capDial: Boolean): View {
        val fmSelected = state.selectedSource == RadioSourceType.HARDWARE_FM
        val card = FrameLayout(context).apply {
            background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE, 20)
        }
        val dial = RadioTunerDialView(context).apply {
            bind(state)
            onStep = { forward -> if (forward) callbacks.onNext() else callbacks.onPrevious() }
            // FM is not playable: the dial is inert when FM is the selection.
            interactive = !fmSelected
        }
        val height = context.dp(if (capDial) 150 else 190)
        card.addView(dial, FrameLayout.LayoutParams(-1, height))
        return card
    }

    // ----- Now playing -----

    private fun nowPlaying(context: Context, state: RadioUiState, callbacks: Callbacks): View {
        val fmSelected = state.selectedSource == RadioSourceType.HARDWARE_FM
        val station = state.selected
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE, 18)
            setPadding(context.dp(12), context.dp(12), context.dp(10), context.dp(12))
        }
        val artwork = FrameLayout(context).apply {
            background = AutoBridgeDesign.surface(context, AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT_RADIO, 0.16f), 14, AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT_RADIO, 0.3f))
            clipToOutline = true
        }
        val initial = TextView(context).apply {
            text = (station?.title?.trim()?.take(1) ?: "♪").uppercase()
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(AutoBridgeDesign.ACCENT_RADIO)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        artwork.addView(initial, FrameLayout.LayoutParams(-1, -1))
        val logo = station?.logo
        if (!logo.isNullOrBlank()) {
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
                setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            artwork.addView(image, FrameLayout.LayoutParams(-1, -1))
            ImageLoader.load(context, logo, image) {
                initial.visibility = View.GONE
                image.visibility = View.VISIBLE
            }
        }
        row.addView(artwork, LinearLayout.LayoutParams(context.dp(56), context.dp(56)))

        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(12), 0, context.dp(8), 0)
        }
        val title = when {
            fmSelected -> context.getString(R.string.radio_source_fm)
            station != null -> station.title
            else -> context.getString(R.string.radio_nothing_playing)
        }
        text.addView(TextView(context).apply {
            this.text = title
            textSize = 17f
            setTextColor(AutoBridgeDesign.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val subtitle = when {
            fmSelected -> state.fmUnavailableReason ?: context.getString(R.string.radio_fm_unavailable)
            // frequencyKHz is always null for internet stations, so no FM frequency is ever shown.
            station?.subtitle?.isNotBlank() == true -> station.subtitle
            else -> context.getString(R.string.radio_source_internet)
        }
        text.addView(TextView(context).apply {
            this.text = subtitle
            textSize = 13f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, context.dp(2), 0, 0)
        })
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))

        // Favourite toggle, only meaningful for a real (persistable) internet station.
        val canFavorite = !fmSelected && station != null && station.sourceType != RadioSourceType.HARDWARE_FM
        val selectedUrl = station?.streamUrl
        val isFavorite = canFavorite && state.favorites.any { fav -> fav.streamUrl == selectedUrl }
        row.addView(
            AutoBridgeDesign.glyphButton(
                context,
                if (isFavorite) "★" else "☆",
                20f,
                tint = if (isFavorite) AutoBridgeDesign.ACCENT_FAVORITE else AutoBridgeDesign.TEXT_MUTED,
            ) { if (canFavorite) callbacks.onToggleFavorite() },
            LinearLayout.LayoutParams(context.dp(44), context.dp(44)),
        )
        return row
    }

    // ----- Transport -----

    private fun transportRow(context: Context, state: RadioUiState, callbacks: Callbacks): View {
        val fmSelected = state.selectedSource == RadioSourceType.HARDWARE_FM
        val enabled = !fmSelected
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(
            transportButton(context, "⏮", filled = false, enabled = enabled, label = context.getString(R.string.action_previous)) {
                if (enabled) callbacks.onPrevious()
            },
            LinearLayout.LayoutParams(context.dp(60), context.dp(60)),
        )
        // The play/pause is the primary control: electric-blue filled circle.
        row.addView(
            transportButton(
                context,
                if (state.isPlaying) "⏸" else "▶",
                filled = true,
                enabled = enabled,
                label = context.getString(if (state.isPlaying) R.string.radio_pause else R.string.radio_play),
            ) { if (enabled) callbacks.onPlayPause() },
            LinearLayout.LayoutParams(context.dp(72), context.dp(72)).apply {
                marginStart = context.dp(20)
                marginEnd = context.dp(20)
            },
        )
        row.addView(
            transportButton(context, "⏭", filled = false, enabled = enabled, label = context.getString(R.string.action_next)) {
                if (enabled) callbacks.onNext()
            },
            LinearLayout.LayoutParams(context.dp(60), context.dp(60)),
        )
        return row
    }

    private fun transportButton(
        context: Context,
        glyph: String,
        filled: Boolean,
        enabled: Boolean,
        label: String,
        onClick: () -> Unit,
    ): View = TextView(context).apply {
        text = glyph
        textSize = if (filled) 26f else 22f
        gravity = Gravity.CENTER
        contentDescription = label
        isClickable = true
        isFocusable = true
        val fill = when {
            !enabled -> AutoBridgeDesign.SURFACE
            filled -> AutoBridgeDesign.ACCENT
            else -> AutoBridgeDesign.SURFACE_RAISED
        }
        setTextColor(
            when {
                !enabled -> AutoBridgeDesign.TEXT_MUTED
                filled -> AutoBridgeDesign.INK
                else -> AutoBridgeDesign.TEXT
            }
        )
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (!filled) setStroke(context.dp(1).coerceAtLeast(1), AutoBridgeDesign.HAIRLINE)
        }
        alpha = if (enabled) 1f else 0.6f
        setOnClickListener { onClick() }
    }

    // ----- Presets 1..6 -----

    private fun presetsGrid(context: Context, state: RadioUiState, callbacks: Callbacks): View {
        val tiles = (0 until 6).map { index ->
            presetTile(context, index, state.presets.getOrNull(index), callbacks)
        }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        tiles.chunked(3).forEach { rowTiles ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowTiles.forEachIndexed { i, tile ->
                row.addView(tile, LinearLayout.LayoutParams(0, context.dp(60), 1f).apply {
                    marginStart = if (i == 0) 0 else context.dp(8)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) })
        }
        return grid
    }

    private fun presetTile(
        context: Context,
        index: Int,
        station: RadioStation?,
        callbacks: Callbacks,
    ): View {
        val slot = index + 1
        val filled = station != null
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = AutoBridgeDesign.tappable(
                context,
                AutoBridgeDesign.SURFACE,
                14,
                AutoBridgeDesign.ACCENT,
                if (filled) AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT, 0.4f) else AutoBridgeDesign.HAIRLINE,
            )
            isClickable = true
            isFocusable = true
            setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
            contentDescription = context.getString(R.string.radio_preset_slot, slot)
            // Tap recalls (empty slot asks the host, which saves the current station there).
            setOnClickListener { callbacks.onRecallPreset(slot, station) }
            // Long-press saves the current station into this slot — inline, no modal stack.
            setOnLongClickListener { callbacks.onSavePreset(slot); true }
        }
        view.addView(TextView(context).apply {
            text = slot.toString()
            textSize = 11f
            setTextColor(if (filled) AutoBridgeDesign.ACCENT else AutoBridgeDesign.TEXT_MUTED)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        view.addView(TextView(context).apply {
            text = station?.title ?: context.getString(R.string.radio_preset_slot, slot)
            textSize = 13f
            setTextColor(if (filled) AutoBridgeDesign.TEXT else AutoBridgeDesign.TEXT_MUTED)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        })
        return view
    }

    // ----- Favourites / recents rows & header -----

    private fun recentsHeader(context: Context, callbacks: Callbacks): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                AutoBridgeDesign.sectionLabel(context, context.getString(R.string.radio_recently_played)),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(TextView(context).apply {
                text = context.getString(R.string.radio_view_all)
                textSize = 12f
                setTextColor(AutoBridgeDesign.ACCENT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(context.dp(10), context.dp(6), context.dp(6), context.dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener { callbacks.onOpenRecents() }
            }, LinearLayout.LayoutParams(-2, -2))
        }

    private fun stationRow(
        context: Context,
        station: RadioStation,
        state: RadioUiState,
        callbacks: Callbacks,
    ): View {
        val isFavorite = state.favorites.any { it.streamUrl == station.streamUrl }
        return AutoBridgeDesign.contentRow(
            context = context,
            title = station.title,
            subtitle = station.subtitle,
            accent = AutoBridgeDesign.ACCENT_RADIO,
            artworkUrl = station.logo,
            trailing = if (isFavorite) "★" else "☆",
            onTrailing = { callbacks.onToggleStationFavorite(station) },
            onClick = { callbacks.onPlayStation(station) },
        )
    }

    /**
     * The station selector dial: a horizontal strip the user swipes/flings to step through the
     * live queue. A settle (lift after a swipe past the threshold, or a fling) fires [onStep].
     *
     * It draws a genuine frequency scale ONLY when the bound station carries a non-null
     * [RadioStation.frequencyKHz]. On this target that is never the case (no accessible tuner), so
     * no FM frequency scale is ever invented for an internet station — the dial shows the station
     * name with neighbour hints instead.
     */
    class RadioTunerDialView(context: Context) : View(context) {

        /** Called with forward=true for "next", false for "previous", once per settle. */
        var onStep: ((forward: Boolean) -> Unit)? = null

        /** When false (FM selected), the dial ignores touches. */
        var interactive: Boolean = true

        private var title: String = ""
        private var frequencyKHz: Int? = null
        private var hasQueue: Boolean = false

        private var downX = 0f
        private var dragX = 0f
        private var dragging = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AutoBridgeDesign.ACCENT
            strokeWidth = context.dp(3).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AutoBridgeDesign.HAIRLINE
            strokeWidth = context.dp(1).toFloat().coerceAtLeast(1f)
        }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AutoBridgeDesign.TEXT
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AutoBridgeDesign.TEXT_MUTED
            textAlign = Paint.Align.CENTER
        }

        fun bind(state: RadioUiState) {
            val station = state.selected
            title = when {
                state.selectedSource == RadioSourceType.HARDWARE_FM ->
                    context.getString(R.string.radio_source_fm)
                station != null -> station.title
                else -> context.getString(R.string.radio_nothing_playing)
            }
            frequencyKHz = station?.frequencyKHz
            hasQueue = station != null && state.selectedSource != RadioSourceType.HARDWARE_FM
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!interactive || !hasQueue) return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    dragX = 0f
                    dragging = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    dragX = event.x - downX
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = event.x - downX
                    if (dragging && abs(moved) > touchSlop * 3) {
                        // Swiping the dial left reveals the station to the right (next), like a reel.
                        onStep?.invoke(moved < 0)
                    } else if (dragging && abs(moved) <= touchSlop) {
                        performClick()
                    }
                    dragging = false
                    dragX = 0f
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    dragX = 0f
                    invalidate()
                    return true
                }
            }
            return false
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val baseY = h * 0.62f

            // A row of ticks suggesting a tuner band; the centre tick is the accent needle. This is
            // decorative on this target — it is NOT a frequency scale (no frequencyKHz exists).
            val tickCount = 21
            val spacing = w / (tickCount + 1)
            val drift = (dragX / 4f)
            for (i in 1..tickCount) {
                val x = i * spacing + drift
                if (x < 0 || x > w) continue
                val tall = (i % 5 == 0)
                val top = baseY - context.dp(if (tall) 16 else 9)
                canvas.drawLine(x, top, x, baseY, tickPaint)
            }

            // The accent needle marks the current station in the centre.
            canvas.drawLine(cx, baseY - context.dp(26), cx, baseY + context.dp(6), needlePaint)

            // Station name (or a genuine frequency, if one ever existed — never on this target).
            titlePaint.textSize = context.dp(20).toFloat()
            val label = frequencyKHz?.let { freqLabel(it) } ?: title
            canvas.drawText(ellipsize(label, titlePaint, w - context.dp(48)), cx, baseY - context.dp(40), titlePaint)

            // Swipe affordance under the band when there is a queue to step through.
            if (hasQueue && interactive) {
                hintPaint.textSize = context.dp(12).toFloat()
                canvas.drawText(
                    context.getString(R.string.radio_dial_hint),
                    cx,
                    baseY + context.dp(30),
                    hintPaint,
                )
            }
        }

        /** A real frequency, kHz -> "MHz" with one decimal. Only reached if frequencyKHz != null. */
        private fun freqLabel(khz: Int): String {
            val mhz = khz / 1000.0
            return String.format(java.util.Locale.US, "%.1f FM", mhz)
        }

        private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
            if (paint.measureText(text) <= maxWidth) return text
            var end = text.length
            while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
            return text.substring(0, end) + "…"
        }
    }
}
