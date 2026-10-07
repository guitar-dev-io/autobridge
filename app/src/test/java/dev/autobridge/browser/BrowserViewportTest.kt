package dev.autobridge.browser

import org.junit.Assert.*
import org.junit.Test

/** Head unit resolutions the browser chrome must stay usable at. */
private val HEAD_UNITS = listOf(
    Triple(800, 480, 160),
    Triple(1024, 600, 160),
    Triple(1280, 720, 160),
    Triple(1280, 720, 240),
    Triple(1920, 720, 160), // widescreen
    // The short stable area a DHU session actually reports: the host keeps a band top and bottom,
    // leaving far less height than the surface suggests. Recorded in CarWebRenderer.setStableArea
    // as "the real 752x300 at 24,88" — the case the fixed-height tile grid used to overflow.
    Triple(800, 320, 160),
)

class BrowserViewportTest {
    /**
     * The WebView is exactly the size of the surface, so nothing is rasterised that is not shown.
     * The CSS width the page sees is carried by [BrowserViewport.pageScalePercent] instead — the
     * page is zoomed, not enlarged.
     */
    @Test fun thePageRastersOnePixelPerSurfacePixel() {
        // 1000px at density 1 sits inside the 600..1280 content-width band, so the page lays out at
        // its own pixel width with no zoom — a clean 1:1 raster to check against.
        val viewport = BrowserViewport.create(1000, 400, 1f)
        assertEquals(1000, viewport.webWidth)
        assertEquals(400, viewport.webHeight)
        assertEquals(1f, viewport.scale, 0.001f)
        // 1000 CSS px into a 1000px view is 1:1 zoom; the page still lays out at 1000 CSS px.
        assertEquals(1000, viewport.contentWidthDp)
        assertEquals(100, viewport.pageScalePercent)
    }

    /**
     * Regression for `pageScale=3.000` on an 800x400 head unit: the page was hosted at the phone's
     * 480dpi, so Chromium laid it out at 800 / 3 = 267 CSS px and no initial scale could widen it.
     * The host display's density must be the one at which webWidth px == contentWidthDp CSS px.
     */
    @Test fun pageDensityMakesTheViewExactlyContentWidthCssPixelsWide() {
        // Mobile on the DHU's 800px @160: 800 CSS px at 160dpi, i.e. page scale 1.
        val mobile = BrowserViewport.create(800, 400, 1f)
        assertEquals(800, mobile.contentWidthDp)
        assertEquals(160, mobile.pageDensityDpi)
        // Desktop pins 1280 CSS px onto the same 800px: 160 * 800 / 1280 = 100dpi.
        assertEquals(100, BrowserViewport.create(800, 400, 1f, desktop = true).pageDensityDpi)
        // Every head unit: the CSS width that density yields is the content width, within rounding.
        for ((w, h, dpi) in HEAD_UNITS) {
            val viewport = BrowserViewport.create(w, h, dpi / 160f)
            val cssWidth = viewport.webWidth * 160f / viewport.pageDensityDpi
            assertEquals("${w}x$h@$dpi", viewport.contentWidthDp.toFloat(), cssWidth, viewport.contentWidthDp * 0.01f)
        }
    }

    /** Desktop mode buys its wide layout with zoom, not with a bigger raster. */
    @Test fun desktopWidthIsReachedByZoomingOutNotByEnlargingTheView() {
        val viewport = BrowserViewport.create(800, 400, 1f, desktop = true)
        assertEquals(BrowserViewport.DESKTOP_CONTENT_WIDTH_DP, viewport.contentWidthDp)
        assertEquals(800, viewport.webWidth)
        assertEquals(400, viewport.webHeight)
        // 800 view px / 1280 CSS px = 62.5% -> 63% once rounded to whole percent.
        assertEquals(63, viewport.pageScalePercent)
    }

    /**
     * Regression for the failure this replaced: an 800x400 panel in desktop mode used to be laid
     * out 3840x1920 (the CSS width times the *phone's* density), 23 times the pixels the surface
     * shows, and Chromium refused to raster it - "tile memory limits exceeded, some content may
     * not draw" - leaving the page half-painted.
     */
    @Test fun noPanelRastersMorePixelsThanItDisplays() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            for (desktop in listOf(false, true)) {
                val viewport = BrowserViewport.create(width, height, dpi / 160f, desktop = desktop)
                assertEquals(
                    "${width}x$height@$dpi desktop=$desktop",
                    width.toLong() * height,
                    viewport.webWidth.toLong() * viewport.webHeight
                )
            }
        }
    }

    @Test fun insetPageAndTouchCoordinatesShareTheSameOriginAndScale() {
        val viewport = BrowserViewport.create(1000, 600, 1f, 24, 80, 940, 580)
        assertFalse(viewport.contains(23f, 100f))
        assertFalse(viewport.contains(940f, 100f))
        val webX = 500f
        val webY = 200f
        val surfaceX = viewport.left + webX * viewport.scale
        val surfaceY = viewport.top + webY * viewport.scale
        assertTrue(viewport.contains(surfaceX, surfaceY))
        assertEquals(webX, viewport.toWebX(surfaceX), 0.01f)
        assertEquals(webY, viewport.toWebY(surfaceY), 0.01f)
    }

    @Test fun staleBoundsAfterResizeFallBackToTheSurface() {
        val viewport = BrowserViewport.create(800, 400, 1f, 20, 80, 1000, 600)
        assertEquals(0, viewport.left)
        assertEquals(800, viewport.width)
        assertEquals(400, viewport.height)
    }

    /**
     * The core stability invariant: chrome is an overlay, so the page's measured size cannot depend
     * on it. This replaces an earlier test that asserted the opposite — that fullscreen *grew* the
     * page — which is exactly the reflow that made the page jump on every toggle.
     */
    @Test fun pageSizeIsIndependentOfChromeState() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val surfaceDensity = dpi / 160f
            val viewport = BrowserViewport.create(width, height, surfaceDensity)
            // Whatever the chrome does, the same surface geometry yields the same page geometry.
            val again = BrowserViewport.create(width, height, surfaceDensity)
            assertEquals("$width x $height @$dpi", viewport, again)
            assertEquals(height.toFloat(), viewport.webHeight * viewport.scale, 1.5f)
        }
    }

    @Test fun contentWidthFollowsPanelDensityNotPixelCount() {
        // Same pixel width, different physical density: the denser panel asks for fewer CSS pixels
        // so text stays legible instead of shrinking with the panel's dpi.
        val lowDpi = BrowserViewport.create(1280, 720, 1f)
        val highDpi = BrowserViewport.create(1280, 720, 1.25f)
        assertEquals(1280, lowDpi.contentWidthDp)
        // 1280 / 1.25 = 1024dp, still inside the 600..1280 band.
        assertEquals(1024, highDpi.contentWidthDp)
        assertTrue(highDpi.contentWidthDp < lowDpi.contentWidthDp)
    }

    @Test fun contentWidthIsClampedToAReadableRange() {
        val tiny = BrowserViewport.create(480, 320, 2f)
        assertEquals(BrowserViewport.MIN_CONTENT_WIDTH_DP, tiny.contentWidthDp)
        val huge = BrowserViewport.create(3840, 1080, 1f)
        assertEquals(BrowserViewport.MAX_CONTENT_WIDTH_DP, huge.contentWidthDp)
    }

    @Test fun invalidDensitiesFallBackInsteadOfProducingNaNGeometry() {
        for (density in listOf(0f, Float.NaN, -1f, Float.POSITIVE_INFINITY)) {
            val viewport = BrowserViewport.create(1024, 600, density)
            assertTrue("density=$density", viewport.scale.isFinite() && viewport.scale > 0f)
            assertTrue("density=$density", viewport.webWidth > 0 && viewport.webHeight > 0)
            assertTrue("density=$density", viewport.pageScalePercent > 0)
        }
    }
}

class AutoUiSizesTest {
    @Test fun iconsStayInTheInfotainmentRangeAtEveryHeadUnit() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val sizes = AutoUiSizes.forCarSurface(dpi)
            val iconDp = sizes.iconMedium / sizes.density
            assertEquals("icon dp at ${width}x$height@$dpi", AutoUiSizes.ICON_MEDIUM_DP, iconDp, 0.01f)
            // The symptom this replaces: a wider panel producing a larger glyph.
            val narrow = AutoUiSizes.forCarSurface(dpi)
            assertEquals(sizes.iconMedium, narrow.iconMedium, 0.01f)
        }
    }

    @Test fun iconVisualSizeIsSmallerThanItsTouchTarget() {
        val sizes = AutoUiSizes.forCarSurface(160)
        assertTrue(sizes.iconMedium < sizes.touchTarget)
        assertTrue(sizes.touchTarget / sizes.density >= 44f)
    }

    @Test fun toolbarNeverEatsTheScreenAndNeverCollapses() {
        HEAD_UNITS.forEach { (_, height, dpi) ->
            val sizes = AutoUiSizes.forCarSurface(dpi)
            val toolbar = sizes.toolbarHeight(height)
            assertTrue("toolbar too tall at $height@$dpi", toolbar <= height * AutoUiSizes.MAX_TOOLBAR_HEIGHT_FRACTION + 0.01f ||
                toolbar == sizes.dp(AutoUiSizes.MIN_TOOLBAR_HEIGHT_DP))
            assertTrue("toolbar too short", toolbar >= sizes.dp(AutoUiSizes.MIN_TOOLBAR_HEIGHT_DP) - 0.01f)
        }
    }

    @Test fun densityIsClampedSoAnImplausibleDpiCannotBlowUpTheChrome() {
        assertEquals(AutoUiSizes.MAX_DENSITY, AutoUiSizes.forCarSurface(2400).density, 0.001f)
        assertEquals(AutoUiSizes.MIN_DENSITY, AutoUiSizes.forCarSurface(40).density, 0.001f)
        assertEquals(1f, AutoUiSizes.forCarSurface(0).density, 0.001f)
    }

    @Test fun fontScaleHasAnUpperBound() {
        assertEquals(1f, AutoUiSizes.clampFontScale(0.5f), 0.001f)
        assertEquals(1.1f, AutoUiSizes.clampFontScale(1.1f), 0.001f)
        assertEquals(AutoUiSizes.MAX_FONT_SCALE, AutoUiSizes.clampFontScale(2.0f), 0.001f)
    }

    @Test fun drawerNeverCoversMostOfASmallPanel() {
        HEAD_UNITS.forEach { (width, _, dpi) ->
            val sizes = AutoUiSizes.forCarSurface(dpi)
            val drawer = sizes.drawerWidth(width)
            assertTrue("drawer too wide at $width", drawer <= width * 0.55f + 0.01f)
            assertTrue("drawer too narrow at $width", drawer >= width * 0.30f - 0.01f)
        }
    }
}

class BrowserChromeLayoutTest {
    private fun layoutFor(width: Int, height: Int, dpi: Int): BrowserChromeLayout {
        val sizes = AutoUiSizes.forCarSurface(dpi)
        val viewport = BrowserViewport.create(width, height, sizes.density)
        return BrowserChromeLayout.create(sizes, viewport)
    }

    /**
     * Regression: the page used to be shrunk to the host's stable area - the intersection of every
     * state the host's own chrome can be in, and so always its smallest. The panel was permanently
     * laid out for the worst case and the difference was drawn black. The page now takes the whole
     * surface while the controls stay where the host promises not to cover them, so a button can
     * never look pressable while the tap lands on host chrome instead.
     */
    @Test fun thePageTakesTheWholeSurfaceWhileControlsStayInsideTheStableArea() {
        // An observed head unit: 800x400 of surface, of which only 752x300 at (24,88) is stable.
        val sizes = AutoUiSizes.forCarSurface(160)
        val viewport = BrowserViewport.create(800, 400, sizes.density)
        val stable = Box(24f, 88f, 776f, 388f)
        val layout = BrowserChromeLayout.create(sizes, viewport, showMenuButton = true, chromeBounds = stable)

        assertEquals(0, viewport.left)
        assertEquals(0, viewport.top)
        assertEquals(800, viewport.width)
        assertEquals(400, viewport.height)

        assertEquals(stable.left, layout.toolbar.left, 0.01f)
        assertEquals(stable.top, layout.toolbar.top, 0.01f)
        assertEquals(stable.right, layout.toolbar.right, 0.01f)
        layout.slots.forEach { slot ->
            assertTrue(
                "${slot.zone} escapes the stable area",
                slot.bounds.left >= stable.left - 0.01f && slot.bounds.right <= stable.right + 0.01f &&
                    slot.bounds.top >= stable.top - 0.01f && slot.bounds.bottom <= stable.bottom + 0.01f
            )
        }
        assertTrue("fab escapes the stable area", layout.fab.bottom <= stable.bottom + 0.01f)
    }

    @Test fun aHostThatReportsNoStableAreaGetsChromeOnTheWholePage() {
        val sizes = AutoUiSizes.forCarSurface(160)
        val viewport = BrowserViewport.create(800, 400, sizes.density)
        val unbounded = BrowserChromeLayout.create(sizes, viewport, showMenuButton = true)
        assertEquals(0f, unbounded.toolbar.left, 0.01f)
        assertEquals(0f, unbounded.toolbar.top, 0.01f)
        assertEquals(800f, unbounded.toolbar.right, 0.01f)
    }

    @Test fun everyToolbarButtonIsHitTestableWhereItIsDrawn() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val layout = layoutFor(width, height, dpi)
            layout.slots.forEach { slot ->
                val zone = layout.hitTest(slot.bounds.centerX, slot.bounds.centerY, chromeVisible = true, drawer = null)
                assertEquals("${slot.zone} at ${width}x$height@$dpi", slot.zone, zone)
            }
        }
    }

    @Test fun buttonsDoNotOverlapAndStayInsideTheBar() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val layout = layoutFor(width, height, dpi)
            val sorted = layout.slots.sortedBy { it.bounds.left }
            sorted.zipWithNext().forEach { (a, b) ->
                assertTrue("overlap at ${width}x$height", a.bounds.right <= b.bounds.left + 0.01f)
            }
            layout.slots.forEach {
                assertTrue(it.bounds.left >= layout.toolbar.left - 0.01f)
                assertTrue(it.bounds.right <= layout.toolbar.right + 0.01f)
            }
        }
    }

    @Test fun addressPillSitsBetweenTheLeadingAndTrailingButtons() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val layout = layoutFor(width, height, dpi)
            val reload = layout.slot(ChromeZone.RELOAD)!!
            // Fullscreen moved into the drawer (see BrowserDrawerModel.fullscreenToggle); Menu is
            // now the only trailing toolbar slot bounding the address pill's right edge.
            val menu = layout.slot(ChromeZone.MENU)!!
            assertTrue(layout.address.left >= reload.bounds.right)
            assertTrue(layout.address.right <= menu.bounds.left + 0.01f)
        }
    }

    @Test fun emptyToolbarSpaceIsConsumedRatherThanFallingThroughToThePage() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val layout = layoutFor(width, height, dpi)
            // The sliver between the last leading button and the address pill is toolbar, not page.
            val reload = layout.slot(ChromeZone.RELOAD)!!
            val gapX = (reload.bounds.right + layout.address.left) / 2f
            val zone = layout.hitTest(gapX, layout.toolbar.centerY, chromeVisible = true, drawer = null)
            assertNotEquals("tap fell through at ${width}x$height@$dpi", ChromeZone.NONE, zone)
            // Just below the toolbar is the page again.
            assertEquals(
                ChromeZone.NONE,
                layout.hitTest(gapX, layout.toolbar.bottom + 1f, chromeVisible = true, drawer = null)
            )
        }
    }

    /**
     * The floating button exists so the menu is never two taps away. It must therefore answer in
     * both chrome states — the hidden state is the one that used to cost an extra tap on a 28dp
     * reveal band before the 46dp toolbar icon could even be aimed at.
     */
    @Test fun floatingButtonOpensTheMenuInEitherChromeState() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val layout = layoutFor(width, height, dpi)
            val x = layout.fab.centerX
            val y = layout.fab.centerY
            assertEquals(ChromeZone.FAB, layout.hitTest(x, y, chromeVisible = false, drawer = null))
            assertEquals(ChromeZone.FAB, layout.hitTest(x, y, chromeVisible = true, drawer = null))
        }
    }

    @Test fun floatingButtonStaysInsideTheViewportAndClearOfTheToolbar() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val sizes = AutoUiSizes.forCarSurface(dpi)
            val viewport = BrowserViewport.create(width, height, sizes.density)
            val layout = layoutFor(width, height, dpi)
            assertTrue(layout.fab.left >= viewport.left.toFloat())
            assertTrue(layout.fab.right <= viewport.left + viewport.width.toFloat())
            assertTrue(layout.fab.bottom <= viewport.top + viewport.height.toFloat())
            // Never overlaps the toolbar it duplicates, so neither can steal the other's taps.
            assertTrue(layout.fab.top >= layout.toolbar.bottom)
            assertTrue(layout.fab.width >= sizes.touchTarget * 0.9f)
        }
    }

    /** The left-side setting mirrors the button into the bottom-left corner, same size and height. */
    @Test fun theFloatingButtonCanSitOnTheLeft() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val sizes = AutoUiSizes.forCarSurface(dpi)
            val viewport = BrowserViewport.create(width, height, sizes.density)
            val right = BrowserChromeLayout.create(sizes, viewport)
            val left = BrowserChromeLayout.create(sizes, viewport, fabOnLeft = true)
            assertTrue(left.fab.centerX < viewport.left + viewport.width / 2f)
            assertTrue(right.fab.centerX > viewport.left + viewport.width / 2f)
            assertTrue(left.fab.left >= viewport.left.toFloat())
            assertEquals(right.fab.width, left.fab.width, 0.01f)
            assertEquals(right.fab.bottom, left.fab.bottom, 0.01f)
            assertEquals(
                ChromeZone.FAB,
                left.hitTest(left.fab.centerX, left.fab.centerY, chromeVisible = false, drawer = null)
            )
        }
    }

    /**
     * A faded-out floating button must stop taking taps. Drawing and hit testing read the same
     * flag, so an invisible button cannot sit there swallowing presses meant for the page.
     */
    @Test fun aHiddenFloatingButtonDoesNotTakeTapsFromThePage() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val layout = BrowserChromeLayout.create(
                AutoUiSizes.forCarSurface(dpi),
                BrowserViewport.create(width, height, AutoUiSizes.forCarSurface(dpi).density)
            )
            val x = layout.fab.centerX
            val y = layout.fab.centerY
            assertEquals(
                ChromeZone.FAB,
                layout.hitTest(x, y, chromeVisible = false, drawer = null, fabVisible = true)
            )
            assertEquals(
                ChromeZone.NONE,
                layout.hitTest(x, y, chromeVisible = false, drawer = null, fabVisible = false)
            )
        }
    }

    /** An open drawer owns every tap, including the one the floating button would otherwise take. */
    @Test fun anOpenDrawerTakesPrecedenceOverTheFloatingButton() {
        val layout = layoutFor(1024, 600, 160)
        val whole = Box(0f, 0f, 1f, 1f)
        assertEquals(
            ChromeZone.DRAWER_SCRIM,
            layout.hitTest(layout.fab.centerX, layout.fab.centerY, chromeVisible = false, drawer = whole)
        )
    }

    @Test fun hiddenChromeLeavesPageTapsAloneOutsideTheEdgeBand() {
        val layout = layoutFor(1024, 600, 160)
        // A tap in the middle of the page must reach the page, not a hidden control.
        assertEquals(ChromeZone.NONE, layout.hitTest(512f, 400f, chromeVisible = false, drawer = null))
        // The reveal band is narrow and only at the very top.
        assertEquals(ChromeZone.EDGE_REVEAL, layout.hitTest(900f, 4f, chromeVisible = false, drawer = null))
        assertEquals(ChromeZone.HANDLE, layout.hitTest(512f, 4f, chromeVisible = false, drawer = null))
        assertTrue(layout.edgeReveal.height <= layout.toolbar.height)
    }

    @Test fun anOpenDrawerOwnsEveryTapAndTheScrimDismisses() {
        val layout = layoutFor(1024, 600, 160)
        val drawer = Box(0f, 0f, 300f, 600f)
        assertEquals(ChromeZone.NONE, layout.hitTest(100f, 300f, chromeVisible = true, drawer = drawer))
        assertEquals(ChromeZone.DRAWER_SCRIM, layout.hitTest(800f, 300f, chromeVisible = true, drawer = drawer))
    }

    @Test fun layoutHonoursAnInsetStableArea() {
        val sizes = AutoUiSizes.forCarSurface(160)
        val viewport = BrowserViewport.create(1024, 600, sizes.density, 40, 20, 984, 580)
        val layout = BrowserChromeLayout.create(sizes, viewport)
        assertEquals(40f, layout.toolbar.left, 0.01f)
        assertEquals(20f, layout.toolbar.top, 0.01f)
        assertEquals(984f, layout.toolbar.right, 0.01f)
        // A tap outside the stable area belongs to the host, not to us.
        assertEquals(ChromeZone.NONE, layout.hitTest(10f, 30f, chromeVisible = true, drawer = null))
    }
}

class ChromeVisibilityTest {
    @Test fun chromeAutoHidesAfterIdleAndComesBackOnInteraction() {
        val visibility = ChromeVisibility(autoHideAfterMs = 1_000L, fadeDurationMs = 0L)
        visibility.onInteraction(0L)
        assertTrue(visibility.isShown)
        assertFalse(visibility.tick(500L))
        assertTrue(visibility.tick(1_500L))
        assertFalse(visibility.isShown)
        visibility.show(1_600L)
        assertTrue(visibility.isShown)
    }

    /**
     * Regression from a head unit trace: the toolbar disappeared ~160ms after the browser opened.
     * The idle clock started at 0 while the caller passes SystemClock.uptimeMillis(), so the first
     * tick measured an idle period of the entire device uptime.
     */
    @Test fun theIdleClockStartsWhenTickingStartsNotAtZero() {
        val visibility = ChromeVisibility(autoHideAfterMs = 1_000L, fadeDurationMs = 0L)
        val uptime = 662_717_394L
        assertFalse("hid immediately on the first tick", visibility.tick(uptime))
        assertTrue(visibility.isShown)
        assertFalse(visibility.tick(uptime + 500L))
        assertTrue(visibility.tick(uptime + 1_100L))
        assertFalse(visibility.isShown)
    }

    /**
     * "Always show URL bar" is a standing preference, not a gesture, so no amount of idle time may
     * take the toolbar away while it is on.
     */
    @Test fun pinningTheToolbarSurvivesAnyAmountOfIdleTime() {
        // The clock starts at a non-zero value: tick() treats 0 as "never interacted" and seeds
        // itself, so an interaction at 0L would cost this test a tick before idle timing begins.
        val visibility = ChromeVisibility(autoHideAfterMs = 1_000L, fadeDurationMs = 0L)
        visibility.onInteraction(1_000L)
        visibility.setAutoHide(1_000L, false)
        assertFalse(visibility.tick(60_000L))
        assertTrue(visibility.isShown)
        // And turning it back on hands idle timing back.
        visibility.setAutoHide(60_000L, true)
        assertTrue(visibility.tick(61_500L))
        assertFalse(visibility.isShown)
    }

    /** Turning the preference on while the toolbar has already faded must bring it back. */
    @Test fun pinningTheToolbarRevealsItImmediately() {
        val visibility = ChromeVisibility(autoHideAfterMs = 1_000L, fadeDurationMs = 0L)
        visibility.onInteraction(1_000L)
        assertTrue(visibility.tick(2_500L))
        assertFalse(visibility.isShown)
        visibility.setAutoHide(2_600L, false)
        assertTrue(visibility.isShown)
    }

    @Test fun anOpenDrawerSuspendsAutoHide() {
        val visibility = ChromeVisibility(autoHideAfterMs = 1_000L, fadeDurationMs = 0L)
        visibility.onInteraction(0L)
        visibility.setDrawerOpen(0L, true)
        assertFalse(visibility.tick(5_000L))
        assertTrue(visibility.isShown)
        visibility.setDrawerOpen(5_000L, false)
        assertTrue(visibility.tick(6_500L))
    }

    @Test fun fullscreenPinsChromeHiddenUntilExplicitlyRecalled() {
        val visibility = ChromeVisibility(autoHideAfterMs = 1_000L, fadeDurationMs = 0L)
        visibility.setFullscreen(0L, true)
        assertFalse(visibility.isShown)
        // Ordinary page interaction must not drag the toolbar back in fullscreen.
        visibility.onInteraction(100L)
        assertFalse(visibility.isShown)
        // The handle / edge band still recalls it, without leaving fullscreen.
        visibility.show(200L)
        assertTrue(visibility.isShown)
        assertTrue(visibility.fullscreen)
    }

    /**
     * Regression from a head unit trace: after the handle recalled the toolbar while fullscreen was
     * pinned, the log showed `chrome=shown fullscreen=true` and never returned to hidden, because
     * tick() treated fullscreen as suspending auto-hide entirely.
     */
    @Test fun chromeRecalledDuringFullscreenStillAutoHides() {
        val visibility = ChromeVisibility(autoHideAfterMs = 1_000L, fadeDurationMs = 0L)
        visibility.setFullscreen(0L, true)
        assertFalse(visibility.isShown)
        visibility.show(1_000L)
        assertTrue(visibility.isShown)
        assertFalse(visibility.tick(1_500L))
        assertTrue("chrome stayed pinned in fullscreen", visibility.tick(2_100L))
        assertFalse(visibility.isShown)
        assertTrue("fullscreen must survive the auto-hide", visibility.fullscreen)
    }

    /**
     * Regression from a head unit session: tapping the edge to recall the toolbar and then tapping a
     * button immediately did nothing, because the second tap landed while the fade was still in
     * flight and the hit test keyed off opacity rather than the requested state.
     */
    @Test fun chromeIsConsideredVisibleTheInstantItIsRequested() {
        val visibility = ChromeVisibility(autoHideAfterMs = 1_000L, fadeDurationMs = 180L)
        visibility.onInteraction(1_000L)
        visibility.hide(2_000L)
        assertFalse(visibility.isShown)
        visibility.show(3_000L)
        assertTrue("buttons must be hittable during the fade-in", visibility.isShown)
        assertTrue(visibility.alphaAt(3_000L) < 0.01f)
    }

    @Test fun transitionsAnimateOpacityRatherThanSnapping() {
        val visibility = ChromeVisibility(autoHideAfterMs = 1_000L, fadeDurationMs = 200L)
        visibility.onInteraction(0L)
        visibility.hide(1_000L)
        assertEquals(1f, visibility.alphaAt(1_000L), 0.01f)
        assertEquals(0.5f, visibility.alphaAt(1_100L), 0.02f)
        assertEquals(0f, visibility.alphaAt(1_200L), 0.01f)
        assertFalse(visibility.isAnimating(1_300L))
    }

    @Test fun anInterruptedFadeResumesFromItsCurrentOpacity() {
        val visibility = ChromeVisibility(autoHideAfterMs = 1_000L, fadeDurationMs = 200L)
        visibility.onInteraction(0L)
        visibility.hide(1_000L)
        visibility.show(1_100L) // reversed halfway out
        assertEquals(0.5f, visibility.alphaAt(1_100L), 0.02f)
        assertEquals(1f, visibility.alphaAt(1_300L), 0.01f)
    }
}

class BrowserTabsStateTest {
    @Test fun openingBeyondTheCapEvictsTheOldestAndReportsIt() {
        var state = BrowserTabsState.single("https://a.example", id = 1L)
        var lastEvicted: BrowserTab? = null
        for (id in 2..BrowserTabsState.MAX_TABS.toLong() + 1) {
            val (next, evicted) = state.open(id, "https://tab$id.example")
            state = next
            if (evicted != null) lastEvicted = evicted
        }
        assertEquals(BrowserTabsState.MAX_TABS, state.count)
        assertEquals(1L, lastEvicted?.id)
        assertFalse(state.tabs.any { it.id == 1L })
    }

    @Test fun closingTheActiveTabActivatesANeighbour() {
        var state = BrowserTabsState.single("https://a.example", id = 1L)
        state = state.open(2L, "https://b.example").first
        state = state.open(3L, "https://c.example").first
        state = state.activate(2L)
        state = state.close(2L)
        assertEquals(2, state.count)
        assertTrue(state.activeId == 1L || state.activeId == 3L)
        assertNotNull(state.active)
    }

    @Test fun closingAnInactiveTabKeepsTheActiveOne() {
        var state = BrowserTabsState.single("https://a.example", id = 1L)
        state = state.open(2L, "https://b.example").first
        assertEquals(2L, state.activeId)
        state = state.close(1L)
        assertEquals(2L, state.activeId)
    }

    @Test fun closingTheLastTabLeavesAnEmptyState() {
        val state = BrowserTabsState.single("https://a.example", id = 1L).close(1L)
        assertEquals(0, state.count)
        assertNull(state.active)
    }

    @Test fun navigationUpdatesTheActiveTabAndKeepsABlankTitle() {
        var state = BrowserTabsState.single("https://a.example", "A", id = 1L)
        state = state.updateActive("https://a.example/page", null)
        assertEquals("A", state.active?.title)
        assertEquals("https://a.example/page", state.active?.url)
        state = state.updateActive("https://a.example/page", "Page")
        assertEquals("Page", state.active?.title)
    }
}

class BrowserDrawerModelTest {
    private val state = BrowserMenuState(
        appName = "AutoBridge",
        pageTitle = "YouTube",
        url = "https://www.youtube.com/",
        tabCount = 2,
        isDesktop = false,
        canGoBack = true,
        canGoForward = false,
        version = "v0.4.4",
    )

    private fun modelFor(
        width: Int,
        height: Int,
        dpi: Int,
        scroll: Float = 0f,
        more: Boolean = false,
        state: BrowserMenuState = this.state,
    ): BrowserDrawerModel {
        val sizes = AutoUiSizes.forCarSurface(dpi)
        val viewport = BrowserViewport.create(width, height, sizes.density)
        return BrowserDrawerModel.create(sizes, viewport, state, more, scroll)
    }

    /**
     * Head units tall enough for the sheet's authored shape. The 800x320 stable area a DHU session
     * reports is deliberately excluded: the reference layout is a header, an address row, two cards,
     * a switch and a footer, and at 320px there is no arrangement of those that leaves a tile at a
     * touchable size. That panel scrolls, and [theSheetScrollsOnlyWhenItsOwnMinimumsDoNotFit]
     * covers it.
     */
    private val tallEnough = HEAD_UNITS.filter { (_, height, dpi) -> height / (dpi / 160f) >= 440f }

    /**
     * The property the band-sizing rewrite exists for. Entries below the fold are the failure mode
     * this menu keeps hitting: a tap that drifts a few px during a scroll is read as a scroll and
     * the entry never fires, so on every panel with room for the sheet, nothing may need scrolling
     * to reach.
     */
    @Test fun bothSheetsFitWithoutScrollingOnEveryHeadUnitWithRoomForThem() {
        tallEnough.forEach { (width, height, dpi) ->
            listOf(false, true).forEach { more ->
                val model = modelFor(width, height, dpi, more = more)
                val name = if (more) "more" else "primary"
                assertEquals(
                    "$name sheet scrolls at ${width}x$height @$dpi",
                    0f, model.maxScroll, 0.01f
                )
                // Header buttons live above the scroll boundary by design.
                (model.rows - model.headerLinks.toSet()).forEach { row ->
                    assertTrue(
                        "${row.item.action} falls outside the sheet at ${width}x$height @$dpi",
                        row.bounds.top >= model.headerBottom - 0.01f &&
                            row.bounds.bottom <= model.panel.bottom + 0.01f
                    )
                }
            }
        }
    }

    /**
     * The short stable area is the one case the authored shape cannot fit, so it degrades the
     * correct way: bands compress to their minimums first, and only what is still over the box
     * becomes scroll. The header and its close button stay put either way, so the sheet is never
     * opened into a state it cannot be closed from.
     */
    @Test fun theSheetScrollsOnlyWhenItsOwnMinimumsDoNotFit() {
        val short = modelFor(800, 320, 160)
        assertTrue("a 320px panel should need to scroll", short.maxScroll > 0f)
        assertTrue(short.closeButton.bottom <= short.headerBottom + 0.01f)
        // Scrolled to the end, the last thing on the sheet is on screen.
        val scrolled = modelFor(800, 320, 160, scroll = short.maxScroll)
        assertTrue(scrolled.contentBottom <= scrolled.panel.bottom + 0.01f)
    }

    /** Every band shrinks by the same fraction of its own slack; nothing is starved to feed another. */
    @Test fun bandsShareTheShortfallInsteadOfTheFirstOneTakingItAll() {
        val bands = listOf(100f to 50f, 40f to 20f)
        assertEquals(listOf(100f, 40f), BrowserDrawerModel.distribute(bands, 200f))
        // 140 wanted, 70 of slack, 35 short -> each gives up half its slack.
        assertEquals(listOf(75f, 30f), BrowserDrawerModel.distribute(bands, 105f))
        // Past every minimum it stops shrinking and the caller scrolls.
        assertEquals(listOf(50f, 20f), BrowserDrawerModel.distribute(bands, 10f))
    }

    /**
     * Regression: the sheet began one margin below the top of the viewport, inside the band the
     * toolbar occupies, and [CarWebRenderer] draws the toolbar after the sheet — so the bar painted
     * over the sheet's header and left the close button 73% hidden behind the fullscreen icon.
     * Taps split too: the few dp of bar above the panel dismissed the sheet while the rest of it
     * landed inside the panel and did nothing at all.
     */
    @Test fun theSheetStartsBelowTheToolbarSoItsHeaderIsNeverPaintedOver() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val sizes = AutoUiSizes.forCarSurface(dpi)
            val viewport = BrowserViewport.create(width, height, sizes.density)
            val toolbar = BrowserChromeLayout.create(sizes, viewport).toolbar
            val model = modelFor(width, height, dpi)
            assertTrue(
                "sheet overlaps the toolbar at ${width}x$height @$dpi",
                model.panel.top >= toolbar.bottom - 0.01f
            )
            // The close button is the thing the overlap used to hide, so assert it specifically.
            assertTrue(model.closeButton.top >= toolbar.bottom - 0.01f)
        }
    }

    @Test fun theSheetOverlaysRatherThanShrinkingTheViewport() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val sizes = AutoUiSizes.forCarSurface(dpi)
            val viewport = BrowserViewport.create(width, height, sizes.density)
            val model = modelFor(width, height, dpi)
            // The sheet sits inside the same viewport; the page geometry is untouched by it.
            assertTrue(model.panel.left >= viewport.left.toFloat())
            assertTrue(model.panel.top >= viewport.top.toFloat())
            assertTrue(model.panel.right <= viewport.left + viewport.width.toFloat())
            assertTrue(model.panel.bottom <= viewport.top + viewport.height.toFloat())
        }
    }

    /**
     * A widescreen head unit gets the reference proportions, not three tiles stretched across two
     * feet of dashboard, and the sheet stays centred so it is reachable from either side.
     */
    @Test fun theSheetIsBoundedInWidthAndCentredOnAWideHeadUnit() {
        val sizes = AutoUiSizes.forCarSurface(160)
        val model = modelFor(1920, 720, 160)
        assertEquals(sizes.dp(AutoUiSizes.MENU_SHEET_MAX_WIDTH_DP), model.panel.width, 0.5f)
        assertEquals(960f, model.panel.centerX, 0.5f)
        // A panel narrower than the cap keeps the whole width it has, less its margins.
        val dense = AutoUiSizes.forCarSurface(240)
        val narrow = modelFor(800, 480, 240)
        assertEquals(800f - dense.contentGap * 2f, narrow.panel.width, 0.5f)
    }

    /**
     * The point of the tiles: an entry is a real button, not a minimum-height text row. 46dp square
     * is the smallest target a finger hits reliably at rest, which is the wrong size for a control
     * used while the car is moving, so a tile must be comfortably larger than that.
     */
    @Test fun everyTileIsLargerThanTheBareMinimumTouchTarget() {
        tallEnough.forEach { (width, height, dpi) ->
            val sizes = AutoUiSizes.forCarSurface(dpi)
            val minimum = sizes.touchTarget * sizes.touchTarget
            modelFor(width, height, dpi).tiles.forEach { row ->
                assertTrue(
                    "tile ${row.item.action} too small at ${width}x$height",
                    row.bounds.width * row.bounds.height >= minimum * 1.5f
                )
                assertTrue(row.bounds.height >= sizes.touchTarget)
            }
        }
    }

    @Test fun nothingOnTheSheetOverlapsAnythingElse() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            listOf(false, true).forEach { more ->
                val rows = modelFor(width, height, dpi, more = more).rows
                    // The address row deliberately contains its own two buttons; it is hit-tested
                    // after them, which is what makes the buttons win.
                    .filter { it.kind != DrawerKind.ADDRESS }
                rows.forEachIndexed { index, row ->
                    rows.drop(index + 1).forEach { other ->
                        val overlaps = row.bounds.left < other.bounds.right &&
                            other.bounds.left < row.bounds.right &&
                            row.bounds.top < other.bounds.bottom &&
                            other.bounds.top < row.bounds.bottom
                        assertTrue("${row.item.action} overlaps ${other.item.action}", !overlaps)
                    }
                }
            }
        }
    }

    @Test fun columnCountFollowsWidthAndStaysBounded() {
        val narrow = AutoUiSizes.forCarSurface(160)
        assertEquals(AutoUiSizes.MENU_COLUMNS_MIN, narrow.menuColumns(10f))
        assertEquals(AutoUiSizes.MENU_COLUMNS_MAX, narrow.menuColumns(100_000f))
        // A denser panel of the same physical width gets the same number of columns, because the
        // tile width is authored in dp — the "enlarged tablet UI" rule applied to the menu.
        val dense = AutoUiSizes.forCarSurface(320)
        assertEquals(narrow.menuColumns(800f), dense.menuColumns(1600f))
    }

    @Test fun everyEntryIsTappableAtItsOwnCentre() {
        listOf(false, true).forEach { more ->
            val model = modelFor(1024, 600, 160, more = more)
            model.rows.filter { it.item.enabled }.forEach { row ->
                val hit = model.rowAt(row.bounds.centerX, row.bounds.centerY)
                assertEquals(
                    "${row.item.action} is not tappable at its own centre",
                    row.item.action, hit?.item?.action
                )
            }
        }
    }

    /**
     * Back with nothing behind it is drawn so the card keeps its shape, but it must not swallow the
     * tap: a control that lights up and does nothing reads as broken, where a dimmed one reads as
     * unavailable.
     */
    @Test fun aDisabledTileIsDrawnButTakesNoTap() {
        val model = modelFor(1024, 600, 160)
        val forward = model.tiles.first { it.item.action == DrawerAction.NAV_FORWARD }
        assertFalse(forward.item.enabled)
        assertNull(model.rowAt(forward.bounds.centerX, forward.bounds.centerY))
        val back = model.tiles.first { it.item.action == DrawerAction.NAV_BACK }
        assertTrue(back.item.enabled)
        assertEquals(
            DrawerAction.NAV_BACK,
            model.actionAt(back.bounds.centerX, back.bounds.centerY)
        )
    }

    /** The address row's own buttons win over the row they sit inside. */
    @Test fun theAddressButtonsOutrankTheRowTheySitIn() {
        val model = modelFor(1024, 600, 160)
        val address = requireNotNull(model.address)
        assertEquals(DrawerAction.ADDRESS_CLEAR, model.actionAt(address.clear.centerX, address.clear.centerY))
        assertEquals(DrawerAction.ADDRESS_KEYBOARD, model.actionAt(address.go.centerX, address.go.centerY))
        assertEquals(
            DrawerAction.ADDRESS_KEYBOARD,
            model.actionAt(address.bounds.left + 1f, address.bounds.centerY)
        )
        // It reports the page the sheet was opened over, which is the whole reason it is there.
        assertTrue(address.secure)
        assertTrue(address.text.contains("youtube.com"))
    }

    /**
     * Regression: the sheet's only dismissal was a tap outside it, and it covers the viewport apart
     * from a margin a few dp wide. On a head unit that band is not a target anyone can hit, so the
     * sheet could be opened and then not closed without choosing an action. The header carries a
     * real close button instead.
     */
    @Test fun theSheetCanBeClosedWithoutTappingTheMarginAroundIt() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val sizes = AutoUiSizes.forCarSurface(dpi)
            val model = modelFor(width, height, dpi)
            val close = model.closeButton
            assertTrue(
                "close button too small at ${width}x$height",
                minOf(close.width, close.height) >= sizes.touchTarget * 0.75f - 0.01f
            )
            // In the header, inside the panel, and never over the content.
            assertTrue(close.top >= model.panel.top)
            assertTrue(close.bottom <= model.headerBottom)
            assertTrue(close.right <= model.panel.right)
            assertTrue(model.hitsClose(close.centerX, close.centerY))
            assertEquals(DrawerAction.CLOSE_SHEET, model.actionAt(close.centerX, close.centerY))
            assertNull(model.rowAt(close.centerX, close.centerY))
            assertTrue(!model.hitsClose(model.panel.centerX, model.panel.bottom - 1f))
        }
    }

    @Test fun entriesScrolledUnderTheHeaderAreNotTappable() {
        val model = modelFor(800, 320, 160, scroll = 200f)
        assertNull(model.rowAt(model.panel.centerX, model.headerBottom - 1f))
    }

    @Test fun scrollIsClampedToTheContent() {
        val model = modelFor(800, 320, 160, scroll = 99_999f)
        assertEquals(model.maxScroll, model.scrollOffset, 0.01f)
        val negative = modelFor(800, 320, 160, scroll = -50f)
        assertEquals(0f, negative.scrollOffset, 0.01f)
    }

    /** The switches report the store, not a label the user has to decode. */
    @Test fun theDesktopAndFullscreenSwitchesShowTheStateTheyToggle() {
        listOf(true, false).forEach { desktop ->
            val model = modelFor(1024, 600, 160, state = state.copy(isDesktop = desktop))
            val (desktopToggle, fullscreenToggle) = model.toggles
            assertEquals(DrawerKind.TOGGLE, desktopToggle.kind)
            assertEquals(desktop, desktopToggle.item.on)
            assertEquals(
                DrawerAction.TOGGLE_DESKTOP,
                model.actionAt(desktopToggle.bounds.centerX, desktopToggle.bounds.centerY)
            )
            // Stacked directly below the desktop switch, same row shape.
            assertEquals(DrawerKind.TOGGLE, fullscreenToggle.kind)
            assertTrue(fullscreenToggle.bounds.top > desktopToggle.bounds.bottom)
            assertEquals(
                DrawerAction.TOGGLE_FULLSCREEN,
                model.actionAt(fullscreenToggle.bounds.centerX, fullscreenToggle.bounds.centerY)
            )
        }
        // The "More" sheet is a plain grid; the switches belong to the page-level sheet only.
        assertTrue(modelFor(1024, 600, 160, more = true).toggles.isEmpty())
    }

    /**
     * Regression: the paste entry used to be hidden unless a clipboard probe succeeded. On a car
     * surface that probe is always refused (the app is not focused on the phone), so the entry never
     * appeared. It is now unconditional and reports failure when tapped.
     */
    @Test fun pasteIsAlwaysOfferedRegardlessOfClipboardReadability() {
        assertTrue(BrowserDrawerModel.moreItems().map { it.action }.contains(DrawerAction.PASTE_AND_GO))
    }

    /**
     * The car sheet mirrors the phone's [BrowserMenuSheet]: one accent primary button, then
     * Back / Reload / Forward and Bookmarks / Settings / Split screen / More, then the desktop
     * and fullscreen switches. Growing it is how the old menu ended up with a fold: the split
     * screen joined the second row rather than adding a third.
     */
    @Test fun thePrimarySheetMirrorsThePhoneSheet() {
        val model = modelFor(1024, 600, 160)
        assertEquals(DrawerAction.TABS, model.primary?.item?.action)
        assertEquals(DrawerKind.PRIMARY, model.primary?.kind)
        assertEquals("2", model.primary?.item?.value)
        assertEquals(
            listOf(
                DrawerAction.NAV_BACK, DrawerAction.RELOAD, DrawerAction.NAV_FORWARD,
                DrawerAction.BOOKMARKS, DrawerAction.SETTINGS, DrawerAction.SPLIT_LAYOUT, DrawerAction.MORE,
            ),
            model.tiles.map { it.item.action }
        )
        // Two rows of three, in that order: the first three share a top, below the primary button.
        val (nav, secondary) = model.tiles.chunked(BrowserDrawerModel.PRIMARY_COLUMNS)
        assertTrue(nav.all { it.bounds.top == nav.first().bounds.top })
        assertTrue(secondary.first().bounds.top > nav.first().bounds.bottom)
        assertTrue(nav.first().bounds.top > model.primary!!.bounds.bottom)
        assertEquals(2, model.toggles.size)
        assertTrue(model.toggles[0].bounds.top > secondary.first().bounds.bottom)
        assertTrue(model.toggles[1].bounds.top > model.toggles[0].bounds.bottom)
        assertEquals(2, model.dividers.size)
    }

    /**
     * The way out of the browser is one tap from either list and never behind "More", because on
     * the car surface it has no other home (see [DrawerAction.APP_HOME]). The "More" list adds a
     * back button in the header, so it is never a dead end.
     */
    @Test fun theHeaderKeepsTheWayOutOneTapAway() {
        val primary = modelFor(1024, 600, 160)
        assertEquals(listOf(DrawerAction.APP_HOME), primary.headerLinks.map { it.item.action })
        val more = modelFor(1024, 600, 160, more = true)
        assertEquals(
            listOf(DrawerAction.BACK_TO_MENU, DrawerAction.APP_HOME),
            more.headerLinks.map { it.item.action }
        )
        listOf(primary, more).forEach { model ->
            model.headerLinks.forEach { link ->
                assertTrue(link.bounds.bottom <= model.headerBottom + 0.01f)
                assertEquals(link.item.action, model.actionAt(link.bounds.centerX, link.bounds.centerY))
                assertTrue(link.bounds.right <= model.closeButton.left)
            }
        }
    }

    /**
     * Every action belongs to a surface and is reachable there, and no action is reachable twice on
     * the same sheet — which is what makes "where is that?" a question with one answer. The two
     * surfaces between them cover the whole enum, so an action can never be added and then left
     * with nothing that offers it.
     */
    @Test fun everySheetOffersEachOfItsActionsExactlyOnce() {
        val covered = mutableSetOf(DrawerAction.CLOSE_SHEET) // the header's button, not a row
        MenuSurface.entries.forEach { surface ->
            val primary = modelFor(1024, 600, 160, state = state.copy(surface = surface))
            val more = modelFor(1024, 600, 160, more = true, state = state.copy(surface = surface))
            covered += (primary.rows + more.rows).map { it.item.action }
            listOf(primary, more).forEach { model ->
                val actions = model.rows.map { it.item.action }
                    // ADDRESS_KEYBOARD is deliberately both the row and its ⌕ button.
                    .filter { it != DrawerAction.ADDRESS_KEYBOARD }
                assertEquals("$surface offers something twice", actions.size, actions.toSet().size)
            }
            // Nothing the other surface owns leaks into this one: a tile over an action this
            // browser cannot perform is a button that does nothing.
            val offered = (primary.rows + more.rows).map { it.item.action }.toSet()
            val foreign = when (surface) {
                MenuSurface.CAR -> setOf(DrawerAction.SEND_TO_CAR, DrawerAction.RECEIVE_FROM_CAR)
                MenuSurface.PHONE -> setOf(
                    DrawerAction.TABS, DrawerAction.NEW_TAB, DrawerAction.AGENT,
                    DrawerAction.MEDIA_CENTER, DrawerAction.NOW_PLAYING,
                    DrawerAction.MEDIA_LIBRARY, DrawerAction.DIAGNOSTICS,
                    DrawerAction.SIDE_SHOW_PAGE, DrawerAction.NAVIGATE_MAPS,
                )
            }
            assertEquals(emptySet<DrawerAction>(), offered intersect foreign)
        }
        assertEquals(DrawerAction.entries.toSet(), covered)
    }

    /** Same rows on both surfaces; only the primary button swaps to what that browser is for. */
    @Test fun thePhoneSheetKeepsTheShapeAndSwapsWhatItCannotDo() {
        val phoneState = state.copy(surface = MenuSurface.PHONE)
        assertEquals(
            BrowserDrawerModel.primaryRows(state).map { row -> row.map { it.action } - DrawerAction.SPLIT_LAYOUT },
            BrowserDrawerModel.primaryRows(phoneState).map { row -> row.map { it.action } },
        )
        assertEquals(DrawerAction.TABS, BrowserDrawerModel.primaryAction(state).action)
        assertEquals(DrawerAction.SEND_TO_CAR, BrowserDrawerModel.primaryAction(phoneState).action)
    }
}


class CarMenuParityTest {
    private val state = BrowserMenuState(tabCount = 3, canGoBack = true)

    @Test fun theCarSheetPutsTheSplitScreenUpFront() {
        val rows = BrowserDrawerModel.primaryRows(state)
        assertEquals(2, rows.size)
        assertEquals(
            listOf(DrawerAction.BOOKMARKS, DrawerAction.SETTINGS, DrawerAction.SPLIT_LAYOUT, DrawerAction.MORE),
            rows[1].map { it.action },
        )
        val more = BrowserDrawerModel.moreItems(state).map { it.action }
        assertFalse(DrawerAction.SPLIT_LAYOUT in more)
        assertEquals(listOf(DrawerAction.SIDE_SHOW_PAGE, DrawerAction.SWAP_SPLIT_SIDES), more.take(2))
    }

    @Test fun theFourTileRowSharesTheSheetWidth() {
        val sizes = AutoUiSizes.forCarSurface(160)
        val model = BrowserDrawerModel.create(sizes, BrowserViewport.create(1024, 600, sizes.density), state)
        val second = model.tiles.filter { it.item.action in setOf(DrawerAction.BOOKMARKS, DrawerAction.MORE) }
        val first = model.tiles.first { it.item.action == DrawerAction.NAV_BACK }
        assertTrue(second.all { it.bounds.width < first.bounds.width })
        val more = model.tiles.single { it.item.action == DrawerAction.MORE }
        assertEquals(model.tiles.single { it.item.action == DrawerAction.NAV_FORWARD }.bounds.right, more.bounds.right, 0.5f)
    }

    @Test fun swappingSidesOnlyActsWhileASplitIsUp() {
        fun swap(s: BrowserMenuState) = BrowserDrawerModel.carMenu(s).single { it.action == DrawerAction.SWAP_SPLIT_SIDES }
        assertFalse(swap(state).enabled)
        assertTrue(swap(state.copy(splitActive = true)).enabled)
    }

    @Test fun theCarMenuListsEveryCarActionOnceInSheetOrder() {
        val actions = BrowserDrawerModel.carMenu(state).map { it.action }
        assertEquals(actions.size, actions.toSet().size)
        assertEquals(DrawerAction.TABS, actions.first())
        assertEquals(DrawerAction.APP_HOME, actions.last())
        listOf(
            DrawerAction.BOOKMARKS, DrawerAction.SETTINGS, DrawerAction.HISTORY, DrawerAction.DOWNLOADS,
            DrawerAction.FIND_IN_PAGE, DrawerAction.ZOOM_IN, DrawerAction.CLEAR_DATA, DrawerAction.TOGGLE_DESKTOP,
            DrawerAction.TOGGLE_FULLSCREEN, DrawerAction.PIN_TOOLBAR, DrawerAction.MIRROR_PHONE,
        ).forEach { assertTrue("$it missing", it in actions) }
        assertFalse(DrawerAction.MORE in actions)
        assertFalse(DrawerAction.SEND_TO_CAR in actions)
        // The split layout is on the primary sheet, ahead of everything behind "More".
        assertTrue(actions.indexOf(DrawerAction.SPLIT_LAYOUT) < actions.indexOf(DrawerAction.TOGGLE_DESKTOP))
    }

    @Test fun aBrowserLeavesOutWhatItCannotDoEverywhere() {
        val unsupported = setOf(
            DrawerAction.TABS, DrawerAction.NEW_TAB, DrawerAction.SPLIT_LAYOUT, DrawerAction.SIDE_SHOW_PAGE,
            DrawerAction.SWAP_SPLIT_SIDES, DrawerAction.AGENT,
        )
        val limited = state.copy(unsupported = unsupported)
        val actions = BrowserDrawerModel.carMenu(limited).map { it.action }
        assertTrue((actions intersect unsupported).isEmpty())
        // No tabs: the start page leads instead.
        assertEquals(DrawerAction.HOME, BrowserDrawerModel.primaryAction(limited).action)
        assertEquals(DrawerAction.HOME, actions.first())
        assertEquals(
            listOf(DrawerAction.BOOKMARKS, DrawerAction.SETTINGS, DrawerAction.MORE),
            BrowserDrawerModel.primaryRows(limited)[1].map { it.action },
        )
        assertTrue(BrowserDrawerModel.moreItems(limited).none { it.action in unsupported })
    }

    @Test fun thePhoneSheetIsUntouchedByTheCarSplitTile() {
        val phone = state.copy(surface = MenuSurface.PHONE)
        assertTrue(BrowserDrawerModel.primaryRows(phone).flatten().none { it.action == DrawerAction.SPLIT_LAYOUT })
    }
}

/**
 * Regression from a head unit session: the trace showed `viewScroll=-279,255`. A negative offset is
 * unreachable for a correctly bounded scroll, and once there the page stayed drawn sideways and
 * further scrolling appeared to do nothing.
 */
class PageScrollTest {
    @Test fun scrollNeverGoesNegative() {
        assertEquals(0, PageScroll.clamp(0, -500, 2000))
        assertEquals(0, PageScroll.clamp(120, -279, 2000))
    }

    @Test fun scrollNeverPassesTheEndOfTheContent() {
        assertEquals(2000, PageScroll.clamp(1900, 500, 2000))
        assertEquals(2000, PageScroll.clamp(2000, 1, 2000))
    }

    @Test fun aPageShorterThanTheViewportCannotScrollAtAll() {
        assertEquals(0, PageScroll.clamp(0, 400, 0))
        assertEquals(0, PageScroll.clamp(0, 400, -50))
    }

    @Test fun ordinaryScrollingIsUntouched() {
        assertEquals(350, PageScroll.clamp(100, 250, 2000))
        assertEquals(100, PageScroll.clamp(350, -250, 2000))
    }
}

/**
 * Regression from a head unit session: bookmarking a Google Shopping result stored a URL thousands
 * of characters long, and the bookmarks list rendered all of it, filling the screen.
 */
class BrowserDisplayUrlTest {
    @Test fun aHugeQueryStringIsReducedToSomethingReadable() {
        val url = "https://www.google.com/search?q=x&" + "sca_esv=080dae4805299e94&".repeat(200)
        val shown = BrowserDisplayUrl.compact(url)
        assertTrue("still too long: ${shown.length}", shown.length <= BrowserDisplayUrl.DEFAULT_MAX)
        assertTrue(shown.startsWith("google.com/search"))
        assertTrue(shown.endsWith("…"))
    }

    @Test fun anOrdinaryUrlIsLeftRecognisable() {
        assertEquals("m.youtube.com", BrowserDisplayUrl.compact("https://m.youtube.com"))
        assertEquals("wikipedia.org", BrowserDisplayUrl.compact("https://www.wikipedia.org/"))
        assertEquals("example.com/a/b", BrowserDisplayUrl.compact("https://example.com/a/b"))
    }

    @Test fun aLongPathWithNoQueryIsTruncatedNotDropped() {
        val url = "https://example.com/" + "segment/".repeat(40)
        val shown = BrowserDisplayUrl.compact(url)
        assertTrue(shown.length <= BrowserDisplayUrl.DEFAULT_MAX)
        assertTrue(shown.startsWith("example.com/segment"))
    }

    @Test fun theLimitIsHonouredAtEveryRequestedWidth() {
        val url = "https://example.com/a/very/long/path?with=query&more=stuff"
        listOf(8, 16, 32, 64, 120).forEach { max ->
            assertTrue(BrowserDisplayUrl.compact(url, max).length <= max)
        }
    }
}
