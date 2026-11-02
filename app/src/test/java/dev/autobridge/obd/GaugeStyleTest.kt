package dev.autobridge.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class GaugeStyleTest {
    @Test fun aStyleRoundTrips() {
        val style = GaugeStyle(DialStyle.NEEDLE, TileStyle.STRIPE, ColorMode.ACCENT, TextSize.LARGE)
        assertEquals(style, GaugeStyle.decode(style.encode()))
        assertEquals(GaugeStyle.DEFAULT, GaugeStyle.decode(GaugeStyle.DEFAULT.encode()))
    }

    @Test fun nothingOrGarbageIsTheDefault() {
        assertEquals(GaugeStyle.DEFAULT, GaugeStyle.decode(null))
        assertEquals(GaugeStyle.DEFAULT, GaugeStyle.decode(""))
        assertEquals(GaugeStyle.DEFAULT, GaugeStyle.decode("x,y,z,w"))
    }

    @Test fun eachPartFallsBackOnItsOwn() {
        val style = GaugeStyle.decode("DIGITAL,NOPE,MONO")
        assertEquals(DialStyle.DIGITAL, style.dial)
        assertEquals(TileStyle.BAR, style.tile)
        assertEquals(ColorMode.MONO, style.color)
        assertEquals(TextSize.NORMAL, style.size)
    }

    @Test fun largerTextIsLarger() {
        assertEquals(true, TextSize.SMALL.scale < TextSize.NORMAL.scale && TextSize.NORMAL.scale < TextSize.LARGE.scale)
    }
}
