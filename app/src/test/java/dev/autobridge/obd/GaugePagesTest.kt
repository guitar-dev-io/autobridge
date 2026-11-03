package dev.autobridge.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugePagesTest {
    @Test fun savedPagesComeBackAsTheyWent() {
        val pages = listOf(
            GaugePage(PageLayout.DIALS3, listOf(Gauge.RPM, Gauge.SPEED, Gauge.BOOST)),
            GaugePage(PageLayout.TILES4, listOf(Gauge.COOLANT, Gauge.ATF, Gauge.BATTERY, Gauge.LOAD)),
        )
        assertEquals(pages, GaugePages.decode(GaugePages.encode(pages)))
    }

    @Test fun theDefaultPagesRoundTripAndMatchTheirLayouts() {
        assertEquals(GaugePages.DEFAULT, GaugePages.decode(GaugePages.encode(GaugePages.DEFAULT)))
        for (page in GaugePages.DEFAULT) assertEquals(page.layout.size, page.gauges.size)
    }

    @Test fun nothingOrGarbageFallsBackToTheDefault() {
        assertEquals(GaugePages.DEFAULT, GaugePages.decode(null))
        assertEquals(GaugePages.DEFAULT, GaugePages.decode(""))
        assertEquals(GaugePages.DEFAULT, GaugePages.decode("hello;world:1,2"))
    }

    @Test fun aPageWithTheWrongNumberOfGaugesIsRepaired() {
        val short = GaugePages.decode("TILES4:RPM,SPEED").single()
        assertEquals(4, short.gauges.size)
        assertEquals(listOf(Gauge.RPM, Gauge.SPEED), short.gauges.take(2))
        assertEquals(4, short.gauges.toSet().size)

        val long = GaugePages.decode("DIAL1:RPM,SPEED,COOLANT").single()
        assertEquals(listOf(Gauge.RPM), long.gauges)
    }

    @Test fun anIdFromAnotherProfileIsKeptAndOldNamesAreMapped() {
        // Page text saved by older versions used the enum names; "FUEL_LEVEL" became the id "fuel".
        val page = GaugePages.decode("DIALS2:NOPE,SPEED").single()
        assertEquals(listOf(Gauge("nope"), Gauge.SPEED), page.gauges)
        assertEquals(listOf(Gauge.RPM, Gauge.FUEL_LEVEL, Gauge.COOLANT, Gauge.ATF),
            GaugePages.decode("TILES4:RPM,FUEL_LEVEL,coolant,atf").single().gauges)
        assertEquals(listOf(Gauge("bad id")), listOf(Gauge("bad id")))
        // Something that cannot be an id is dropped and the page is repaired.
        assertEquals(4, GaugePages.decode("TILES4:A B,\$").single().gauges.size)
    }

    @Test fun aBadPageDoesNotTakeTheGoodOnesWithIt() {
        val pages = GaugePages.decode("BOGUS:RPM;DIAL1:BOOST")
        assertEquals(listOf(GaugePage(PageLayout.DIAL1, listOf(Gauge.BOOST))), pages)
    }

    @Test fun thereAreNeverMoreThanTheMaximumPages() {
        val text = List(GaugePages.MAX_PAGES + 4) { "DIAL1:RPM" }.joinToString(";")
        assertEquals(GaugePages.MAX_PAGES, GaugePages.decode(text).size)
    }

    @Test fun changingTheLayoutKeepsWhatStillFits() {
        val start = GaugePage(PageLayout.TILES6, listOf(Gauge.RPM, Gauge.SPEED, Gauge.COOLANT, Gauge.ATF, Gauge.BATTERY, Gauge.LOAD))
        val smaller = GaugePages.withLayout(start, PageLayout.TILES4)
        assertEquals(start.gauges.take(4), smaller.gauges)
        val bigger = GaugePages.withLayout(smaller, PageLayout.TILES8)
        assertEquals(8, bigger.gauges.size)
        assertEquals(smaller.gauges, bigger.gauges.take(4))
        assertTrue(bigger.gauges.toSet().size == 8)
    }

    @Test fun aSlotTakesTheChosenGauge() {
        val page = GaugePages.withGauge(GaugePages.DEFAULT[0], 1, Gauge.BOOST)
        assertEquals(Gauge.BOOST, page.gauges[1])
        assertEquals(GaugePages.DEFAULT[0].gauges[0], page.gauges[0])
    }
}
