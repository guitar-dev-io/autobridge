package dev.autobridge.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R

/**
 * The car browser menu as ordinary views, for the two car browsers that are real view trees:
 * Bridge Web (the projection route) and the browser in a Duo Screen pane.
 *
 * The Android Auto template browser draws the same menu on a Canvas from the same
 * [BrowserDrawerModel] lists. This builds rows from [BrowserDrawerModel.carMenu], so all three
 * offer the same entries, in the same order, under the same names; each host only supplies its
 * colours and what tapping an entry does.
 */
object CarMenuList {

    /** The host's colours. Everything else (labels, icons, order, state) comes from the model. */
    data class Style(
        val text: Int,
        val textSecondary: Int,
        val rowFill: Int,
        val accent: Int,
        val onAccent: Int,
    )

    /**
     * The menu for [state] as a compact grid: one line per subject (tabs, navigation, saved
     * pages, split screen, page view, page tools, address, places, app), each a row of up to four
     * buttons. A one-row-per-entry list ran past the bottom of a portrait head unit; grouped, the
     * whole menu fits and related things sit together.
     *
     * The primary entry (Tabs, or the start page on a browser without tabs) is accent-filled; a
     * switch that is on (desktop, fullscreen, pinned toolbar, the split) is too. A disabled entry
     * (Back with nothing behind it, Swap sides with no split up) is dimmed and does nothing.
     * Anything the layout does not place (a car entry added later) still appears, three to a row.
     */
    fun build(
        context: Context,
        state: BrowserMenuState,
        style: Style,
        onAction: (DrawerAction) -> Unit,
    ): List<View> {
        val items = BrowserDrawerModel.carMenu(state)
        val primary = items.firstOrNull()?.action ?: return emptyList()
        return rows(items).map { buttonRow(context, it, style, onAction, primary) }
    }

    /**
     * [items] (a [BrowserDrawerModel.carMenu] list, primary first) arranged into the grid's lines:
     * the primary entry with New tab, then [LAYOUT], then whatever is left three to a line. Every
     * item lands exactly once.
     */
    internal fun rows(items: List<DrawerItem>): List<List<DrawerItem>> {
        val primary = items.firstOrNull() ?: return emptyList()
        val byAction = items.associateBy { it.action }
        val emitted = HashSet<DrawerAction>()
        val rows = ArrayList<List<DrawerItem>>()
        fun emit(row: List<DrawerItem>) {
            val fresh = row.filter { emitted.add(it.action) }
            if (fresh.isNotEmpty()) rows += fresh
        }
        // Lives in Split options as "side page on the right"; a second control for it here was
        // one more button in an already crowded line.
        emitted += DrawerAction.SWAP_SPLIT_SIDES
        emit(listOfNotNull(primary, byAction[DrawerAction.NEW_TAB]))
        LAYOUT.forEach { group -> emit(group.mapNotNull { byAction[it] }) }
        items.filterNot { it.action in emitted }.chunked(3).forEach(::emit)
        return rows
    }

    /** The lines of the grid, top to bottom; each is one subject. */
    private val LAYOUT = listOf(
        listOf(DrawerAction.NAV_BACK, DrawerAction.RELOAD, DrawerAction.NAV_FORWARD),
        listOf(DrawerAction.BOOKMARKS, DrawerAction.BOOKMARK_PAGE, DrawerAction.HISTORY, DrawerAction.DOWNLOADS),
        // Swap sides is a switch inside Split options, not a button of its own here.
        listOf(DrawerAction.SPLIT_LAYOUT, DrawerAction.SPLIT_CHOOSE, DrawerAction.SIDE_SHOW_PAGE),
        listOf(DrawerAction.TOGGLE_DESKTOP, DrawerAction.TOGGLE_FULLSCREEN, DrawerAction.PIN_TOOLBAR),
        listOf(DrawerAction.FIND_IN_PAGE, DrawerAction.ZOOM_OUT, DrawerAction.ZOOM_IN),
        listOf(DrawerAction.COPY_URL, DrawerAction.PASTE_AND_GO, DrawerAction.OPEN_EXTERNAL),
        listOf(DrawerAction.HOME, DrawerAction.NAVIGATE_MAPS, DrawerAction.MIRROR_PHONE),
        listOf(DrawerAction.SETTINGS, DrawerAction.CLEAR_DATA, DrawerAction.DIAGNOSTICS, DrawerAction.APP_HOME),
    )

    /**
     * The menu's title line: the [title] and a round ✕ that calls [onClose]. Dragging the line
     * down ([dragToClose]) closes the menu as well.
     */
    fun header(context: Context, title: String, style: Style, onClose: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(52)
            addView(TextView(context).apply {
                text = title
                setTextColor(style.textSecondary)
                textSize = 15f
                setPadding(context.dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(BrowserIcon.CLOSE.resId)
                setColorFilter(style.text)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = context.getString(R.string.browser_close)
                val inset = context.dp(13)
                setPadding(inset, inset, inset, inset)
                background = RippleDrawable(
                    ColorStateList.valueOf(style.textSecondary),
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(style.rowFill)
                    },
                    null
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { onClose() }
            }, LinearLayout.LayoutParams(context.dp(52), context.dp(52)))
        }

    /**
     * Lets [handle] be dragged downwards to close [panel]: the panel follows the finger, and a
     * drag past a quarter of its height (or 96dp) closes it with [onClose]; anything shorter
     * springs back. Only downward movement counts, so a stray upward drag never lifts the sheet.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun dragToClose(handle: View, panel: View, onClose: () -> Unit) {
        var startY = 0f
        var dragging = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    dragging = true
                    // The sheet sits in a ScrollView, which would otherwise take a vertical drag
                    // for itself after a few pixels.
                    handle.parent?.requestDisallowInterceptTouchEvent(true)
                    panel.animate().cancel()
                    // Consumed so the moves that follow come here. A touch on the ✕ never gets
                    // this far: the button takes its own touches before the line sees them.
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    panel.translationY = (event.rawY - startY).coerceAtLeast(0f)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return@setOnTouchListener false
                    dragging = false
                    val dy = panel.translationY
                    val threshold = minOf(panel.height * 0.25f, handle.context.dp(96).toFloat())
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP && dy > threshold) {
                        panel.animate().translationY(panel.height.toFloat()).setDuration(150)
                            .withEndAction { onClose() }.start()
                    } else {
                        panel.animate().translationY(0f).setDuration(150).start()
                    }
                    dy > 0f
                }
                else -> false
            }
        }
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun background(context: Context, fill: Int, ripple: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(ripple),
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(14).toFloat()
            setColor(fill)
        },
        null
    )

    /** The switches report their state as On/Off; a tab count or ✓ shows as given. */
    private fun detail(context: Context, item: DrawerItem): String = when (item.action) {
        DrawerAction.TOGGLE_DESKTOP, DrawerAction.TOGGLE_FULLSCREEN, DrawerAction.PIN_TOOLBAR,
        DrawerAction.SPLIT_LAYOUT ->
            context.getString(if (item.on) R.string.browser_menu_on else R.string.browser_menu_off)
        else -> item.value.takeIf { it != "✓" }.orEmpty()
    }

    private fun icon(context: Context, item: DrawerItem, tint: Int): ImageView = ImageView(context).apply {
        setImageResource(item.icon.resId)
        setColorFilter(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Entries that are on/off settings: drawn with a switch rather than as a plain button. */
    private val SWITCHES = setOf(
        DrawerAction.TOGGLE_DESKTOP, DrawerAction.TOGGLE_FULLSCREEN,
        DrawerAction.PIN_TOOLBAR, DrawerAction.SPLIT_LAYOUT,
    )

    /**
     * A switch, drawn: a rounded track with a round knob at the "on" end in the accent, or at the
     * other end in grey. Display only; the row or tile around it takes the tap, so the whole
     * line is the target rather than a 44dp control.
     */
    fun switchView(context: Context, on: Boolean, style: Style): View {
        val width = context.dp(44)
        val height = context.dp(26)
        val knob = context.dp(20)
        return android.widget.FrameLayout(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = height / 2f
                setColor(if (on) style.accent else style.textSecondary)
                alpha = if (on) 255 else 110
            }
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (on) style.onAccent else style.text)
                }
            }, android.widget.FrameLayout.LayoutParams(knob, knob).apply {
                gravity = Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)
                marginStart = context.dp(3)
                marginEnd = context.dp(3)
            })
            layoutParams = ViewGroup.LayoutParams(width, height)
            minimumWidth = width
            minimumHeight = height
        }
    }

    /**
     * A settings line: [label] on the left, a [switchView] on the right, the whole line toggling.
     * The state is the switch; no "On"/"Off" text.
     */
    fun switchRow(context: Context, label: String, on: Boolean, style: Style, onToggle: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = background(context, style.rowFill, style.textSecondary)
            minimumHeight = context.dp(60)
            setPadding(context.dp(16), context.dp(6), context.dp(14), context.dp(6))
            contentDescription = "$label, ${context.getString(if (on) R.string.browser_menu_on else R.string.browser_menu_off)}"
            isClickable = true
            isFocusable = true
            setOnClickListener { onToggle() }
            addView(TextView(context).apply {
                text = label
                setTextColor(style.text)
                textSize = 17f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(switchView(context, on, style), LinearLayout.LayoutParams(context.dp(44), context.dp(26)).apply {
                marginStart = context.dp(12)
            })
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(6) }
        }

    /**
     * The split layouts as picture cards, two to a line: each draws its two panes at their real
     * proportions (the side page in the accent, on the side it will be on), with its name under
     * it. [current] is outlined. Tapping a card calls [onPick].
     */
    fun splitLayoutCards(
        context: Context,
        current: BrowserSplitLayout?,
        sideOnRight: Boolean,
        style: Style,
        onPick: (BrowserSplitLayout) -> Unit,
    ): List<View> = BrowserSplitLayout.entries
        .filter { it != BrowserSplitLayout.SINGLE }
        .chunked(2)
        .map { pair ->
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEachIndexed { index, layout ->
                    addView(splitCard(context, layout, layout == current, sideOnRight, style, onPick),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (index > 0) marginStart = context.dp(8)
                        })
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = context.dp(8) }
            }
        }

    /** The side page's share of the width in each layout's picture. */
    private fun sideShare(layout: BrowserSplitLayout): Float = when (layout) {
        BrowserSplitLayout.SINGLE -> 0f
        BrowserSplitLayout.HALF -> 0.5f
        BrowserSplitLayout.FORTY_SIXTY -> 0.4f
        BrowserSplitLayout.SIXTY_FIVE_THIRTY_FIVE -> 0.35f
        BrowserSplitLayout.PORTRAIT_LANDSCAPE -> 0.3f
    }

    private fun splitCard(
        context: Context,
        layout: BrowserSplitLayout,
        selected: Boolean,
        sideOnRight: Boolean,
        style: Style,
        onPick: (BrowserSplitLayout) -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(8))
        background = RippleDrawable(
            ColorStateList.valueOf(style.textSecondary),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.dp(14).toFloat()
                setColor(style.rowFill)
                if (selected) setStroke(context.dp(3), style.accent)
            },
            null
        )
        val label = layout.label(context)
        contentDescription = label
        isClickable = true
        isFocusable = true
        setOnClickListener { onPick(layout) }
        val side = sideShare(layout)
        fun pane(fill: Int) = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.dp(4).toFloat()
                setColor(fill)
            }
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val sidePane = pane(if (selected) style.accent else style.textSecondary)
            val mainPane = pane(style.text).apply { alpha = 0.35f }
            val sideParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, side)
            val mainParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - side)
            if (sideOnRight) {
                addView(mainPane, mainParams)
                addView(sidePane, sideParams.apply { marginStart = context.dp(3) })
            } else {
                addView(sidePane, sideParams)
                addView(mainPane, mainParams.apply { marginStart = context.dp(3) })
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(44)))
        addView(TextView(context).apply {
            text = label
            setTextColor(if (selected) style.text else style.textSecondary)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 2
            if (selected) typeface = Typeface.create(typeface, Typeface.BOLD)
            setPadding(0, context.dp(6), 0, 0)
        })
    }

    /**
     * Up to four equal buttons on one line: an icon over a label of at most two lines. The
     * primary entry and any switch that is on are accent-filled, so the grid says which page
     * opens on Tabs and which of its switches are set.
     */
    private fun buttonRow(
        context: Context,
        items: List<DrawerItem>,
        style: Style,
        onAction: (DrawerAction) -> Unit,
        primary: DrawerAction,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { index, item ->
            val isSwitch = item.action in SWITCHES
            val filled = item.enabled && !isSwitch && (item.on || item.action == primary)
            val fg = if (filled) style.onAccent else style.text
            val label = item.label(context)
            val state = detail(context, item)
            val shown = if (item.action == primary && state.isNotBlank()) "$label ($state)" else label
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = background(context, if (filled) style.accent else style.rowFill, style.textSecondary)
                minimumHeight = context.dp(64)
                setPadding(context.dp(4), context.dp(8), context.dp(4), context.dp(8))
                contentDescription = if (state.isBlank()) label else "$label, $state"
                alpha = if (item.enabled) 1f else 0.4f
                isClickable = item.enabled
                isFocusable = item.enabled
                if (item.enabled) setOnClickListener { onAction(item.action) }
                addView(icon(context, item, fg), LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
                addView(TextView(context).apply {
                    text = shown
                    setTextColor(fg)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    if (item.action == primary) typeface = Typeface.create(typeface, Typeface.BOLD)
                })
                if (isSwitch) {
                    addView(switchView(context, item.on, style), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = context.dp(4) })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (index > 0) marginStart = context.dp(6)
            })
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(6) }
    }
}

/**
 * "About" for the car browsers that have no access to the Android Auto diagnostics screen: what is
 * running and on what, as label/value pairs, so a report from the car names the build, the WebView
 * and the display the page was laid out for.
 */
object CarBrowserAbout {
    fun lines(context: Context): List<Pair<String, String>> {
        val metrics = context.resources.displayMetrics
        val webView = runCatching {
            androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context)
                ?.let { "${it.packageName} ${it.versionName}" }
        }.getOrNull() ?: "-"
        return listOf(
            context.getString(R.string.browser_about_version) to
                "${dev.autobridge.BuildConfig.VERSION_NAME} (${dev.autobridge.BuildConfig.VERSION_CODE})",
            context.getString(R.string.browser_about_webview) to webView,
            context.getString(R.string.car_diag_car_display) to
                "${metrics.widthPixels}×${metrics.heightPixels} @ ${metrics.densityDpi} dpi",
        )
    }
}
