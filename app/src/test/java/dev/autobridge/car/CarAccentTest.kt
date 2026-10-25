package dev.autobridge.car

import dev.autobridge.design.AccentColor
import dev.autobridge.design.AppearanceSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** Template screens carry only the accent; both shades go to the host, which picks by day/night. */
class CarAccentTest {
    @Test fun bothShadesOfTheChosenAccent() {
        assertEquals(AccentColor.ROSE.onDark to AccentColor.ROSE.onLight, CarAccent.shades(AppearanceSpec(accent = AccentColor.ROSE)))
    }

    @Test fun theDefaultAccentWhenTheCarSwitchIsOff() {
        assertEquals(
            AccentColor.SKY.onDark to AccentColor.SKY.onLight,
            CarAccent.shades(AppearanceSpec(accent = AccentColor.LIME, carSameLook = false))
        )
    }
}
