package dev.autobridge.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.autobridge.R

/**
 * "Share → AutoBridge", and the `VIEW` intent that makes AutoBridge an option for opening a link.
 *
 * It is a transparent, no-UI activity on purpose. The user is in another app — YouTube, a
 * browser, a chat — and the whole interaction should be: tap share, pick AutoBridge, get a
 * one-line confirmation, stay where you were. Showing a screen here would mean a second thing to
 * dismiss before getting back to what they were doing.
 *
 * The three outcomes it reports are the three that actually differ to the user:
 *
 *  - **opened** — the car is connected and the content is on it now;
 *  - **saved for the car** — nothing is connected, and it will open by itself on the next
 *    connect (this is the common case: people share a link indoors and then walk to the car);
 *  - **refused** — with the reason, which for a protected service is a statement of fact rather
 *    than a failure to retry.
 *
 * Everything about *what* to do with the link is [AutoBridgeSessionManager]'s; this only turns an
 * `Intent` into a [BridgeSource].
 */
class ShareToCarActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val source = extract(intent)
        if (source == null) {
            BridgeLog.w("share.no_link", "action" to intent?.action, "type" to intent?.type)
            toast(getString(R.string.bridge_share_no_link))
            return
        }

        BridgeLog.i("share.received", "url" to source.url, "action" to intent?.action)
        AutoBridgeSessionManager.initialize(this)
        when (val result = AutoBridgeSessionManager.sendToCar(this, source)) {
            is AutoBridgeSessionManager.SendResult.Opened ->
                toast(getString(R.string.bridge_share_opened, source.displayHost))

            AutoBridgeSessionManager.SendResult.Pending ->
                toast(getString(R.string.bridge_share_pending, source.displayHost))

            is AutoBridgeSessionManager.SendResult.Refused ->
                toast(getString(result.error.messageRes))
        }
    }

    /**
     * The link in [intent], from whichever extra carries it.
     *
     * `ACTION_SEND` text is the share sheet. `ACTION_VIEW` data is "open with AutoBridge" from a
     * link, where the URI is the whole payload and there is no surrounding prose to mine for a
     * title. Both end at the same [ShareIntake] call so a link arriving by either route is
     * normalised identically.
     */
    private fun extract(intent: Intent?): BridgeSource? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> ShareIntake.parse(
                text = intent.getStringExtra(Intent.EXTRA_TEXT),
                subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            )

            Intent.ACTION_VIEW -> intent.dataString?.let { data ->
                ShareIntake.parse(text = data, subject = null)
            }

            else -> null
        }
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
