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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.autobridge.R

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
 * Beside "send it now" sits "play it after this one": the same resolved address can go into
 * [BrowserPlayQueue] instead, which is why both buttons live on one sheet. Sending replaces what the
 * car is showing; queueing leaves it alone and takes over when it finishes. The queue itself is
 * listed here too, because a queue nothing can show or empty is a queue nobody trusts.
 *
 * @param onSend given the raw text and the chosen engine; the caller resolves and sends, returning
 *   true on success so the sheet closes only when the car accepted it.
 * @param onSendUrl given an already-resolved URL (the current-page card), for the same send path.
 * @param queue read/write access to the play queue, so this sheet holds no store logic of its own.
 */
class SendToCarSheet(
    private val activity: Activity,
    private val sizes: AutoUiSizes,
    private val isConnected: () -> Boolean,
    private val currentUrl: () -> String?,
    private val currentTitle: () -> String?,
    private val engine: () -> SearchEngine,
    private val onEngineChange: (SearchEngine) -> Unit,
    private val onSend: (input: String, engine: SearchEngine) -> Boolean,
    private val onSendUrl: (url: String) -> Boolean,
    private val queue: QueueAccess,
) {
    /**
     * What this sheet may do to the play queue. An interface rather than four more lambdas, and
     * implemented by the activity so the queue store is reached from one place.
     */
    interface QueueAccess {
        fun items(): List<BrowserPlayQueue.Item>

        /**
         * Queues [input], or the page the browser is on when it is null.
         *
         * @return the queue's new length, or null when nothing was added — an unusable address, or
         *   one already in the queue.
         */
        fun add(input: String?, engine: SearchEngine): Int?

        fun remove(url: String)

        fun clear()
    }

    private val shell = BrowserSheetShell(activity, sizes)

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

        val url = currentUrl()
        if (url != null) {
            container.addView(sectionLabel(activity.getString(R.string.send_section_current_page)))
            container.addView(currentPageCard(url))
        }

        container.addView(sectionLabel(activity.getString(R.string.send_section_or_type)))
        container.addView(inputField())
        container.addView(sectionLabel(activity.getString(R.string.send_section_search_engine)))
        container.addView(engineRow())
        container.addView(primaryButton())
        container.addView(queueButton())

        val queued = queue.items()
        if (queued.isNotEmpty()) {
            container.addView(
                sectionLabel(activity.getString(R.string.send_section_queue, queued.size))
            )
            queued.forEach { container.addView(queueRow(it)) }
            container.addView(clearQueueButton())
        }
    }

    // -------------------------------------------------------------------------- header + tabs

    private fun header(): View {
        val title = TextView(activity).apply {
            text = "Send to car"
            textSize = shell.sp(AutoUiSizes.ICON_LARGE_DP * 0.9f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(BrowserTheme.textPrimary)
        }
        val connected = isConnected()
        val pillColor = if (connected) BrowserTheme.secureBadge else BrowserTheme.textSecondary
        val pillLabel = activity.getString(
            if (connected) R.string.conn_connected_plain else R.string.conn_not_connected
        )
        val pill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.argb(
                    40,
                    android.graphics.Color.red(pillColor),
                    android.graphics.Color.green(pillColor),
                    android.graphics.Color.blue(pillColor)
                ))
                cornerRadius = sizes.dp(AutoUiSizes.TOUCH_TARGET_DP) / 2f
            }
            val hPad = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
            val vPad = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP / 2f)
            setPadding(hPad, vPad, hPad, vPad)
            addView(View(activity).apply {
                val dotSize = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(pillColor)
                }
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = sizes.dpInt(AutoUiSizes.CONTENT_GAP_DP / 2f)
                }
            })
            addView(TextView(activity).apply {
                text = pillLabel
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.75f)
                setTextColor(pillColor)
                maxLines = 1
            })
            contentDescription = pillLabel
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = shell.gap()
            })
            addView(shell.closeButton { shell.dismiss() })
            layoutParams = rowParams()
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
            contentDescription = activity.getString(R.string.send_current_page_action)
            addView(favicon)
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { if (onSendUrl(url)) shell.dismiss() }
            layoutParams = rowParams()
        }
    }

    // -------------------------------------------------------------------------------- input field

    private fun inputField(): View {
        val search = ImageView(activity).apply {
            setImageDrawable(iconDrawable(BrowserIcon.SEARCH, BrowserTheme.textSecondary))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val size = sizes.dpInt(AutoUiSizes.ICON_MEDIUM_DP)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        input = EditText(activity).apply {
            setSingleLine()
            hint = activity.getString(R.string.send_input_hint)
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
        val clear = ImageView(activity).apply {
            setImageDrawable(iconDrawable(BrowserIcon.CLOSE, BrowserTheme.textSecondary))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = activity.getString(R.string.send_clear_text)
            val side = sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP * 0.8f)
            val inset = (side - sizes.dpInt(AutoUiSizes.ICON_SMALL_DP)) / 2
            setPadding(inset, inset, inset, inset)
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
                text = activity.getString(
                if (candidate == SearchEngine.YOUTUBE) R.string.send_search_youtube
                else R.string.send_search_google
            )
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
        addView(ImageView(activity).apply {
            setImageDrawable(iconDrawable(BrowserIcon.CAR, BrowserTheme.onPrimary))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val side = sizes.dpInt(AutoUiSizes.SHEET_ICON_DP)
            layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = shell.pad() }
        })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = activity.getString(R.string.send_now_title)
                textSize = shell.sp(AutoUiSizes.ICON_MEDIUM_DP)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(BrowserTheme.onPrimary)
            })
            addView(TextView(activity).apply {
                text = activity.getString(R.string.send_now_caption)
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.78f)
                setTextColor(BrowserTheme.onPrimary)
            })
        })
        setOnClickListener { sendTyped() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(AutoUiSizes.SHEET_PRIMARY_CTA_HEIGHT_DP)
        ).apply { topMargin = shell.gap() }
    }

    /** The secondary action: queue instead of replacing what the car is showing. */
    private fun queueButton(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(BrowserTheme.sheetCardBackground)
            cornerRadius = shell.cornerRadius()
            setStroke(sizes.dpInt(1.5f), BrowserTheme.accent)
        }
        contentDescription = "Add to queue"
        addView(ImageView(activity).apply {
            setImageDrawable(iconDrawable(BrowserIcon.ADD, BrowserTheme.accent))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val side = sizes.dpInt(AutoUiSizes.SHEET_ICON_DP)
            layoutParams = LinearLayout.LayoutParams(side, side).apply { marginEnd = shell.pad() }
        })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = activity.getString(R.string.send_queue_title)
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(BrowserTheme.textPrimary)
            })
            addView(TextView(activity).apply {
                text = activity.getString(R.string.send_queue_caption)
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.78f)
                setTextColor(BrowserTheme.textSecondary)
            })
        })
        setOnClickListener { queueTyped() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, sizes.dpInt(AutoUiSizes.SHEET_PRIMARY_CTA_HEIGHT_DP * 0.8f)
        ).apply { topMargin = shell.gap() }
    }

    /** One queued page: what it is, and a way to take it back out. */
    private fun queueRow(item: BrowserPlayQueue.Item): View {
        val label = item.title.ifBlank { BrowserDisplayUrl.compact(item.url, max = 48) }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shell.rounded(BrowserTheme.sheetCardBackground, shell.cornerRadius())
            setPadding(shell.pad(), shell.gap(), sizes.dpInt(6f), shell.gap())
            addView(TextView(activity).apply {
                text = label
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.85f)
                setTextColor(BrowserTheme.textPrimary)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(ImageView(activity).apply {
                setImageDrawable(iconDrawable(BrowserIcon.CLOSE, BrowserTheme.textSecondary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = activity.getString(R.string.send_queue_remove)
                val side = sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP * 0.8f)
                val inset = (side - sizes.dpInt(AutoUiSizes.ICON_SMALL_DP)) / 2
                setPadding(inset, inset, inset, inset)
                layoutParams = LinearLayout.LayoutParams(side, side)
                setOnClickListener {
                    queue.remove(item.url)
                    render()
                }
            })
            layoutParams = rowParams()
        }
    }

    private fun clearQueueButton(): View = TextView(activity).apply {
        text = activity.getString(R.string.send_queue_clear)
        gravity = Gravity.CENTER
        textSize = shell.sp(AutoUiSizes.ICON_SMALL_DP * 0.85f)
        setTextColor(BrowserTheme.textSecondary)
        contentDescription = activity.getString(R.string.send_queue_clear)
        minHeight = sizes.dpInt(AutoUiSizes.TOUCH_TARGET_DP * 0.85f)
        setOnClickListener {
            queue.clear()
            render()
        }
        layoutParams = rowParams()
    }

    /**
     * Queues what the text field holds, or the current page when it is empty — the same fallback
     * [sendTyped] uses, so both buttons act on the same thing.
     *
     * The sheet deliberately stays open: queueing several things in a row is the normal way to use a
     * queue, and the list below updates so each one is visible as it lands. The field is cleared so
     * the next entry starts fresh.
     */
    private fun queueTyped() {
        val typed = input.text.toString().trim()
        queue.add(typed.takeIf { it.isNotEmpty() }, selectedEngine)
        if (typed.isNotEmpty()) input.setText("")
        render()
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

    private fun sectionLabel(text: String): View = TextView(activity).apply {
        this.text = text
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

    /** The shared [BrowserIcon] vector tinted for use in this sheet; see [BrowserIcon]. */
    private fun iconDrawable(icon: BrowserIcon, color: Int) =
        ContextCompat.getDrawable(activity, icon.resId)!!.mutate().apply { setTint(color) }
}
