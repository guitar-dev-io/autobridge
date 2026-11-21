package dev.autobridge.car

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.Alert
import androidx.car.app.model.CarText
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.app.NotificationCompat
import dev.autobridge.R
import dev.autobridge.logging.StructuredLog
import dev.autobridge.obd.Dtc
import dev.autobridge.obd.DtcAlertPolicy
import dev.autobridge.obd.DtcDatabase
import dev.autobridge.obd.DtcSystem
import dev.autobridge.obd.ObdConnection
import dev.autobridge.obd.ObdDtcActivity
import dev.autobridge.obd.ObdSettings
import dev.autobridge.obd.ObdState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Warns on the car screen when the car stores a new fault code, while a car session is up and the
 * OBD adapter is live. What is announced, and when, is [DtcAlertPolicy]; this only reads, shows
 * and remembers.
 *
 * The warning is an [Alert] with a Details button on hosts at car API 5 and later, a long
 * [CarToast] before that, and a phone notification in either case, so a warning that arrives while
 * another app is on the car screen is still somewhere to be found.
 */
object CarDtcAlerts {
    private const val TAG = "DTC_ALERT"
    private const val ALERT_ID = 0x4454 // "DT"
    private const val ALERT_MS = 15_000L
    private const val CHANNEL_ID = "autobridge_obd_dtc"
    private const val NOTIFICATION_ID = 0x4454

    private var scope: CoroutineScope? = null
    private var owner: CarContext? = null

    /** For this car session; the setting is read again before every check. */
    fun start(carContext: CarContext) {
        scope?.cancel()
        val app = carContext.applicationContext
        val running = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = running
        owner = carContext
        running.launch {
            ObdConnection.state
                .map { it.status == ObdState.Status.LIVE }
                .distinctUntilChanged()
                .collectLatest { live ->
                    if (!live) return@collectLatest
                    delay(DtcAlertPolicy.FIRST_CHECK_DELAY_MS)
                    while (true) {
                        if (ObdSettings.dtcAlerts(app)) check(carContext)
                        delay(DtcAlertPolicy.CHECK_INTERVAL_MS)
                    }
                }
        }
    }

    /**
     * Ends the checks [carContext]'s session started. A replacement session can start before the
     * outgoing one is destroyed, and the outgoing one must not stop the new one's.
     */
    fun stop(carContext: CarContext) {
        if (owner !== carContext) return
        scope?.cancel()
        scope = null
        owner = null
    }

    private suspend fun check(carContext: CarContext) {
        val app = carContext.applicationContext
        val report = try {
            withContext(Dispatchers.IO) { ObdConnection.readDtcs() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StructuredLog.w(TAG, "fault code check failed: $e")
            null
        } ?: return
        val outcome = DtcAlertPolicy.evaluate(ObdSettings.lastStoredDtcs(app), report)
        outcome.remember?.let { ObdSettings.setLastStoredDtcs(app, it) }
        StructuredLog.i(
            TAG,
            "check stored=${report.stored} pending=${report.pending} answered=${report.storedAnswered} new=${outcome.announce}"
        )
        if (outcome.announce.isNotEmpty()) announce(carContext, outcome.announce)
    }

    private fun announce(carContext: CarContext, codes: List<String>) {
        val title = carContext.getString(R.string.obd_dtc_alert_title)
        val line = line(carContext, codes.first())
        val more = codes.size - 1
        val body = if (more == 0) line else carContext.resources.getQuantityString(R.plurals.obd_dtc_alert_more, more, line, more)
        val shown = carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5 && showAlert(carContext, title, body)
        if (!shown) runCatching { CarToast.makeText(carContext, "$title: $body", CarToast.LENGTH_LONG).show() }
        notifyPhone(carContext.applicationContext, title, body)
    }

    private fun showAlert(carContext: CarContext, title: String, body: String): Boolean = runCatching {
        val details = Action.Builder()
            .setTitle(carContext.getString(R.string.obd_dtc_alert_details))
            .setOnClickListener {
                carContext.getCarService(ScreenManager::class.java).push(CarDtcScreen(carContext))
            }
            .build()
        val alert = Alert.Builder(ALERT_ID, CarText.create(title), ALERT_MS)
            .setSubtitle(CarText.create(body))
            .setIcon(CarIcons.of(carContext, R.drawable.ic_browser_warning))
            .addAction(details)
            .build()
        carContext.getCarService(AppManager::class.java).showAlert(alert)
        true
    }.getOrElse {
        StructuredLog.w(TAG, "alert not shown: $it")
        false
    }

    private fun notifyPhone(context: Context, title: String, body: String) {
        // The runtime permission exists from Android 13; before that, notifications are allowed
        // unless switched off, which areNotificationsEnabled() below covers.
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!allowed || !manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.obd_dtc_alert_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            ObdDtcActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_browser_warning)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    /** `P0133 · O2 Sensor Circuit Slow Response`, or the system's name when the list has no entry. */
    fun line(context: Context, code: String): String = "$code · ${describe(context, code)}"

    fun describe(context: Context, code: String): String =
        DtcDatabase.describe(context, code) ?: context.getString(
            R.string.obd_dtc_alert_no_description,
            context.getString(
                when (Dtc(code, pending = false).system) {
                    DtcSystem.POWERTRAIN -> R.string.obd_dtc_system_powertrain
                    DtcSystem.CHASSIS -> R.string.obd_dtc_system_chassis
                    DtcSystem.BODY -> R.string.obd_dtc_system_body
                    DtcSystem.NETWORK -> R.string.obd_dtc_system_network
                }
            )
        )
}
