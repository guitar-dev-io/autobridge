package dev.autobridge.browser

import android.app.Activity
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * The phone's main browser sheet, redesigned around the one action the phone browser exists for:
 * handing a page or a search to the car.
 *
 * **Hierarchy, top to bottom.** A compact header (the app name, the current page title, and a round
 * ✕ to close); the address/search field; a large, accent-filled **Send to car** button that is the
 * loudest thing on the sheet; a medium-emphasis row of the three controls used mid-page
 * (Back / Reload / Forward); a lower-emphasis row of Bookmarks / Settings / More; and a compact
 * desktop-site toggle. Everything rare — Find, Copy URL, Paste & go, Open external, Start page,
 * History, Downloads, Zoom and Get-from-car — moved into the [MoreActionsSheet] behind "More", and
 * "Clear browsing data" moved into Settings ▸ Privacy, so the main sheet carries only what is
 * reached often and nothing destructive sits next to a navigation button.
 *
 * **What this is and is not.** This is phone-only. The car surface draws its own menu on a Canvas
 * through [CarWebRenderer] from the shared [BrowserDrawerModel] data; this sheet renders real Views
 * and now lays itself out directly rather than mirroring that model's two-card grid, because the
 * phone's hierarchy (one big primary button, two small rows) is deliberately different from the
 * car's. It still owns no browser behaviour: every control dispatches a [DrawerAction] back through
 * [onAction], or navigates through [onNavigate], or opens one of the two secondary sheets through
 * [onSendToCar] / [onMore].
 */
class BrowserMenuSheet(
    private val activity: Activity,
    private val sizes: AutoUiSizes,
    /** Re-read on every render, so the sheet redraws the switch it just moved. */
    private val state: () -> BrowserMenuState,
    private val onNavigate: (String) -> Unit,
    private val onAction: (DrawerAction) -> Unit,
    /** Opens the dedicated "Send to car" sheet; the primary button's whole job. */
    private val onSendToCar: () -> Unit,
    /** Opens the "More actions" sheet. */
    private val onMore: () -> Unit,
) {
    private val shell = BrowserSheetShell(activity, sizes)
    private lateinit var container: LinearLayout

    /** Opens the sheet. */
    fun show() {
        container = shell.contentColumn()
        render()
        shell.show(container)
    }

    private fun perform(action: DrawerAction) {
        shell.dismiss()
        onAction(action)
    }

    private fun render() {
        val current = state()
        container.removeAllViews()
        container.addView(shell.grip())
        container.addView(header(current))
        container.addView(addressRow(current))
        container.addView(sendToCarButton())
        container.addView(divider())
        container.addView(navRow(current))
        container.addView(secondaryRow())
        container.addView(divider())
        container.addView(desktopToggle(current))
    }

    /** The faint full-width rule the mockup draws between its grouped sections. */
    private fun divider(): View = View(activity).apply {
        setBackgroundColor(BrowserTheme.hairline)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(1f)
        ).apply { topMargin = shell.gap() / 2; bottomMargin = shell.gap() }
    }

    // ------------------------------------------------------------------------------------ header

    private fun header(current: BrowserMenuState): View {
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = current.appName
                textSize = shell.sp(AutoUiSizes.ICON_LARGE_DP * 0.9f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(BrowserTheme.textPrimary)
            })
            val subtitle = current.pageTitle
            if (subtitle.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = subtitle
                    textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.82f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(BrowserTheme.textSecondary)
                })
            }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(shell.closeButton { shell.dismiss() })
            layoutParams = rowParams()
        }
    }

    // ------------------------------------------------------------------------------- address row

    /**
     * The sheet's URL / search field. Accepts either a URL or a free-text query and hands it to
     * [onNavigate], which resolves it the same way the toolbar's address bar does.
     */
    private fun addressRow(current: BrowserMenuState): View {
        val field = EditText(activity).apply {
            setText(current.url)
            setSingleLine()
            setSelectAllOnFocus(true)
            hint = "URL / ค้นหา"
            setHintTextColor(BrowserTheme.iconDisabled)
            setTextColor(BrowserTheme.textPrimary)
            textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(shell.gap(), 0, shell.gap(), 0)
        }
        fun go() {
            val target = field.text.toString().trim()
            shell.dismiss()
            if (target.isNotEmpty()) onNavigate(target)
        }
        // Accept both the IME "Go" action and a raw Enter key event. Some soft keyboards deliver
        // Enter as a KEYCODE_ENTER key event with IME_ACTION_UNSPECIFIED rather than IME_ACTION_GO;
        // handling only the latter is why the sheet sometimes stayed open on submit while nothing
        // navigated. This mirrors the toolbar's own address field.
        field.setOnEditorActionListener { _, action, event ->
            val enterUp = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_UP
            if (action == EditorInfo.IME_ACTION_GO || enterUp) { go(); true } else false
        }
        val lock = TextView(activity).apply {
            text = if (current.secure) "🔒" else "!"
            textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
            setTextColor(if (current.secure) BrowserTheme.secureBadge else BrowserTheme.insecureBadge)
        }
        val clear = TextView(activity).apply {
            text = "✕"
            gravity = Gravity.CENTER
            textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
            setTextColor(BrowserTheme.textSecondary)
            contentDescription = "ล้าง URL"
            val side = sizes.dpInt(AutoUiSizes.SHEET_URL_FIELD_HEIGHT_DP * 0.78f)
            layoutParams = LinearLayout.LayoutParams(side, side)
            setOnClickListener { field.setText(""); field.requestFocus() }
        }
        val fieldHeight = sizes.dpInt(AutoUiSizes.SHEET_URL_FIELD_HEIGHT_DP)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shell.rounded(BrowserTheme.addressPillBackground, fieldHeight / 2f)
            setPadding(shell.pad(), 0, sizes.dpInt(6f), 0)
            addView(lock, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = shell.gap() / 2 })
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(clear)
            layoutParams = rowParams().apply { height = fieldHeight }
        }
    }

    // --------------------------------------------------------------------------- send-to-car CTA

    /**
     * The primary action: large, accent-filled, with a car icon, a bold title and a subtitle. It
     * opens the focused [SendToCarSheet] rather than firing straight away so the user can choose the
     * page, a different URL or a search before it goes to the car.
     */
    private fun sendToCarButton(): View {
        val icon = TextView(activity).apply {
            text = "🚗"
            gravity = Gravity.CENTER
            textSize = shell.sp(AutoUiSizes.SHEET_ICON_DP * 1.15f)
            setPadding(0, 0, shell.pad(), 0)
        }
        val label = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "Send to car"
                textSize = shell.sp(AutoUiSizes.ICON_MEDIUM_DP)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(BrowserTheme.onPrimary)
            })
            addView(TextView(activity).apply {
                text = "ส่งหน้าเว็บหรือคำค้นไปที่หน้าจอรถ"
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.78f)
                setTextColor(BrowserTheme.onPrimary)
            })
        }
        val chevron = TextView(activity).apply {
            text = "›"
            gravity = Gravity.CENTER
            textSize = shell.sp(AutoUiSizes.ICON_LARGE_DP)
            setTextColor(BrowserTheme.onPrimary)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shell.rounded(BrowserTheme.accent, shell.cornerRadius())
            setPadding(shell.pad() + shell.gap(), shell.pad(), shell.pad(), shell.pad())
            contentDescription = "Send to car"
            addView(icon)
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron)
            setOnClickListener { shell.dismiss(); onSendToCar() }
            layoutParams = rowParams().apply {
                height = sizes.dpInt(AutoUiSizes.SHEET_PRIMARY_CTA_HEIGHT_DP)
            }
        }
    }

    // ------------------------------------------------------------------------------------- rows

    /** Back / Reload / Forward: the three controls used while reading a page. Medium emphasis. */
    private fun navRow(current: BrowserMenuState): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(actionButton("←", "Back", enabled = current.canGoBack) { perform(DrawerAction.NAV_BACK) }, equalParams(0))
        addView(actionButton("↻", "Reload", enabled = true) { perform(DrawerAction.RELOAD) }, equalParams(1))
        addView(actionButton("→", "Forward", enabled = current.canGoForward) { perform(DrawerAction.NAV_FORWARD) }, equalParams(2))
        layoutParams = rowParams()
    }

    /** Bookmarks / Settings / More: lower emphasis than navigation, but still on the main sheet. */
    private fun secondaryRow(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(actionButton("☆", "Bookmarks", enabled = true) { perform(DrawerAction.BOOKMARKS) }, equalParams(0))
        addView(actionButton("⚙", "Settings", enabled = true) { perform(DrawerAction.SETTINGS) }, equalParams(1))
        addView(actionButton("⋯", "More", enabled = true) { shell.dismiss(); onMore() }, equalParams(2))
        layoutParams = rowParams()
    }

    /**
     * One button in a navigation/secondary row: a glyph chip over a label, on a tonal background.
     * Disabled entries keep their place (so the row never reflows) but take no tap and read dimmed.
     */
    private fun actionButton(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = shell.rounded(
                if (enabled) BrowserTheme.tileBackground else BrowserTheme.tileDisabledBackground,
                shell.cornerRadius()
            )
            setPadding(shell.gap(), shell.gap(), shell.gap(), shell.gap())
            contentDescription = label
            isClickable = enabled
            isFocusable = enabled
            if (enabled) setOnClickListener { onClick() }
            val tint = if (enabled) BrowserTheme.iconEnabled else BrowserTheme.iconDisabled
            addView(TextView(activity).apply {
                text = glyph
                gravity = Gravity.CENTER
                textSize = shell.sp(AutoUiSizes.SHEET_ICON_DP)
                setTextColor(tint)
                setPadding(0, 0, 0, shell.gap())
            })
            addView(TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                maxLines = 1
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.78f)
                setTextColor(if (enabled) BrowserTheme.textPrimary else BrowserTheme.iconDisabled)
            })
        }

    /** A compact full-width row with a switch — not an oversized card. 60dp, matching the mockup. */
    private fun desktopToggle(current: BrowserMenuState): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
        setPadding(shell.pad(), 0, shell.pad(), 0)
        addView(TextView(activity).apply {
            text = "🖥"
            textSize = shell.sp(AutoUiSizes.SHEET_ICON_DP * 0.85f)
            setTextColor(BrowserTheme.textSecondary)
            setPadding(0, 0, shell.pad(), 0)
        })
        addView(
            TextView(activity).apply {
                text = "Request desktop site"
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
                setTextColor(BrowserTheme.textPrimary)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(Switch(activity).apply {
            isChecked = current.isDesktop
            // The sheet stays open: the page reloads under it, which is what the setting changes.
            setOnCheckedChangeListener { _, _ -> onAction(DrawerAction.TOGGLE_DESKTOP) }
        })
        layoutParams = rowParams().apply { height = sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP) }
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = shell.gap() }

    /** Equal-width tiles in a nav/secondary row, each a fixed [AutoUiSizes.SHEET_TILE_HEIGHT_DP] tall. */
    private fun equalParams(index: Int) = LinearLayout.LayoutParams(
        0, sizes.dpInt(AutoUiSizes.SHEET_TILE_HEIGHT_DP), 1f
    ).apply { if (index > 0) marginStart = shell.gap() }
}
