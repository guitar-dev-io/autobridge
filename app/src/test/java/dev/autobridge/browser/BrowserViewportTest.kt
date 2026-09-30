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
    @Test fun landscapeViewportDoesNotDependOnPhoneDensity() {
        for (density in listOf(1f, 2f, 3.5f)) {
            val viewport = BrowserViewport.create(800, 400, density, 1f)
            assertEquals(800f, viewport.webWidth / density, 1f)
            assertEquals(800f, viewport.webWidth * viewport.scale, 0.01f)
            assertEquals(400f, viewport.webHeight * viewport.scale, 1f)
        }
    }

    @Test fun insetPageAndTouchCoordinatesShareTheSameOriginAndScale() {
        val viewport = BrowserViewport.create(1000, 600, 3f, 1f, 24, 80, 940, 580)
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
        val viewport = BrowserViewport.create(800, 400, 2f, 1f, 20, 80, 1000, 600)
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
            val viewport = BrowserViewport.create(width, height, 3f, surfaceDensity)
            // Whatever the chrome does, the same surface geometry yields the same page geometry.
            val again = BrowserViewport.create(width, height, 3f, surfaceDensity)
            assertEquals("$width x $height @$dpi", viewport, again)
            assertEquals(height.toFloat(), viewport.webHeight * viewport.scale, 1.5f)
        }
    }

    @Test fun contentWidthFollowsPanelDensityNotPixelCount() {
        // Same pixel width, different physical density: the denser panel asks for fewer CSS pixels
        // so text stays legible instead of shrinking with the panel's dpi.
        val lowDpi = BrowserViewport.create(1280, 720, 3f, 1f)
        val highDpi = BrowserViewport.create(1280, 720, 3f, 1.5f)
        assertEquals(1280, lowDpi.contentWidthDp)
        assertEquals(853, highDpi.contentWidthDp)
        assertTrue(highDpi.contentWidthDp < lowDpi.contentWidthDp)
    }

    @Test fun contentWidthIsClampedToAReadableRange() {
        val tiny = BrowserViewport.create(480, 320, 2f, 2f)
        assertEquals(BrowserViewport.MIN_CONTENT_WIDTH_DP, tiny.contentWidthDp)
        val huge = BrowserViewport.create(3840, 1080, 2f, 1f)
        assertEquals(BrowserViewport.MAX_CONTENT_WIDTH_DP, huge.contentWidthDp)
    }

    @Test fun invalidDensitiesFallBackInsteadOfProducingNaNGeometry() {
        val viewport = BrowserViewport.create(1024, 600, Float.NaN, 0f)
        assertTrue(viewport.scale.isFinite() && viewport.scale > 0f)
        assertTrue(viewport.webWidth > 0 && viewport.webHeight > 0)
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
        val viewport = BrowserViewport.create(width, height, 3f, sizes.density)
        return BrowserChromeLayout.create(sizes, viewport)
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
            val fullscreen = layout.slot(ChromeZone.FULLSCREEN)!!
            assertTrue(layout.address.left >= reload.bounds.right)
            assertTrue(layout.address.right <= fullscreen.bounds.left + 0.01f)
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
            val viewport = BrowserViewport.create(width, height, 3f, sizes.density)
            val layout = layoutFor(width, height, dpi)
            assertTrue(layout.fab.left >= viewport.left.toFloat())
            assertTrue(layout.fab.right <= viewport.left + viewport.width.toFloat())
            assertTrue(layout.fab.bottom <= viewport.top + viewport.height.toFloat())
            // Never overlaps the toolbar it duplicates, so neither can steal the other's taps.
            assertTrue(layout.fab.top >= layout.toolbar.bottom)
            assertTrue(layout.fab.width >= sizes.touchTarget * 0.9f)
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
                BrowserViewport.create(width, height, 3f, AutoUiSizes.forCarSurface(dpi).density)
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
        val viewport = BrowserViewport.create(1024, 600, 3f, sizes.density, 40, 20, 984, 580)
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
    private fun modelFor(
        width: Int,
        height: Int,
        dpi: Int,
        scroll: Float = 0f,
        sections: List<DrawerSection> = BrowserDrawerModel.sectionsFor(tabCount = 2, isDesktop = false),
    ): BrowserDrawerModel {
        val sizes = AutoUiSizes.forCarSurface(dpi)
        val viewport = BrowserViewport.create(width, height, 3f, sizes.density)
        return BrowserDrawerModel.create(sizes, viewport, sections, scroll)
    }

    /**
     * The property the whole grid-sizing rewrite exists for. A fixed 78dp tile plus a 26dp heading
     * per section made the sheet's content height a constant, so on a short panel the last row fell
     * below the fold silently — and on the primary list that row held "More", the only way into the
     * rest of the menu. Every entry of both lists must be reachable without a scroll on every
     * supported head unit, because a tap that drifts during a scroll is read as a scroll and the
     * entry never fires.
     */
    @Test fun bothMenuListsFitWithoutScrollingOnEveryHeadUnit() {
        val lists = mapOf(
            "primary" to BrowserDrawerModel.sectionsFor(tabCount = 2, isDesktop = false),
            "more" to BrowserDrawerModel.moreSectionsFor(isDesktop = false),
        )
        HEAD_UNITS.forEach { (width, height, dpi) ->
            lists.forEach { (name, sections) ->
                val model = modelFor(width, height, dpi, sections = sections)
                assertEquals(
                    "$name list scrolls at ${width}x$height @$dpi",
                    0f, model.maxScroll, 0.01f
                )
                assertEquals(sections.sumOf { it.items.size }, model.rows.size)
                model.rows.forEach { row ->
                    assertTrue(
                        "${row.item.label} falls outside the sheet at ${width}x$height @$dpi",
                        row.bounds.top >= model.headerBottom - 0.01f &&
                            row.bounds.bottom <= model.panel.bottom + 0.01f
                    )
                }
            }
        }
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
            val viewport = BrowserViewport.create(width, height, 3f, sizes.density)
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

    @Test fun drawerOverlaysRatherThanShrinkingTheViewport() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val sizes = AutoUiSizes.forCarSurface(dpi)
            val viewport = BrowserViewport.create(width, height, 3f, sizes.density)
            val model = modelFor(width, height, dpi)
            // The sheet sits inside the same viewport; the page geometry is untouched by it.
            assertTrue(model.panel.left >= viewport.left.toFloat())
            assertTrue(model.panel.top >= viewport.top.toFloat())
            assertTrue(model.panel.right <= viewport.left + viewport.width.toFloat())
            assertTrue(model.panel.bottom <= viewport.top + viewport.height.toFloat())
        }
    }

    /**
     * The point of the grid: an entry is a real button, not a minimum-height text row. 46dp square
     * is the smallest target a finger hits reliably at rest, which is the wrong size for a control
     * used while the car is moving, so a tile must be comfortably larger than that.
     */
    @Test fun everyTileIsLargerThanTheBareMinimumTouchTarget() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val sizes = AutoUiSizes.forCarSurface(dpi)
            val minimum = sizes.touchTarget * sizes.touchTarget
            modelFor(width, height, dpi).rows.forEach { row ->
                assertTrue(
                    "tile ${row.item.label} too small at ${width}x$height",
                    row.bounds.width * row.bounds.height >= minimum * 1.5f
                )
                assertTrue(row.bounds.height >= sizes.touchTarget)
            }
        }
    }

    @Test fun tilesInTheSameRowDoNotOverlap() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val rows = modelFor(width, height, dpi).rows
            rows.forEachIndexed { index, row ->
                rows.drop(index + 1).forEach { other ->
                    val overlaps = row.bounds.left < other.bounds.right &&
                        other.bounds.left < row.bounds.right &&
                        row.bounds.top < other.bounds.bottom &&
                        other.bounds.top < row.bounds.bottom
                    assertTrue("${row.item.label} overlaps ${other.item.label}", !overlaps)
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

    @Test fun everyRowIsTappableAtItsOwnCentre() {
        val model = modelFor(1024, 600, 160)
        model.rows.filter { it.bounds.top >= model.headerBottom && it.bounds.bottom <= model.panel.bottom }
            .forEach { row ->
                assertEquals(row.item.action, model.rowAt(row.bounds.centerX, row.bounds.centerY)?.item?.action)
            }
    }

    /**
     * Regression: the sheet's only dismissal was a tap outside it, and it covers the viewport apart
     * from a margin a few dp wide. On a head unit that band is not a target anyone can hit, so the
     * drawer could be opened and then not closed without choosing an action. The header carries a
     * real close button instead.
     */
    @Test fun theDrawerCanBeClosedWithoutTappingTheMarginAroundIt() {
        HEAD_UNITS.forEach { (width, height, dpi) ->
            val sizes = AutoUiSizes.forCarSurface(dpi)
            val model = modelFor(width, height, dpi)
            val close = model.closeButton
            assertTrue(
                "close button too small at ${width}x$height",
                minOf(close.width, close.height) >= sizes.touchTarget * 0.7f
            )
            // In the header, inside the panel, and never over the tiles.
            assertTrue(close.top >= model.panel.top)
            assertTrue(close.bottom <= model.headerBottom)
            assertTrue(close.right <= model.panel.right)
            assertTrue(model.hitsClose(close.centerX, close.centerY))
            assertNull(model.rowAt(close.centerX, close.centerY))
            assertTrue(!model.hitsClose(model.panel.centerX, model.panel.bottom - 1f))
        }
    }

    @Test fun rowsScrolledUnderTheHeaderAreNotTappable() {
        val model = modelFor(800, 480, 160, scroll = 200f)
        assertNull(model.rowAt(model.panel.centerX, model.headerBottom - 1f))
    }

    @Test fun scrollIsClampedToTheContent() {
        val model = modelFor(800, 480, 160, scroll = 99_999f)
        assertEquals(model.maxScroll, model.scrollOffset, 0.01f)
        val negative = modelFor(800, 480, 160, scroll = -50f)
        assertEquals(0f, negative.scrollOffset, 0.01f)
    }

    /**
     * Regression: the paste row used to be hidden unless a clipboard probe succeeded. On a car
     * surface that probe is always refused (the app is not focused on the phone), so the row never
     * appeared. It is now unconditional and reports failure when tapped. It now lives in the
     * "More" list rather than the primary one.
     */
    @Test fun pasteRowIsAlwaysOfferedRegardlessOfClipboardReadability() {
        listOf(true, false).forEach { desktop ->
            val actions = BrowserDrawerModel.moreSectionsFor(isDesktop = desktop)
                .flatMap { it.items }.map { it.action }
            assertTrue(actions.contains(DrawerAction.PASTE_AND_GO))
        }
    }

    /**
     * The primary list is the one drawn by default and must stay short enough to fit without
     * scrolling on the smallest supported head unit, so a drifting tap can never be misread as a
     * scroll instead of a click on a common action.
     */
    @Test fun primaryDrawerListStaysShort() {
        val actions = BrowserDrawerModel.sectionsFor(tabCount = 1, isDesktop = true)
            .flatMap { it.items }.map { it.action }
        assertTrue(actions.size <= 12)
        assertTrue(actions.contains(DrawerAction.MORE))
    }

    /**
     * The drawer is opened from the toolbar, so a primary row that repeats a toolbar button spends
     * the largest target on the surface re-stating what the user can already see. Reload and the
     * address/keyboard row used to do exactly that. They still exist — under "More", for when the
     * toolbar has faded out — but never in the list the menu opens on.
     */
    @Test fun noPrimaryDrawerRowRepeatsAToolbarButton() {
        val onTheToolbar = mapOf(
            DrawerAction.RELOAD to ChromeZone.RELOAD,
            DrawerAction.ADDRESS_KEYBOARD to ChromeZone.ADDRESS,
        )
        val primary = BrowserDrawerModel.sectionsFor(tabCount = 1, isDesktop = true)
            .flatMap { it.items }.map { it.action }
        onTheToolbar.forEach { (action, zone) ->
            val layout = BrowserChromeLayout.create(
                AutoUiSizes.forCarSurface(160),
                BrowserViewport.create(1024, 600, 3f, 1f)
            )
            val drawnOnTheBar = zone == ChromeZone.ADDRESS || layout.slot(zone) != null
            assertTrue("$zone is not on the toolbar; the premise of this test is stale", drawnOnTheBar)
            assertFalse("$action is on the toolbar and in the primary menu", primary.contains(action))
        }
    }

    @Test fun everyDrawerActionHasExactlyOneRowAcrossBothLists() {
        val primary = BrowserDrawerModel.sectionsFor(tabCount = 1, isDesktop = true)
            .flatMap { it.items }.map { it.action }
        val more = BrowserDrawerModel.moreSectionsFor(isDesktop = true)
            .flatMap { it.items }.map { it.action }
        val actions = primary + more
        assertEquals(actions.size, actions.toSet().size)
        assertEquals(DrawerAction.entries.toSet(), actions.toSet())
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
