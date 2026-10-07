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
import dev.autobridge.R
import dev.autobridge.agent.AgentCommandRouter
import dev.autobridge.audio.VoiceFeedback
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.settings.AppPreferences
import dev.autobridge.voice.CommandValidator
import dev.autobridge.voice.Validation
import dev.autobridge.voice.VoiceAction
import dev.autobridge.voice.VoiceCommandParser

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
                // Only when the permission is already held: "prefer voice" is a convenience
                // setting, not consent to be asked for the microphone the moment a screen opens.
                if (!autoListenDone && AppPreferences.preferVoice(carContext) &&
                    !voice.needsMicPermission
                ) {
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
            .setSearchHint(
                carContext.getString(
                    if (listening) R.string.car_agent_hint_listening
                    else R.string.car_agent_hint_idle
                )
            )
            .setShowKeyboardByDefault(false)
            // ACTIONS_CONSTRAINTS_SIMPLE: at most two actions and at most ONE with a custom title.
            // Either limit exceeded throws in setActionStrip and kills the app as the screen opens
            // (the previous two titled chips hit the title limit).
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(micAction())
                    .addAction(chip(carContext.getString(R.string.car_agent_chip_resume)) {
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
                    IconCompat.createWithResource(carContext, R.drawable.ic_car_mic)
                ).build()
            )
            // Icon only: the SearchTemplate strip allows one titled action, and "resume" has it.
            .setOnClickListener {
                if (voice.isListening) {
                    voice.cancel()
                    listening = false
                    invalidate()
                } else {
                    startListening()
                }
            }
            .build()

    /**
     * Starts listening, with the microphone disclosure in front of the system prompt the first time
     * (or any time the permission has been revoked since). The disclosure is a screen rather than a
     * dialog because the car surface has no Activity to host one - see [CarDisclosureScreen].
     */
    private fun startListening() {
        if (voice.needsMicPermission) {
            screenManager.push(
                CarDisclosureScreen(
                    carContext,
                    R.string.mic_disclosure_title,
                    R.string.mic_disclosure_body
                ) { voice.requestPermissionAndStart() }
            )
        } else {
            voice.start()
        }
    }

    private fun chip(title: String, onClick: () -> Unit): Action =
        Action.Builder().setTitle(title).setOnClickListener { onClick() }.build()

    private fun run(input: String, fromVoice: Boolean) {
        if (runVoiceCommand(input, fromVoice)) return
        val command = AgentCommandRouter.parse(input)
        if (command == null) {
            // Same prompt the phone shows for unparseable input; see AgentCommandRouter.
            val prompt = carContext.getString(R.string.agent_prompt_say_command)
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

    /**
     * The offline voice grammar ([VoiceCommandParser] + [CommandValidator]) first: it knows YouTube
     * Music, TikTok and iQIYI by name in Thai and English, and Home/Back. What it opens goes through
     * the same [AgentCommandRouter] browser action as everything else here. Returns false for a
     * phrase it does not recognise, which then takes the Agent's own path below unchanged.
     *
     * Validated commands run at once on the car, as every Agent command always has: there is no
     * confirmation step to show a driver. A rejected address (not HTTPS, a private host) is refused.
     */
    private fun runVoiceCommand(input: String, fromVoice: Boolean): Boolean {
        val command = VoiceCommandParser.parse(input)
        val validated = when (val validation = CommandValidator.validate(command)) {
            is Validation.Valid -> validation.command
            is Validation.Rejected -> {
                if (validation.reason == Validation.Reason.UNKNOWN_COMMAND) return false
                val message = carContext.getString(R.string.agent_toast_invalid_website)
                CarToast.makeText(carContext, message, CarToast.LENGTH_SHORT).show()
                if (fromVoice) feedback.speak(carContext.getString(R.string.agent_spoken_invalid_website))
                return true
            }
        }
        RecentActivityStore.record(
            carContext,
            RecentActivityStore.Entry(RecentActivityStore.Kind.AGENT, input.trim())
        )
        when (validated.command.action) {
            VoiceAction.GO_BACK -> screenManager.pop()
            VoiceAction.GO_HOME -> screenManager.popToRoot()
            VoiceAction.OPEN_SCREEN -> validated.command.screen?.let { screen ->
                // Through the same navigator the home tiles use. The speed-safety row on Settings
                // needs the Home screen's vehicle-state session, so from here it points there.
                CarHomeNavigator.open(carContext, screenManager, screen.section) {
                    CarToast.makeText(
                        carContext,
                        carContext.getString(R.string.car_agent_safety_from_home),
                        CarToast.LENGTH_LONG
                    ).show()
                }
            }
            else -> {
                val url = validated.url
                runCommand(
                    if (url == null) AgentCommandRouter.Command(AgentCommandRouter.AgentAction.OPEN_BROWSER)
                    else AgentCommandRouter.Command(AgentCommandRouter.AgentAction.OPEN_URL, url),
                    fromVoice
                )
            }
        }
        return true
    }

    private fun runCommand(command: AgentCommandRouter.Command, fromVoice: Boolean) {
        val result = AgentCommandRouter.execute(this, carContext, command)
        CarToast.makeText(carContext, result.message, CarToast.LENGTH_SHORT).show()
        if (fromVoice) feedback.speak(result.spoken)
    }
}
