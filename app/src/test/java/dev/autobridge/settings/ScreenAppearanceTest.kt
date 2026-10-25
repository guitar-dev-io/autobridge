package dev.autobridge.settings

import dev.autobridge.R
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.ThemeHolder
import dev.autobridge.design.ThemeMode
import dev.autobridge.mycar.MyCarFacts.Tone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenAppearanceTest {

    @After
    fun restore() = ThemeHolder.update(AppearanceSpec.DEFAULT, systemDark = true, minuteOfDay = 720)

    private fun use(mode: ThemeMode) = ThemeHolder.update(AppearanceSpec.DEFAULT.copy(mode = mode), systemDark = true, minuteOfDay = 720)

    @Test
    fun `My Car tones map to the status roles of the current palette`() {
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            use(mode)
            val palette = ThemeHolder.palette
            assertEquals(palette.danger, ScreenAppearance.tone(Tone.ALERT))
            assertEquals(palette.warn, ScreenAppearance.tone(Tone.WARN))
            assertEquals(palette.good, ScreenAppearance.tone(Tone.NORMAL))
            assertEquals(palette.textSecondary, ScreenAppearance.tone(Tone.EMPTY))
        }
    }

    @Test
    fun `dialogs follow the page brightness`() {
        use(ThemeMode.LIGHT)
        assertEquals(R.style.ToolDialogLight, ScreenAppearance.dialogTheme())
        use(ThemeMode.AMOLED)
        assertEquals(R.style.ToolDialogDark, ScreenAppearance.dialogTheme())
    }

    @Test
    fun `section accents are left alone on a dark page`() {
        use(ThemeMode.DARK)
        assertEquals(0xFFFFB35C.toInt(), ScreenAppearance.readable(0xFFFFB35C.toInt()))
    }
}
