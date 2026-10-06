package dev.autobridge.checkpoint

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import java.text.DateFormat
import java.util.Date

/**
 * The driver's own list of checkpoints: add one from coordinates (paste them, or a map link) or
 * from where the phone is now, delete one. The car's checkpoint screen
 * ([dev.autobridge.car.CarCheckpointScreen]) shows how far the nearest are.
 *
 * Location is read only for "mark here" and, once allowed here or on the car, on the car's
 * checkpoint screen while it is open. It is never stored or sent anywhere.
 */
class CheckpointActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, CheckpointActivity::class.java)

        private const val REQUEST_LOCATION = 4102
    }

    private val accent = AutoBridgeDesign.ACCENT

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val points = CheckpointStore.all(this)
        val body = AutoBridgeDesign.body(this)

        body.stack(
            TextView(this).apply {
                text = getString(R.string.checkpoint_privacy)
                textSize = 13f
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
            },
            gap = 12
        )
        body.stack(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    AutoBridgeDesign.pill(this@CheckpointActivity, getString(R.string.checkpoint_mark_here), primary = true, accent = accent) { markHere() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
                )
                addView(
                    AutoBridgeDesign.pill(this@CheckpointActivity, getString(R.string.checkpoint_add), accent = accent) { showAddDialog() },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
            },
            gap = 12
        )
        body.stack(
            AutoBridgeDesign.pill(
                this,
                getString(if (CheckpointStore.locationAllowed(this)) R.string.checkpoint_location_on else R.string.checkpoint_location_allow),
                primary = !CheckpointStore.locationAllowed(this),
                accent = accent
            ) { toggleLocation() },
            gap = 20
        )

        if (points.isEmpty()) {
            body.stack(
                AutoBridgeDesign.emptyState(
                    context = this,
                    title = getString(R.string.checkpoint_empty_title),
                    message = getString(R.string.checkpoint_empty_message),
                    accent = accent
                )
            )
        } else {
            points.forEach { point ->
                body.stack(
                    AutoBridgeDesign.contentRow(
                        context = this,
                        title = point.name.ifBlank { getString(R.string.checkpoint_unnamed) },
                        subtitle = "%.5f, %.5f · %s".format(java.util.Locale.US, point.lat, point.lon, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(point.createdMs))),
                        accent = accent,
                        badgeText = "🚧",
                    ) { confirmDelete(point) }
                )
            }
        }

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.checkpoint_title),
                    subtitle = getString(R.string.checkpoint_subtitle),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    private fun hasPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun toggleLocation() {
        if (CheckpointStore.locationAllowed(this)) {
            CheckpointStore.setLocationAllowed(this, false)
            render()
        } else {
            CheckpointStore.setLocationAllowed(this, true)
            if (!hasPermission()) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
            render()
        }
    }

    /** Marks where the phone last was, asking for location first if it has not been given. */
    private fun markHere() {
        if (!hasPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val fix = runCatching {
            @Suppress("MissingPermission") // Checked above.
            listOfNotNull(
                manager?.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                manager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
            ).maxByOrNull { it.time }
        }.getOrNull()
        if (fix == null) {
            Toast.makeText(this, getString(R.string.checkpoint_no_fix), Toast.LENGTH_LONG).show()
            return
        }
        CheckpointStore.add(this, getString(R.string.checkpoint_default_name), fix.latitude, fix.longitude)
        render()
    }

    private fun showAddDialog() {
        val name = EditText(this).apply { hint = getString(R.string.maint_field_name) }
        val where = EditText(this).apply { hint = getString(R.string.checkpoint_field_coordinates) }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(name)
            addView(where)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.checkpoint_add))
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.fuel_save)) { _, _ ->
                val position = CheckpointMath.parseCoordinates(where.text.toString())
                if (position == null) {
                    Toast.makeText(this, getString(R.string.checkpoint_invalid), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                CheckpointStore.add(this, name.text.toString(), position.first, position.second)
                render()
            }
            .show()
    }

    private fun confirmDelete(point: Checkpoint) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.checkpoint_delete_title))
            .setMessage(point.name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.fuel_delete)) { _, _ ->
                CheckpointStore.delete(this, point.id)
                render()
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
