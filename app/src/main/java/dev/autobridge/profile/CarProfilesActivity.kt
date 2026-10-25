package dev.autobridge.profile

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.autobridge.BuildConfig
import dev.autobridge.R
import dev.autobridge.car.CarDisplayInfo
import dev.autobridge.display.SurfaceProfile
import dev.autobridge.i18n.AppLocale
import dev.autobridge.settings.SettingsStore
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Car profiles on the phone: the built-in ones, the ones saved from a car, and ones shared by
 * other drivers.
 *
 * "Save this car" records the screen of the car connected now (see [CarProfile.capture]); "Share"
 * sends it as a small JSON file through any app; "Import" reads such a file back. A file is only
 * ever read from storage the user picks; nothing is downloaded. Choosing a profile is always the
 * driver's tap; a profile matching the connected car is marked, never applied by itself.
 */
class CarProfilesActivity : Activity() {

    private companion object {
        const val REQUEST_IMPORT = 6101
        const val SHARE_DIR = "exports"
    }

    private val accent: Int get() = dev.autobridge.settings.ScreenAppearance.readable(AutoBridgeDesign.ACCENT_SYSTEM)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(dev.autobridge.ui.AppearanceContext.wrap(AppLocale.rebase(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // System and Auto modes depend on night mode and the clock, which may have moved on.
        dev.autobridge.settings.AppearanceStore.refreshPalette(this)
        dev.autobridge.settings.ScreenAppearance.applyWindow(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val connected = CarDisplayInfo.last
        val selectedId = CarProfileStore.selectedId(this)
        val body = AutoBridgeDesign.body(this)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(AutoBridgeDesign.pill(this, getString(R.string.car_profiles_save_this_car), primary = true, accent = accent) {
            saveThisCar()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 16 })
        actions.addView(AutoBridgeDesign.pill(this, getString(R.string.car_profiles_import), accent = accent) {
            pickFile()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.stack(actions, gap = 2)

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.car_profiles_built_in)), gap = 12)
        SurfaceProfile.entries.forEach { builtIn ->
            val inUse = selectedId == null && SurfaceProfile.active == builtIn
            body.stack(
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = builtIn.displayLabel,
                    subtitle = getString(
                        if (builtIn.hardwareValidated) R.string.car_profiles_builtin_default
                        else R.string.car_profiles_builtin_unverified
                    ),
                    accent = if (inUse) dev.autobridge.design.ThemeHolder.palette.good else AutoBridgeDesign.TEXT_MUTED,
                    badgeText = if (inUse) "✓" else null,
                    subtitleMaxLines = 2,
                    onClick = { useBuiltIn(builtIn) }
                )
            )
        }

        val saved = CarProfileStore.list(this)
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.car_profiles_saved)), gap = 12)
        if (saved.isEmpty()) {
            body.stack(
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = getString(R.string.car_profiles_none_title),
                    subtitle = getString(R.string.car_profiles_none_caption),
                    accent = AutoBridgeDesign.TEXT_MUTED,
                    // Not an item: without this the badge shows the title's first letter.
                    badgeText = "–",
                    subtitleMaxLines = 3,
                    onClick = {}
                )
            )
        }
        saved.forEach { profile ->
            val inUse = profile.id == selectedId
            body.stack(
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = profile.name,
                    subtitle = describe(profile, profile.matches(connected)),
                    accent = if (inUse) dev.autobridge.design.ThemeHolder.palette.good else accent,
                    badgeText = if (inUse) "✓" else null,
                    subtitleMaxLines = 3,
                    onClick = { showOptions(profile) }
                )
            )
        }

        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.car_profiles_title),
                    subtitle = getString(R.string.car_profiles_in_use, ActiveCarProfile.label),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    /** "Ford Ranger 2023 · 800x1280 @ 160 dpi · Unverified · Matches this car" */
    private fun describe(profile: CarProfile, matches: Boolean): String = listOfNotNull(
        profile.vehicle.takeIf { it.isNotBlank() },
        profile.headUnit.takeIf { it.isNotBlank() },
        profile.screen?.label,
        getString(R.string.car_profiles_unverified),
        getString(R.string.car_profiles_matches).takeIf { matches }
    ).joinToString(" · ")

    private fun useBuiltIn(profile: SurfaceProfile) {
        SurfaceProfile.active = profile
        SettingsStore.persistSurfaceProfile(this, profile)
        CarProfileStore.select(this, null)
        render()
    }

    private fun showOptions(profile: CarProfile) {
        val options = arrayOf(
            getString(R.string.car_profiles_use),
            getString(R.string.car_profiles_share),
            getString(R.string.car_profiles_delete)
        )
        AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(profile.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        CarProfileStore.select(this, profile.id)
                        toast(getString(R.string.car_profiles_now_using, profile.name))
                        render()
                    }
                    1 -> share(profile)
                    2 -> confirmDelete(profile)
                }
            }
            .show()
    }

    private fun confirmDelete(profile: CarProfile) {
        AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(getString(R.string.car_profiles_delete_title, profile.name))
            .setPositiveButton(R.string.car_profiles_delete) { _, _ ->
                CarProfileStore.remove(this, profile.id)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Asks for a name, then records the connected car's screen; nothing else is measured or guessed. */
    private fun saveThisCar() {
        val fingerprint = CarDisplayInfo.fingerprint
        if (fingerprint.surface == null) {
            toast(getString(R.string.car_profiles_connect_first))
            return
        }
        val name = EditText(this).apply {
            hint = getString(R.string.car_profiles_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val vehicle = EditText(this).apply {
            hint = getString(R.string.car_profiles_vehicle_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(name)
            addView(vehicle)
        }
        AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(R.string.car_profiles_save_this_car)
            .setMessage(getString(R.string.car_profiles_save_message, fingerprint.surface.let { "${it.width}x${it.height} @ ${it.dpi} dpi" }))
            .setView(form)
            .setPositiveButton(R.string.car_profiles_save) { _, _ ->
                val title = name.text.toString().trim().ifEmpty { getString(R.string.car_profiles_default_name) }
                val profile = CarProfile.capture(
                    id = CarProfileStore.newId(),
                    name = title.take(CarProfileCodec.MAX_TEXT),
                    vehicle = vehicle.text.toString().trim().take(CarProfileCodec.MAX_TEXT),
                    fingerprint = fingerprint,
                    measuredAt = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                    appVersion = BuildConfig.VERSION_NAME
                ) ?: return@setPositiveButton
                CarProfileStore.add(this, profile)
                toast(getString(R.string.car_profiles_saved_toast, profile.name))
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shares the profile as a file; read access goes to the chosen app only. */
    private fun share(profile: CarProfile) {
        val dir = File(cacheDir, SHARE_DIR).apply { mkdirs() }
        val safeName = profile.name.replace(Regex("[^A-Za-z0-9\\u0E01-\\u0E59._-]+") /* Thai letters and digits stay readable */, "-").trim('-').ifEmpty { "car" }
        val file = File(dir, "$safeName.autobridge-car.json").apply { writeText(CarProfileCodec.encode(profile)) }
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, profile.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.car_profiles_share))) }
    }

    private fun pickFile() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        runCatching { startActivityForResult(pick, REQUEST_IMPORT) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        data?.data?.let(::import)
    }

    /** Reads at most a little over the cap, so a huge file is refused without loading all of it. */
    private fun import(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytesCompat(CarProfileCodec.MAX_BYTES + 1)
                String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()
        if (text == null) {
            toast(getString(R.string.car_profiles_import_unreadable))
            return
        }
        when (val result = CarProfileCodec.decode(text, CarProfileStore.newId())) {
            is CarProfileCodec.Result.Ok -> {
                CarProfileStore.add(this, result.profile)
                toast(getString(R.string.car_profiles_imported, result.profile.name))
                render()
            }
            is CarProfileCodec.Result.Error -> toast(getString(importError(result.reason)))
        }
    }

    private fun importError(reason: CarProfileCodec.Reason): Int = when (reason) {
        CarProfileCodec.Reason.TOO_LARGE -> R.string.car_profiles_error_too_large
        CarProfileCodec.Reason.NOT_JSON, CarProfileCodec.Reason.NOT_A_PROFILE -> R.string.car_profiles_error_not_profile
        CarProfileCodec.Reason.NEWER_VERSION -> R.string.car_profiles_error_newer
        CarProfileCodec.Reason.MISSING_NAME, CarProfileCodec.Reason.BAD_VALUE -> R.string.car_profiles_error_bad
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
