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
        width: Float = 1184f,
        height: Float = 720f,
        density: Float = 1f
    ) = HomeDashboardLayout.compute(
        MenuBox(0f, 0f, width, height), density, labels, content, measure,
        recentTitle = "Recently sent from phone",
        queueLink = if (content.queueTotal > 0) "Queue · ${content.queueTotal} items" else null
    )

    private fun full() = content(hero = true, sent = 3, queued = 2)

    // ------------------------------------------------------------------ sections appear and go

    @Test
    fun `an empty dashboard is the six cards and nothing else`() {
        val layout = layout(content())
        assertEquals(6, layout.cards.size)
        assertNull(layout.hero)
        assertNull(layout.recentHeader)
        assertNull(layout.queueHeader)
        assertTrue(layout.recentRows.isEmpty())
    }

    @Test
    fun `every section present produces its boxes`() {
        val layout = layout(full())
        assertNotNull(layout.hero)
        assertNotNull(layout.recentHeader)
        assertNotNull(layout.queueHeader)
        assertEquals(3, layout.recentRows.size)
    }

    @Test
    fun `the row shows at most three sent items`() {
        assertEquals(HomeDashboardLayout.MAX_RECENT, layout(content(sent = 5)).recentRows.size)
    }

    @Test
    fun `a queue with nothing sent still gets its link`() {
        val layout = layout(content(queued = 2))
        assertNull(layout.recentHeader)
        assertNotNull(layout.queueHeader)
        assertTrue(layout.recentRows.isEmpty())
    }

    // --------------------------------------------------------------------- the reference layout

    @Test
    fun `the design size lays out at scale one`() {
        // The design is 1280 x 720 with a 96dp host rail: 1184 x 720 is the surface it was drawn for.
        val layout = layout(full())
        val hero = layout.hero!!
        assertEquals(HomeDashboardTheme.Dp.HERO_WIDTH, hero.card.width, 1f)
        assertEquals(HomeDashboardTheme.Dp.CARD_HEIGHT, layout.cards.first().height, 1f)
        assertEquals(HomeDashboardTheme.Dp.ROW_HEIGHT, layout.recentRows.first().height, 1f)
    }

    @Test
    fun `now playing sits left of the grid and matches its height`() {
        val layout = layout(full())
        val hero = layout.hero!!.card
        assertTrue("grid should start right of the card", layout.cards.first().left > hero.right)
        assertEquals(hero.top, layout.cards.first().top, 0.5f)
        assertEquals(hero.bottom, layout.cards.last().bottom, 0.5f)
    }

    @Test
    fun `the three sent items sit side by side under the top row`() {
        val layout = layout(full())
        val rows = layout.recentRows
        assertEquals(rows[0].top, rows[2].top, 0.5f)
        assertTrue(rows[0].right < rows[1].left && rows[1].right < rows[2].left)
        assertTrue(rows[0].top > layout.cards.last().bottom)
    }

    @Test
    fun `the queue link is right-aligned on the section line`() {
        val layout = layout(full())
        assertEquals(layout.cards.last().right, layout.queueHeader!!.right, 0.5f)
        assertEquals(layout.recentHeader!!.centerY, layout.queueHeader!!.centerY, 0.5f)
    }

    @Test
    fun `the controls sit inside the card, play in the middle`() {
        val hero = layout(full()).hero!!
        listOf(hero.previous, hero.play, hero.next, hero.art, hero.progress).forEach {
            assertTrue("$it should be inside the card", it.left >= hero.card.left && it.right <= hero.card.right &&
                it.top >= hero.card.top && it.bottom <= hero.card.bottom)
        }
        assertEquals(hero.card.centerX, hero.play.centerX, 0.5f)
        assertTrue(hero.play.width > hero.previous.width)
        assertTrue(hero.previous.right < hero.play.left && hero.play.right < hero.next.left)
        assertTrue("the times must clear the buttons", hero.times.bottom <= hero.play.top)
    }

    // ------------------------------------------------------------------------- responsiveness

    @Test
    fun `a taller screen scales the column up, within the cap`() {
        val base = layout(full())
        val tall = layout(full(), width = 1700f, height = 1000f)
        assertTrue(tall.cards.first().height > base.cards.first().height)
        assertTrue(tall.unit <= HomeDashboardTheme.MAX_SCALE + 0.001f)
        assertTrue(!tall.scrollable)
    }

    @Test
    fun `a short screen scrolls rather than shrinking the cards below the touch minimum`() {
        val layout = layout(full(), width = 1184f, height = 360f)
        assertTrue("expected the column to scroll", layout.scrollable)
        assertEquals(HomeDashboardTheme.MIN_SCALE, layout.unit, 0.001f)
        assertNotNull(layout.scrollUp)
        assertNotNull(layout.scrollDown)
    }

    @Test
    fun `a narrow head unit stacks the card above the grid and the sent items too`() {
        val layout = layout(full(), width = 480f, height = 1400f)
        val hero = layout.hero!!.card
        assertTrue("grid should sit below the card", layout.cards.first().top > hero.bottom)
        assertEquals(hero.left, layout.cards.first().left, 0.5f)
        val rows = layout.recentRows
        assertTrue("rows should stack", rows[1].top >= rows[0].bottom)
    }

    @Test
    fun `density scales the layout rather than the pixel count`() {
        val onex = layout(full(), width = 1920f, height = 1080f, density = 1f)
        val twox = layout(full(), width = 1920f, height = 1080f, density = 2f)
        assertTrue(
            "the same surface at twice the density must fit less",
            twox.cards.first().height / 2f < onex.cards.first().height || twox.scrollable
        )
    }

    @Test
    fun `an ultra-wide unit centres the column instead of stretching it`() {
        val layout = layout(full(), width = 3200f, height = 720f)
        val left = layout.hero!!.card.left
        val right = layout.cards.last().right
        assertEquals("the column should be centred", left, 3200f - right, 1f)
    }

    // -------------------------------------------------------------------------- hit testing

    @Test
    fun `each region answers for its own box`() {
        val layout = layout(full())
        val hero = layout.hero!!
        assertEquals(HomeHit(HomeRegion.CONTINUE), layout.hitAt(hero.art))
        assertEquals(HomeHit(HomeRegion.CONTROL_PREVIOUS), layout.hitAt(hero.previous))
        assertEquals(HomeHit(HomeRegion.CONTROL_PLAY), layout.hitAt(hero.play))
        assertEquals(HomeHit(HomeRegion.CONTROL_NEXT), layout.hitAt(hero.next))
        assertEquals(HomeHit(HomeRegion.QUICK_ACCESS, 4), layout.hitAt(layout.cards[4]))
        assertEquals(HomeHit(HomeRegion.RECENT_HEADER), layout.hitAt(layout.recentHeader!!))
        assertEquals(HomeHit(HomeRegion.QUEUE_HEADER), layout.hitAt(layout.queueHeader!!))
        assertEquals(HomeHit(HomeRegion.RECENT_ITEM, 2), layout.hitAt(layout.recentRows[2]))
    }

    @Test
    fun `nothing claims a tap on empty space`() {
        val layout = layout(full())
        // Between the two header targets on the section line.
        val header = layout.recentHeader!!
        val x = (header.right + layout.queueHeader!!.left) / 2f
        assertNull(layout.hit(x, header.centerY, 0f))
    }

    @Test
    fun `no two regions overlap`() {
        val layout = layout(full())
        val boxes = buildList {
            add(layout.hero!!.card)
            addAll(layout.cards)
            layout.recentHeader?.let { add(it) }
            layout.queueHeader?.let { add(it) }
            addAll(layout.recentRows)
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
        val layout = layout(full(), width = 1184f, height = 360f)
        val row = layout.recentRows[0]
        val step = layout.scrollStep
        assertEquals(
            HomeHit(HomeRegion.RECENT_ITEM, 0),
            layout.hit(row.centerX, row.centerY - step, step)
        )
    }

    @Test
    fun `the scroll buttons stay put while the column moves`() {
        val layout = layout(full(), width = 1184f, height = 360f)
        val up = layout.scrollUp!!
        assertEquals(HomeHit(HomeRegion.SCROLL_UP), layout.hit(up.centerX, up.centerY, 0f))
        assertEquals(HomeHit(HomeRegion.SCROLL_UP), layout.hit(up.centerX, up.centerY, layout.maxScroll))
    }

    @Test
    fun `a long hero title cannot move the grid`() {
        val base = layout(full())
        val longTitle = full().let { c -> c.copy(continueWatching = c.continueWatching!!.copy(title = "x".repeat(400))) }
        val stretched = layout(longTitle)
        assertEquals(base.cards.first(), stretched.cards.first())
    }

    private fun HomeDashboardLayout.hitAt(box: MenuBox) = hit(box.centerX, box.centerY, 0f)
}
