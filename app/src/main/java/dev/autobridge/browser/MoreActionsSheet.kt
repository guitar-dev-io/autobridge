package dev.autobridge.browser

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The secondary "More actions" sheet.
 *
 * Everything moved off the main browser sheet so it stays uncluttered lives here, as a compact
 * vertical list rather than the main sheet's large tiles — these are rare actions, and a scannable
 * list of labelled rows reads faster than a wall of equal-weight squares. Each row carries an icon,
 * a title, an optional subtitle, and a chevron when it opens another screen (so a row that acts in
 * place and a row that navigates look different before they are tapped).
 *
 * Every row dispatches a [DrawerAction] straight back to [BrowserActivity.runMenuAction] through
 * [onAction]; this sheet implements no browser behaviour of its own, exactly as [BrowserMenuSheet]
 * does, so moving an action here changes where it is reached and nothing about what it does.
 *
 * Zoom is a single row with its own "−" and "+" controls instead of two separate entries, so the
 * two opposite adjustments of one setting sit together and can be repeated without the sheet
 * closing between taps.
 */
class MoreActionsSheet(
    private val activity: Activity,
    private val sizes: AutoUiSizes,
    private val onAction: (DrawerAction) -> Unit,
    /** Reopens the main sheet when the header back arrow is tapped. Null closes without reopening. */
    private val onBack: (() -> Unit)? = null,
) {
    private val shell = BrowserSheetShell(activity, sizes)

    /** One list entry. [chevron] marks rows that open another screen (History, Downloads, …). */
    private data class Row(
        val action: DrawerAction,
        val glyph: String,
        val title: String,
        val subtitle: String,
        val chevron: Boolean,
    )

    private val rows = listOf(
        Row(DrawerAction.RECEIVE_FROM_CAR, "◀", "Get from car", "ดึง URL จากจอรถมาที่โทรศัพท์", chevron = false),
        Row(DrawerAction.FIND_IN_PAGE, "⌕", "Find in page", "ค้นหาข้อความในหน้าเว็บ", chevron = true),
        Row(DrawerAction.COPY_URL, "⧉", "Copy URL", "คัดลอกลิงก์หน้าปัจจุบัน", chevron = false),
        Row(DrawerAction.PASTE_AND_GO, "⎘", "Paste & go", "วางลิงก์แล้วเปิดหน้าเว็บ", chevron = false),
        Row(DrawerAction.OPEN_EXTERNAL, "↗", "Open external", "เปิดลิงก์ในเบราว์เซอร์ภายนอก", chevron = true),
        Row(DrawerAction.HOME, "⌂", "Start page", "ไปที่หน้าเริ่มต้น", chevron = false),
        Row(DrawerAction.HISTORY, "↺", "History", "ประวัติการเข้าชม", chevron = true),
        Row(DrawerAction.DOWNLOADS, "↓", "Downloads", "ไฟล์ที่ดาวน์โหลด", chevron = true),
        Row(DrawerAction.SUPPORT, "☕", "Support AutoBridge", "สนับสนุนผู้พัฒนา (Buy Me a Coffee)", chevron = true),
        Row(DrawerAction.LICENSES, "⚖", "Open-source licenses", "สัญญาอนุญาตโอเพนซอร์ส", chevron = true),
        Row(DrawerAction.GITHUB, "⌥", "GitHub", "ซอร์สโค้ดบน GitHub", chevron = true),
    )

    fun show() {
        val container = shell.contentColumn()
        container.addView(shell.grip())
        container.addView(header())
        rows.forEach { container.addView(listRow(it)) }
        container.addView(zoomRow())
        shell.show(container)
    }

    /**
     * Header: a circular back arrow, then "More actions", matching the mockup's `‹  More actions`.
     * The back arrow dismisses this sheet and reopens the main one through [onBack] when supplied;
     * with no handler it simply closes, so the sheet is never a dead end.
     */
    private fun header(): View {
        val back = TextView(activity).apply {
            text = "‹"
            gravity = Gravity.CENTER
            textSize = shell.sp(AutoUiSizes.ICON_LARGE_DP)
            setTextColor(BrowserTheme.textPrimary)
            contentDescription = "ย้อนกลับ"
            val side = sizes.dpInt(AutoUiSizes.SHEET_CLOSE_BUTTON_DP)
            background = shell.rounded(BrowserTheme.sheetCardBackground, side / 2f)
            layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = shell.pad() }
            setOnClickListener { shell.dismiss(); onBack?.invoke() }
        }
        val title = TextView(activity).apply {
            text = "More actions"
            textSize = shell.sp(AutoUiSizes.ICON_LARGE_DP * 0.9f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BrowserTheme.textPrimary)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            layoutParams = rowParams()
        }
    }

    private fun listRow(row: Row): View {
        val icon = circularIcon(row.glyph)
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = row.title
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.95f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(BrowserTheme.textPrimary)
            })
            if (row.subtitle.isNotBlank()) {
                addView(TextView(activity).apply {
                    text = row.subtitle
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.72f)
                    setTextColor(BrowserTheme.textSecondary)
                })
            }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
            setPadding(shell.pad(), 0, shell.pad(), 0)
            contentDescription = row.title
            addView(icon)
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (row.chevron) {
                addView(TextView(activity).apply {
                    text = "›"
                    textSize = shell.sp(AutoUiSizes.ICON_MEDIUM_DP)
                    setTextColor(BrowserTheme.textSecondary)
                })
            }
            setOnClickListener {
                shell.dismiss()
                onAction(row.action)
            }
            layoutParams = rowParams().apply { height = sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP) }
        }
    }

    /** A leading glyph inside a circular tonal area, the mockup's list-row icon treatment. */
    private fun circularIcon(glyph: String): View = TextView(activity).apply {
        text = glyph
        gravity = Gravity.CENTER
        textSize = shell.sp(AutoUiSizes.ICON_MEDIUM_DP)
        setTextColor(BrowserTheme.iconEnabled)
        val side = sizes.dpInt(AutoUiSizes.SHEET_LIST_ICON_DP)
        background = shell.rounded(BrowserTheme.tileIconChip, side / 2f)
        layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = shell.pad() }
    }

    /** Zoom in one row: a label on the left, "−" and "+" that repeat without closing the sheet. */
    private fun zoomRow(): View {
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "Zoom"
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.95f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(BrowserTheme.textPrimary)
            })
            addView(TextView(activity).apply {
                text = "ขยาย / ย่อหน้าเว็บ"
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.72f)
                setTextColor(BrowserTheme.textSecondary)
            })
        }
        val icon = circularIcon("⊕")
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
            setPadding(shell.pad(), 0, shell.pad(), 0)
            addView(icon)
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // These do not dismiss the sheet: zoom is adjusted by repeated taps, and closing between
            // each would make "+ + +" three separate sheet openings.
            addView(zoomButton("−", "Zoom out") { onAction(DrawerAction.ZOOM_OUT) })
            addView(zoomButton("+", "Zoom in") { onAction(DrawerAction.ZOOM_IN) }.also {
                (it.layoutParams as LinearLayout.LayoutParams).marginStart = shell.gap()
            })
            layoutParams = rowParams().apply { height = sizes.dpInt(AutoUiSizes.SHEET_ROW_HEIGHT_DP) }
        }
    }

    private fun zoomButton(glyph: String, description: String, onClick: () -> Unit): View =
        TextView(activity).apply {
            text = glyph
            gravity = Gravity.CENTER
            textSize = shell.sp(AutoUiSizes.ICON_MEDIUM_DP)
            setTextColor(BrowserTheme.textPrimary)
            contentDescription = description
            val side = sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP)
            background = shell.rounded(BrowserTheme.tileBackground, side / 2f)
            layoutParams = LinearLayout.LayoutParams(side, side)
            setOnClickListener { onClick() }
        }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = shell.gap() }
}
