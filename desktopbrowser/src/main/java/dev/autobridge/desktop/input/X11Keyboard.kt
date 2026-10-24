package dev.autobridge.desktop.input

import dev.autobridge.desktop.DesktopLog
import dev.autobridge.desktop.display.X11Connection
import dev.autobridge.desktop.keyboard.KeycodePool
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/** X keysyms for the keys the bridge sends by name. */
object Keysym {
    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESCAPE = 0xff1b
    const val DELETE = 0xffff
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val INSERT = 0xff63
    const val F1 = 0xffbe
    const val F2 = 0xffbf
    const val F3 = 0xffc0
    const val F4 = 0xffc1
    const val F5 = 0xffc2
    const val F6 = 0xffc3
    const val F7 = 0xffc4
    const val F8 = 0xffc5
    const val F9 = 0xffc6
    const val F10 = 0xffc7
    const val F11 = 0xffc8
    const val F12 = 0xffc9
    const val SHIFT_L = 0xffe1
    const val SHIFT_R = 0xffe2
    const val CONTROL_L = 0xffe3
    const val CONTROL_R = 0xffe4
    const val ALT_L = 0xffe9
    const val ALT_R = 0xffea
    const val SUPER_L = 0xffeb
    const val SPACE = 0x20

    /** The keysym a character types: Latin-1 maps to itself, anything else to 0x01000000 + code point. */
    fun forCodePoint(codePoint: Int): Int = when {
        codePoint == '\n'.code -> RETURN
        codePoint == '\t'.code -> TAB
        codePoint in 0x20..0x7e || codePoint in 0xa0..0xff -> codePoint
        else -> 0x01000000 or codePoint
    }
}

/**
 * Types characters and key chords through XTEST. A keysym on the core keyboard map is pressed on
 * its keycode (with Shift for the second column). Any other one (Thai, emoji) is bound to a spare
 * keycode from a [KeycodePool] first: the keysyms a text run needs are bound in a few batched
 * ChangeKeyboardMapping requests and one round trip, then typed. With a big enough pool the Thai
 * block is bound once per display and never remapped.
 *
 * Input-thread confined, except the MappingNotify listener (reader thread), which only marks the
 * cached mapping stale. Keys it presses are always released, even when a write fails half way.
 */
class X11Keyboard(
    private val x: X11Connection,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val minKeycode = x.setup.minKeycode
    private val pool: KeycodePool
    /** keysym -> keycode on the core map, with [SHIFTED] when it is in the second column. */
    private val core = HashMap<Int, Int>()
    @Volatile private var stale = false
    /** (first keycode shl 8) or count of our own mapping writes, so their MappingNotify is ignored. */
    private val ownWrites = ConcurrentLinkedQueue<Int>()
    private val held = IntArray(MAX_HELD)
    private var heldCount = 0
    private var thaiChecked = false
    private var warnedNoSlot = false
    private var loggedChunking = false

    private val mappingListener: (Int, ByteArray) -> Unit = { code, event ->
        if (code == X11Connection.MAPPING_NOTIFY) onMappingNotify(event)
    }

    init {
        val mapping = x.keyboardMapping()
        val modifiers = try {
            x.getModifierMapping()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            DesktopLog.w("keyboard modifier map unavailable; using empty keycodes only", e)
            null
        }
        pool = KeycodePool(mapping, minKeycode, modifiers, clock)
        rebuildCore(mapping)
        x.addEventListener(mappingListener)
        DesktopLog.i("keyboard pool ${pool.size} keycodes")
    }

    val poolSize: Int get() = pool.size
    val pinnedCount: Int get() = pool.pinnedCount
    val boundCount: Int get() = pool.boundCount

    /** Same as [typeRun]; kept for existing callers. */
    fun typeText(text: String) = typeRun(text)

    /**
     * Types [text] code point by code point. Keysyms off the core map are bound first, in chunks
     * of at most the pool's evictable capacity, so a long run of distinct characters still types.
     */
    fun typeRun(text: String) {
        if (text.isEmpty()) return
        refreshIfStale()
        if (!thaiChecked) {
            thaiChecked = true
            pool.planThaiPin()?.let { apply(it) }
        }
        val keysyms = IntArray(text.codePointCount(0, text.length))
        var i = 0
        var n = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            keysyms[n++] = Keysym.forCodePoint(cp)
            i += Character.charCount(cp)
        }
        val cap = pool.evictableCapacity
        val chunk = LinkedHashSet<Int>()
        var start = 0
        for (k in keysyms.indices) {
            val ks = keysyms[k]
            if (cap == 0 || !needsSlot(ks) || ks in chunk) continue
            if (chunk.size == cap) {
                if (!loggedChunking) { loggedChunking = true; DesktopLog.i("keyboard: typing in chunks of $cap new characters") }
                typeChunk(keysyms, start, k, chunk)
                start = k
                chunk.clear()
            }
            chunk += ks
        }
        typeChunk(keysyms, start, keysyms.size, chunk)
    }

    fun tapKeysym(keysym: Int, vararg modifiers: Int) {
        refreshIfStale()
        var code = resolve(keysym)
        if (code < 0 && pool.evictableCapacity > 0) {
            apply(pool.plan(intArrayOf(keysym)))
            code = resolve(keysym)
        }
        if (code < 0) {
            if (!warnedNoSlot) { warnedNoSlot = true; DesktopLog.w("keyboard: no keycode for keysym 0x${Integer.toHexString(keysym)}; skipped") }
            return
        }
        val base = heldCount
        try {
            for (m in modifiers) {
                val mc = resolve(m)
                if (mc >= 0) press(mc and KEYCODE_MASK)
            }
            if ((code and SHIFTED) != 0) {
                val shift = resolve(Keysym.SHIFT_L)
                if (shift >= 0 && !isHeld(shift and KEYCODE_MASK, base)) press(shift and KEYCODE_MASK)
            }
            val keycode = code and KEYCODE_MASK
            x.fakeInput(X11Connection.KEY_PRESS, keycode)
            x.fakeInput(X11Connection.KEY_RELEASE, keycode)
            pool.notePressed(keycode)
        } finally {
            while (heldCount > base) {
                x.fakeInput(X11Connection.KEY_RELEASE, held[heldCount - 1])
                heldCount--
            }
        }
    }

    /** Releases any key a failed operation left pressed (bridge reset, first key after reconnect). */
    fun releaseAll() {
        while (heldCount > 0) {
            x.fakeInput(X11Connection.KEY_RELEASE, held[heldCount - 1])
            heldCount--
        }
    }

    private fun typeChunk(keysyms: IntArray, from: Int, to: Int, needs: Set<Int>) {
        if (needs.isNotEmpty()) apply(pool.plan(needs.toIntArray()))
        for (k in from until to) tapKeysym(keysyms[k])
    }

    private fun needsSlot(keysym: Int): Boolean = !core.containsKey(keysym) && !pool.isPinnedKeysym(keysym)

    /** Core keycode (maybe [SHIFTED]) or pool keycode for [keysym], or -1. */
    private fun resolve(keysym: Int): Int = core[keysym] ?: pool.keycodeOf(keysym)

    private fun apply(plan: KeycodePool.BindPlan) {
        if (plan.runs.isEmpty()) return
        if (plan.waitMs > 0) {
            val wait = plan.waitMs.coerceAtMost(KeycodePool.REBIND_QUARANTINE_MS)
            DesktopLog.d("keyboard: waiting ${wait} ms for keycodes to age out")
            Thread.sleep(wait)
        }
        for (run in plan.runs) {
            if (ownWrites.size >= MAX_OWN_WRITES) ownWrites.poll()
            ownWrites.add((run.firstKeycode shl 8) or run.count)
            x.changeKeyboardMappingRun(run.firstKeycode, run.keysymsPerKeycode, run.keysyms)
        }
        x.sync()
        // Chrome re-reads its keymap only after it handles the MappingNotify; a key pressed before
        // that is read with the old (empty) binding and lost. Measured on device: without this,
        // about half of the newly bound Thai letters in a burst were dropped.
        Thread.sleep(BIND_SETTLE_MS)
    }

    private fun refreshIfStale() {
        if (!stale) return
        stale = false
        val mapping = x.keyboardMapping()
        pool.refresh(mapping)
        rebuildCore(mapping)
        if (!pool.thaiPinned) thaiChecked = false
    }

    private fun rebuildCore(mapping: Array<IntArray>) {
        core.clear()
        for (i in mapping.indices) {
            val keycode = i + minKeycode
            if (pool.isMember(keycode)) continue
            val row = mapping[i]
            if (row.isNotEmpty() && row[0] != 0) core.putIfAbsent(row[0], keycode)
            if (row.size > 1 && row[1] != 0) core.putIfAbsent(row[1], keycode or SHIFTED)
        }
    }

    private fun onMappingNotify(event: ByteArray) {
        val request = event[4].toInt() and 0xff
        if (request == MAPPING_POINTER) return
        val first = event[5].toInt() and 0xff
        val count = event[6].toInt() and 0xff
        if (request == MAPPING_KEYBOARD && ownWrites.remove((first shl 8) or count)) return
        stale = true
    }

    private fun press(keycode: Int) {
        x.fakeInput(X11Connection.KEY_PRESS, keycode)
        if (heldCount < MAX_HELD) held[heldCount++] = keycode
    }

    private fun isHeld(keycode: Int, from: Int): Boolean {
        for (i in from until heldCount) if (held[i] == keycode) return true
        return false
    }

    private companion object {
        const val SHIFTED = 0x100
        const val KEYCODE_MASK = 0xff
        const val MAX_HELD = 16
        const val MAX_OWN_WRITES = 64
        /** After a rebind, how long the X clients get to pick up the new keymap. */
        const val BIND_SETTLE_MS = 60L
        const val MAPPING_KEYBOARD = 1
        const val MAPPING_POINTER = 2
    }
}
