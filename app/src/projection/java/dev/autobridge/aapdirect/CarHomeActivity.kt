package dev.autobridge.aapdirect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.LinearLayout
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp

/**
 * The first screen the car shows in Android Auto direct: AutoBridge's sections as large tiles.
 * Each opens the same activity the phone uses, on this display (an activity started from here
 * stays on the car screen), so TV, radio, the browser and the library need no car-specific copy.
 */
class CarHomeActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, CarHomeActivity::class.java)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(dev.autobridge.ui.AppearanceContext.wrap(AppLocale.rebase(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.autobridge.settings.AppearanceStore.refreshPalette(this)
        render()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    private fun render() {
        val tiles = listOf(
            tile(R.string.section_tv, R.string.section_tv_caption, R.drawable.ic_tile_tv, AutoBridgeDesign.ACCENT_TV) {
                dev.autobridge.library.LibraryActivity.intent(this, dev.autobridge.library.LibraryActivity.Section.TV)
            },
            tile(R.string.section_radio, R.string.section_radio_caption, R.drawable.ic_tile_radio, AutoBridgeDesign.ACCENT_RADIO) {
                dev.autobridge.library.LibraryActivity.intent(this, dev.autobridge.library.LibraryActivity.Section.RADIO)
            },
            tile(R.string.section_web, R.string.section_web_caption, R.drawable.ic_tile_web, AutoBridgeDesign.ACCENT_WEB) {
                Intent(this, dev.autobridge.browser.BrowserActivity::class.java)
            },
            tile(R.string.section_folders, R.string.section_folders_caption, R.drawable.ic_tile_folder, AutoBridgeDesign.ACCENT_FILES) {
                dev.autobridge.library.LibraryActivity.intent(this, dev.autobridge.library.LibraryActivity.Section.FOLDERS)
            },
            tile(R.string.section_gallery, R.string.section_gallery_caption, R.drawable.ic_tile_gallery, AutoBridgeDesign.ACCENT_WEB) {
                dev.autobridge.library.LibraryActivity.intent(this, dev.autobridge.library.LibraryActivity.Section.GALLERY)
            },
            tile(R.string.section_weather, R.string.section_weather_caption, R.drawable.ic_tile_weather, AutoBridgeDesign.ACCENT_SYSTEM) {
                dev.autobridge.weather.WeatherActivity.intent(this)
            },
        )
        val landscape = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        val body = AutoBridgeDesign.body(this)
        AutoBridgeDesign.grid(this, tiles, columns = if (landscape) 3 else 2, gap = 10).forEach { row ->
            body.addView(row, LinearLayout.LayoutParams(-1, dp(112)).apply { bottomMargin = dp(10) })
        }
        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(this, title = getString(R.string.app_name)),
                body = body,
                applyInsets = false,
            ),
            keepScroll = false,
        )
    }

    private fun tile(title: Int, caption: Int, icon: Int, accent: Int, target: () -> Intent) =
        AutoBridgeDesign.sectionCard(this, getString(title), getString(caption), icon, accent) {
            runCatching { startActivity(target()) }
                .onFailure { DirectLog.add("car home: could not open ${getString(title)}: ${it.message}") }
        }
}
