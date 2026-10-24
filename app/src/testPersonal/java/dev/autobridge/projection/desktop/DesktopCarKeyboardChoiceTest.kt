package dev.autobridge.projection.desktop

import dev.autobridge.projection.desktop.DesktopCarKeyboard.Choice
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopCarKeyboardChoiceTest {
    @Test
    fun storedChoiceWins() {
        assertEquals(Choice.PHONE, DesktopCarKeyboard.resolve("PHONE", true))
        assertEquals(Choice.AUTOBRIDGE, DesktopCarKeyboard.resolve("AUTOBRIDGE", true))
        assertEquals(Choice.CAR, DesktopCarKeyboard.resolve("CAR", false))
    }

    @Test
    fun legacyCarKeyboardMigratesToCar() {
        assertEquals(Choice.CAR, DesktopCarKeyboard.resolve(null, true))
    }

    @Test
    fun legacyAutoBridgeKeyboardMigratesToAutoBridge() {
        assertEquals(Choice.AUTOBRIDGE, DesktopCarKeyboard.resolve(null, false))
    }

    @Test
    fun nothingStoredDefaultsToCar() {
        assertEquals(Choice.CAR, DesktopCarKeyboard.resolve(null, null))
    }

    @Test
    fun unknownStoredValueFallsBackToLegacy() {
        assertEquals(Choice.AUTOBRIDGE, DesktopCarKeyboard.resolve("bogus", false))
        assertEquals(Choice.CAR, DesktopCarKeyboard.resolve("bogus", null))
    }

    @Test
    fun ordinalsMatchTheRailSegments() {
        assertEquals(0, Choice.AUTOBRIDGE.ordinal)
        assertEquals(1, Choice.CAR.ordinal)
        assertEquals(2, Choice.PHONE.ordinal)
    }
}
