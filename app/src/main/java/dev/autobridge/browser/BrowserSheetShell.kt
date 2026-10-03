package dev.autobridge.browser

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.autobridge.R

/**
 * The bottom-sheet chrome shared by every phone browser sheet.
 *
 * All three phone sheets ([BrowserMenuSheet], [SendToCarSheet], [MoreActionsSheet]) are the same
 * kind of surface: a dark, top-rounded panel anchored to the bottom of the window, dimming the page
 * behind it, scrolling as a whole when it outgrows a short screen. That window/scroll/dim boilerplate
 * lived once in [BrowserMenuSheet] and would have been copied into each new sheet; it is centralised
 * here instead so every sheet reads and dismisses identically and only its *content* differs.
 *
 * It deliberately uses the same translucent [Dialog] approach [BrowserMenuSheet] established rather
 * than a Material `BottomSheetDialog`, so the project keeps one sheet implementation and does not
 * take on the Material Components dependency for the browser.
 */
internal class BrowserSheetShell(
    private val activity: Activity,
    private val sizes: AutoUiSizes,
) {
    private var dialog: Dialog? = null

    private val fontScale = AutoUiSizes.clampFontScale(activity.resources.configuration.fontScale)

    /** Text size in sp for a dp token, with the accessibility scale already capped. */
    fun sp(dp: Float) = dp * (fontScale / activity.resources.configuration.fontScale.coerceAtLeast(0.01f))

    /** 16dp — the mockup's internal padding and the spacing between its sections. */
    fun pad() = sizes.dpInt(AutoUiSizes.SHEET_PADDING_DP)

    /** 12dp — the vertical gap the mockup leaves between stacked sections/cards. */
    fun gap() = sizes.dpInt(AutoUiSizes.SHEET_SECTION_GAP_DP)

    /** 26dp — the generous card corner radius the mockup uses throughout. */
    fun cornerRadius() = sizes.dp(AutoUiSizes.SHEET_CORNER_RADIUS_DP)

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    /** The decorative drag pill at the top of every sheet. Not a tap target. */
    fun grip(): View = View(activity).apply {
        background = rounded(BrowserTheme.outline, sizes.dp(2f))
        layoutParams = LinearLayout.LayoutParams(sizes.dpInt(40f), sizes.dpInt(4f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = gap()
        }
    }

    /**
     * The circular, icon-only "✕" close button in a sheet header — 48dp per the mockup, no "Close"
     * text. A perfect circle (radius = half the side) on a tonal surface.
     */
    fun closeButton(onClick: () -> Unit): View = TextView(activity).apply {
        text = "✕"
        gravity = Gravity.CENTER
        textSize = sp(AutoUiSizes.ICON_SMALL_DP)
        setTextColor(BrowserTheme.textSecondary)
        contentDescription = activity.getString(R.string.browser_close)
        val side = sizes.dpInt(AutoUiSizes.SHEET_CLOSE_BUTTON_DP)
        background = rounded(BrowserTheme.sheetCardBackground, side / 2f)
        layoutParams = LinearLayout.LayoutParams(side, side)
        setOnClickListener { onClick() }
    }

    /**
     * Builds the panel background (dark, rounded only on top because its lower edge is the screen
     * edge) and the content column the caller fills. 16dp padding on every side matches the mockup.
     */
    fun contentColumn(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(BrowserTheme.sheetBackground)
            val r = cornerRadius()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        setPadding(pad(), pad(), pad(), pad())
    }

    /**
     * Shows [content] as the bottom sheet.
     *
     * The sheet is anchored to the bottom and inset by [AutoUiSizes.SHEET_SIDE_MARGIN_DP] left and
     * right so it reads as a floating card with compact side margins, the way the mockup draws it,
     * rather than edge-to-edge. A bottom margin of the same size lifts the rounded lower corners
     * clear of the screen edge; because the panel no longer touches the bottom, it is rounded on
     * all four corners here (the content column still rounds only its top, which is what shows
     * through the padding).
     */
    fun show(content: View) {
        val margin = sizes.dpInt(AutoUiSizes.SHEET_SIDE_MARGIN_DP)
        // Round all four corners of the actual panel now that it floats off every edge.
        (content.background as? GradientDrawable)?.cornerRadius = cornerRadius()
        dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(ScrollView(activity).apply {
                isFillViewport = true
                setPadding(margin, 0, margin, margin)
                clipToPadding = false
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
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.55f)
            }
            show()
        }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}
