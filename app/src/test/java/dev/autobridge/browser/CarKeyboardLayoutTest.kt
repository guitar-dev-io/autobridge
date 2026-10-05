package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car keyboard's layouts. Pure data, so the rules that are easy to break silently — Thai shift
 * producing a different letter rather than a case change, every layer carrying a way back out —
 * are asserted here rather than discovered on a head unit.
 */
class CarKeyboardLayoutTest {

    private fun texts(rows: List<List<CarKey>>): List<CarKey.Text> =
        rows.flatten().filterIsInstance<CarKey.Text>()

    @Test
    fun `every layer ends with the function row`() {
        for (language in CarKeyboardLanguage.entries) {
            for (shifted in listOf(false, true)) {
                for (symbols in listOf(false, true)) {
                    val rows = CarKeyboardLayouts.rows(language, shifted, symbols)
                    val last = rows.last()
                    assertTrue(
                        "$language shifted=$shifted symbols=$symbols has no Go key",
                        last.any { it is CarKey.Go }
                    )
                    assertTrue(last.any { it is CarKey.Backspace })
                    assertTrue(last.any { it is CarKey.Space })
                    // Without these two a driver who reaches the symbol layer or the wrong
                    // language has no way back to letters.
                    assertTrue(last.any { it is CarKey.Symbols })
                    assertTrue(last.any { it is CarKey.Language })
                }
            }
        }
    }

    @Test
    fun `latin shift is a case change`() {
        val q = texts(CarKeyboardLayouts.rows(CarKeyboardLanguage.LATIN, shifted = false, symbols = false))
            .first { it.lower == "q" }
        assertEquals("Q", q.upper)
    }

    @Test
    fun `thai shift is a different character, never a case change`() {
        // uppercase() is a no-op on Thai, so a layout that derived the shift layer would produce a
        // keyboard whose shift key visibly does nothing.
        val plain = texts(CarKeyboardLayouts.rows(CarKeyboardLanguage.THAI, shifted = false, symbols = false))
            .map { it.lower }
        val shifted = texts(CarKeyboardLayouts.rows(CarKeyboardLanguage.THAI, shifted = true, symbols = false))
            .map { it.lower }
        assertNotEquals(plain, shifted)
        assertTrue("ฤ is on the Thai shift layer", shifted.contains("ฤ"))
        assertTrue("ก is on the Thai base layer", plain.contains("ก"))
    }

    @Test
    fun `the symbol layer is the same whichever language is underneath it`() {
        val fromThai = texts(CarKeyboardLayouts.rows(CarKeyboardLanguage.THAI, shifted = false, symbols = true))
            .map { it.lower }
        val fromLatin = texts(CarKeyboardLayouts.rows(CarKeyboardLanguage.LATIN, shifted = false, symbols = true))
            .map { it.lower }
        assertEquals(fromThai, fromLatin)
    }

    @Test
    fun `no row is dense enough to lose its touch targets`() {
        // 12 keys across the narrowest head unit this app supports is already the limit; more
        // would put a key under the width of a fingertip.
        for (language in CarKeyboardLanguage.entries) {
            for (shifted in listOf(false, true)) {
                for (symbols in listOf(false, true)) {
                    CarKeyboardLayouts.rows(language, shifted, symbols).forEach { row ->
                        assertTrue(
                            "$language shifted=$shifted symbols=$symbols row of ${row.size}",
                            row.size <= 12
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `space is the widest key on its row`() {
        val row = CarKeyboardLayouts.rows(CarKeyboardLanguage.THAI, shifted = false, symbols = false).last()
        val widest = row.maxBy { CarKeyboardLayouts.weight(it) }
        assertTrue(widest is CarKey.Space)
    }
}
