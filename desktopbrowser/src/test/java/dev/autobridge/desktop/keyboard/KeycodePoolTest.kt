package dev.autobridge.desktop.keyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeycodePoolTest {
    private var now = 10_000L
    private val clock = { now }

    /** Rows for keycodes [min]..: every keycode NoSymbol unless given. */
    private fun mapping(count: Int, columns: Int = 2, rows: Map<Int, IntArray> = emptyMap(), min: Int = MIN): Array<IntArray> =
        Array(count) { i -> rows[i + min]?.let { r -> IntArray(columns) { r.getOrElse(it) { 0 } } } ?: IntArray(columns) }

    private fun ks(i: Int) = 0x01000000 or (0x2000 + i)

    @Test fun selectionTakesNoSymbolXf86AndF13ToF35MinusModifiers() {
        val m = mapping(8, rows = mapOf(
            8 to intArrayOf('a'.code, 'A'.code),
            9 to intArrayOf(0x1008FF11), // XF86AudioLowerVolume
            10 to intArrayOf(0xFFCA),    // F13
            11 to intArrayOf(0xFFE0),    // F35
            12 to intArrayOf(0xFFE1),    // Shift_L: not F13–F35
            13 to intArrayOf(0x1008FF12),
        ))
        // 14, 15 NoSymbol; 13 is a modifier keycode here.
        assertArrayEquals(intArrayOf(9, 10, 11, 14, 15), KeycodePool.select(m, MIN, intArrayOf(13)))
        // Without a modifier map only NoSymbol keycodes are used.
        assertArrayEquals(intArrayOf(14, 15), KeycodePool.select(m, MIN, null))
    }

    @Test fun nonConsecutivePoolGivesOneRunPerBlockAndNeverWritesOutsideIt() {
        // Pool {100, 101, 103}: 102 holds 'a'.
        val min = 100
        val m = mapping(4, rows = mapOf(102 to intArrayOf('a'.code, 'A'.code)), min = min)
        val pool = KeycodePool(m, min, intArrayOf(), clock)
        assertArrayEquals(intArrayOf(100, 101, 103), pool.members)
        val plan = pool.plan(intArrayOf(ks(1), ks(2), ks(3)))
        assertEquals(2, plan.runs.size)
        assertEquals(100, plan.runs[0].firstKeycode)
        assertEquals(101, plan.runs[0].lastKeycode)
        assertEquals(103, plan.runs[1].firstKeycode)
        assertEquals(103, plan.runs[1].lastKeycode)
        val written = plan.runs.flatMap { r -> (r.firstKeycode..r.lastKeycode).toList() }
        assertFalse(102 in written)
        assertArrayEquals(intArrayOf(ks(1), ks(1), ks(2), ks(2)), plan.runs[0].keysyms)
        assertArrayEquals(intArrayOf(ks(3), ks(3)), plan.runs[1].keysyms)
        assertEquals(0, plan.waitMs)
        assertEquals(100, pool.keycodeOf(ks(1)))
        assertEquals(103, pool.keycodeOf(ks(3)))
    }

    @Test fun runsKeepTheCachedKeysymsOfMembersNotRebound() {
        val min = 100
        val m = mapping(3, columns = 4, rows = mapOf(101 to intArrayOf(0x1008FF11)), min = min)
        val pool = KeycodePool(m, min, intArrayOf(), clock)
        pool.plan(intArrayOf(ks(1), ks(2), ks(3))) // 100, 101, 102
        assertEquals(101, pool.keycodeOf(ks(2)))
        now += 1000
        // ks(2) stays (needed by this run); 100 and 102 are rebound; 101 is rewritten unchanged.
        val plan = pool.plan(intArrayOf(ks(2), ks(4), ks(5)))
        assertEquals(1, plan.runs.size)
        val run = plan.runs[0]
        assertEquals(100, run.firstKeycode)
        assertEquals(3, run.count)
        assertEquals(4, run.keysymsPerKeycode)
        assertArrayEquals(intArrayOf(ks(2), ks(2), 0, 0), run.keysyms.copyOfRange(4, 8))
        assertEquals(101, pool.keycodeOf(ks(2)))
        assertEquals(-1, pool.keycodeOf(ks(1)))
        assertEquals(-1, pool.keycodeOf(ks(3)))
    }

    @Test fun lruEvictsTheLeastRecentlyUsedSlot() {
        val min = 100
        val pool = KeycodePool(mapping(3, min = min), min, intArrayOf(), clock)
        pool.plan(intArrayOf(ks(1), ks(2), ks(3)))
        now += 1000
        pool.notePressed(pool.keycodeOf(ks(1)))
        now += 1000
        pool.notePressed(pool.keycodeOf(ks(3)))
        now += 1000
        pool.notePressed(pool.keycodeOf(ks(2)))
        now += 1000
        val freedSlot = pool.keycodeOf(ks(1))
        val plan = pool.plan(intArrayOf(ks(4)))
        assertEquals(freedSlot, pool.keycodeOf(ks(4)))
        assertEquals(-1, pool.keycodeOf(ks(1)))
        assertEquals(0, plan.waitMs)
    }

    @Test fun recentlyPressedSlotsAreQuarantined() {
        val min = 100
        val pool = KeycodePool(mapping(2, min = min), min, intArrayOf(), clock)
        pool.plan(intArrayOf(ks(1), ks(2)))
        pool.notePressed(100)
        now += 50
        pool.notePressed(101)
        now += 100
        // Slot 100 was pressed 150 ms ago, 101 100 ms ago: the older one is taken after 150 ms more.
        val plan = pool.plan(intArrayOf(ks(3)))
        assertEquals(150, plan.waitMs)
        assertEquals(100, pool.keycodeOf(ks(3)))
        // Both: wait for the younger one.
        now += 1000
        pool.notePressed(100)
        now += 10
        pool.notePressed(101)
        val both = pool.plan(intArrayOf(ks(4), ks(5)))
        assertEquals(KeycodePool.REBIND_QUARANTINE_MS, both.waitMs)
    }

    @Test fun readySlotsArePreferredOverQuarantinedOnes() {
        val min = 100
        val pool = KeycodePool(mapping(3, min = min), min, intArrayOf(), clock)
        pool.plan(intArrayOf(ks(1), ks(2)))
        pool.notePressed(100)
        pool.notePressed(101)
        val plan = pool.plan(intArrayOf(ks(3)))
        assertEquals(0, plan.waitMs)
        assertEquals(102, pool.keycodeOf(ks(3)))
    }

    @Test fun keysymsAlreadyBoundForTheRunAreNotEvicted() {
        val min = 100
        val pool = KeycodePool(mapping(2, min = min), min, intArrayOf(), clock)
        pool.plan(intArrayOf(ks(1), ks(2)))
        now += 1000
        pool.plan(intArrayOf(ks(1), ks(3)))
        assertEquals(100, pool.keycodeOf(ks(1)))
        assertEquals(101, pool.keycodeOf(ks(3)))
        // More new keysyms than free slots: the extra one stays unbound.
        now += 1000
        val plan = pool.plan(intArrayOf(ks(1), ks(4), ks(5)))
        assertEquals(1, plan.runs.sumOf { it.count })
        assertEquals(100, pool.keycodeOf(ks(1)))
        assertTrue(pool.keycodeOf(ks(4)) == 101 && pool.keycodeOf(ks(5)) == -1)
    }

    @Test fun thaiIsPinnedOnlyWithEnoughSlots() {
        val small = KeycodePool(mapping(KeycodePool.THAI_PIN_MIN - 1), MIN, intArrayOf(), clock)
        assertNull(small.planThaiPin())
        assertEquals(KeycodePool.THAI_PIN_MIN - 1, small.evictableCapacity)

        val pool = KeycodePool(mapping(KeycodePool.THAI_PIN_MIN), MIN, intArrayOf(), clock)
        val plan = pool.planThaiPin()!!
        val thai = KeycodePool.THAI_KEYSYMS.size
        assertEquals(77, thai)
        assertEquals(thai, plan.runs.sumOf { it.count })
        assertEquals(1, plan.runs.size) // contiguous NoSymbol keycodes: one request
        assertEquals(thai, pool.pinnedCount)
        val spare = KeycodePool.THAI_PIN_MIN - thai
        assertEquals(spare, pool.evictableCapacity)
        assertTrue(pool.thaiPinned)
        assertTrue(pool.isPinnedKeysym(0x01000E01))
        assertTrue(pool.isPinnedKeysym(0x01000E31)) // ั, the character that went to keycode 180
        assertTrue(pool.isPinnedKeysym(0x01000E5B))
        assertFalse(pool.isPinnedKeysym(0x01000E3B)) // unassigned gap
        assertFalse(pool.isPinnedKeysym(0x01000E50)) // Thai digits go to the LRU slots
        assertNull(pool.planThaiPin())

        // Heavy non-Thai typing never evicts a pinned slot.
        val thaiKo = pool.keycodeOf(0x01000E01)
        repeat(10) { round ->
            now += 1000
            pool.plan(IntArray(spare) { ks(round * spare + it) })
        }
        assertEquals(thaiKo, pool.keycodeOf(0x01000E01))
        assertEquals(thai, pool.pinnedCount)
    }

    @Test fun thaiIsPinnedOnTheGuestsPoolSize() {
        // The guest's Xvfb keymap leaves 110 pool keycodes (measured on device), 92 without the
        // keycodes Chrome acts on.
        val pool = KeycodePool(mapping(92), MIN, intArrayOf(), clock)
        assertNotNull(pool.planThaiPin())
        assertEquals(77, pool.pinnedCount)
        assertEquals(15, pool.evictableCapacity)
    }

    /** Chrome turns these keycodes into browser/media keys whatever keysym they carry. */
    @Test fun keycodesChromeActsOnAreNeverMembers() {
        // 178..182 NoSymbol: 179 MediaSelect, 180 BrowserHome, 181 BrowserRefresh are left out.
        val m = mapping(5, rows = mapOf(182 to intArrayOf(0x1008FF56)), min = 178) // 182 XF86Close (Exit)
        assertArrayEquals(intArrayOf(178, 182), KeycodePool.select(m, 178, intArrayOf()))
        assertArrayEquals(intArrayOf(178), KeycodePool.select(m, 178, null))
    }

    @Test fun refreshUnbindsSlotsChangedByAnotherClient() {
        val min = 100
        val pool = KeycodePool(mapping(2, min = min), min, intArrayOf(), clock)
        pool.plan(intArrayOf(ks(1), ks(2)))
        val server = arrayOf(intArrayOf(ks(1), ks(1)), intArrayOf(0x41, 0x61))
        pool.refresh(server)
        assertEquals(100, pool.keycodeOf(ks(1)))
        assertEquals(-1, pool.keycodeOf(ks(2)))
        assertEquals(1, pool.boundCount)
    }

    private companion object {
        const val MIN = 8
    }
}
