package dev.autobridge.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The streaming launcher's geometry: three cards to a row, scrolling when they do not fit. */
class StreamingGridLayoutTest {
    /** The DHU 800 x 480 profile's stable area. */
    private val small = MenuBox(12f, 88f, 788f, 388f)

    /** A 1280 x 720 unit's. */
    private val wide = MenuBox(12f, 88f, 1178f, 688f)

    private fun layout(area: MenuBox, count: Int = 24, notes: Boolean = false) =
        StreamingGridLayout.compute(area, density = 1f, count = count, hasNotes = notes)

    @Test fun aWideScreenGetsThreeColumns() {
        val l = layout(wide)
        assertEquals(3, l.columns)
        // The first three cards share a row, the fourth starts the next.
        assertEquals(l.cards[0].top, l.cards[2].top, 0.01f)
        assertTrue(l.cards[3].top > l.cards[0].bottom)
        assertEquals(l.cards[0].left, l.cards[3].left, 0.01f)
    }

    @Test fun aNarrowOrPortraitScreenGetsTwo() {
        val l = layout(MenuBox(0f, 0f, 400f, 900f))
        assertEquals(2, l.columns)
    }

    @Test fun cardsKeepTheirEightByFiveShape() {
        val l = layout(wide)
        l.cards.forEach { assertEquals(StreamingGridLayout.CARD_ASPECT, it.height / it.width, 0.001f) }
    }

    @Test fun cardsStayInsideTheScreenAndDoNotOverlap() {
        val l = layout(wide)
        l.cards.forEach { assertTrue(it.left >= wide.left && it.right <= wide.right) }
        for (i in 0 until l.cards.size - 1) {
            val a = l.cards[i]
            val b = l.cards[i + 1]
            if (a.top == b.top) assertTrue("neighbours must not overlap", b.left >= a.right)
        }
    }

    @Test fun threeColumnsAreBiggerThanFour() {
        // The point of drawing the grid: at three across a card is wider than the host's four-up tile.
        val three = layout(wide).cards[0].width
        val four = (wide.width - 2 * StreamingGridLayout.MARGIN - StreamingGridLayout.GAP * 3) / 4
        assertTrue(three > four)
    }

    @Test fun aShortListThatFitsDoesNotScroll() {
        val l = layout(wide, count = 3)
        assertFalse(l.scrollable)
        assertNull(l.scrollUp)
        assertNull(l.scrollDown)
    }

    @Test fun aLongListOnASmallScreenScrollsAndKeepsTheCardsOutOfTheButtons() {
        val l = layout(small)
        assertTrue(l.scrollable)
        assertNotNull(l.scrollUp)
        assertNotNull(l.scrollDown)
        // The cards start to the right of the scroll lane.
        assertTrue(l.cards[0].left >= l.scrollUp!!.right)
        assertTrue(l.maxScroll > 0f)
        assertEquals(l.cards[3].top - l.cards[0].top, l.scrollStep, 0.01f)
    }

    @Test fun theLastRowCanBeScrolledIntoView() {
        val l = layout(small)
        val last = l.cards.last().offset(-l.maxScroll)
        assertTrue("the last card must end inside the viewport", last.bottom <= l.viewport.bottom + l.labels.last().height + 0.5f)
    }

    @Test fun aTapFindsTheCardUnderIt() {
        val l = layout(wide)
        val card = l.cards[4]
        assertEquals(StreamingHit(StreamingRegion.CARD, 4), l.hit(card.centerX, card.centerY, 0f))
    }

    @Test fun aTapOnTheNameStripCountsAsTheCard() {
        val l = layout(wide)
        val label = l.labels[1]
        assertEquals(StreamingHit(StreamingRegion.CARD, 1), l.hit(label.centerX, label.centerY, 0f))
    }

    @Test fun aTapBetweenCardsHitsNothing() {
        val l = layout(wide)
        val gapX = (l.cards[0].right + l.cards[1].left) / 2f
        assertNull(l.hit(gapX, l.cards[0].centerY, 0f))
    }

    @Test fun scrollingMovesWhatIsUnderTheFinger() {
        val l = layout(small)
        val row1 = l.cards[3]
        val scrolled = l.hit(row1.centerX, row1.centerY - l.scrollStep, l.scrollStep)
        assertEquals(StreamingHit(StreamingRegion.CARD, 3), scrolled)
    }

    @Test fun theScrollButtonsAreHitBeforeTheCards() {
        val l = layout(small)
        assertEquals(StreamingHit(StreamingRegion.SCROLL_UP), l.hit(l.scrollUp!!.centerX, l.scrollUp!!.centerY, 0f))
        assertEquals(StreamingHit(StreamingRegion.SCROLL_DOWN), l.hit(l.scrollDown!!.centerX, l.scrollDown!!.centerY, 0f))
    }

    @Test fun aTapAboveTheViewportHitsNothing() {
        val l = layout(small)
        assertNull(l.hit(l.title.centerX, l.title.centerY, 0f))
    }

    @Test fun notesMakeTheNameStripTaller() {
        assertTrue(layout(wide, notes = true).labels[0].height > layout(wide, notes = false).labels[0].height)
    }

    // --- Sections and the status strip -------------------------------------------------------

    private fun sectioned(area: MenuBox, sections: List<Int>, banner: Boolean = false) =
        StreamingGridLayout.compute(area, density = 1f, count = sections.sum(), hasNotes = false, sections = sections, banner = banner)

    @Test fun eachSectionGetsAHeadingAboveItsFirstCard() {
        val l = sectioned(wide, listOf(2, 7, 3))
        assertEquals(3, l.headings.size)
        assertTrue(l.headings[0].bottom <= l.cards[0].top + 0.01f)
        assertTrue(l.headings[1].bottom <= l.cards[2].top + 0.01f)
        assertTrue(l.headings[2].bottom <= l.cards[9].top + 0.01f)
    }

    @Test fun aSectionStartsOnAFreshRowBelowTheLastRowOfThePreviousOne() {
        val l = sectioned(wide, listOf(2, 7, 3))
        // Section one has one row; section two's first card sits below it, not beside it.
        assertTrue(l.cards[2].top >= l.cards[1].bottom)
        // Section two's seven cards take three rows; section three starts below the third.
        assertTrue(l.cards[9].top >= l.cards[8].bottom)
        assertEquals(l.cards[2].left, l.cards[5].left, 0.01f)
    }

    @Test fun anUnsectionedGridHasNoHeadingsAndNoBanner() {
        val l = layout(wide)
        assertTrue(l.headings.isEmpty())
        assertNull(l.banner)
    }

    @Test fun theBannerSitsAboveTheFirstHeadingAndIsATapTarget() {
        val l = sectioned(wide, listOf(2, 7), banner = true)
        val banner = l.banner!!
        assertTrue(banner.bottom <= l.headings[0].top + 0.01f)
        val hit = l.hit(banner.centerX, banner.centerY, scroll = 0f)
        assertEquals(StreamingRegion.BANNER, hit?.region)
    }

    @Test fun theBannerScrollsAwayWithTheCards() {
        val l = sectioned(small, listOf(2, 7, 3, 4, 3), banner = true)
        assertTrue(l.scrollable)
        val banner = l.banner!!
        // A little scrolled, it has moved up with the content but is still under the viewport's top.
        val nudge = 4f
        assertEquals(StreamingRegion.BANNER, l.hit(banner.centerX, banner.centerY - nudge, scroll = nudge)?.region)
        // Scrolled past it, nothing at its old spot is the banner any more.
        val past = banner.bottom - l.viewport.top + 10f
        assertTrue(l.hit(banner.centerX, banner.centerY, scroll = past)?.region != StreamingRegion.BANNER)
    }

    @Test fun aLongSectionedListCanScrollItsLastCardIntoView() {
        val l = sectioned(small, listOf(2, 7, 3, 4, 3, 3, 3), banner = true)
        val last = l.cards.last().offset(-l.maxScroll)
        assertTrue(last.bottom <= l.viewport.bottom + l.labels.last().height + 0.5f)
    }

    @Test fun sectionedCardsStillKeepTheirShapeAndStayInside() {
        val l = sectioned(wide, listOf(2, 7, 3), banner = true)
        l.cards.forEach {
            assertEquals(StreamingGridLayout.CARD_ASPECT, it.height / it.width, 0.001f)
            assertTrue(it.left >= wide.left && it.right <= wide.right)
        }
    }
}
