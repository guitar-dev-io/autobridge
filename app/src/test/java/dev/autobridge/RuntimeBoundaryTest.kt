package dev.autobridge

import dev.autobridge.core.model.AutoBridgeMode
import dev.autobridge.core.model.Environment
import dev.autobridge.entertainment.ContentKind
import dev.autobridge.entertainment.ContentKindResolver
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.display.ScreenOffController
import dev.autobridge.input.InputCapability
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.input.ShizukuInputCommand
import dev.autobridge.input.ShizukuRealTouchController
import dev.autobridge.media.MediaControllerAuthorization
import dev.autobridge.mirror.ReconnectTracker
import dev.autobridge.safety.DevModeEvaluator
import dev.autobridge.settings.MirrorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBoundaryTest {
    @Test
    fun devModeRequiresLabDebugEmulatorAndNonRealEnvironment() {
        assertTrue(DevModeEvaluator.isEnabled(AutoBridgeMode.LAB, Environment.EMULATOR, true, true))
        assertFalse(DevModeEvaluator.isEnabled(AutoBridgeMode.LAB, Environment.EMULATOR, false, true))
        assertFalse(DevModeEvaluator.isEnabled(AutoBridgeMode.LAB, Environment.EMULATOR, true, false))
        assertFalse(DevModeEvaluator.isEnabled(AutoBridgeMode.LAB, Environment.REAL_CAR, true, true))
        assertFalse(DevModeEvaluator.isEnabled(AutoBridgeMode.PERSONAL, Environment.EMULATOR, true, true))
    }

    @Test
    fun contentKindSeparatesWebAudioAndVideo() {
        assertEquals(ContentKind.WEB, ContentKindResolver.classify("https://example.com/page"))
        assertEquals(ContentKind.AUDIO, ContentKindResolver.classify("file:///sdcard/music/song.mp3"))
        assertEquals(ContentKind.AUDIO, ContentKindResolver.classify("content://media/1", "audio/mpeg"))
        assertEquals(ContentKind.VIDEO, ContentKindResolver.classify("https://example.com/live.m3u8?token=x"))
        assertEquals(ContentKind.VIDEO, ContentKindResolver.classify("content://media/2", "video/mp4"))
        assertEquals(null, ContentKindResolver.classify("content://media/unknown"))
    }

    @Test
    fun mediaControllerAuthorizationRejectsUnknownCallers() {
        assertTrue(
            MediaControllerAuthorization.isAllowed(
                packageName = "dev.autobridge",
                uid = 20_001,
                ownPackageName = "dev.autobridge",
                ownUid = 20_001,
                systemUid = 1_000
            )
        )
        assertTrue(
            MediaControllerAuthorization.isAllowed(
                packageName = "com.google.android.projection.gearhead",
                uid = 30_001,
                ownPackageName = "dev.autobridge",
                ownUid = 20_001,
                systemUid = 1_000
            )
        )
        assertTrue(
            MediaControllerAuthorization.isAllowed(
                packageName = "com.example.system-controller",
                uid = 1_000,
                ownPackageName = "dev.autobridge",
                ownUid = 20_001,
                systemUid = 1_000
            )
        )
        assertFalse(
            MediaControllerAuthorization.isAllowed(
                packageName = "com.example.unknown",
                uid = 30_001,
                ownPackageName = "dev.autobridge",
                ownUid = 20_001,
                systemUid = 1_000
            )
        )
    }

    @Test
    fun shizukuCommandsHaveExplicitVectors() {
        assertEquals(
            listOf("tap", "10", "20"),
            ShizukuInputCommand.args("tap", "10", "20")
        )
        assertEquals(
            listOf("swipe", "1", "2", "3", "4", "250"),
            ShizukuInputCommand.args("swipe", "1", "2", "3", "4", "250")
        )
    }

    @Test
    fun privilegedCapabilitiesFailClosedWithoutRemoteService() {
        assertFalse(ShizukuInputBackend.isPanelPowerAvailable)
        assertFalse(ShizukuInputBackend.isRealTouchAvailable)
        assertFalse(InputCapability.REAL_TOUCH in ShizukuInputBackend.capabilities)
        assertEquals("Real-touch injection unavailable", dev.autobridge.input.TouchRouter.rawTouchStatusLabel())
    }

    @Test
    fun privilegedSettingsDefaultToDisabled() {
        assertFalse(MirrorSettings.screenOffOnAutoDim)
        assertFalse(MirrorSettings.realTouchEnabled)
        assertFalse(ScreenOffController.isAvailable(ScreenOffController.PipelineMode.OWN_CONTENT))
    }

    @Test
    fun rawPointerValidationRejectsUnsafeRequestsBeforeInjection() {
        assertFalse(ShizukuRealTouchController.touchDown(-1, 0, 0))
        assertFalse(ShizukuRealTouchController.touchDown(32, 0, 0))
        assertFalse(ShizukuRealTouchController.touchDown(0, -1, 0))
        assertFalse(ShizukuRealTouchController.touchDown(0, 10_001, 0))
        assertFalse(ShizukuRealTouchController.touchMove(intArrayOf(), intArrayOf(), intArrayOf()))
        assertFalse(ShizukuRealTouchController.touchMove(intArrayOf(0), intArrayOf(), intArrayOf()))
        assertFalse(ShizukuRealTouchController.touchUp(0, 0, 0))
        assertTrue(ShizukuRealTouchController.touchCancel())
    }

    @Test
    fun diagnosticsFormatAndClearPreserveActiveClock() {
        MirrorDiagnostics.reset()
        MirrorDiagnostics.record("consent_received", nowMs = 100)
        MirrorDiagnostics.record("car_surface_attached", nowMs = 250)
        assertEquals(
            "consent_received @ 100ms\ncar_surface_attached @ 250ms",
            MirrorDiagnostics.format()
        )
        assertEquals("car_surface_attached @ 250ms", MirrorDiagnostics.format(limit = 1))

        MirrorDiagnostics.onMirroringActiveChanged(true, nowMs = 300)
        MirrorDiagnostics.clearEvents()
        assertTrue(MirrorDiagnostics.recent().isEmpty())
        assertEquals(75L, MirrorDiagnostics.currentMirroringUptimeMs(nowMs = 375))
        assertEquals("", MirrorDiagnostics.format())
        MirrorDiagnostics.reset()
    }

    @Test
    fun reconnectTrackerDistinguishesFreshDuplicateAndResume() {
        ReconnectTracker.reset()
        assertEquals(ReconnectTracker.Outcome.FRESH_START, ReconnectTracker.onSurfaceArrived(projectionAlive = true))
        assertEquals(
            ReconnectTracker.Outcome.UNCHANGED,
            ReconnectTracker.onSurfaceArrived(projectionAlive = true, surfaceChanged = false)
        )
        assertEquals(0, ReconnectTracker.reconnectCount)
        ReconnectTracker.onSurfaceDetached()
        assertEquals(ReconnectTracker.Outcome.RESUMED, ReconnectTracker.onSurfaceArrived(projectionAlive = true))
        assertEquals(1, ReconnectTracker.reconnectCount)
        ReconnectTracker.reset()
    }
}
