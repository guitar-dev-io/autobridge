package dev.autobridge.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack

/**
 * The phone launcher shell: the home grid and the settings-style list pages.
 *
 * Both forms are drawn with [AutoBridgeDesign], so the launcher, the library sections and the
 * player all read as one app. The grid is a two-column (three on a tablet) set of accent-badged
 * cards; the list is the same surface treatment in a single column.
 */
object PhoneLauncherUi {
    /**
     * One home entry. [caption] is the quiet second line on a card and [accent] the colour the
     * section carries through its own screens; list pages ignore both.
     */
    data class Entry(
        val title: String,
        val icon: Int,
        val caption: String = "",
        val accent: Int = AutoBridgeDesign.ACCENT,
        val open: () -> Unit
    )

    fun screen(
        context: Context,
        title: String,
        entries: List<Entry>,
        grid: Boolean,
        home: (() -> Unit)? = null,
        menu: () -> Unit,
        statusChip: String? = null,
        headerAction: Pair<String, () -> Unit>? = null,
        subtitle: String? = null,
        bottomBar: View? = null,
        applyInsets: Boolean = true
    ): View {
        val header = AutoBridgeDesign.header(
            context = context,
            title = title,
            subtitle = subtitle,
            onBack = home,
            chip = statusChip,
            // The overflow menu always stays reachable but stays quiet; a screen-specific action
            // (the home microphone) is the primary one.
            actions = listOfNotNull(
                AutoBridgeDesign.HeaderAction("≡", menu),
                headerAction?.let { (glyph, handler) ->
                    AutoBridgeDesign.HeaderAction(glyph, handler, filled = true)
                }
            )
        )

        val body = AutoBridgeDesign.body(context)
        if (grid) {
            val columns = if (context.resources.configuration.screenWidthDp >= 600) 3 else 2
            entries.chunked(columns).forEach { chunk ->
                val row = LinearLayout(context)
                chunk.forEachIndexed { index, entry ->
                    row.addView(
                        AutoBridgeDesign.sectionCard(
                            context = context,
                            title = entry.title,
                            caption = entry.caption,
                            icon = entry.icon,
                            accent = entry.accent,
                            onClick = entry.open
                        ),
                        LinearLayout.LayoutParams(0, context.dp(132), 1f).apply {
                            marginStart = if (index == 0) 0 else context.dp(10)
                        }
                    )
                }
                // Keep the last row's cards the same width as every other row's.
                repeat(columns - chunk.size) {
                    row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply {
                        marginStart = context.dp(10)
                    })
                }
                body.stack(row, gap = 10)
            }
        } else entries.forEach { entry ->
            body.stack(
                AutoBridgeDesign.contentRow(
                    context = context,
                    title = entry.title,
                    subtitle = entry.caption,
                    accent = entry.accent,
                    badgeText = entry.title.trim().take(1),
                    badgeIcon = entry.icon,
                    trailing = "›",
                    onClick = entry.open
                )
            )
        }

        return AutoBridgeDesign.page(
            context = context,
            header = header,
            body = body,
            bottomBar = bottomBar,
            applyInsets = applyInsets
        )
    }
}
