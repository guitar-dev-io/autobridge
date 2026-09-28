package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import dev.autobridge.agent.AgentCommandRouter
import dev.autobridge.core.state.RecentActivityStore

/**
 * Agent surface: "Ask, search, and control". The user speaks/types a phrase; [AgentCommandRouter]
 * maps it to an existing internal action (open browser/url/mirror/media, resume, recent,
 * desktop/fullscreen). The agent reuses existing navigation and never duplicates feature logic.
 *
 * Implemented on [SearchTemplate] because it is the only Car App template with a free-text +
 * voice-capable input field. Suggestion actions in the strip mirror the reference quick chips.
 */
class CarAgentScreen(carContext: CarContext) : Screen(carContext) {

    private var pendingText: String = ""

    override fun onGetTemplate(): Template {
        return SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) {
                    pendingText = searchText
                }

                override fun onSearchSubmitted(searchText: String) {
                    run(searchText)
                }
            }
        )
            .setHeaderAction(Action.BACK)
            .setSearchHint("พูดหรือพิมพ์คำสั่ง…")
            .setShowKeyboardByDefault(false)
            // A SearchTemplate's ActionStrip takes at most two actions (ACTIONS_CONSTRAINTS_SIMPLE);
            // a third throws and kills the app as soon as this screen is shown. "Open Google" is
            // dropped because typing it is the screen's whole purpose, while the other two shortcuts
            // have no equivalent here.
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(chip("ล่าสุด") { runCommand(AgentCommandRouter.Command(AgentCommandRouter.AgentAction.OPEN_RECENT)) })
                    .addAction(chip("เล่นเพลงต่อ") { runCommand(AgentCommandRouter.Command(AgentCommandRouter.AgentAction.RESUME_MEDIA)) })
                    .build()
            )
            .build()
    }

    private fun chip(title: String, onClick: () -> Unit): Action =
        Action.Builder().setTitle(title).setOnClickListener { onClick() }.build()

    private fun run(input: String) {
        val command = AgentCommandRouter.parse(input)
        if (command == null) {
            CarToast.makeText(carContext, "Say what you'd like AutoBridge to do", CarToast.LENGTH_SHORT).show()
            return
        }
        RecentActivityStore.record(
            carContext,
            RecentActivityStore.Entry(RecentActivityStore.Kind.AGENT, input.trim())
        )
        runCommand(command)
    }

    private fun runCommand(command: AgentCommandRouter.Command) {
        val result = AgentCommandRouter.execute(this, carContext, command)
        CarToast.makeText(carContext, result.message, CarToast.LENGTH_SHORT).show()
    }
}
