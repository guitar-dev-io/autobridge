package dev.autobridge.parking

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
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
 * Where the car is parked: mark the spot (one tap, from a fresh fix), add a note to find it by,
 * and open Maps on it. Location is read only when the mark button is tapped, and only the spot
 * the driver marks is kept.
 */
class ParkingActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, ParkingActivity::class.java)

        private const val REQUEST_LOCATION = 4104
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
        val spot = ParkingStore.get(this)
        val body = AutoBridgeDesign.body(this)
        body.stack(
            TextView(this).apply {
                text = getString(R.string.parking_privacy)
                textSize = 13f
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
            },
            gap = 12
        )
        body.stack(
            AutoBridgeDesign.pill(this, getString(R.string.parking_mark_here), primary = true, accent = accent) { markHere() },
            gap = 16
        )
        if (spot == null) {
            body.stack(
                AutoBridgeDesign.emptyState(
                    context = this,
                    title = getString(R.string.parking_empty_title),
                    message = getString(R.string.parking_empty_message),
                    accent = accent
                )
            )
        } else {
            body.stack(
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = spot.note.ifBlank { getString(R.string.parking_no_note) },
                    subtitle = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(spot.savedMs)),
                    accent = accent,
                    badgeText = "🅿",
                ) { editNote(spot) },
                gap = 12
            )
            body.stack(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        AutoBridgeDesign.pill(this@ParkingActivity, getString(R.string.parking_navigate), primary = true, accent = accent) { navigate(spot) },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
                    )
                    addView(
                        AutoBridgeDesign.pill(this@ParkingActivity, getString(R.string.parking_clear), accent = accent) {
                            ParkingStore.clear(this@ParkingActivity)
                            render()
                        },
                        LinearLayout.LayoutParams(0, -2, 1f)
                    )
                }
            )
        }
        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.parking_title),
                    subtitle = getString(R.string.parking_subtitle),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) markHere()
    }

    private fun markHere() {
        if (!ParkingLocator.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
            return
        }
        Toast.makeText(this, getString(R.string.parking_finding), Toast.LENGTH_SHORT).show()
        ParkingLocator.fix(this) { location ->
            if (isFinishing || isDestroyed) return@fix
            if (location != null && ParkingStore.save(this, location.latitude, location.longitude)) {
                render()
                editNote(ParkingStore.get(this))
            } else {
                Toast.makeText(this, getString(R.string.parking_no_fix), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun editNote(spot: ParkingSpot?) {
        if (spot == null) return
        val note = EditText(this).apply {
            hint = getString(R.string.parking_note_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(spot.note)
        }
        val frame = LinearLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(note, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.parking_note_title))
            .setView(frame)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.fuel_save)) { _, _ ->
                ParkingStore.setNote(this, note.text.toString())
                render()
            }
            .show()
    }

    private fun navigate(spot: ParkingSpot) {
        val opened = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spot.geoUri()))) }.isSuccess
        if (!opened) Toast.makeText(this, getString(R.string.maps_app_missing), Toast.LENGTH_LONG).show()
    }
}
