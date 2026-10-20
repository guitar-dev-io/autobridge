package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.agent.AgentCommandRouter
import dev.autobridge.core.state.RecentActivityStore

/**
 * Recent Activity list (Browser / Media / Mirror / Agent), newest first. Tapping an entry resumes
 * that action by routing through the same navigation the rest of the app uses (via
 * [AgentCommandRouter] for browser URLs / media / mirror), so no business logic is duplicated here.
 */
class CarRecentScreen(carContext: CarContext) : Screen(carContext) {
    private var deleteMode = false


    override fun onGetTemplate(): Template {
        val entries = RecentActivityStore.list(carContext)
        val list = ItemList.Builder()
        if (entries.isEmpty()) {
            list.setNoItemsMessage(carContext.getString(R.string.car_recent_empty))
        } else {
            entries.forEach { entry -> list.addItem(row(entry)) }
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(
                        carContext.getString(if (deleteMode) R.string.car_history_edit else R.string.car_recent_title)
                    )
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, if (deleteMode) CarIcons.DONE else CarIcons.EDIT))
                            .setOnClickListener {
                                deleteMode = !deleteMode
                                invalidate()
                            }
                            .build()
                    )
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.CLEAR))
                            .setOnClickListener {
                                screenManager.push(
                                    CarConfirmClearScreen(
                                        carContext,
                                        R.string.car_history_clear_confirm_title,
                                        R.string.car_recent_clear_confirm_message
                                    ) {
                                        RecentActivityStore.clear(carContext)
                                        invalidate()
                                    }
                                )
                            }
                            .build()
                    )
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }

    private fun row(entry: RecentActivityStore.Entry): Row {
        val age = RecentActivityStore.relativeAge(entry.timestampMs)
        val subtitle = listOfNotNull(entry.subtitle, age.takeIf { it.isNotBlank() }).joinToString("  •  ")
        val builder = Row.Builder().setTitle(entry.title)
        if (subtitle.isNotBlank()) builder.addText(subtitle)
        builder.setOnClickListener {
            if (deleteMode) {
                RecentActivityStore.remove(carContext, entry)
                invalidate()
            } else {
                resume(entry)
            }
        }
        return builder.build()
    }

    private fun resume(entry: RecentActivityStore.Entry) {
        val command = when (entry.kind) {
            RecentActivityStore.Kind.BROWSER ->
                AgentCommandRouter.Command(
                    AgentCommandRouter.AgentAction.OPEN_URL,
                    entry.data ?: "https://${entry.title}"
                )
            RecentActivityStore.Kind.MEDIA ->
                AgentCommandRouter.Command(AgentCommandRouter.AgentAction.RESUME_MEDIA)
            RecentActivityStore.Kind.MIRROR ->
                AgentCommandRouter.Command(AgentCommandRouter.AgentAction.OPEN_MIRROR)
            RecentActivityStore.Kind.AGENT ->
                AgentCommandRouter.parse(entry.data ?: entry.title)
                    ?: AgentCommandRouter.Command(AgentCommandRouter.AgentAction.OPEN_RECENT)
        }
        AgentCommandRouter.execute(this, carContext, command)
    }
}
