package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.logging.StructuredLog
import dev.autobridge.obd.ObdConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The car's fault codes, read fresh when the screen opens: stored codes first, then pending ones.
 * Reading only; clearing codes stays on the phone, where it asks first and says what it resets.
 */
class CarDtcScreen(carContext: CarContext) : Screen(carContext) {
    private sealed interface State {
        data object Loading : State
        data object NotConnected : State
        data object Failed : State
        data class Ready(val report: ObdConnection.DtcReport) : State
    }

    private val scope = MainScope()
    private var state: State = State.Loading

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = scope.cancel()
        })
        read()
    }

    private fun read() {
        state = State.Loading
        invalidate()
        scope.launch {
            state = try {
                withContext(Dispatchers.IO) { ObdConnection.readDtcs() }?.let { State.Ready(it) } ?: State.NotConnected
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StructuredLog.w("DTC_ALERT", "car fault code read failed: $e")
                State.Failed
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val header = Header.Builder()
            .setTitle(carContext.getString(R.string.obd_dtc_title))
            .setStartHeaderAction(Action.BACK)
            .build()
        val builder = ListTemplate.Builder().setHeader(header)
        val current = state
        if (current is State.Loading) return builder.setLoading(true).build()

        val list = ItemList.Builder()
        when (current) {
            State.NotConnected -> list.setNoItemsMessage(carContext.getString(R.string.obd_dtc_car_connect_first))
            State.Failed -> list.setNoItemsMessage(carContext.getString(R.string.obd_dtc_read_failed))
            is State.Ready -> {
                val rows = current.report.stored.map { it to false } + current.report.pending.map { it to true }
                rows.take(limit()).forEach { (code, pending) ->
                    list.addItem(
                        Row.Builder()
                            .setTitle(code)
                            .addText(CarDtcAlerts.describe(carContext, code))
                            .addText(carContext.getString(if (pending) R.string.obd_dtc_pending else R.string.obd_dtc_stored))
                            .build()
                    )
                }
                if (rows.isEmpty()) list.setNoItemsMessage(carContext.getString(R.string.obd_dtc_none))
            }
            State.Loading -> Unit
        }
        return builder
            .setSingleList(list.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.obd_dtc_read))
                            .setOnClickListener { read() }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun limit(): Int = runCatching {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6)
}
