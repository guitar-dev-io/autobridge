package dev.autobridge.browser

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * The phone's browser menu: the same sheet [CarWebRenderer] composites over the car surface, built
 * out of Views instead of Canvas calls.
 *
 * **What is shared and what is not.** The *content* comes from [BrowserDrawerModel] — the same two
 * cards, the same desktop switch, the same footer pair — so an entry added for one surface exists on
 * both and neither drifts into being a different menu. The *geometry* does not: a View hierarchy
 * measures itself, and re-deriving pixel boxes here would be reimplementing layout on top of a
 * layout engine. [BrowserDrawerModel]'s boxes stay the car's, where nothing measures anything.
 *
 * It replaces an `AlertDialog` of tiles. That dialog had no address row, so the menu could not say
 * which page it was acting on; it had no grouping, so nine common actions and nine rare ones were
 * drawn identically; and it stated the desktop-site setting as a label ("Desktop: เปิด") that the
 * user had to read and decode rather than a switch they could see the position of.
 */
class BrowserMenuSheet(
    private val activity: Activity,
    private val sizes: AutoUiSizes,
    /** Re-read on every render, so the sheet redraws the switch it just moved. */
    private val state: () -> BrowserMenuState,
    private val onNavigate: (String) -> Unit,
    private val onAction: (DrawerAction) -> Unit,
) {
    private var dialog: Dialog? = null

    /** Text size in sp for a dp token, with the accessibility scale already capped by the caller. */
    private fun sp(dp: Float) = dp * (fontScale / activity.resources.configuration.fontScale.coerceAtLeast(0.01f))
    private val fontScale = AutoUiSizes.clampFontScale(activity.resources.configuration.fontScale)

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    /** Opens the sheet on its primary list. */
    fun show() {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // Only the top corners: the sheet is anchored to the bottom of the window, so its
            // lower edge is the screen edge and rounding it would draw a gap the page shows through.
            background = GradientDrawable().apply {
                setColor(BrowserTheme.sheetBackground)
                val r = sizes.cornerRadius * 2f
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(
                sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP), sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP),
                sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP), sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
            )
        }
        render(content, more = false)

        // Translucent rather than the platform dialog theme, which insets its own panel from the
        // window and would draw a card inside the sheet. Everything the sheet shows is drawn here,
        // so the window contributes nothing but the dimming behind it.
        dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            // The sheet can outgrow a short phone in landscape, so it scrolls as a whole rather
            // than any one card scrolling inside it — a nested scroll is what turns a tap that
            // drifted a few px into a scroll gesture that never fires the entry under it.
            setContentView(ScrollView(activity).apply {
                isFillViewport = true
                addView(
                    content,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            })
            setCanceledOnTouchOutside(true)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                // The translucent theme carries no dim of its own; the sheet needs one for the same
                // reason the car surface draws a scrim — it has to read as a layer over the page.
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.55f)
            }
            show()
        }
    }

    private fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    /** Runs [action] and closes the sheet, the default for anything that leaves the menu behind. */
    private fun perform(action: DrawerAction) {
        dismiss()
        onAction(action)
    }

    /**
     * Fills [container] with one list. Called again in place for the "More" round trip rather than
     * opening a second dialog, so the sheet does not blink out and back on the way.
     */
    private fun render(container: LinearLayout, more: Boolean) {
        val current = state()
        container.removeAllViews()
        container.addView(grip())
        container.addView(header(current, more))
        container.addView(addressRow(current))
        val groups = if (more) listOf(BrowserDrawerModel.moreItems(current.surface)) else BrowserDrawerModel.primaryCards(current)
        // The first primary card holds navigation (Back/Reload/Forward…). It gets the accent edge
        // and the accent-tinted glyph chips; the rest stay neutral. "More" has one flat grid.
        groups.forEachIndexed { index, items ->
            container.addView(card(items, more, primary = !more && index == 0))
        }
        if (!more) container.addView(desktopSwitch(BrowserDrawerModel.desktopToggle(current)))
        container.addView(footer(current, more, container))
    }

    private fun gap() = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)

    private fun grip(): View = View(activity).apply {
        background = rounded(BrowserTheme.iconDisabled, sizes.dp(2f))
        layoutParams = LinearLayout.LayoutParams(sizes.dpInt(44f), sizes.dpInt(4f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = gap()
        }
    }

    private fun header(current: BrowserMenuState, more: Boolean): View {
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = if (more) "More" else current.appName
                textSize = sp(AutoUiSizes.ICON_MEDIUM_DP)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(BrowserTheme.textPrimary)
            })
            val subtitle = if (more) "All browser actions" else current.pageTitle
            if (subtitle.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = subtitle
                    textSize = sp(AutoUiSizes.ICON_SMALL_DP * 0.8f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(BrowserTheme.textSecondary)
                })
            }
        }
        // The word as well as the glyph: a bare ✕ is a guess, and this is the control someone who
        // opened the menu by mistake reaches for first.
        val close = pill("✕", "Close") { dismiss() }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(BrowserTheme.sheetCardBackground, sizes.cornerRadius)
            setPadding(sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), gap(), sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), gap())
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = gap() }
        }
    }

    /**
     * The sheet's copy of the address bar. The toolbar auto-hides and the menu is frequently what
     * gets opened *instead* of recalling it, so a menu that cannot say — or change — which page it
     * is acting on is one the user has to close again to do either.
     */
    private fun addressRow(current: BrowserMenuState): View {
        val field = EditText(activity).apply {
            setText(current.url)
            setSingleLine()
            setSelectAllOnFocus(true)
            hint = "URL / ค้นหา"
            setHintTextColor(BrowserTheme.iconDisabled)
            setTextColor(BrowserTheme.textPrimary)
            textSize = sp(AutoUiSizes.ICON_SMALL_DP * 0.85f)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(gap(), 0, gap(), 0)
        }
        fun go() {
            val target = field.text.toString().trim()
            dismiss()
            if (target.isNotEmpty()) onNavigate(target)
        }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { go(); true } else false
        }
        val lock = TextView(activity).apply {
            text = if (current.secure) "🔒" else "!"
            textSize = sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
            setTextColor(if (current.secure) BrowserTheme.secureBadge else BrowserTheme.insecureBadge)
        }
        val clear = TextView(activity).apply {
            text = "✕"
            gravity = Gravity.CENTER
            textSize = sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
            setTextColor(BrowserTheme.textSecondary)
            contentDescription = "Clear address"
            setOnClickListener { field.setText(""); field.requestFocus() }
            layoutParams = LinearLayout.LayoutParams(sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP * 0.8f), sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP * 0.8f))
        }
        val search = TextView(activity).apply {
            text = "⌕"
            gravity = Gravity.CENTER
            textSize = sp(AutoUiSizes.ICON_MEDIUM_DP * 0.9f)
            setTextColor(BrowserTheme.iconEnabled)
            contentDescription = "Go"
            background = rounded(BrowserTheme.tileBackground, sizes.dp(AutoUiSizes.TOUCH_TARGET_DP * 0.8f) / 2f)
            setOnClickListener { go() }
            layoutParams = LinearLayout.LayoutParams(sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP * 0.8f), sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP * 0.8f))
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(BrowserTheme.addressPillBackground, sizes.dp(AutoUiSizes.TOUCH_TARGET_DP) / 2f)
            setPadding(sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), sizes.dpInt(4f), sizes.dpInt(6f), sizes.dpInt(4f))
            addView(lock)
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(clear)
            addView(search)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = gap() }
        }
    }

    /**
     * One card of tiles: a recessed background so the tiles on it read as raised. The [primary]
     * card (navigation) carries a thin accent edge down its leading side, so the eye lands on it
     * first without a heading eating a whole row.
     */
    private fun card(items: List<DrawerItem>, more: Boolean, primary: Boolean = false): View {
        val columns = if (more && activity.resources.configuration.screenWidthDp >= 600) {
            AutoUiSizes.MENU_COLUMNS_MAX.coerceAtMost(items.size)
        } else {
            BrowserDrawerModel.PRIMARY_COLUMNS
        }
        val inset = sizes.dpInt(AutoUiSizes.MENU_TILE_GAP_DP) / 2
        val grid = GridLayout(activity).apply {
            columnCount = columns
            background = rounded(BrowserTheme.sheetCardBackground, sizes.cornerRadius)
            setPadding(inset, inset, inset, inset)
        }
        items.forEach { item -> grid.addView(tile(item, primary), tileParams()) }
        val cardParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = gap() }

        if (!primary) return grid.apply { layoutParams = cardParams }

        // Accent edge + card in a horizontal strip. The edge is a slim rounded bar, not a full
        // border, so it reads as a marker rather than an outline.
        val edge = View(activity).apply {
            background = rounded(BrowserTheme.primaryCardAccent, sizes.dp(2f))
            layoutParams = LinearLayout.LayoutParams(sizes.dpInt(3f), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginEnd = inset
            }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(edge)
            addView(grid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            layoutParams = cardParams
        }
    }

    private fun tileParams() = GridLayout.LayoutParams().apply {
        width = 0
        height = sizes.dpInt(AutoUiSizes.MENU_TILE_HEIGHT_DP)
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        val margin = sizes.dpInt(AutoUiSizes.MENU_TILE_GAP_DP) / 2
        setMargins(margin, margin, margin, margin)
    }

    private fun tile(item: DrawerItem, primary: Boolean = false): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = rounded(
            if (item.enabled) BrowserTheme.tileBackground else BrowserTheme.tileDisabledBackground,
            sizes.dp(AutoUiSizes.MENU_TILE_HEIGHT_DP) / 2f
        )
        contentDescription = item.label
        // Disabled entries keep their place so the card never reflows under the user's finger, but
        // they take no tap: a control that lights up and does nothing reads as broken, where a
        // dimmed one reads as unavailable.
        isClickable = item.enabled
        isFocusable = item.enabled
        if (item.enabled) setOnClickListener { perform(item.action) }
        val tint = if (item.enabled) BrowserTheme.iconEnabled else BrowserTheme.iconDisabled
        // The glyph sits in a rounded chip rather than floating as bare text, which gives each tile
        // a small focal point and lets the primary card tint it toward the accent. A disabled tile
        // shows no chip at all — nothing to press means nothing raised.
        val chipSide = sizes.dpInt(AutoUiSizes.ICON_LARGE_DP + AutoUiSizes.CONTENT_GAP_DP)
        val glyphView = TextView(activity).apply {
            text = item.glyph
            textSize = sp(AutoUiSizes.ICON_MEDIUM_DP)
            gravity = Gravity.CENTER
            setTextColor(tint)
            if (item.enabled) {
                val chipColor = if (primary) BrowserTheme.primaryTileIconChip else BrowserTheme.tileIconChip
                background = rounded(chipColor, chipSide / 2f)
            }
            layoutParams = LinearLayout.LayoutParams(chipSide, chipSide).apply {
                bottomMargin = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP) / 2
            }
        }
        addView(glyphView)
        addView(TextView(activity).apply {
            text = if (item.value.isBlank()) item.label else "${item.label}  ${item.value}"
            textSize = sp(AutoUiSizes.ICON_SMALL_DP * 0.66f)
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(if (item.enabled) BrowserTheme.textPrimary else BrowserTheme.iconDisabled)
        })
    }

    /**
     * A switch rather than a tile, because what it reports is a state. The label it replaced —
     * "Desktop: เปิด" — asked the user to read two things and work out which one was the button.
     */
    private fun desktopSwitch(item: DrawerItem): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(BrowserTheme.sheetCardBackground, sizes.cornerRadius)
        setPadding(sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), gap(), sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), gap())
        addView(TextView(activity).apply {
            text = item.glyph
            textSize = sp(AutoUiSizes.ICON_MEDIUM_DP)
            setTextColor(BrowserTheme.textSecondary)
        })
        addView(
            TextView(activity).apply {
                text = item.label
                textSize = sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
                setTextColor(BrowserTheme.textPrimary)
                setPadding(sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(Switch(activity).apply {
            isChecked = item.on
            // The sheet stays open: closing it on the way out would hide the one thing the tap was
            // for. The page reloads under it, which is what the setting changes.
            setOnCheckedChangeListener { _, _ -> onAction(item.action) }
        })
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = gap() }
    }

    /**
     * Who is drawing the page on the left, and on the right the two routes the sheet must never
     * bury: the secondary list, and the way out of the browser.
     */
    private fun footer(current: BrowserMenuState, more: Boolean, container: LinearLayout): View {
        val identity = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "${current.appName} — Browser"
                textSize = sp(AutoUiSizes.ICON_SMALL_DP * 0.72f)
                setTextColor(BrowserTheme.textSecondary)
            })
            addView(TextView(activity).apply {
                text = current.version
                textSize = sp(AutoUiSizes.ICON_SMALL_DP * 0.66f)
                setTextColor(BrowserTheme.iconDisabled)
            })
        }
        val secondary = if (more) {
            pill("‹", "Back") { render(container, more = false) }
        } else {
            pill("⋯", "More") { render(container, more = true) }
        }
        val exit = pill("⏏", "Exit") { perform(DrawerAction.APP_HOME) }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), gap(), 0, 0)
            addView(identity, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(secondary)
            addView(exit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = gap() })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(activity).apply {
                setBackgroundColor(BrowserTheme.hairline)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(1f)
                )
            })
            addView(row)
        }
    }

    private fun pill(glyph: String, label: String, onClick: () -> Unit): View = TextView(activity).apply {
        text = "$glyph  $label"
        gravity = Gravity.CENTER
        textSize = sp(AutoUiSizes.ICON_SMALL_DP * 0.75f)
        setTextColor(BrowserTheme.textPrimary)
        contentDescription = label
        background = rounded(BrowserTheme.tileBackground, sizes.dp(AutoUiSizes.TOUCH_TARGET_DP * 0.8f) / 2f)
        minHeight = sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP * 0.8f)
        setPadding(sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), 0, sizes.dpInt(AutoUiSizes.HORIZONTAL_PADDING_DP), 0)
        setOnClickListener { onClick() }
    }
}
