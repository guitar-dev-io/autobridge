package dev.autobridge.ui

import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import dev.autobridge.R
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.Wallpaper
import dev.autobridge.settings.AppearanceStore
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.appearance.AppearanceActions
import dev.autobridge.ui.appearance.AppearanceLabels
import dev.autobridge.ui.appearance.AppearanceModel
import dev.autobridge.ui.appearance.AppearanceModel.Page
import dev.autobridge.ui.appearance.AppearancePages
import dev.autobridge.ui.appearance.AppearanceUi
import dev.autobridge.ui.appearance.WallpaperPhoto
import dev.autobridge.ui.appearance.WallpaperUiState
import dev.autobridge.design.WallpaperLook
import androidx.annotation.StringRes

/**
 * Settings > Appearance: the page from the redesign, with its four sub-pages (Theme & colors,
 * Text, Wallpaper, Icons & cards) kept inside this screen rather than as phone routes. Which
 * sub-page is open is this screen's own state, with its own Back (header and system).
 *
 * Every change goes through [AppearanceStore.update] and then `onChanged`, which the phone shell
 * answers by recreating itself so everything already built picks the new look up. The open
 * sub-page and the scroll position are carried across that recreation here, in the process, so a
 * tap lands back where it was made; arriving from Settings starts on the main page.
 */
object AppearanceScreen {

    private var page = Page.MAIN
    private var scrollY = 0

    /** The Wallpaper page's own state (tab, theme being edited, crop device); fresh on every visit. */
    private var wallpaperUi = WallpaperUiState()

    /** Set just before `onChanged` recreates the activity, so the next [build] restores the page. */
    private var restoring = false

    private const val PHOTO_PICKER_KEY = "autobridge.appearance.wallpaper_photo"

    fun build(context: Context, onBack: () -> Unit, onChanged: () -> Unit): View {
        if (!restoring) {
            page = Page.MAIN
            scrollY = 0
            wallpaperUi = WallpaperUiState()
        }
        restoring = false
        val host = FrameLayout(context)
        val activity = context as? ComponentActivity
        lateinit var render: (Int) -> Unit

        val back = object : OnBackPressedCallback(page != Page.MAIN) {
            override fun handleOnBackPressed() {
                AppearanceModel.parent(page)?.let { page = it; isEnabled = it != Page.MAIN; render(0) }
            }
        }
        // Registered while the page is on screen only: the shell's own Back handler (added in
        // onCreate, so beneath this one) takes over again once the page is gone.
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                activity?.onBackPressedDispatcher?.addCallback(back)
            }

            override fun onViewDetachedFromWindow(v: View) = back.remove()
        })

        val actions = object : AppearanceActions {
            override val spec: AppearanceSpec get() = AppearanceStore.current()
            override val systemDark: Boolean
                get() = (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            override val systemScale: Float get() = Resources.getSystem().configuration.fontScale

            override fun change(transform: (AppearanceSpec) -> AppearanceSpec) {
                val before = AppearanceStore.current()
                AppearanceStore.update(context, transform)
                if (AppearanceStore.current() == before) return
                scrollY = AutoBridgeDesign.pageScroll(host)?.scrollY ?: 0
                restoring = true
                onChanged()
            }

            override fun open(page: Page) {
                this@AppearanceScreen.page = page
                back.isEnabled = page != Page.MAIN
                render(0)
            }

            override fun pickPhoto() {
                if (activity == null) return
                pickWallpaperPhoto(activity, host) {
                    if (AppearanceStore.current().wallpaper == Wallpaper.CUSTOM) {
                        // Same setting, new photo: nothing to recreate for, so redraw in place.
                        render(AutoBridgeDesign.pageScroll(host)?.scrollY ?: 0)
                    } else {
                        change { it.copy(wallpaper = Wallpaper.CUSTOM) }
                    }
                }
            }

            override fun askReset() = confirmReset(context, R.string.appearance_reset_title, R.string.appearance_reset_body) { change { AppearanceSpec.DEFAULT } }

            override val wallpaperUi: WallpaperUiState get() = this@AppearanceScreen.wallpaperUi

            override fun refresh() = render(AutoBridgeDesign.pageScroll(host)?.scrollY ?: 0)

            override fun removePhoto() {
                WallpaperPhoto.remove(context)
                Toast.makeText(context.applicationContext, R.string.appearance_wallpaper_photo_removed, Toast.LENGTH_SHORT).show()
                val before = AppearanceStore.current()
                change { spec ->
                    fun look(l: WallpaperLook) = if (l.wallpaper == Wallpaper.CUSTOM) l.copy(wallpaper = Wallpaper.GLOW) else l
                    spec.withWallpaperLook(false, look(spec.wallpaperLook(false)))
                        .let { s -> if (s.separateWallpapers) s.withWallpaperLook(true, look(s.wallpaperLook(true))) else s }
                }
                // Nothing in the spec pointed at the photo: still redraw, since it is gone from the page.
                if (AppearanceStore.current() == before) refresh()
            }

            override fun askResetWallpaper() = confirmReset(context, R.string.appearance_wallpaper_reset_title, R.string.appearance_wallpaper_reset_body) {
                Toast.makeText(context.applicationContext, R.string.appearance_wallpaper_reset_done, Toast.LENGTH_SHORT).show()
                this@AppearanceScreen.wallpaperUi = WallpaperUiState()
                change { spec ->
                    val d = AppearanceSpec.DEFAULT
                    spec.copy(
                        wallpaper = d.wallpaper, wallpaperDim = d.wallpaperDim, wallpaperBlur = d.wallpaperBlur, wallpaperAngle = d.wallpaperAngle,
                        phoneCrop = d.phoneCrop, carCrop = d.carCrop, lightWallpaper = null, wallpaperTarget = d.wallpaperTarget
                    )
                }
            }

            override fun pickTime(minuteOfDay: Int, onPicked: (Int) -> Unit) {
                TimePickerDialog(context, { _, hour, minute -> onPicked(hour * 60 + minute) },
                    minuteOfDay / 60, minuteOfDay % 60, DateFormat.is24HourFormat(context)).show()
            }
        }

        render = { scroll ->
            val view = AutoBridgeDesign.page(
                context = context,
                header = AutoBridgeDesign.header(
                    context = context,
                    title = AppearancePages.title(context, page),
                    subtitle = if (page == Page.MAIN) context.getString(R.string.appearance_subtitle) else null,
                    onBack = { if (page == Page.MAIN) onBack() else back.handleOnBackPressed() }
                ),
                body = AppearancePages.body(context, page, actions),
                applyInsets = false
            )
            host.removeAllViews()
            host.addView(view, FrameLayout.LayoutParams(-1, -1))
            val scrollView = AutoBridgeDesign.pageScroll(view)
            if (scroll > 0 && scrollView != null) {
                scrollView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        scrollView.viewTreeObserver.removeOnPreDrawListener(this)
                        scrollView.scrollTo(0, scroll)
                        return true
                    }
                })
            }
        }
        render(scrollY)
        return host
    }

    /** One line for the Settings row: "Dark · AutoBridge · Sky". */
    fun summary(context: Context): String {
        val spec = AppearanceStore.current()
        return listOf(
            AppearanceLabels.mode(context, spec.mode),
            AppearanceLabels.preset(context, spec.preset),
            AppearanceLabels.accent(context, spec.accent)
        ).joinToString(" · ")
    }

    /**
     * Opens the Android photo picker; the picked image is downscaled and saved off the main thread,
     * then [onSaved] runs on it. A registration without a lifecycle, so it can be made after the
     * activity started, and it is dropped as soon as the result comes back.
     */
    private fun pickWallpaperPhoto(activity: ComponentActivity, host: View, onSaved: () -> Unit) {
        var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null
        launcher = activity.activityResultRegistry.register(PHOTO_PICKER_KEY, ActivityResultContracts.PickVisualMedia()) { uri ->
            launcher?.unregister()
            if (uri == null) return@register
            val app = activity.applicationContext
            val main = Handler(Looper.getMainLooper())
            Thread {
                val saved = WallpaperPhoto.save(app, uri)
                main.post {
                    when {
                        !saved -> Toast.makeText(app, R.string.appearance_wallpaper_failed, Toast.LENGTH_SHORT).show()
                        host.isAttachedToWindow -> onSaved()
                    }
                }
            }.apply { name = "appearance-photo" }.start()
        }
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** "Reset appearance?" in the page's own look: title, explanation, Cancel and a danger Reset. */
    private fun confirmReset(context: Context, @StringRes title: Int, @StringRes body: Int, onConfirm: () -> Unit) {
        val p = AppearanceUi.p
        val dialog = Dialog(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppearanceUi.shape(context, p.surface, p.corners.card)
            setPadding(context.dp(22), context.dp(22), context.dp(22), context.dp(16))
            addView(AppearanceUi.text(context, context.getString(title), 20f, p.text, bold = true))
            addView(AppearanceUi.text(context, context.getString(body), 14f, p.textSecondary).apply {
                setLineSpacing(0f, 1.4f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                fun button(label: String, fill: Int, ink: Int, stroke: Int, onClick: () -> Unit) =
                    AppearanceUi.text(context, label, 15f, ink, bold = true, lines = 1).apply {
                        gravity = Gravity.CENTER
                        minHeight = context.dp(44)
                        minWidth = context.dp(88)
                        setPadding(context.dp(16), 0, context.dp(16), 0)
                        background = AppearanceUi.ripple(AppearanceUi.shape(context, fill, p.corners.button, stroke))
                        AppearanceUi.makeTappable(this, label) { onClick() }
                    }
                addView(button(context.getString(R.string.action_cancel), Color.TRANSPARENT, p.text, p.hairline) { dialog.dismiss() })
                addView(button(
                    context.getString(R.string.appearance_reset_confirm),
                    p.danger,
                    if (p.isLight) Color.WHITE else 0xFF1A0A0D.toInt(),
                    Color.TRANSPARENT
                ) { dialog.dismiss(); onConfirm() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(20) })
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(minOf(context.resources.displayMetrics.widthPixels - context.dp(48), context.dp(400)), -2)
    }
}
