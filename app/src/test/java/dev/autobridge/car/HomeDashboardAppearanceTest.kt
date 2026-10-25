package dev.autobridge.car

import dev.autobridge.bridge.BridgeSource
import dev.autobridge.bridge.BridgeStore
import dev.autobridge.bridge.EngineKind
import dev.autobridge.design.AccentColor
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.CardStyle
import dev.autobridge.design.Corners
import dev.autobridge.design.HomeLayout
import dev.autobridge.design.IconStyle
import dev.autobridge.design.TextScaleCurve
import dev.autobridge.design.TextStep
import dev.autobridge.design.ThemeMode
import dev.autobridge.design.ThemeResolver
import dev.autobridge.design.Wallpaper
import dev.autobridge.design.WallpaperTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings > Appearance on the car home: which home is laid out, how far car text grows, and what
 * the look takes from the phone's choices. The geometry of the Dashboard itself is
 * [HomeDashboardLayoutTest].
 */
class HomeDashboardAppearanceTest {

    private val labels = listOf("TV", "Radio", "Web browser", "YouTube", "YouTube Music", "Streaming")
    private val eight = labels + listOf("Multi-app", "Desktop browser")
    private val measure: (String, Float) -> Float = { text, size -> text.length * size * 0.5f }

    private fun look(spec: AppearanceSpec, systemFontScale: Float = 1f) =
        HomeDashboardLook.of(spec, ThemeResolver.resolveCar(spec), systemFontScale)

    private fun content(hero: Boolean): HomeDashboardContent {
        val source = BridgeSource("https://m.youtube.com/watch?v=dQw4w9WgXcQ", "Japan Travel 4K")
        return HomeDashboardContent(
            continueWatching = if (!hero) null else ContinueItem(
                source = source,
                snapshot = BridgeStore.Snapshot(source, EngineKind.BROWSER, 754_000L, 1L, 1_880_000L),
                title = source.title,
                sourceLabel = "YouTube",
                artwork = Artwork(HomeMenuGlyph.YOUTUBE, 0xFFF0848F.toInt(), null),
                positionMs = 754_000L,
                durationMs = 1_880_000L
            )
        )
    }

    private fun layout(
        look: HomeDashboardLook,
        hero: Boolean = true,
        width: Float = 1184f,
        height: Float = 720f,
        cards: List<String> = labels
    ) = HomeDashboardLayout.compute(
        MenuBox(0f, 0f, width, height), 1f, cards, content(hero), measure,
        recentTitle = "Recently sent from phone", queueLabel = "Queue empty", look = look
    )

    private fun spec(layout: HomeLayout) = AppearanceSpec(homeLayout = layout)

    private fun HomeDashboardLayout.hitAt(box: MenuBox) = hit(box.centerX, box.centerY, 0f)

    // ------------------------------------------------------------------------ layout selection

    @Test
    fun `the default look is the dashboard`() {
        val layout = layout(HomeDashboardLook.DEFAULT)
        assertEquals(HomeLayout.DASHBOARD, layout.kind)
        assertNotNull(layout.hero)
        assertNotNull(layout.sectionTitle)
        assertNull(layout.strip)
    }

    @Test
    fun `each home layout setting lays out its own home`() {
        HomeLayout.entries.forEach { choice ->
            assertEquals(choice, layout(look(spec(choice))).kind)
        }
    }

    @Test
    fun `classic shows the features alone, two rows of three for six`() {
        val layout = layout(look(spec(HomeLayout.CLASSIC)))
        assertNull(layout.hero)
        assertNull(layout.emptyHero)
        assertNull(layout.strip)
        assertNull(layout.sectionTitle)
        assertTrue(layout.recentRows.isEmpty())
        assertEquals(3, layout.columns)
        assertEquals(2, layout.cards.map { it.top }.distinct().size)
        assertFalse("six tiles fit a 720 screen", layout.scrollable)
    }

    @Test
    fun `classic puts eight features four across`() {
        val layout = layout(look(spec(HomeLayout.CLASSIC)), cards = eight)
        assertEquals(4, layout.columns)
        assertEquals(2, layout.cards.map { it.top }.distinct().size)
        layout.cards.forEachIndexed { index, box -> assertEquals(HomeHit(HomeRegion.QUICK_ACCESS, index), layout.hitAt(box)) }
    }

    @Test
    fun `classic tiles share the free height`() {
        val layout = layout(look(spec(HomeLayout.CLASSIC)))
        val bottom = layout.cards.maxOf { it.bottom }
        assertTrue("the grid reaches the bottom edge", bottom > 720f - 40f)
    }

    @Test
    fun `hiding labels keeps every tile a target`() {
        val layout = layout(look(spec(HomeLayout.CLASSIC).copy(showTileLabels = false)))
        assertEquals(6, layout.cards.size)
        layout.cards.forEachIndexed { index, box -> assertEquals(HomeHit(HomeRegion.QUICK_ACCESS, index), layout.hitAt(box)) }
    }

    @Test
    fun `compact is two columns of rows and the now playing strip`() {
        val layout = layout(look(spec(HomeLayout.COMPACT)))
        assertEquals(2, layout.columns)
        assertEquals(3, layout.cards.map { it.top }.distinct().size)
        val strip = layout.strip!!
        assertNull(layout.hero)
        assertTrue("the strip sits under the rows", strip.card.top > layout.cards.maxOf { it.bottom })
        assertEquals(HomeHit(HomeRegion.CONTROL_PLAY), layout.hitAt(strip.play))
        assertEquals(HomeHit(HomeRegion.CONTINUE), layout.hitAt(strip.text))
        layout.cards.forEachIndexed { index, box -> assertEquals(HomeHit(HomeRegion.QUICK_ACCESS, index), layout.hitAt(box)) }
    }

    @Test
    fun `compact with nothing playing shows the strip's outline, not a target`() {
        val layout = layout(look(spec(HomeLayout.COMPACT)), hero = false)
        assertNull(layout.strip)
        val outline = layout.emptyStrip!!
        assertNull(layout.hit(outline.centerX, outline.centerY, 0f))
    }

    @Test
    fun `the strip does not move between nothing playing and something playing`() {
        val look = look(spec(HomeLayout.COMPACT))
        assertEquals(layout(look, hero = false).emptyStrip, layout(look, hero = true).strip!!.card)
        assertEquals(layout(look, hero = false).cards, layout(look, hero = true).cards)
    }

    @Test
    fun `classic and compact scroll on a short unit instead of crushing their targets`() {
        listOf(HomeLayout.CLASSIC, HomeLayout.COMPACT).forEach { choice ->
            // Shorter than the DHU's small profile (776 x 300 under the host's chrome).
            val layout = layout(look(spec(choice)), width = 776f, height = 220f, cards = eight)
            val minimum = if (choice == HomeLayout.CLASSIC) HomeDashboardTheme.Dp.CLASSIC_TILE_MIN else HomeDashboardTheme.Dp.COMPACT_ROW_MIN
            layout.cards.forEach { assertTrue("$choice ${it.height}", it.height >= minimum * layout.unit - 0.5f) }
            assertTrue(choice.name, layout.scrollable)
            assertNotNull(layout.scrollDown)
            // Scrolled to the end, the last card is inside the viewport and answers for itself.
            val last = layout.cards.last()
            assertEquals(
                HomeHit(HomeRegion.QUICK_ACCESS, layout.cards.lastIndex),
                layout.hit(last.centerX, last.centerY - layout.maxScroll, layout.maxScroll)
            )
        }
    }

    @Test
    fun `the DHU small profile keeps classic whole and its tiles above the minimum`() {
        val layout = layout(look(spec(HomeLayout.CLASSIC)), width = 776f, height = 300f, cards = eight)
        assertFalse(layout.scrollable)
        layout.cards.forEach { assertTrue(it.height >= HomeDashboardTheme.Dp.CLASSIC_TILE_MIN * layout.unit) }
    }

    @Test
    fun `no two targets overlap in any home`() {
        HomeLayout.entries.forEach { choice ->
            val layout = layout(look(spec(choice)), cards = eight)
            val boxes = layout.cards + listOfNotNull(layout.strip?.card)
            boxes.forEachIndexed { i, a ->
                boxes.drop(i + 1).forEach { b ->
                    val overlap = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
                    assertFalse("$choice: $a overlaps $b", overlap)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------ text cap

    @Test
    fun `car text follows the phone and stops at 130 percent`() {
        assertEquals(1f, look(AppearanceSpec()).textScale, 0f)
        assertEquals(1.15f, look(AppearanceSpec(textStep = TextStep.LARGE)).textScale, 0f)
        assertEquals(TextScaleCurve.CAR_CAP, look(AppearanceSpec(textStep = TextStep.HUGE)).textScale, 0f)
        assertEquals(TextScaleCurve.CAR_CAP, look(AppearanceSpec(textStep = TextStep.MAX)).textScale, 0f)
        assertEquals(
            "following Android's size is capped too",
            TextScaleCurve.CAR_CAP, look(AppearanceSpec(followSystemText = true), systemFontScale = 2f).textScale, 0f
        )
        assertEquals(0.85f, look(AppearanceSpec(followSystemText = true), systemFontScale = 0.85f).textScale, 0f)
    }

    @Test
    fun `layout text grows with the scale up to the cap and no further`() {
        listOf(HomeLayout.DASHBOARD, HomeLayout.CLASSIC, HomeLayout.COMPACT).forEach { choice ->
            fun at(step: TextStep) = layout(look(AppearanceSpec(homeLayout = choice, textStep = step)))
            val normal = at(TextStep.DEFAULT)
            val larger = at(TextStep.LARGER)
            val huge = at(TextStep.HUGE)
            assertTrue("$choice title grows", larger.titleSize > normal.titleSize)
            assertTrue("$choice labels grow", larger.labelSize > normal.labelSize)
            assertEquals("$choice title stops", larger.titleSize, huge.titleSize, 0.001f)
            assertEquals("$choice labels stop", larger.labelSize, huge.labelSize, 0.001f)
            assertEquals(larger.sp(20f), huge.sp(20f), 0.001f)
        }
    }

    @Test
    fun `the cap follows the text curve, so large text grows less`() {
        val layout = layout(look(AppearanceSpec(textStep = TextStep.HUGE)))
        assertEquals(TextScaleCurve.scaled(14f, 1.3f) * layout.unit, layout.sp(14f), 0.001f)
        assertTrue(layout.sp(36f) / layout.dp(36f) < layout.sp(14f) / layout.dp(14f))
    }

    @Test
    fun `the dashboard keeps its grid in place when text grows`() {
        val normal = layout(look(AppearanceSpec()))
        val big = layout(look(AppearanceSpec(textStep = TextStep.HUGE)))
        assertEquals(normal.cards, big.cards)
    }

    // --------------------------------------------------------------------------- the look

    @Test
    fun `turning off the same look gives the car the whole default look`() {
        val phone = AppearanceSpec(
            mode = ThemeMode.LIGHT, accent = AccentColor.ROSE, homeLayout = HomeLayout.CLASSIC,
            iconStyle = IconStyle.MONO, cardStyle = CardStyle.OUTLINED, corners = Corners.SHARP,
            wallpaper = Wallpaper.LINES, textStep = TextStep.HUGE, carSameLook = false
        )
        assertEquals(HomeDashboardLook.DEFAULT, look(phone))
    }

    @Test
    fun `the car takes the phone's choices when the same look is on`() {
        val phone = AppearanceSpec(
            mode = ThemeMode.LIGHT, accent = AccentColor.ROSE, homeLayout = HomeLayout.COMPACT,
            iconStyle = IconStyle.MONO, cardStyle = CardStyle.OUTLINED, corners = Corners.SHARP
        )
        val look = look(phone)
        assertTrue(look.palette.isLight)
        assertEquals(AccentColor.ROSE.onLight, look.accent)
        assertEquals(HomeLayout.COMPACT, look.homeLayout)
        assertEquals(Corners.SHARP.card.toFloat(), look.cardRadius, 0f)
        assertEquals("an outlined card has no fill", 0, look.card ushr 24)
        assertTrue(look.hasCardEdge)
        assertNotEquals("its press still shows", 0, look.cardPressed ushr 24)
    }

    @Test
    fun `the wallpaper is drawn on the car only when it targets the car`() {
        assertEquals(Wallpaper.GLOW, look(AppearanceSpec(wallpaperTarget = WallpaperTarget.BOTH)).wallpaper)
        assertEquals(Wallpaper.GLOW, look(AppearanceSpec(wallpaperTarget = WallpaperTarget.CAR)).wallpaper)
        assertEquals(Wallpaper.NONE, look(AppearanceSpec(wallpaperTarget = WallpaperTarget.PHONE)).wallpaper)
    }

    @Test
    fun `icon styles paint the chip as on the phone`() {
        val radio = HomeDashboardTheme.ACCENT_RADIO
        val tinted = look(AppearanceSpec(iconStyle = IconStyle.TINTED)).chip(radio)
        assertEquals("tinted keeps the feature's hue", radio, tinted.glyph)
        assertNotEquals(tinted.glyph, tinted.fill)

        val accentLook = look(AppearanceSpec(iconStyle = IconStyle.ACCENT))
        val accent = accentLook.chip(radio)
        assertEquals(accentLook.accent, accent.fill)
        assertEquals(accentLook.onAccent, accent.glyph)

        val monoLook = look(AppearanceSpec(iconStyle = IconStyle.MONO))
        val mono = monoLook.chip(radio)
        assertEquals(monoLook.control, mono.fill)
        assertEquals(monoLook.textPrimary, mono.glyph)

        listOf(tinted, accent, mono).forEach { assertNotEquals("the play mark stays visible", it.glyph, it.cutout) }
    }

    @Test
    fun `a look change is a different look, so the layout is rebuilt`() {
        val before = look(AppearanceSpec())
        val after = look(AppearanceSpec(accent = AccentColor.TEAL))
        assertNotEquals(before, after)
        assertEquals(before, look(AppearanceSpec()))
        assertEquals(after, layout(after).look)
    }
}
