package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import dev.autobridge.BuildConfig
import dev.autobridge.R
import dev.autobridge.support.QrCode
import dev.autobridge.support.SupportChannels

/**
 * Car Settings > Support: the same channels as the phone's support card, one at a time.
 *
 * A car screen cannot open a link, so the QR is the whole point here: a passenger scans it from the
 * head unit. The pane shows the chosen channel's QR as its image, the link (or the PromptPay hint)
 * under the channel name, and one action that steps to the next channel - a pane takes at most
 * two actions, which is not enough for one button per channel.
 */
class CarSupportScreen(carContext: CarContext) : Screen(carContext) {

    private val channels = SupportChannels.available(safeBuild = BuildConfig.AUTOBRIDGE_MODE == "SAFE")
    private var index = 0

    override fun onGetTemplate(): Template {
        val title = carContext.getString(R.string.support_card_title)
        val channel = channels.getOrNull(index)
            ?: return MessageTemplate.Builder(carContext.getString(R.string.support_card_message))
                .setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
                .build()

        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(channel.label)
                    .addText(channel.displayUrl ?: carContext.getString(R.string.support_scan_bank_app))
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.support_car_scan_title))
                    .addText(carContext.getString(R.string.support_card_message))
                    .build()
            )
        // Pane images arrived with Car API 4; older hosts still get the link as text.
        if (carContext.carAppApiLevel >= 4) pane.setImage(qrIcon(channel))
        if (channels.size > 1) {
            val next = channels[(index + 1) % channels.size]
            pane.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.support_car_next, next.label))
                    .setOnClickListener {
                        index = (index + 1) % channels.size
                        invalidate()
                    }
                    .build()
            )
        }
        return PaneTemplate.Builder(pane.build())
            .setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
            .build()
    }

    private fun qrIcon(channel: SupportChannels.Channel): CarIcon {
        val bundled = QrCode.bundled(channel.kind)
        val icon =
            if (bundled != null) IconCompat.createWithBitmap(QrCode.bundledOnWhite(carContext, bundled, QR_PX))
            else IconCompat.createWithBitmap(QrCode.bitmap(channel.url.orEmpty(), QR_PX))
        return CarIcon.Builder(icon).build()
    }

    private companion object {
        /** Large enough to stay sharp on a wide head unit; the host scales it into the pane. */
        const val QR_PX = 480
    }
}
