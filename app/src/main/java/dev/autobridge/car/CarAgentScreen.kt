package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.agent.AgentCommandRouter
import dev.autobridge.audio.VoiceFeedback
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.settings.AppPreferences

/**
 * Agent surface: "Ask, search, and control". The user speaks/types a phrase; [AgentCommandRouter]
 * maps it to an existing internal action (open browser/url/mirror/media, resume, recent,
 * desktop/fullscreen). The agent reuses existing navigation and never duplicates feature logic.
 *
 * Voice: the mic action starts [CarVoiceInput] (car microphone when the host supports it, phone
 * microphone otherwise). With "Prefer voice actions" on, listening starts as soon as the screen
 * opens, so one tap on Agent is enough. A voice command is confirmed out loud by [VoiceFeedback];
 * a typed one only gets the toast.
 *
 * Implemented on [SearchTemplate] because it is the only Car App template with a free-text input
 * field. Its ActionStrip takes at most two actions, only one of them titled, so the strip holds an
 * icon-only mic and a titled "resume"; "recent" is still reachable by saying "ล่าสุด".
 */
class CarAgentScreen(carContext: CarContext) : Screen(carContext) {

    private var listening = false
    private var autoListenDone = false
    private val feedback = VoiceFeedback(carContext)

    private val voice = CarVoiceInput(carContext, object : CarVoiceInput.Listener {
        override fun onListening(source: CarVoiceInput.Source) {
            listening = true
            invalidate()
        }

        override fun onResult(text: String) {
            listening = false
            invalidate()
            run(text, fromVoice = true)
        }

        override fun onError(message: String) {
            listening = false
            invalidate()
            CarToast.makeText(carContext, message, CarToast.LENGTH_SHORT).show()
            feedback.speak(message)
        }
    })

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!autoListenDone && AppPreferences.preferVoice(carContext)) {
                    autoListenDone = true
                    voice.start()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                // Leaving the screen (including to the screen a command opened) ends listening.
                if (voice.isListening) {
                    voice.cancel()
                    listening = false
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                voice.cancel()
                feedback.shutdown()
            }
        })
    }

    override fun onGetTemplate(): Template {
        return SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) = Unit

                override fun onSearchSubmitted(searchText: String) {
                    run(searchText, fromVoice = false)
                }
            }
        )
            .setHeaderAction(Action.BACK)
            .setSearchHint(if (listening) "กำลังฟัง… พูดได้เลย" else "แตะไมค์แล้วพูด หรือพิมพ์คำสั่ง…")
            .setShowKeyboardByDefault(false)
            // ACTIONS_CONSTRAINTS_SIMPLE: at most two actions and at most ONE with a custom title.
            // Either limit exceeded throws in setActionStrip and kills the app as the screen opens
            // (the previous two titled chips hit the title limit).
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(micAction())
                    .addAction(chip("เล่นเพลงต่อ") {
                        runCommand(
                            AgentCommandRouter.Command(AgentCommandRouter.AgentAction.RESUME_MEDIA),
                            fromVoice = false
                        )
                    })
                    .build()
            )
            .build()
    }

    private fun micAction(): Action =
        Action.Builder()
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, dev.autobridge.R.drawable.ic_car_mic)
                ).build()
            )
            // Icon only: the SearchTemplate strip allows one titled action, and "resume" has it.
            .setOnClickListener {
                if (voice.isListening) {
                    voice.cancel()
                    listening = false
                    invalidate()
                } else {
                    voice.start()
                }
            }
            .build()

    private fun chip(title: String, onClick: () -> Unit): Action =
        Action.Builder().setTitle(title).setOnClickListener { onClick() }.build()

    private fun run(input: String, fromVoice: Boolean) {
        val command = AgentCommandRouter.parse(input)
        if (command == null) {
            val prompt = "บอกสิ่งที่อยากให้ AutoBridge ทำ"
            CarToast.makeText(carContext, prompt, CarToast.LENGTH_SHORT).show()
            if (fromVoice) feedback.speak(prompt)
            return
        }
        RecentActivityStore.record(
            carContext,
            RecentActivityStore.Entry(RecentActivityStore.Kind.AGENT, input.trim())
        )
        runCommand(command, fromVoice)
    }

    private fun runCommand(command: AgentCommandRouter.Command, fromVoice: Boolean) {
        val result = AgentCommandRouter.execute(this, carContext, command)
        CarToast.makeText(carContext, result.message, CarToast.LENGTH_SHORT).show()
        if (fromVoice) feedback.speak(result.spoken)
    }
}
