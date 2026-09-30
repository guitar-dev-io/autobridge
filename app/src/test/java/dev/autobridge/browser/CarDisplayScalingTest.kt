package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car display decides the density a browser *window* gets, and head units disagree wildly about
 * what dpi to report for panels of similar physical size. These pin the resulting CSS width to the
 * band the car surface already enforces, so both routes onto the head unit lay a page out the same
 * way and a site's responsive breakpoints cannot resolve differently between them.
 */
class CarDisplayScalingTest {

    private fun contentWidthDp(widthPx: Int, densityDpi: Int) = widthPx * 160 / densityDpi

    @Test
    fun aPanelAlreadyInsideTheBandKeepsItsOwnDensity() {
        // The panel from the reported session: 780px at 171dpi is 729dp, comfortably in band.
        assertEquals(171, CarDisplayScaling.densityDpiFor(widthPx = 780, densityDpi = 171))
    }

    @Test
    fun aDensePanelIsThinnedUntilThePageIsWideEnoughToReadAtArmsLength() {
        // 800px at 213dpi sees only 601dp — a cramped phone layout on a car screen.
        assertTrue(contentWidthDp(800, 213) < BrowserViewport.MIN_CONTENT_WIDTH_DP)
        val corrected = CarDisplayScaling.densityDpiFor(widthPx = 800, densityDpi = 213)
        assertTrue(corrected < 213)
        // Flooring the density can only widen the page, so the minimum is a floor and never a
        // figure the result lands just under.
        assertTrue(contentWidthDp(800, corrected) >= BrowserViewport.MIN_CONTENT_WIDTH_DP)
        assertTrue(contentWidthDp(800, corrected) <= BrowserViewport.MAX_CONTENT_WIDTH_DP)
    }

    @Test
    fun aWidePanelIsCappedSoTextDoesNotShrinkToDesktopSize() {
        // 1920px at 160dpi sees 1920dp; capped to 1280dp the text stays car-sized.
        val corrected = CarDisplayScaling.densityDpiFor(widthPx = 1920, densityDpi = 160)
        assertEquals(240, corrected)
        assertEquals(BrowserViewport.MAX_CONTENT_WIDTH_DP, contentWidthDp(1920, corrected))
    }

    @Test
    fun densityNeverDropsBelowTheLegibilityFloor() {
        // A narrow panel cannot be widened into the band without an absurd density; it stops at 120.
        assertTrue(CarDisplayScaling.densityDpiFor(widthPx = 320, densityDpi = 320) >= 120)
    }

    @Test
    fun nonsenseMetricsAreLeftAlone() {
        assertEquals(171, CarDisplayScaling.densityDpiFor(widthPx = 0, densityDpi = 171))
        assertEquals(0, CarDisplayScaling.densityDpiFor(widthPx = 780, densityDpi = 0))
    }
}
