package dev.autobridge.parking

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.CarToolForm
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

    /** What a position being looked for is for, or null when none is. */
    private enum class Finding { NEW_SPOT, ADD_TO_SPOT }

    private var finding: Finding? = null

    /** Bumped when a search is abandoned, so its late answer is ignored. */
    private var search = 0

    /**
     * As the design: the spot as one card (pin, how long ago, when, the note, the photo), then
     * Navigate back + Mark again, then Photo + Edit note, then one line on privacy.
     */
    private fun render() {
        val spot = ParkingStore.get(this)
        val hasPhoto = ParkingStore.hasPhoto(this)
        val body = AutoBridgeDesign.body(this)
        fun pill(label: Int, primary: Boolean = false, onClick: () -> Unit) =
            AutoBridgeDesign.pill(this, getString(label), primary = primary, accent = accent, onClick = onClick)
        val photoPill = pill(if (hasPhoto) R.string.parking_photo_retake else R.string.parking_photo_take) { takePhoto() }
        if (finding != null) {
            // In a garage GPS may never come: the way out is on screen the whole time.
            body.stack(CarToolViews.card(this) {
                addView(CarToolViews.text(context, getString(R.string.parking_finding_card, (ParkingLocator.TIMEOUT_MS / 1000).toInt()), 16f, bold = true))
                addView(CarToolViews.text(context, getString(R.string.parking_finding_hint), 13f, AutoBridgeDesign.TEXT_MUTED, top = 4))
            }, gap = 8)
            body.stack(pill(R.string.parking_skip_gps) { skipGps() }, gap = 12)
        } else if (spot == null) {
            body.stack(
                AutoBridgeDesign.emptyState(
                    context = this,
                    title = getString(R.string.parking_empty_title),
                    message = getString(R.string.parking_empty_message),
                    accent = accent
                ),
                gap = 12
            )
            body.stack(CarToolViews.pillRow(this, listOf(
                pill(R.string.parking_mark_here, primary = true) { markHere(Finding.NEW_SPOT) },
                pill(R.string.parking_save_without_gps) { saveWithoutGps(null) }
            )), gap = 8)
            // The photo works without a marked spot too: a picture of the pillar number is worth having.
            body.stack(CarToolViews.pillRow(this, listOfNotNull(
                photoPill,
                if (hasPhoto) pill(R.string.parking_photo_view) { showPhoto() } else null
            )), gap = 12)
        } else if (spot.hasPosition) {
            body.stack(spotCard(spot, hasPhoto), gap = 12)
            body.stack(CarToolViews.pillRow(this, listOf(
                pill(R.string.parking_navigate_back, primary = true) { navigate(spot) },
                pill(R.string.parking_mark_again) { markHere(Finding.NEW_SPOT) }
            )), gap = 8)
            body.stack(CarToolViews.pillRow(this, listOf(photoPill, pill(R.string.parking_edit_note) { editNote(spot) })), gap = 12)
        } else {
            // Saved without a position: the note and the photo are what finds the car.
            body.stack(spotCard(spot, hasPhoto), gap = 12)
            body.stack(CarToolViews.pillRow(this, listOf(
                photoPill,
                pill(R.string.parking_edit_note) { editNote(spot) }
            )), gap = 8)
            body.stack(CarToolViews.pillRow(this, listOf(
                pill(R.string.parking_add_gps) { markHere(Finding.ADD_TO_SPOT) },
                pill(R.string.parking_new_without_gps) { saveWithoutGps(null) }
            )), gap = 12)
        }
        body.stack(CarToolViews.footnote(this, getString(R.string.parking_privacy_short)))
        AutoBridgeDesign.showPage(
            this,
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

    /** Pin, "Parked 2 hours ago", the date and time, the note; the photo on the right opens it. */
    private fun spotCard(spot: ParkingSpot, hasPhoto: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        background = AutoBridgeDesign.tappable(context, AutoBridgeDesign.SURFACE, 16, accent, android.graphics.Color.TRANSPARENT)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        isClickable = true
        setOnClickListener { editNote(spot) }
        addView(CarToolViews.iconChip(context, R.drawable.ic_car_parking, AutoBridgeDesign.ACCENT_TV).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = pad * 3 / 4
            (layoutParams as LinearLayout.LayoutParams).gravity = android.view.Gravity.TOP
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(CarToolViews.text(context, getString(R.string.parking_parked_ago, RecentActivityStore.relativeAge(context, spot.savedMs)), 18f, bold = true))
            addView(CarToolViews.text(
                context,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(spot.savedMs)),
                13f,
                AutoBridgeDesign.TEXT_MUTED,
                top = 2
            ))
            if (!spot.hasPosition) addView(CarToolViews.text(context, getString(R.string.parking_no_position), 13f, AutoBridgeDesign.ACCENT_RADIO, top = 4))
            addView(CarToolViews.text(
                context,
                spot.note.ifBlank { getString(R.string.parking_no_note) },
                15f,
                if (spot.note.isBlank()) AutoBridgeDesign.TEXT_MUTED else AutoBridgeDesign.TEXT,
                top = 8
            ))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (hasPhoto) {
            val side = pad * 4
            ParkingPhoto.load(ParkingStore.photoFile(context), maxSide = side * 2)?.let { bitmap ->
                addView(android.widget.ImageView(context).apply {
                    setImageBitmap(bitmap)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE_RAISED, 12, android.graphics.Color.TRANSPARENT)
                    contentDescription = getString(R.string.parking_photo_view)
                    setOnClickListener { showPhoto() }
                }, LinearLayout.LayoutParams(side, side).apply { marginStart = pad * 3 / 4 })
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        val target = pendingFinding ?: Finding.NEW_SPOT
        pendingFinding = null
        if (grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            markHere(target)
        } else if (target == Finding.NEW_SPOT) {
            // No location allowed: the spot can still be saved as a note.
            saveWithoutGps(getString(R.string.parking_no_permission_subtitle))
        }
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

    private var pendingFinding: Finding? = null

    /**
     * Looks for the position, at most [ParkingLocator.TIMEOUT_MS], with "save without GPS" on
     * screen the whole time. With none in time, the note sheet opens so the spot is saved anyway.
     */
    private fun markHere(purpose: Finding) {
        if (!ParkingLocator.hasPermission(this)) {
            pendingFinding = purpose
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        finding = purpose
        val mine = ++search
        render()
        ParkingLocator.fix(this) { location ->
            if (isFinishing || isDestroyed || mine != search) return@fix
            finding = null
            val saved = location != null && when (purpose) {
                Finding.NEW_SPOT -> ParkingStore.save(this, location.latitude, location.longitude)
                Finding.ADD_TO_SPOT -> ParkingStore.addPosition(this, location.latitude, location.longitude)
            }
            render()
            when {
                saved && purpose == Finding.NEW_SPOT -> editNote(ParkingStore.get(this))
                saved -> Toast.makeText(this, getString(R.string.parking_position_added), Toast.LENGTH_SHORT).show()
                purpose == Finding.NEW_SPOT -> saveWithoutGps(getString(R.string.parking_no_fix_subtitle))
                else -> Toast.makeText(this, getString(R.string.parking_no_fix), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Stops waiting for GPS and saves the spot as a note instead. */
    private fun skipGps() {
        val purpose = finding
        search++
        finding = null
        render()
        if (purpose == Finding.NEW_SPOT) saveWithoutGps(null)
    }

    /**
     * A spot with no position: a note (floor, pillar, the sign) and the photo find the car. [why],
     * when given, says why GPS is not being used (none found, not allowed).
     */
    private fun saveWithoutGps(why: String?) {
        val form = CarToolForm(this, getString(R.string.parking_no_gps_title), why ?: getString(R.string.parking_no_gps_subtitle), accent = accent)
        val note = form.field(getString(R.string.parking_form_note), CarToolForm.Kind.TEXT, hint = getString(R.string.parking_note_hint))
        form.note(getString(R.string.parking_no_gps_photo_hint))
        form.show(getString(R.string.fuel_save)) {
            // A photo taken just before, for this spot, stays with it.
            ParkingStore.saveWithoutPosition(this, note.text.toString(), keepPhoto = ParkingStore.get(this) == null)
            render()
            null
        }
    }

    private fun editNote(spot: ParkingSpot?) {
        if (spot == null) return
        val form = CarToolForm(this, getString(R.string.parking_note_title), accent = accent)
        val note = form.field(getString(R.string.parking_form_note), CarToolForm.Kind.TEXT, initial = spot.note, hint = getString(R.string.parking_note_hint))
        // Clearing the spot is rare, so it lives here rather than as a button on the page.
        form.show(getString(R.string.fuel_save), danger = getString(R.string.parking_clear_spot) to {
            ParkingStore.clear(this)
            render()
        }) {
            ParkingStore.setNote(this, note.text.toString())
            render()
            null
        }
    }

    private fun navigate(spot: ParkingSpot) {
        val uri = spot.geoUri() ?: return
        val opened = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }.isSuccess
        if (!opened) Toast.makeText(this, getString(R.string.maps_app_missing), Toast.LENGTH_LONG).show()
    }
}
