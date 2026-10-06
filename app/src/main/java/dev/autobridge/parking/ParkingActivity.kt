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
        // The photo works with or without a marked spot: a picture of the pillar number is worth having either way.
        body.stack(photoSection(), gap = 16)
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

    /** Take, view and delete the photo; a line says what it is for and where it stays. */
    private fun photoSection(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val has = ParkingStore.hasPhoto(this@ParkingActivity)
        addView(
            LinearLayout(this@ParkingActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    AutoBridgeDesign.pill(
                        this@ParkingActivity,
                        getString(if (has) R.string.parking_photo_retake else R.string.parking_photo_take),
                        accent = accent
                    ) { takePhoto() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
                )
                if (has) {
                    addView(
                        AutoBridgeDesign.pill(this@ParkingActivity, getString(R.string.parking_photo_view), accent = accent) { showPhoto() },
                        LinearLayout.LayoutParams(0, -2, 1f)
                    )
                }
            }
        )
        addView(TextView(this@ParkingActivity).apply {
            text = getString(if (has) R.string.parking_photo_saved_hint else R.string.parking_photo_hint)
            textSize = 12f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        })
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
