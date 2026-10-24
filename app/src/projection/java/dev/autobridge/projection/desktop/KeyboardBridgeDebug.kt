package dev.autobridge.projection.desktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import dev.autobridge.BuildConfig
import dev.autobridge.desktop.DesktopEngine
import dev.autobridge.desktop.keyboard.KeyboardInputBridge
import dev.autobridge.desktop.keyboard.Shortcut
import dev.autobridge.desktop.keyboard.TypingTarget
import dev.autobridge.safety.MockVehicleStateProvider
import dev.autobridge.safety.ParkingStateStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug builds only: drives and reads the keyboard bridge from `adb shell`, to prove text reaches
 * the focused Chrome field without a DevTools port. Never registered in release (BuildConfig.DEBUG,
 * not DEV_MODE, which release sets too). Only the shell can send: the broadcast needs DUMP.
 *
 * ```
 * adb shell am broadcast -a dev.autobridge.debug.KB --es op commit --es text 'สวัสดี'
 * adb shell am broadcast -a dev.autobridge.debug.KB --es op state
 * ```
 * Ops: prepare (text=field|address), commit, compose, finish, key (keysym hex, mods hex list),
 * shortcut (text=SELECT_ALL|COPY|CUT|PASTE|UNDO|REDO), paste, click (x, y), title, clipboard,
 * state, vehicle (vstate=parked|moving|unknown). Text from `text` or `b64` (UTF-8). Typing ops are
 * parked-only ([KeyboardTypingGate]). Results go to logcat tag AutoBridgeKbTest, percent-encoded.
 */
object KeyboardBridgeDebug {
    private const val ACTION = "dev.autobridge.debug.KB"
    private const val TAG = "AutoBridgeKbTest"
    private const val MAX_TEXT_BYTES = 200 * 1024
    private val registered = AtomicBoolean(false)

    fun register(context: Context) {
        if (!BuildConfig.DEBUG) return
        if (!registered.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    handle(intent)
                } catch (e: Exception) {
                    log("error", e.toString())
                }
            }
        }
        ContextCompat.registerReceiver(
            context.applicationContext, receiver, IntentFilter(ACTION),
            "android.permission.DUMP", null, ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun handle(intent: Intent) {
        val op = intent.getStringExtra("op") ?: return log("error", "no op")
        when (op) {
            "vehicle" -> return vehicle(intent.getStringExtra("vstate"))
            "title" -> return probe(op, KeyboardInputBridge.DebugRead.TITLE)
            "clipboard" -> return probe(op, KeyboardInputBridge.DebugRead.CLIPBOARD)
            "state" -> return probe(op, KeyboardInputBridge.DebugRead.STATE)
        }
        if (op !in TYPING_OPS) return log("error", "unknown op $op")
        if (!KeyboardTypingGate.allowed()) return log(op, "refused: not parked")
        val input = DesktopEngine.session.value?.input ?: return log(op, "refused: no session")
        val keys = input.keyboard
        when (op) {
            "prepare" -> keys.prepareForTyping(if (intent.getStringExtra("text") == "address") TypingTarget.ADDRESS else TypingTarget.FIELD)
            "commit" -> keys.commitText(text(intent) ?: return log(op, "error: no text"))
            "compose" -> keys.setComposingText(text(intent) ?: return log(op, "error: no text"))
            "finish" -> keys.finishComposing()
            "key" -> {
                val keysym = hex(intent, "keysym") ?: return log(op, "error: bad keysym")
                val mods = intent.getStringExtra("mods")?.split(',')?.filter { it.isNotBlank() }
                    ?.map { parseHex(it.trim()) ?: return log(op, "error: bad mods") }?.toIntArray() ?: IntArray(0)
                keys.key(keysym, *mods)
            }
            "shortcut" -> {
                val name = intent.getStringExtra("text")
                keys.shortcut(Shortcut.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return log(op, "error: bad shortcut"))
            }
            "paste" -> keys.pasteText(text(intent) ?: return log(op, "error: no text"))
            "click" -> {
                val map = DesktopEngine.session.value?.renderer?.mapping
                val maxX = (map?.displayWidth ?: Int.MAX_VALUE) - 1
                val maxY = (map?.displayHeight ?: Int.MAX_VALUE) - 1
                input.click(intent.getIntExtra("x", 0).coerceIn(0, maxX), intent.getIntExtra("y", 0).coerceIn(0, maxY))
            }
        }
        DesktopEngine.session.value?.renderer?.burst()
        log(op, "ok")
    }

    private fun probe(op: String, read: KeyboardInputBridge.DebugRead) {
        val keys = DesktopEngine.session.value?.input?.keyboard ?: return log(op, "error: no session")
        keys.debugProbe(read) { log(op, it) }
    }

    private fun vehicle(state: String?) {
        val s = when (state) {
            "parked" -> ParkingStateStore.State.PARKED
            "moving" -> ParkingStateStore.State.MOVING
            "unknown" -> ParkingStateStore.State.UNKNOWN
            else -> return log("vehicle", "error: vstate must be parked|moving|unknown")
        }
        if (!MockVehicleStateProvider.hasPermission()) log("vehicle", "mock inactive")
        MockVehicleStateProvider.setState(s)
        log("vehicle", "state=${ParkingStateStore.state}")
    }

    private fun text(intent: Intent): String? {
        val text = intent.getStringExtra("text")
            ?: intent.getStringExtra("b64")?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() }
            ?: return null
        return text.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES }
    }

    private fun hex(intent: Intent, name: String): Int? =
        intent.getStringExtra(name)?.let(::parseHex) ?: intent.getIntExtra(name, -1).takeIf { it in 0..MAX_KEYSYM }

    private fun parseHex(value: String): Int? =
        value.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.takeIf { it in 0..MAX_KEYSYM }

    private fun log(op: String, result: String) {
        Log.i(TAG, "op=$op result=${Uri.encode(result)}")
    }

    private const val MAX_KEYSYM = 0x1FFFFFFF
    private val TYPING_OPS = setOf("prepare", "commit", "compose", "finish", "key", "shortcut", "paste", "click")
}
