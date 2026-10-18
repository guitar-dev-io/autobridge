package dev.autobridge.car

import org.junit.Assert.assertEquals
import org.junit.Test

class CarStationTileTest {
    @Test
    fun frequencyInTheNameIsTheLabel() {
        assertEquals("90.5", CarStationTile.label("มิติข่าว 90.5"))
        assertEquals("101", CarStationTile.label("101 kiss fm สุราษฎร์ธานี"))
        assertEquals("98", CarStationTile.label("Isan FM 98"))
        assertEquals("91", CarStationTile.label("สวพ.91"))
        assertEquals("104.5", CarStationTile.label("Flex 104,5"))
    }

    @Test
    fun withoutAFrequencyTheInitialsAreTheLabel() {
        assertEquals("CF", CarStationTile.label("Cool fahrenheit"))
        assertEquals("ล", CarStationTile.label("ลูกทุ่งเน็ตเวิร์ค"))
        assertEquals("T", CarStationTile.label("Top"))
        assertEquals("FM", CarStationTile.label("   "))
    }

    @Test
    fun aNumberOutsideTheDialIsNotAFrequency() {
        assertEquals("R", CarStationTile.label("Radio 2000"))
    }

    @Test
    fun aStationKeepsItsColour() {
        assertEquals(CarStationTile.colorFor("BKK.FM"), CarStationTile.colorFor(" bkk.fm "))
    }
}
