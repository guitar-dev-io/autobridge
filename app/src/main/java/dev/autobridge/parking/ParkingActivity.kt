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
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.CarToolViews
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
        private const val REQUEST_PHOTO = 4105
    }

    private val accent = AutoBridgeDesign.ACCENT

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    /** The spot as one card, "navigate back" as the one main button, the rest below it. */
    private fun render() {
        val spot = ParkingStore.get(this)
        val hasPhoto = ParkingStore.hasPhoto(this)
        val body = AutoBridgeDesign.body(this)
        if (spot == null) {
            body.stack(
                AutoBridgeDesign.emptyState(
                    context = this,
                    title = getString(R.string.parking_empty_title),
                    message = getString(R.string.parking_empty_message),
                    accent = accent
                ),
                gap = 12
            )
            body.stack(AutoBridgeDesign.pill(this, getString(R.string.parking_mark_here), primary = true, accent = accent) { markHere() }, gap = 8)
        } else {
            body.stack(spotCard(spot), gap = 12)
            body.stack(AutoBridgeDesign.pill(this, getString(R.string.parking_navigate_back), primary = true, accent = accent) { navigate(spot) }, gap = 8)
        }
        // The photo works with or without a marked spot: a picture of the pillar number is worth having either way.
        body.stack(
            CarToolViews.pillRow(this, listOfNotNull(
                spot?.let { AutoBridgeDesign.pill(this, getString(R.string.parking_mark_again), accent = accent) { markHere() } },
                AutoBridgeDesign.pill(this, getString(if (hasPhoto) R.string.parking_photo_retake else R.string.parking_photo_take), accent = accent) { takePhoto() },
                if (hasPhoto) AutoBridgeDesign.pill(this, getString(R.string.parking_photo_view), accent = accent) { showPhoto() } else null
            )),
            gap = 8
        )
        if (spot != null) {
            body.stack(
                CarToolViews.pillRow(this, listOf(
                    AutoBridgeDesign.pill(this, getString(R.string.parking_edit_note), accent = accent) { editNote(spot) },
                    AutoBridgeDesign.pill(this, getString(R.string.parking_clear), accent = accent) {
                        ParkingStore.clear(this)
                        render()
                    }
                )),
                gap = 16
            )
        }
        body.stack(CarToolViews.footnote(this, getString(R.string.parking_privacy_short)), gap = 2)
        body.stack(CarToolViews.footnote(this, getString(if (hasPhoto) R.string.parking_photo_saved_hint else R.string.parking_photo_hint)))
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

    /** "Parked 2 hours ago", the date and time, and the note; tapping it edits the note. */
    private fun spotCard(spot: ParkingSpot) = CarToolViews.card(this) {
        addView(CarToolViews.text(context, getString(R.string.parking_parked_ago, RecentActivityStore.relativeAge(context, spot.savedMs)), 20f, bold = true))
        addView(CarToolViews.text(
            context,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(spot.savedMs)),
            13f,
            AutoBridgeDesign.TEXT_MUTED,
            top = 2
        ))
        addView(CarToolViews.text(
            context,
            spot.note.ifBlank { getString(R.string.parking_no_note) },
            15f,
            if (spot.note.isBlank()) AutoBridgeDesign.TEXT_MUTED else AutoBridgeDesign.TEXT,
            top = 10
        ))
        background = AutoBridgeDesign.tappable(context, AutoBridgeDesign.SURFACE, 16, accent, android.graphics.Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { editNote(spot) }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) markHere()
    }

    /** Opens the camera app to write one picture into the app's own folder; no camera permission is needed. */
    private fun takePhoto() {
        ParkingStore.discardPendingPhoto(this)
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.updates", ParkingStore.pendingPhotoFile(this))
        val capture = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Some camera apps only honour the grant when the URI also travels as clip data.
        capture.clipData = android.content.ClipData.newRawUri("", uri)
        val started = runCatching { startActivityForResult(capture, REQUEST_PHOTO) }.isSuccess
        if (!started) Toast.makeText(this, getString(R.string.parking_photo_no_camera), Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PHOTO) return
        if (resultCode == RESULT_OK && ParkingStore.commitPendingPhoto(this)) {
            Toast.makeText(this, getString(R.string.parking_photo_saved), Toast.LENGTH_SHORT).show()
        } else {
            ParkingStore.discardPendingPhoto(this)
        }
        render()
    }

    /** The photo full width, with the way to delete it. */
    private fun showPhoto() {
        val bitmap = ParkingPhoto.load(ParkingStore.photoFile(this))
        if (bitmap == null) {
            ParkingStore.deletePhoto(this)
            render()
            return
        }
        val image = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            contentDescription = getString(R.string.parking_photo_view)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.parking_title))
            .setView(image)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(getString(R.string.parking_photo_delete)) { _, _ ->
                ParkingStore.deletePhoto(this)
                Toast.makeText(this, getString(R.string.parking_photo_deleted), Toast.LENGTH_SHORT).show()
                render()
            }
            .setOnDismissListener { image.setImageDrawable(null) }
            .show()
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
