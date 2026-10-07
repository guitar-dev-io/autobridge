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
     * The menu for [state] (docs/design/18_CarBrowserMenu.png, 19_CarBrowserMore.png), returned as
     * one view that holds both of its pages:
     *
     * - **Main page**: two lines of four equal buttons (Back · Reload · Forward · Tabs, then
     *   Save · Bookmarks · History · New tab), a "This page" card of switches and a zoom stepper,
     *   a "Split screen" card, and a "More" line (with Exit beside it when the browser has one).
     * - **More page**, opened in place: the rarer actions as grouped rows.
     *
     * The old menu put every entry in one grid of 23 buttons; this keeps the ones a driver reaches
     * for in reach and everything else one tap away. Every entry of [BrowserDrawerModel.carMenu]
     * still lands exactly once (see [plan]); one this layout does not know (added later) goes to
     * the More page's last group. Tapping an entry calls [onAction]; switching pages does not.
     */
    fun build(
        context: Context,
        state: BrowserMenuState,
        style: Style,
        onAction: (DrawerAction) -> Unit,
    ): List<View> {
        val items = BrowserDrawerModel.carMenu(state)
        if (items.isEmpty()) return emptyList()
        val plan = plan(items)
        val pages = android.widget.FrameLayout(context)
        lateinit var showMain: () -> Unit
        val showMore: () -> Unit = {
            pages.removeAllViews()
            pages.addView(morePage(context, plan, style, onAction) { showMain() })
        }
        showMain = {
            pages.removeAllViews()
            pages.addView(mainPage(context, plan, state, style, onAction, showMore))
        }
        showMain()
        pages.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return listOf(pages)
    }

    /** Where every entry goes. Pure, so the placement is asserted on the JVM. */
    internal data class Plan(
        /** Up to two lines of up to four buttons. */
        val grid: List<List<DrawerItem>>,
        /** "This page" switches: desktop, fullscreen, pinned toolbar. */
        val switches: List<DrawerItem>,
        /** Zoom out / zoom in, shown as one stepper line; null unless both exist. */
        val zoom: Pair<DrawerItem, DrawerItem>?,
        val split: DrawerItem?,
        val splitChoose: DrawerItem?,
        val sideShow: DrawerItem?,
        val exit: DrawerItem?,
        /** The More page: (group label, rows). */
        val more: List<Pair<Int, List<DrawerItem>>>,
    ) {
        /** Everything placed, in reading order. */
        val all: List<DrawerItem>
            get() = grid.flatten() + switches + listOfNotNull(zoom?.first, zoom?.second, split, splitChoose, sideShow, exit) +
                more.flatMap { it.second }
    }

    internal fun plan(items: List<DrawerItem>): Plan {
        val byAction = items.associateBy { it.action }
        val used = HashSet<DrawerAction>()
        // Lives in Split options as "side page on the right"; not a menu entry of its own.
        used += DrawerAction.SWAP_SPLIT_SIDES
        fun take(action: DrawerAction): DrawerItem? = byAction[action]?.takeIf { used.add(action) }
        fun firstOf(vararg actions: DrawerAction): DrawerItem? =
            actions.firstOrNull { it in byAction && it !in used }?.let(::take)

        // A browser without tabs (a Duo pane) puts the start page where Tabs would be.
        val line1 = listOfNotNull(
            take(DrawerAction.NAV_BACK), take(DrawerAction.RELOAD), take(DrawerAction.NAV_FORWARD),
            firstOf(DrawerAction.TABS, DrawerAction.HOME),
        )
        val line2 = listOfNotNull(
            take(DrawerAction.BOOKMARK_PAGE), take(DrawerAction.BOOKMARKS), take(DrawerAction.HISTORY),
            firstOf(DrawerAction.NEW_TAB, DrawerAction.DOWNLOADS, DrawerAction.HOME),
        )
        val switches = listOfNotNull(
            take(DrawerAction.TOGGLE_DESKTOP), take(DrawerAction.TOGGLE_FULLSCREEN), take(DrawerAction.PIN_TOOLBAR),
        )
        val zoom = if (DrawerAction.ZOOM_OUT in byAction && DrawerAction.ZOOM_IN in byAction) {
            take(DrawerAction.ZOOM_OUT)!! to take(DrawerAction.ZOOM_IN)!!
        } else {
            null
        }
        val split = take(DrawerAction.SPLIT_LAYOUT)
        val splitChoose = take(DrawerAction.SPLIT_CHOOSE)
        val sideShow = take(DrawerAction.SIDE_SHOW_PAGE)
        val exit = take(DrawerAction.APP_HOME)

        val page = listOfNotNull(
            take(DrawerAction.FIND_IN_PAGE), take(DrawerAction.COPY_URL),
            take(DrawerAction.PASTE_AND_GO), take(DrawerAction.OPEN_EXTERNAL),
        )
        val goTo = listOfNotNull(
            take(DrawerAction.HOME), take(DrawerAction.DOWNLOADS), take(DrawerAction.MEDIA_CENTER),
            take(DrawerAction.NOW_PLAYING), take(DrawerAction.MEDIA_LIBRARY), take(DrawerAction.AGENT),
        )
        val app = listOfNotNull(
            take(DrawerAction.NAVIGATE_MAPS), take(DrawerAction.MIRROR_PHONE), take(DrawerAction.SETTINGS),
            take(DrawerAction.CLEAR_DATA), take(DrawerAction.DIAGNOSTICS),
        ) + items.filter { used.add(it.action) }
        val more = listOf(
            R.string.drawer_section_page to page,
            R.string.drawer_section_goto to goTo,
            R.string.drawer_section_app to app,
        ).filter { it.second.isNotEmpty() }

        return Plan(
            grid = listOf(line1, line2).filter { it.isNotEmpty() },
            switches = switches,
            zoom = zoom,
            split = split,
            splitChoose = splitChoose,
            sideShow = sideShow,
            exit = exit,
            more = more,
        )
    }

    /** Clearer names than the shared list's, where the car menu's layout needs them. */
    private fun label(context: Context, item: DrawerItem, state: BrowserMenuState? = null): String =
        when (item.action) {
            DrawerAction.NAV_FORWARD -> context.getString(R.string.drawer_forward_short)
            DrawerAction.BOOKMARK_PAGE -> context.getString(
                if (state?.bookmarked == true) R.string.drawer_bookmark_saved else R.string.drawer_bookmark_save
            )
            DrawerAction.TOGGLE_DESKTOP -> context.getString(R.string.drawer_desktop_site)
            DrawerAction.SIDE_SHOW_PAGE -> context.getString(R.string.drawer_side_show_page_long)
            DrawerAction.FIND_IN_PAGE -> context.getString(R.string.drawer_find_long)
            DrawerAction.PASTE_AND_GO -> context.getString(R.string.drawer_paste_and_go_long)
            DrawerAction.OPEN_EXTERNAL -> context.getString(R.string.drawer_external_long)
            DrawerAction.NAVIGATE_MAPS -> context.getString(R.string.drawer_navigate_maps_long)
            else -> item.label(context)
        }

    /** Bookmarks gets a list icon so it no longer looks like a second "save" star. */
    private fun iconRes(item: DrawerItem, state: BrowserMenuState? = null): Int = when (item.action) {
        DrawerAction.BOOKMARKS -> R.drawable.ic_browser_bookmarks
        DrawerAction.BOOKMARK_PAGE ->
            if (state?.bookmarked == true) R.drawable.ic_browser_star else R.drawable.ic_browser_star_border
        else -> item.icon.resId
    }

    private fun mainPage(
        context: Context,
        plan: Plan,
        state: BrowserMenuState,
        style: Style,
        onAction: (DrawerAction) -> Unit,
        onMore: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        plan.grid.forEach { line -> addView(tileLine(context, line, state, style, onAction)) }

        val pageRows = buildList<View> {
            plan.switches.forEach { item ->
                add(cardRow(context, iconRes(item), label(context, item), style, onClick = { onAction(item.action) }) {
                    switchView(context, item.on, style)
                }.withState(context, item.on))
            }
            plan.zoom?.let { (out, zoomIn) -> add(zoomRow(context, out, zoomIn, style, onAction)) }
        }
        if (pageRows.isNotEmpty()) {
            addView(sectionLabel(context, context.getString(R.string.drawer_section_page), style))
            addView(card(context, pageRows, style))
        }

        val splitRows = buildList<View> {
            plan.split?.let { split -> add(splitRow(context, split, plan.splitChoose, state, style, onAction)) }
            if (plan.split == null) plan.splitChoose?.let { add(listRow(context, it, style, onAction)) }
            plan.sideShow?.let { add(listRow(context, it, style, onAction)) }
        }
        if (splitRows.isNotEmpty()) {
            addView(sectionLabel(context, context.getString(R.string.drawer_section_split), style))
            addView(card(context, splitRows, style))
        }

        if (plan.more.isNotEmpty() || plan.exit != null) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (plan.more.isNotEmpty()) {
                    addView(
                        cardRow(
                            context, BrowserIcon.MORE.resId, context.getString(R.string.drawer_more), style,
                            detail = context.getString(R.string.drawer_more_hint), onClick = onMore,
                        ) { chevron(context, style) }.also {
                            it.background = background(context, style.rowFill, style.textSecondary, CARD_RADIUS_DP)
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                }
                plan.exit?.let { exit ->
                    // The car browser's way back to the dashboard: kept on the first page.
                    addView(
                        pill(context, exit, style, onAction),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            .apply { if (plan.more.isNotEmpty()) marginStart = context.dp(GAP_DP) }
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = context.dp(SECTION_GAP_DP) }
            })
        }
    }

    private fun morePage(
        context: Context,
        plan: Plan,
        style: Style,
        onAction: (DrawerAction) -> Unit,
        onBack: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(cardRow(
            context, BrowserIcon.BACK.resId, context.getString(R.string.drawer_more), style,
            bold = true, onClick = onBack,
        ) { null }.also {
            it.contentDescription = context.getString(R.string.drawer_back)
            it.background = background(context, style.rowFill, style.textSecondary, CARD_RADIUS_DP)
        })
        plan.more.forEach { (labelRes, rows) ->
            addView(sectionLabel(context, context.getString(labelRes), style))
            addView(card(context, rows.map { listRow(context, it, style, onAction) }, style))
        }
    }

    // ------------------------------------------------------------------ pieces

    private const val TILE_HEIGHT_DP = 76
    private const val ROW_HEIGHT_DP = 60
    private const val GAP_DP = 8
    private const val SECTION_GAP_DP = 14
    private const val CARD_RADIUS_DP = 16
    private const val DISABLED_ALPHA = 0.34f

    /** Four equal buttons; a line with fewer keeps the same widths, so columns always line up. */
    private fun tileLine(
        context: Context,
        items: List<DrawerItem>,
        state: BrowserMenuState,
        style: Style,
        onAction: (DrawerAction) -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        weightSum = 4f
        items.forEachIndexed { index, item ->
            addView(tile(context, item, state, style, onAction),
                LinearLayout.LayoutParams(0, context.dp(TILE_HEIGHT_DP), 1f).apply {
                    if (index > 0) marginStart = context.dp(GAP_DP)
                })
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = context.dp(GAP_DP) }
    }

    private fun tile(
        context: Context,
        item: DrawerItem,
        state: BrowserMenuState,
        style: Style,
        onAction: (DrawerAction) -> Unit,
    ): View = android.widget.FrameLayout(context).apply {
        val saved = item.action == DrawerAction.BOOKMARK_PAGE && state.bookmarked
        val tint = if (saved) style.accent else style.text
        val text = label(context, item, state)
        val count = item.value.takeIf { item.action == DrawerAction.TABS && it.isNotBlank() }
        background = background(context, style.rowFill, style.textSecondary, CARD_RADIUS_DP)
        contentDescription = if (count != null) "$text, $count" else text
        alpha = if (item.enabled) 1f else DISABLED_ALPHA
        isClickable = item.enabled
        isFocusable = item.enabled
        if (item.enabled) setOnClickListener { onAction(item.action) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dp(4), context.dp(6), context.dp(4), context.dp(6))
            addView(ImageView(context).apply {
                setImageResource(iconRes(item, state))
                setColorFilter(tint)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(context.dp(26), context.dp(26)))
            addView(TextView(context).apply {
                this.text = text
                setTextColor(tint)
                textSize = 14f
                typeface = Typeface.create(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, context.dp(6), 0, 0)
            })
        }, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        if (count != null) {
            addView(TextView(context).apply {
                this.text = count
                setTextColor(style.onAccent)
                textSize = 12f
                typeface = Typeface.create(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                minWidth = context.dp(22)
                setPadding(context.dp(6), 0, context.dp(6), 0)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = context.dp(11).toFloat()
                    setColor(style.accent)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(22), Gravity.TOP or Gravity.END
            ).apply { setMargins(0, context.dp(8), context.dp(8), 0) })
        }
    }

    private fun sectionLabel(context: Context, text: String, style: Style): View = TextView(context).apply {
        this.text = text
        setTextColor(style.textSecondary)
        textSize = 13f
        typeface = Typeface.create(typeface, Typeface.BOLD)
        setPadding(context.dp(6), context.dp(SECTION_GAP_DP), 0, context.dp(6))
    }

    /** Rows stacked in one rounded card, separated by hairlines. */
    private fun card(context: Context, rows: List<View>, style: Style): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(CARD_RADIUS_DP).toFloat()
            setColor(style.rowFill)
        }
        clipToOutline = true
        rows.forEachIndexed { index, row ->
            if (index > 0) addView(View(context).apply {
                setBackgroundColor(style.textSecondary)
                alpha = 0.18f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * One line of a card: icon, label (and an optional second line), and whatever [trailing]
     * makes on the right. The whole line is the target.
     */
    private fun cardRow(
        context: Context,
        icon: Int,
        label: String,
        style: Style,
        detail: String? = null,
        enabled: Boolean = true,
        bold: Boolean = false,
        onClick: () -> Unit,
        trailing: () -> View?,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(ROW_HEIGHT_DP)
        setPadding(context.dp(16), context.dp(6), context.dp(14), context.dp(6))
        background = background(context, android.graphics.Color.TRANSPARENT, style.textSecondary, 0)
        contentDescription = if (detail.isNullOrBlank()) label else "$label, $detail"
        alpha = if (enabled) 1f else DISABLED_ALPHA
        isClickable = enabled
        isFocusable = enabled
        if (enabled) setOnClickListener { onClick() }
        addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(style.textSecondary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(22), context.dp(22)).apply { marginEnd = context.dp(14) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = label
                setTextColor(style.text)
                textSize = 17f
                if (bold) typeface = Typeface.create(typeface, Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (!detail.isNullOrBlank()) addView(TextView(context).apply {
                text = detail
                setTextColor(style.textSecondary)
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing()?.let { view ->
            addView(view, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = context.dp(12) })
        }
    }

    private fun LinearLayout.withState(context: Context, on: Boolean): LinearLayout = apply {
        contentDescription = "$contentDescription, ${context.getString(if (on) R.string.browser_menu_on else R.string.browser_menu_off)}"
    }

    private fun chevron(context: Context, style: Style): View = ImageView(context).apply {
        setImageResource(BrowserIcon.CHEVRON_RIGHT.resId)
        setColorFilter(style.textSecondary)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(context.dp(24), context.dp(24))
    }

    /** A plain action line: icon, label, chevron. Dimmed and inert when the entry is disabled. */
    private fun listRow(context: Context, item: DrawerItem, style: Style, onAction: (DrawerAction) -> Unit): View {
        val detail = if (item.action == DrawerAction.NAVIGATE_MAPS) context.getString(R.string.drawer_navigate_maps_hint) else null
        return cardRow(
            context, iconRes(item), label(context, item), style,
            detail = detail, enabled = item.enabled, onClick = { onAction(item.action) },
        ) { chevron(context, style) }
    }

    /** "Zoom" with − and + buttons; there is no page zoom value to show, so none is invented. */
    private fun zoomRow(
        context: Context,
        out: DrawerItem,
        zoomIn: DrawerItem,
        style: Style,
        onAction: (DrawerAction) -> Unit,
    ): View = cardRow(
        context, BrowserIcon.SEARCH.resId, context.getString(R.string.drawer_zoom), style, onClick = {},
    ) {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(out, zoomIn).forEachIndexed { index, item ->
                addView(ImageView(context).apply {
                    setImageResource(item.icon.resId)
                    setColorFilter(style.text)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    contentDescription = item.label(context)
                    val inset = context.dp(10)
                    setPadding(inset, inset, inset, inset)
                    background = background(context, style.textSecondary.withAlpha(0x33), style.textSecondary, 12)
                    isClickable = item.enabled
                    if (item.enabled) setOnClickListener { onAction(item.action) }
                }, LinearLayout.LayoutParams(context.dp(52), context.dp(44)).apply {
                    if (index > 0) marginStart = context.dp(GAP_DP)
                })
            }
        }
    }.apply {
        // The line itself does nothing; only its two buttons act.
        isClickable = false
        isFocusable = false
        background = null
    }

    /**
     * The split screen in one line: the switch on the right turns it on or off; the rest of the
     * line (name, current layout) opens the layout chooser when there is one.
     */
    private fun splitRow(
        context: Context,
        split: DrawerItem,
        choose: DrawerItem?,
        state: BrowserMenuState,
        style: Style,
        onAction: (DrawerAction) -> Unit,
    ): View {
        val current = state.splitLayout?.takeIf { split.on && it != BrowserSplitLayout.SINGLE }?.label(context)
        val row = cardRow(
            context, iconRes(split), split.label(context), style,
            detail = if (choose != null) context.getString(R.string.drawer_split_hint) else null,
            onClick = { onAction(choose?.action ?: split.action) },
        ) {
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (current != null) addView(TextView(context).apply {
                    text = current
                    setTextColor(style.accent)
                    textSize = 14f
                    typeface = Typeface.create(typeface, Typeface.BOLD)
                    setPadding(0, 0, context.dp(12), 0)
                })
                // Its own target, so the switch toggles while the rest of the line chooses.
                addView(android.widget.FrameLayout(context).apply {
                    contentDescription = "${split.label(context)}, " +
                        context.getString(if (split.on) R.string.browser_menu_on else R.string.browser_menu_off)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onAction(split.action) }
                    val pad = context.dp(10)
                    setPadding(pad, pad, 0, pad)
                    addView(switchView(context, split.on, style))
                })
            }
        }
        return row
    }

    /** A compact labelled button beside the More line (Exit). */
    private fun pill(context: Context, item: DrawerItem, style: Style, onAction: (DrawerAction) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = context.dp(ROW_HEIGHT_DP)
            setPadding(context.dp(18), 0, context.dp(20), 0)
            background = background(context, style.rowFill, style.textSecondary, CARD_RADIUS_DP)
            contentDescription = item.label(context)
            isClickable = true
            isFocusable = true
            setOnClickListener { onAction(item.action) }
            addView(ImageView(context).apply {
                setImageResource(item.icon.resId)
                setColorFilter(style.text)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(context.dp(22), context.dp(22)).apply { marginEnd = context.dp(8) })
            addView(TextView(context).apply {
                text = item.label(context)
                setTextColor(style.text)
                textSize = 16f
                typeface = Typeface.create(typeface, Typeface.BOLD)
            })
        }

    private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha shl 24)

    /**
     * The menu's title line: the [title] and a round ✕ that calls [onClose]. Dragging the line
     * down ([dragToClose]) closes the menu as well.
     */
    fun header(
        context: Context,
        title: String,
        style: Style,
        subtitle: String? = null,
        onClose: () -> Unit,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(52)
            // With a subtitle the title is the page's own name, so it reads as a heading.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(8), 0, 0, 0)
                addView(TextView(context).apply {
                    text = title
                    setTextColor(if (subtitle.isNullOrBlank()) style.textSecondary else style.text)
                    textSize = if (subtitle.isNullOrBlank()) 15f else 18f
                    if (!subtitle.isNullOrBlank()) typeface = Typeface.create(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (!subtitle.isNullOrBlank()) addView(TextView(context).apply {
                    text = subtitle
                    setTextColor(style.textSecondary)
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
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

    private fun background(context: Context, fill: Int, ripple: Int, radiusDp: Int = 14): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(ripple),
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(radiusDp).toFloat()
            setColor(fill)
        },
        // A mask, so the ripple shows on a transparent row too.
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(radiusDp).toFloat()
            setColor(android.graphics.Color.WHITE)
        }
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
