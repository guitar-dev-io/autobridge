package dev.autobridge.desktop.keyboard

/**
 * Spare keycodes that Unicode keysyms (Thai, emoji, anything not on the core map) are bound to.
 * Input-thread confined, pure: it decides, [dev.autobridge.desktop.input.X11Keyboard] sends.
 *
 * - Members: keycodes whose keysyms are all NoSymbol, or whose non-zero keysyms are all XF86
 *   (`0x1008FF00..0x1008FFFF`) or F13–F35, minus every modifier keycode and the keycodes Chrome
 *   acts on by keycode ([CHROME_ACTION_KEYCODES]). Xvfb has no physical keyboard, so the other
 *   media keys are free. Without a modifier map ([modifierKeycodes] null) only NoSymbol keycodes
 *   are used.
 * - With at least [THAI_PIN_MIN] members the Thai block is pinned once ([planThaiPin]) and never
 *   evicted, so Thai never remaps again.
 * - Other slots are LRU, and a slot is rebound only [REBIND_QUARANTINE_MS] after its last press
 *   (a rebind while the press is still being processed would type the new keysym).
 * - A [BindPlan] holds one [Run] per maximal block of consecutive member keycodes, so a request
 *   never rewrites a keycode outside the pool; members inside a run that are not rebound are
 *   written with their cached keysyms.
 */
class KeycodePool(
    mapping: Array<IntArray>,
    private val minKeycode: Int,
    modifierKeycodes: IntArray?,
    private val clock: () -> Long,
) {
    class Run(val firstKeycode: Int, val keysymsPerKeycode: Int, val keysyms: IntArray) {
        val count: Int get() = keysyms.size / keysymsPerKeycode
        val lastKeycode: Int get() = firstKeycode + count - 1
    }

    /** Requests to send, after waiting [waitMs] for quarantined slots to age out. */
    class BindPlan(val runs: List<Run>, val waitMs: Long) {
        companion object { val EMPTY = BindPlan(emptyList(), 0) }
    }

    /** Keysyms per keycode written in a run (at least 2: the keysym goes in columns 0 and 1). */
    val columns: Int = maxOf(2, mapping.firstOrNull()?.size ?: 2)
    val members: IntArray = select(mapping, minKeycode, modifierKeycodes)

    private val member = BooleanArray(KEYCODES)
    private val rows = arrayOfNulls<IntArray>(KEYCODES)
    private val slotKeysym = IntArray(KEYCODES)
    private val lastPress = LongArray(KEYCODES) { NEVER }
    private val lastUse = LongArray(KEYCODES)
    private val pinnedSlot = BooleanArray(KEYCODES)
    private val bound = HashMap<Int, Int>()
    private var useCounter = 0L

    var pinnedCount = 0
        private set
    var thaiPinned = false
        private set
    val size: Int get() = members.size
    val boundCount: Int get() = bound.size
    /** Slots LRU may rebind (pool size − pinned). */
    val evictableCapacity: Int get() = size - pinnedCount

    init {
        for (kc in members) {
            member[kc] = true
            rows[kc] = rowOf(mapping[kc - minKeycode])
        }
    }

    fun isMember(keycode: Int): Boolean = keycode in 0 until KEYCODES && member[keycode]

    /** The pool keycode [keysym] is bound to, or -1. */
    fun keycodeOf(keysym: Int): Int = bound[keysym] ?: -1

    fun isPinnedKeysym(keysym: Int): Boolean = bound[keysym]?.let { pinnedSlot[it] } ?: false

    fun notePressed(keycode: Int) {
        if (!isMember(keycode)) return
        lastPress[keycode] = clock()
        lastUse[keycode] = ++useCounter
    }

    /**
     * Binds every keysym in [keysyms] that is not bound yet; the ones already bound are kept for
     * this run (not evicted by it). Keysyms beyond the free capacity stay unbound.
     */
    fun plan(keysyms: IntArray): BindPlan = allocate(keysyms, pin = false)

    /** The Thai block, bound and pinned in one plan; null when the pool is small or already pinned. */
    fun planThaiPin(): BindPlan? {
        if (thaiPinned || size < THAI_PIN_MIN) return null
        thaiPinned = true
        return allocate(THAI_KEYSYMS, pin = true)
    }

    /**
     * Re-syncs with the server's mapping after a change we did not make: a slot whose keycode no
     * longer holds its keysym is unbound (and unpinned, so the Thai pin is redone later).
     */
    fun refresh(mapping: Array<IntArray>) {
        for (kc in members) {
            val index = kc - minKeycode
            if (index !in mapping.indices) continue
            val row = rowOf(mapping[index])
            val ks = slotKeysym[kc]
            if (ks != 0 && row[0] != ks) {
                bound.remove(ks)
                slotKeysym[kc] = 0
                if (pinnedSlot[kc]) { pinnedSlot[kc] = false; pinnedCount--; thaiPinned = false }
            }
            rows[kc] = row
        }
    }

    private fun allocate(keysyms: IntArray, pin: Boolean): BindPlan {
        val keep = BooleanArray(KEYCODES)
        val needs = ArrayList<Int>()
        for (ks in keysyms) {
            val kc = bound[ks]
            if (kc != null) {
                keep[kc] = true
                lastUse[kc] = ++useCounter
                if (pin && !pinnedSlot[kc]) { pinnedSlot[kc] = true; pinnedCount++ }
            } else if (ks !in needs) {
                needs += ks
            }
        }
        if (needs.isEmpty()) return BindPlan.EMPTY
        val now = clock()
        val readyBefore = now - REBIND_QUARANTINE_MS
        // Free slots first (lowest keycode, so pins and batches stay contiguous), then the least
        // recently used bound ones, then slots still in quarantine, soonest to age out first.
        fun group(kc: Int): Int = when {
            lastPress[kc] > readyBefore -> 2
            slotKeysym[kc] == 0 -> 0
            else -> 1
        }
        fun order(kc: Int): Long = when (group(kc)) {
            0 -> kc.toLong()
            1 -> lastUse[kc]
            else -> lastPress[kc]
        }
        val candidates = members.filter { !pinnedSlot[it] && !keep[it] }
            .sortedWith(compareBy<Int>({ group(it) }, { order(it) }))
        val chosen = candidates.take(needs.size)
        var wait = 0L
        for ((i, kc) in chosen.withIndex()) {
            if (lastPress[kc] != NEVER) wait = maxOf(wait, lastPress[kc] + REBIND_QUARANTINE_MS - now)
            val old = slotKeysym[kc]
            if (old != 0) bound.remove(old)
            val ks = needs[i]
            slotKeysym[kc] = ks
            bound[ks] = kc
            rows[kc] = IntArray(columns) { if (it < 2) ks else 0 }
            lastUse[kc] = ++useCounter
            if (pin) { pinnedSlot[kc] = true; pinnedCount++ }
        }
        return BindPlan(runs(chosen.sorted()), wait.coerceAtLeast(0))
    }

    private fun runs(sorted: List<Int>): List<Run> {
        val out = ArrayList<Run>()
        var i = 0
        while (i < sorted.size) {
            val start = sorted[i]
            var end = start
            var j = i + 1
            while (j < sorted.size && (end + 1..sorted[j]).all { member[it] }) {
                end = sorted[j]
                j++
            }
            val keysyms = IntArray((end - start + 1) * columns) { rows[start + it / columns]!![it % columns] }
            out += Run(start, columns, keysyms)
            i = j
        }
        return out
    }

    private fun rowOf(source: IntArray): IntArray = IntArray(columns) { source.getOrElse(it) { 0 } }

    companion object {
        const val REBIND_QUARANTINE_MS = 300L
        private const val KEYCODES = 256
        private const val NEVER = Long.MIN_VALUE / 2

        /**
         * The pinned Thai block as Unicode keysyms: U+0E01–U+0E3A and U+0E3F–U+0E4F, U+0E5A–U+0E5B
         * (77). Thai digits U+0E50–U+0E59 are left to the LRU slots: Thai keyboards type Arabic
         * digits by default, and the pool is too small to pin them as well.
         */
        val THAI_KEYSYMS: IntArray = ((0x0E01..0x0E3A) + (0x0E3F..0x0E4F) + (0x0E5A..0x0E5B))
            .map { 0x01000000 or it }.toIntArray()

        /**
         * The Thai block pinned + at least 12 slots left for everything else. The guest's Xvfb
         * keymap gives a 92-keycode pool once [CHROME_ACTION_KEYCODES] are left out (110 before,
         * measured on device), so Thai is pinned there and never remaps mid-typing (rebinding
         * under queued presses lost letters in Chrome).
         */
        val THAI_PIN_MIN = THAI_KEYSYMS.size + 12

        /**
         * Keycodes (evdev + 8) Chrome acts on by keycode whatever keysym they carry: for a keysym
         * with no key code of its own (Thai, emoji) Chrome falls back to the keycode's DomCode,
         * and these map to browser, media, launch and New/Close/Execute keys. Seen on device:
         * ั bound to keycode 180 (BrowserHome) sent Chrome to its home page. Media keycodes may
         * also hold Chrome's global media-key grabs.
         */
        val CHROME_ACTION_KEYCODES: Set<Int> = setOf(
            142, // Open -> VKEY_EXECUTE
            148, 152, // LaunchApp2, LaunchApp1
            163, // LaunchMail
            164, 166, 167, 180, 181, 225, // BrowserFavorites, Back, Forward, Home, Refresh, Search
            171, 172, 173, 174, // MediaTrackNext, MediaPlayPause, MediaTrackPrevious, MediaStop
            179, // MediaSelect
            189, 214, // New, Close
            212, // AllApplications (VKEY_ZOOM by hardware code)
        )

        fun isXf86(keysym: Int): Boolean = keysym in 0x1008FF00..0x1008FFFF
        fun isF13ToF35(keysym: Int): Boolean = keysym in 0xFFCA..0xFFE0

        /** Pool members for a GetKeyboardMapping result starting at [minKeycode]. */
        fun select(mapping: Array<IntArray>, minKeycode: Int, modifierKeycodes: IntArray?): IntArray {
            val out = ArrayList<Int>()
            for (i in mapping.indices) {
                val kc = i + minKeycode
                if (kc >= KEYCODES) break
                val row = mapping[i]
                val free = if (modifierKeycodes == null) {
                    row.all { it == 0 }
                } else {
                    kc !in modifierKeycodes && row.all { it == 0 || isXf86(it) || isF13ToF35(it) }
                }
                if (free && kc !in CHROME_ACTION_KEYCODES) out += kc
            }
            return out.toIntArray()
        }
    }
}
