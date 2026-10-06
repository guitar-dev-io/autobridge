package dev.autobridge.backup

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.autobridge.R
import dev.autobridge.fuel.FuelLogStore
import dev.autobridge.i18n.AppLocale
import dev.autobridge.maintenance.MaintenanceStore
import dev.autobridge.trip.TripStore
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import java.io.File

/**
 * Moves the fuel/charging log, the maintenance list and the trips to another phone as one file:
 * share it out, open it on the other side. Restoring replaces what is there, after asking.
 */
class BackupActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, BackupActivity::class.java)

        private const val REQUEST_PICK = 4101
    }

    private val accent = AutoBridgeDesign.ACCENT

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = AutoBridgeDesign.body(this)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.backup_export),
                subtitle = getString(R.string.backup_export_caption),
                accent = accent,
                badgeText = "📤",
            ) { export() },
            gap = 10
        )
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.backup_import),
                subtitle = getString(R.string.backup_import_caption),
                accent = accent,
                badgeText = "📥",
            ) { pick() }
        )
        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.backup_title),
                    subtitle = getString(R.string.backup_subtitle),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    private fun export() {
        val text = BackupFormat.write(
            Backup(
                ev = FuelLogStore.isEv(this),
                fuel = FuelLogStore.rawJson(this),
                maintenance = MaintenanceStore.rawJson(this),
                trips = TripStore.rawJson(this),
                expenses = dev.autobridge.expense.ExpenseStore.rawJson(this),
            )
        )
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "autobridge-backup.json").apply { writeText(text) }
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.backup_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.backup_export))) }
    }

    private fun pick() {
        val open = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        runCatching { startActivityForResult(open, REQUEST_PICK) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || uri == null) return
        val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val backup = text?.let(BackupFormat::read)
        if (backup == null) {
            Toast.makeText(this, getString(R.string.backup_invalid), Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.backup_import))
            .setMessage(getString(R.string.backup_confirm))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.backup_replace)) { _, _ ->
                val ok = FuelLogStore.restore(this, backup.fuel, backup.ev) &&
                    MaintenanceStore.restore(this, backup.maintenance) &&
                    TripStore.restore(this, backup.trips) &&
                    dev.autobridge.expense.ExpenseStore.restore(this, backup.expenses)
                Toast.makeText(this, getString(if (ok) R.string.backup_done else R.string.backup_invalid), Toast.LENGTH_LONG).show()
            }
            .show()
    }
}
