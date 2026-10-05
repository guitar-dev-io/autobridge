package dev.autobridge.car

import dev.autobridge.bridge.BridgeSource
import dev.autobridge.bridge.BridgeStore
import dev.autobridge.bridge.EngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard's geometry, which is the part of the car UI that cannot be checked by looking at
 * one head unit: every rule here is about what happens on a *different* screen size, or with a
 * different amount of real content, than the one in front of whoever changed it.
 */
class HomeDashboardLayoutTest {

    private val labels = listOf("TV", "Radio", "Web browser", "YouTube", "YouTube Music", "Streaming")

    /** Stands in for the real TextPaint; width is proportional, which is all the layout needs. */
    private val measure: (String, Float) -> Float = { text, size -> text.length * size * 0.5f }

    private fun artwork() = Artwork(HomeMenuGlyph.GLOBE, 0x112233, null)

    private fun content(hero: Boolean = false, sent: Int = 0, queued: Int = 0): HomeDashboardContent {
        val source = BridgeSource("https://m.youtube.com/watch?v=dQw4w9WgXcQ", "Japan Travel 4K")
        return HomeDashboardContent(
            continueWatching = if (!hero) null else ContinueItem(
                source = source,
                snapshot = BridgeStore.Snapshot(source, EngineKind.BROWSER, 754_000L, 1L, 1_880_000L),
                title = source.title,
                sourceLabel = "YouTube",
                artwork = artwork(),
                positionMs = 754_000L,
                durationMs = 1_880_000L
            ),
            recentlySent = (0 until sent).map { SentItem("https://e.test/$it", "Sent $it", "meta", artwork()) },
            queue = (0 until queued).map { QueueItem("https://e.test/q$it", "Queued $it", "31:20", artwork()) },
            queueTotal = queued
        )
    }

    private fun layout(
        content: HomeDashboardContent,
        width: Float = 1920f,
        height: Float = 1080f,
        density: Float = 1f
    ) = HomeDashboardLayout.compute(MenuBox(0f, 0f, width, height), density, labels, content, measure)

    // ------------------------------------------------------------------ sections appear and go

    @Test
    fun `an empty dashboard is the six cards and nothing else`() {
        val layout = layout(content())
        assertEquals(6, layout.cards.size)
        assertNull(layout.continueCard)
        assertNull(layout.recentBlock)
        assertNull(layout.queueBlock)
        assertTrue(layout.recentRows.isEmpty())
        assertTrue(layout.queueRows.isEmpty())
    }

    @Test
    fun `a section with no content gives its space back to the cards`() {
        // Measured on a screen whose grid is not already at its ceiling, so the budget that the
        // absent sections hand back actually shows up as height rather than being clamped away.
        val bare = layout(content(), width = 1280f, height = 760f)
        val full = layout(content(hero = true, sent = 2, queued = 3), width = 1280f, height = 760f)
        assertTrue(
            "cards should be taller with nothing else on screen",
            bare.cards.first().height > full.cards.first().height
        )
    }

    @Test
    fun `cards do not grow past their ceiling just because the screen is empty and tall`() {
        // On a large screen both dashboards saturate the card ceiling, so the space an empty
        // dashboard reclaims becomes breathing room at the bottom rather than oversized tiles.
        val bare = layout(content(), height = 1600f)
        val full = layout(content(hero = true, sent = 2, queued = 3), height = 1600f)
        assertEquals(HomeDashboardTheme.Dp.CARD_MAX_HEIGHT, bare.cards.first().height, 0.5f)
        assertEquals(bare.cards.first().height, full.cards.first().height, 0.5f)
    }

    @Test
    fun `every section present produces a row box per item`() {
        val layout = layout(content(hero = true, sent = 2, queued = 3))
        assertNotNull(layout.continueCard)
        assertNotNull(layout.continueTitle)
        assertEquals(2, layout.recentRows.size)
        assertEquals(3, layout.queueRows.size)
        assertNotNull(layout.recentHeader)
        assertNotNull(layout.queueHeader)
    }

    // ------------------------------------------------------------------------- responsiveness

    @Test
    fun `a wide head unit sits the two blocks side by side`() {
        val layout = layout(content(hero = true, sent = 2, queued = 3))
        val recent = layout.recentBlock!!
        val queue = layout.queueBlock!!
        assertEquals(recent.top, queue.top, 0.5f)
        assertTrue("recent should start left of queue", recent.right <= queue.left)
    }

    @Test
    fun `a narrow head unit stacks them instead`() {
        val layout = layout(content(hero = true, sent = 2, queued = 3), width = 720f, height = 1280f)
        val recent = layout.recentBlock!!
        val queue = layout.queueBlock!!
        assertTrue("queue should sit below recent", queue.top >= recent.bottom)
        assertEquals(recent.left, queue.left, 0.5f)
    }

    @Test
    fun `a short screen scrolls rather than shrinking the cards below the touch minimum`() {
        val layout = layout(content(hero = true, sent = 2, queued = 3), width = 1280f, height = 480f)
        assertTrue("expected the column to scroll", layout.scrollable)
        assertTrue(layout.cards.first().height >= HomeDashboardTheme.Dp.CARD_MIN_HEIGHT - 0.5f)
        assertNotNull(layout.scrollUp)
        assertNotNull(layout.scrollDown)
    }

    @Test
    fun `density scales the layout rather than the pixel count`() {
        val onex = layout(content(hero = true, sent = 2, queued = 3), width = 1920f, height = 1080f, density = 1f)
        val twox = layout(content(hero = true, sent = 2, queued = 3), width = 1920f, height = 1080f, density = 2f)
        assertTrue(
            "the same surface at twice the density must fit less",
            twox.cards.first().height < onex.cards.first().height || twox.scrollable
        )
    }

    @Test
    fun `an ultra-wide unit centres the column instead of stretching cards across it`() {
        val layout = layout(content(), width = 3200f, height = 1080f)
        val card = layout.cards.first()
        assertTrue("cards must stay within the aspect cap", card.width <= card.height * HomeDashboardTheme.CARD_MAX_ASPECT + 0.5f)
        val right = layout.cards.last().right
        assertEquals("the column should be centred", card.left, 3200f - right, 1f)
    }

    // -------------------------------------------------------------------------- hit testing

    @Test
    fun `each region answers for its own box`() {
        val layout = layout(content(hero = true, sent = 2, queued = 3))
        assertEquals(HomeHit(HomeRegion.CONTINUE), layout.hitAt(layout.continueCard!!))
        assertEquals(HomeHit(HomeRegion.QUICK_ACCESS, 4), layout.hitAt(layout.cards[4]))
        assertEquals(HomeHit(HomeRegion.RECENT_HEADER), layout.hitAt(layout.recentHeader!!))
        assertEquals(HomeHit(HomeRegion.RECENT_ITEM, 1), layout.hitAt(layout.recentRows[1]))
        assertEquals(HomeHit(HomeRegion.QUEUE_HEADER), layout.hitAt(layout.queueHeader!!))
        assertEquals(HomeHit(HomeRegion.QUEUE_ITEM, 2), layout.hitAt(layout.queueRows[2]))
    }

    @Test
    fun `nothing claims a tap on empty space`() {
        val layout = layout(content(hero = true, sent = 2, queued = 3))
        val between = (layout.quickTitle.top + layout.quickTitle.bottom) / 2f
        assertNull(layout.hit(layout.viewport.left - 50f, between, 0f))
    }

    @Test
    fun `no two regions overlap`() {
        val layout = layout(content(hero = true, sent = 2, queued = 3))
        val boxes = buildList {
            layout.continueCard?.let { add(it) }
            addAll(layout.cards)
            layout.recentHeader?.let { add(it) }
            addAll(layout.recentRows)
            layout.queueHeader?.let { add(it) }
            addAll(layout.queueRows)
        }
        for (a in boxes.indices) {
            for (b in a + 1 until boxes.size) {
                val first = boxes[a]
                val second = boxes[b]
                val overlaps = first.left < second.right && second.left < first.right &&
                    first.top < second.bottom && second.top < first.bottom
                assertTrue("regions $a and $b overlap", !overlaps)
            }
        }
    }

    @Test
    fun `a scrolled column hits what is drawn under the finger, not what was there`() {
        val layout = layout(content(hero = true, sent = 2, queued = 3), width = 1280f, height = 480f)
        val card = layout.cards[0]
        val step = layout.scrollStep
        // The card has moved up by one step, so its old centre no longer answers for it.
        assertEquals(HomeHit(HomeRegion.QUICK_ACCESS, 0), layout.hit(card.centerX, card.centerY, 0f))
        assertEquals(
            HomeHit(HomeRegion.QUICK_ACCESS, 0),
            layout.hit(card.centerX, card.centerY - step, step)
        )
    }

    @Test
    fun `the scroll buttons stay put while the column moves`() {
        val layout = layout(content(hero = true, sent = 2, queued = 3), width = 1280f, height = 480f)
        val up = layout.scrollUp!!
        assertEquals(HomeHit(HomeRegion.SCROLL_UP), layout.hit(up.centerX, up.centerY, 0f))
        assertEquals(HomeHit(HomeRegion.SCROLL_UP), layout.hit(up.centerX, up.centerY, layout.maxScroll))
    }

    // ---------------------------------------------------------------------- the vertical budget

    /** The free height the budget divides: the safe area less its top and bottom padding. */
    private fun usable(height: Float, density: Float = 1f) =
        height - (HomeDashboardTheme.Dp.TOP_PADDING + HomeDashboardTheme.Dp.BOTTOM_PADDING) * density

    @Test
    fun `each band takes its budgeted share of the free height`() {
        // A mid-size head unit, where every band's percentage sits inside its dp range and the
        // clamps do not come into it - the case the budget is written for.
        val layout = layout(content(hero = true, sent = 2, queued = 3), width = 1280f, height = 720f)
        val free = usable(720f)
        val top = HomeDashboardTheme.Dp.TOP_PADDING

        val header = (layout.continueTitle!!.top - top) / free
        val hero = (layout.quickTitle.top - layout.continueTitle!!.top) / free
        val quickTitle = (layout.cards.first().top - layout.quickTitle.top) / free
        val grid = (layout.recentBlock!!.top - layout.cards.first().top) / free

        assertInRange("header", header, 0.12f, 0.14f)
        assertInRange("continue watching", hero, 0.20f, 0.22f)
        assertInRange("quick access title", quickTitle, 0.055f, 0.065f)
        assertInRange("quick access grid", grid, 0.44f, 0.48f)
    }

    @Test
    fun `what the bands leave is enough for the blocks to peek plus breathing room`() {
        val layout = layout(content(hero = true, sent = 2, queued = 3), width = 1280f, height = 720f)
        val free = usable(720f)
        val leftOver = (layout.viewport.bottom - layout.recentBlock!!.top) / free
        val wanted = HomeDashboardTheme.Budget.BLOCK_HEADER + HomeDashboardTheme.Budget.BREATHING
        assertTrue(
            "expected at least ${wanted * 100}% left under the grid, got ${leftOver * 100}%",
            leftOver >= HomeDashboardTheme.Budget.BLOCK_HEADER
        )
    }

    @Test
    fun `the block header peeks above the fold rather than waiting behind a scroll`() {
        val layout = layout(content(hero = true, sent = 2, queued = 3), width = 1280f, height = 720f)
        assertTrue(
            "the Recently Sent header should be visible at rest",
            layout.recentHeader!!.bottom <= layout.viewport.bottom
        )
        // Stacked blocks can only ever peek one header; side-by-side ones share a top edge, so
        // the Queue header is visible exactly when the two blocks sit level.
        if (layout.queueBlock!!.top == layout.recentBlock!!.top) {
            assertTrue(
                "the Queue header should peek too when the blocks sit side by side",
                layout.queueHeader!!.bottom <= layout.viewport.bottom
            )
        }
    }

    @Test
    fun `a longer queue cannot take height from the quick-access grid`() {
        // The whole point of budgeting the grid first: the thing a driver aims at keeps its share
        // however much content the sections under it happen to have.
        val short = layout(content(hero = true, sent = 1, queued = 1), width = 1280f, height = 720f)
        val long = layout(content(hero = true, sent = 2, queued = 3), width = 1280f, height = 720f)
        assertEquals(
            short.cards.first().height.toDouble(),
            long.cards.first().height.toDouble(),
            0.5
        )
        assertEquals(short.cards.first().top.toDouble(), long.cards.first().top.toDouble(), 0.5)
    }

    @Test
    fun `a long hero title cannot take height from the grid either`() {
        // Titles are ellipsized rather than wrapped, so this pins the band, not the text.
        val base = layout(content(hero = true, sent = 2, queued = 3), width = 1280f, height = 720f)
        val longTitle = content(hero = true, sent = 2, queued = 3).let { c ->
            c.copy(continueWatching = c.continueWatching!!.copy(title = "x".repeat(400)))
        }
        val stretched = layout(longTitle, width = 1280f, height = 720f)
        assertEquals(
            base.cards.first().height.toDouble(),
            stretched.cards.first().height.toDouble(),
            0.5
        )
    }

    private fun assertInRange(name: String, value: Float, low: Float, high: Float) {
        assertTrue(
            "$name should take ${low * 100}-${high * 100}% of the free height, took ${value * 100}%",
            value in low..high
        )
    }

    private fun HomeDashboardLayout.hitAt(box: MenuBox) = hit(box.centerX, box.centerY, 0f)
}
