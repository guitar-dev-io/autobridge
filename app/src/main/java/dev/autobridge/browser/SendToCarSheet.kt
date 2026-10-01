package dev.autobridge.browser

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The focused "Send to car" sheet.
 *
 * This is the core AutoBridge gesture given its own surface: phone → tap "Send to car" → the car
 * browser opens the destination. The main browser sheet keeps a large primary button that opens
 * this; everything about *what* to send is decided here so the main sheet stays uncluttered.
 *
 * Two ways in, one way out. The current page is offered for a one-tap send (favicon placeholder,
 * title, shortened URL — the user never retypes the URL they are already on), and a text field
 * takes either a URL or a free-text query. A URL is sent verbatim; a query is turned into a search
 * on the selected engine ([SearchEngine]), which defaults to YouTube because this browser is used
 * mostly to put media on the car. The resolve/engine logic is [BrowserInputResolver], shared with
 * the address bar, so "is this a page or a search?" has one answer across the app.
 *
 * @param onSend given the raw text and the chosen engine; the caller resolves and sends, returning
 *   true on success so the sheet closes only when the car accepted it.
 * @param onSendUrl given an already-resolved URL (the current-page card), for the same send path.
 */
class SendToCarSheet(
    private val activity: Activity,
    private val sizes: AutoUiSizes,
    private val currentUrl: () -> String?,
    private val currentTitle: () -> String?,
    private val engine: () -> SearchEngine,
    private val onEngineChange: (SearchEngine) -> Unit,
    private val onSend: (input: String, engine: SearchEngine) -> Boolean,
    private val onSendUrl: (url: String) -> Boolean,
) {
    private val shell = BrowserSheetShell(activity, sizes)

    /** Which tab is active. "Send URL" shows the current page; "Search" focuses the query field. */
    private enum class Mode { URL, SEARCH }

    private var mode = Mode.URL
    private var selectedEngine = engine()
    private lateinit var container: LinearLayout
    private lateinit var input: EditText

    fun show() {
        container = shell.contentColumn()
        render()
        shell.show(container)
    }

    private fun render() {
        container.removeAllViews()
        container.addView(shell.grip())
        container.addView(header())
        container.addView(tabRow())

        val url = currentUrl()
        if (mode == Mode.URL && url != null) {
            container.addView(sectionLabel("หน้าปัจจุบัน", "Current page"))
            container.addView(currentPageCard(url))
        }

        container.addView(sectionLabel("หรือพิมพ์ URL / คำค้นหา", "Or enter URL or search query"))
        container.addView(inputField())
        container.addView(sectionLabel("เครื่องมือค้นหา", "Search engine"))
        container.addView(engineRow())
        container.addView(primaryButton())
    }

    // -------------------------------------------------------------------------- header + tabs

    private fun header(): View {
        val title = TextView(activity).apply {
            text = "Send to car"
            textSize = shell.sp(AutoUiSizes.ICON_LARGE_DP * 0.9f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BrowserTheme.textPrimary)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(shell.closeButton { shell.dismiss() })
            layoutParams = rowParams()
        }
    }

    /** Segmented control: "Send URL" vs "Search on car". */
    private fun tabRow(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shell.rounded(BrowserTheme.sheetCardBackground, sizes.dp(AutoUiSizes.TOUCH_TARGET_DP) / 2f)
            setPadding(sizes.dpInt(4f), sizes.dpInt(4f), sizes.dpInt(4f), sizes.dpInt(4f))
            layoutParams = rowParams()
        }
        row.addView(tab("Send URL", Mode.URL), tabParams())
        row.addView(tab("Search on car", Mode.SEARCH), tabParams())
        return row
    }

    private fun tab(label: String, forMode: Mode): View = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        maxLines = 1
        textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.85f)
        val active = mode == forMode
        setTextColor(if (active) BrowserTheme.onPrimary else BrowserTheme.textSecondary)
        if (active) setTypeface(typeface, Typeface.BOLD)
        background = shell.rounded(
            if (active) BrowserTheme.accent else android.graphics.Color.TRANSPARENT,
            sizes.dp(AutoUiSizes.TOUCH_TARGET_DP) / 2f
        )
        minHeight = sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP * 0.85f)
        setOnClickListener {
            if (mode != forMode) {
                mode = forMode
                render()
                if (forMode == Mode.SEARCH) input.requestFocus()
            }
        }
    }

    // ------------------------------------------------------------------------- current page card

    private fun currentPageCard(url: String): View {
        val title = currentTitle() ?: BrowserDisplayUrl.compact(url)
        // A favicon-sized placeholder chip. The real favicon is not held by the activity, so a
        // neutral glyph keeps the row's shape without claiming an image it does not have.
        val favicon = TextView(activity).apply {
            text = "🌐"
            gravity = Gravity.CENTER
            textSize = shell.sp(AutoUiSizes.ICON_MEDIUM_DP)
            val side = sizes.dpInt(AutoUiSizes.ICON_LARGE_DP + AutoUiSizes.CONTENT_GAP_DP * 2)
            background = shell.rounded(BrowserTheme.tileBackground, sizes.dp(AutoUiSizes.CORNER_RADIUS_DP))
            layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = shell.gap() }
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.95f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(BrowserTheme.textPrimary)
            })
            addView(TextView(activity).apply {
                text = BrowserDisplayUrl.compact(url, max = 48)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.78f)
                setTextColor(BrowserTheme.textSecondary)
            })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
            setPadding(shell.pad(), shell.gap(), shell.pad(), shell.gap())
            contentDescription = "ส่งหน้าปัจจุบันไปที่รถ"
            addView(favicon)
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // The mockup's filled "selected" radio on the right; the whole row is the tap target.
            addView(TextView(activity).apply {
                text = "◉"
                textSize = shell.sp(AutoUiSizes.ICON_MEDIUM_DP)
                setTextColor(BrowserTheme.accent)
            })
            setOnClickListener { if (onSendUrl(url)) shell.dismiss() }
            layoutParams = rowParams()
        }
    }

    // -------------------------------------------------------------------------------- input field

    private fun inputField(): View {
        val search = TextView(activity).apply {
            text = "⌕"
            textSize = shell.sp(AutoUiSizes.ICON_MEDIUM_DP)
            setTextColor(BrowserTheme.textSecondary)
        }
        input = EditText(activity).apply {
            setSingleLine()
            hint = "เพลง bodyslam ล่าสุด หรือ youtube.com"
            setHintTextColor(BrowserTheme.iconDisabled)
            setTextColor(BrowserTheme.textPrimary)
            textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(shell.gap(), 0, shell.gap(), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { sendTyped(); true } else false
            }
        }
        val clear = TextView(activity).apply {
            text = "✕"
            gravity = Gravity.CENTER
            textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP)
            setTextColor(BrowserTheme.textSecondary)
            contentDescription = "ล้างข้อความ"
            val side = sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP * 0.8f)
            layoutParams = LinearLayout.LayoutParams(side, side)
            setOnClickListener { input.setText(""); input.requestFocus() }
        }
        val fieldHeight = sizes.dpInt(AutoUiSizes.SHEET_URL_FIELD_HEIGHT_DP)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shell.rounded(BrowserTheme.addressPillBackground, fieldHeight / 2f)
            setPadding(shell.pad(), 0, sizes.dpInt(6f), 0)
            addView(search)
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = shell.gap()
            })
            addView(clear)
            layoutParams = rowParams().apply { height = fieldHeight }
        }
    }

    // --------------------------------------------------------------------------- engine chooser

    private fun engineRow(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = rowParams()
        }
        SearchEngine.entries.forEachIndexed { index, candidate ->
            row.addView(engineChip(candidate), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = shell.gap()
            })
        }
        return row
    }

    private fun engineChip(candidate: SearchEngine): View {
        val active = selectedEngine == candidate
        val glyph = if (candidate == SearchEngine.YOUTUBE) "▶" else "G"
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = candidate.label
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.9f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (active) BrowserTheme.onPrimaryContainer else BrowserTheme.textPrimary)
            })
            addView(TextView(activity).apply {
                text = if (candidate == SearchEngine.YOUTUBE) "ค้นหาบน YouTube" else "ค้นหาบน Google"
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.7f)
                setTextColor(if (active) BrowserTheme.onPrimaryContainer else BrowserTheme.textSecondary)
            })
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(if (active) BrowserTheme.primaryContainer else BrowserTheme.sheetCardBackground)
                cornerRadius = shell.cornerRadius()
                if (active) setStroke(sizes.dpInt(1.5f), BrowserTheme.accent)
            }
            setPadding(shell.pad(), shell.gap(), shell.pad(), shell.gap())
            contentDescription = candidate.label
            addView(TextView(activity).apply {
                text = glyph
                gravity = Gravity.CENTER
                textSize = shell.sp(AutoUiSizes.ICON_MEDIUM_DP)
                setTextColor(if (candidate == SearchEngine.YOUTUBE) BrowserTheme.errorAccent else BrowserTheme.accent)
                val side = sizes.dpInt(AutoUiSizes.ICON_LARGE_DP)
                layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = shell.gap() }
            })
            addView(texts)
            setOnClickListener {
                if (selectedEngine != candidate) {
                    selectedEngine = candidate
                    onEngineChange(candidate)
                    render()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------- primary button

    private fun primaryButton(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = shell.rounded(BrowserTheme.accent, shell.cornerRadius())
        contentDescription = "Send to car"
        addView(TextView(activity).apply {
            text = "🚗"
            textSize = shell.sp(AutoUiSizes.SHEET_ICON_DP * 1.1f)
            setPadding(0, 0, shell.pad(), 0)
        })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "Send to car"
                textSize = shell.sp(AutoUiSizes.ICON_MEDIUM_DP)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(BrowserTheme.onPrimary)
            })
            addView(TextView(activity).apply {
                text = "ส่งไปที่หน้าจอรถทันที"
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.78f)
                setTextColor(BrowserTheme.onPrimary)
            })
        })
        setOnClickListener { sendTyped() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(AutoUiSizes.SHEET_PRIMARY_CTA_HEIGHT_DP)
        ).apply { topMargin = shell.gap() }
    }

    /**
     * Sends what the text field holds; if it is empty, falls back to the current page so the big
     * button is never a dead tap when the user just wants to send the page they are on.
     */
    private fun sendTyped() {
        val typed = input.text.toString().trim()
        val ok = if (typed.isEmpty()) {
            currentUrl()?.let { onSendUrl(it) } ?: false
        } else {
            onSend(typed, selectedEngine)
        }
        if (ok) shell.dismiss()
    }

    // ------------------------------------------------------------------------------------ helpers

    private fun sectionLabel(thai: String, english: String): View = TextView(activity).apply {
        text = thai
        contentDescription = english
        textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.8f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(BrowserTheme.textSecondary)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = shell.gap(); bottomMargin = shell.gap() / 2 }
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = shell.gap() }

    private fun tabParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
}
