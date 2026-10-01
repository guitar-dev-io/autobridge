package dev.autobridge.library

import dev.autobridge.media.VideoAspect
import dev.autobridge.settings.AspectRatio
import dev.autobridge.settings.ChannelGesture
import dev.autobridge.settings.PlayerDpi
import dev.autobridge.settings.PreferredPlayer
import dev.autobridge.settings.SplitLayout
import dev.autobridge.settings.VideoEnhancement
import dev.autobridge.settings.VideoSettings
import dev.autobridge.settings.VideoSettingsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the video settings that are arithmetic or decoding, covered without a player, a
 * Context or a surface.
 */
class VideoSettingsTest {

    @Test
    fun `a missing or renamed stored value falls back instead of throwing`() {
        assertEquals(PreferredPlayer.AUTO, VideoSettingsCodec.preferredPlayer(null))
        assertEquals(PreferredPlayer.AUTO, VideoSettingsCodec.preferredPlayer("VLC"))
        assertEquals(PreferredPlayer.SOFTWARE, VideoSettingsCodec.preferredPlayer("SOFTWARE"))

        assertEquals(ChannelGesture.SWIPE_VERTICAL, VideoSettingsCodec.channelGesture(null))
        assertEquals(ChannelGesture.OFF, VideoSettingsCodec.channelGesture("OFF"))

        assertEquals(SplitLayout.MAIN_SUB, VideoSettingsCodec.splitLayout(null))
        assertEquals(SplitLayout.SIDE_BY_SIDE, VideoSettingsCodec.splitLayout("SIDE_BY_SIDE"))

        assertEquals(AspectRatio.AUTO, VideoSettingsCodec.aspectRatio(null))
        assertEquals(AspectRatio.CLASSIC, VideoSettingsCodec.aspectRatio("CLASSIC"))

        assertEquals(PlayerDpi.DEFAULT, VideoSettingsCodec.playerDpi(null))
        assertEquals(PlayerDpi.LARGE, VideoSettingsCodec.playerDpi("LARGE"))
    }

    @Test
    fun `enhancement is neutral unless it is switched on and moved off zero`() {
        assertTrue(VideoEnhancement(enabled = false, brightness = 40, contrast = 0, saturation = 0).isNeutral)
        assertTrue(VideoEnhancement(enabled = true, brightness = 0, contrast = 0, saturation = 0).isNeutral)
        assertFalse(VideoEnhancement(enabled = true, brightness = 0, contrast = -5, saturation = 0).isNeutral)
    }

    @Test
    fun `slider values convert to what each effect takes`() {
        val enhancement = VideoEnhancement(enabled = true, brightness = 30, contrast = 50, saturation = -20)
        // HSL works in the same -100..100 the sliders do; contrast is -1..1.
        assertEquals(30f, enhancement.lightnessAdjustment, 0.0001f)
        assertEquals(-20f, enhancement.saturationAdjustment, 0.0001f)
        assertEquals(0.5f, enhancement.contrastAdjustment, 0.0001f)
        assertEquals(100, VideoSettings.ADJUSTMENT_RANGE)
    }

    @Test
    fun `auto letterboxes a widescreen frame into a squarer box`() {
        // 16:9 into a 4:3-ish box: full width, shorter than the box, nothing cropped.
        val size = VideoAspect.layout(AspectRatio.AUTO, 1920, 1080, 800, 600)
        assertEquals(800, size.width)
        assertEquals(450, size.height)
    }

    @Test
    fun `auto pillarboxes a tall frame`() {
        val size = VideoAspect.layout(AspectRatio.AUTO, 720, 1280, 800, 600)
        assertEquals(338, size.width)
        assertEquals(600, size.height)
    }

    @Test
    fun `fill overflows the box so the crop happens at the edges`() {
        val size = VideoAspect.layout(AspectRatio.FILL, 1920, 1080, 800, 600)
        assertEquals(1067, size.width)
        assertEquals(600, size.height)
        assertTrue("fill has to overflow, not fit", size.width > 800)
    }

    @Test
    fun `stretch takes the box exactly, whatever the source shape is`() {
        assertEquals(VideoAspect.Size(800, 600), VideoAspect.layout(AspectRatio.STRETCH, 1920, 1080, 800, 600))
        assertEquals(VideoAspect.Size(800, 600), VideoAspect.layout(AspectRatio.STRETCH, 0, 0, 800, 600))
    }

    @Test
    fun `a forced ratio ignores the frame's own shape`() {
        assertEquals(VideoAspect.Size(800, 450), VideoAspect.layout(AspectRatio.WIDE, 720, 576, 800, 600))
        assertEquals(VideoAspect.Size(800, 600), VideoAspect.layout(AspectRatio.CLASSIC, 1920, 1080, 800, 600))
    }

    @Test
    fun `an unknown frame size or an unmeasured box is left alone`() {
        // The first seconds of a stream report no size; filling the box is what the view did
        // before any aspect ratio existed, so the picture does not jump when the size arrives.
        assertEquals(VideoAspect.Size(800, 600), VideoAspect.layout(AspectRatio.AUTO, 0, 0, 800, 600))
        assertEquals(VideoAspect.Size(0, 0), VideoAspect.layout(AspectRatio.AUTO, 1920, 1080, 0, 0))
    }
}
