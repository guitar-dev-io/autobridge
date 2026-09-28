package dev.autobridge

import android.view.Surface
import dev.autobridge.apps.FavoriteAppsListing
import dev.autobridge.apps.InstalledApp
import dev.autobridge.apps.InstalledAppRepository
import dev.autobridge.apps.QuickAppLauncher
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.display.OrientationMonitor
import dev.autobridge.display.PerAppDisplayController
import dev.autobridge.display.ScreenOffController
import dev.autobridge.display.StructuredLog
import dev.autobridge.display.SurfaceProfile
import dev.autobridge.input.CoordinateMapper
import dev.autobridge.input.DisplayProfile
import dev.autobridge.input.DisplayTransform
import dev.autobridge.input.PinchGeometry
import dev.autobridge.media.MediaSourceResolver
import dev.autobridge.mirror.ReconnectTracker
import dev.autobridge.safety.ParkingStateStore.State
import dev.autobridge.safety.SpeedPolicy
import dev.autobridge.settings.SettingsCodec
import org.junit.Assert.*
import org.junit.Test

class CoreRegressionTest {
    @Test fun zeroAndSensorNoiseAreParked() {
        listOf(0f, -0f, 0.001f, -0.001f, 0.049f, -0.049f).forEach {
            assertEquals(State.PARKED, SpeedPolicy.classify(it))
        }
    }
    @Test fun atOrAboveEpsilonIsMoving() {
        listOf(0.05f, -0.05f, 0.3f, -25f).forEach { assertEquals(State.MOVING, SpeedPolicy.classify(it)) }
    }
    @Test fun invalidSpeedIsUnknown() {
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(State.UNKNOWN, SpeedPolicy.classify(it))
        }
    }
    @Test fun portraitCenterMapsToPhoneCenter() {
        val point = CoordinateMapper.mapFit(400f, 240f, 800, 480, 1080, 1920)!!
        assertEquals(540f, point.x, 0.01f)
        assertEquals(960f, point.y, 0.01f)
    }
    @Test fun rejectsBarsAndInvalidCoordinates() {
        assertNull(CoordinateMapper.mapFit(0f, 240f, 800, 480, 1080, 1920))
        assertNull(CoordinateMapper.mapFit(Float.NaN, 240f, 800, 480, 1080, 1920))
        assertNull(CoordinateMapper.mapFit(400f, Float.POSITIVE_INFINITY, 800, 480, 1080, 1920))
        assertNull(CoordinateMapper.mapFit(0f, 0f, 0, 480, 1080, 1920))
    }
    @Test fun landscapeEdgeStaysWithinPhonePixels() {
        val point = CoordinateMapper.mapFit(800f, 465f, 800, 480, 1920, 1080)!!
        assertEquals(1919f, point.x, 0.01f)
        assertEquals(1079f, point.y, 0.01f)
    }

    @Test fun pinchScaleAboveOneSpreadsFingersApart() {
        val gesture = PinchGeometry.forPinch(540f, 960f, 2f, 1080, 1920)!!
        val fromSpan = gesture.bFromX - gesture.aFromX
        val toSpan = gesture.bToX - gesture.aToX
        assertTrue("expected fingers to spread apart on zoom-in", toSpan > fromSpan)
    }

    @Test fun pinchScaleBelowOneBringsFingersTogether() {
        val gesture = PinchGeometry.forPinch(540f, 960f, 0.5f, 1080, 1920)!!
        val fromSpan = gesture.bFromX - gesture.aFromX
        val toSpan = gesture.bToX - gesture.aToX
        assertTrue("expected fingers to come together on zoom-out", toSpan < fromSpan)
    }

    @Test fun pinchStaysWithinPhoneBoundsNearEdge() {
        val gesture = PinchGeometry.forPinch(5f, 960f, 3f, 1080, 1920)!!
        listOf(gesture.aFromX, gesture.aToX, gesture.bFromX, gesture.bToX).forEach {
            assertTrue("expected x within [0, 1079], got $it", it in 0f..1079f)
        }
    }

    @Test fun pinchRejectsInvalidInputs() {
        assertNull(PinchGeometry.forPinch(Float.NaN, 960f, 2f, 1080, 1920))
        assertNull(PinchGeometry.forPinch(540f, 960f, Float.POSITIVE_INFINITY, 1080, 1920))
        assertNull(PinchGeometry.forPinch(540f, 960f, 2f, 0, 1920))
    }

    @Test fun displayTransformFitMatchesCoordinateMapper() {
        val expected = CoordinateMapper.mapFit(400f, 240f, 800, 480, 1080, 1920)!!
        val actual = DisplayTransform.mapPoint(400f, 240f, 800, 480, 1080, 1920, DisplayProfile.FIT)!!
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
    }

    @Test fun displayTransformFillAndStretchUseViewport() {
        assertNotNull(
            DisplayTransform.mapPoint(400f, 240f, 800, 480, 1080, 1920, DisplayProfile.FILL)
        )
        assertNotNull(
            DisplayTransform.mapPoint(400f, 240f, 800, 480, 1080, 1920, DisplayProfile.STRETCH)
        )
    }

    @Test fun displayTransformHonorsVisibleAreaAndSafeInsets() {
        val info = DisplayTransform.resolve(
            carWidth = 1000,
            carHeight = 600,
            phoneWidth = 1000,
            phoneHeight = 600,
            profile = DisplayProfile.STRETCH,
            safeInsets = dev.autobridge.core.model.Insets(left = 100, top = 20),
            visibleBounds = dev.autobridge.input.ContentBounds(200f, 50f, 900f, 550f)
        )!!
        assertNull(info.mapPoint(150f, 100f))
        val center = info.mapPoint(550f, 300f)!!
        assertTrue(kotlin.math.abs(center.x - 500f) <= 0.01f)
        assertTrue(kotlin.math.abs(center.y - 300f) <= 0.01f)
    }

    @Test fun displayTransformHonorsTouchOffsetsAndDebugCoordinates() {
        val info = DisplayTransform.resolve(
            carWidth = 1000,
            carHeight = 500,
            phoneWidth = 1000,
            phoneHeight = 500,
            profile = DisplayProfile.STRETCH,
            touchOffsetX = 10f,
            touchOffsetY = -10f
        )!!
        val debug = info.debugMap(500f, 250f)!!
        assertEquals(0.5f, debug.normalizedX, 0.01f)
        assertEquals(0.5f, debug.normalizedY, 0.01f)
        assertEquals(510f, debug.phoneX, 0.01f)
        assertEquals(240f, debug.phoneY, 0.01f)
    }

    @Test fun contentBoundsIntersectionRejectsDisjointAreas() {
        val surface = dev.autobridge.input.ContentBounds.surface(800, 480)!!
        assertEquals(
            dev.autobridge.input.ContentBounds(100f, 50f, 700f, 400f),
            surface.intersect(dev.autobridge.input.ContentBounds(100f, 50f, 700f, 400f))
        )
        assertNull(
            surface.intersect(dev.autobridge.input.ContentBounds(900f, 50f, 1000f, 400f))
        )
    }

    @Test fun displayTransformRotatesPortraitPhoneIntoLandscape() {
        val info = DisplayTransform.resolve(
            carWidth = 1920,
            carHeight = 1080,
            phoneWidth = 1080,
            phoneHeight = 1920,
            profile = DisplayProfile.STRETCH,
            rotationMode = dev.autobridge.core.model.RotationMode.LANDSCAPE
        )!!
        val center = info.mapPoint(960f, 540f)!!
        assertTrue(kotlin.math.abs(center.x - 540f) <= 1f)
        assertTrue(kotlin.math.abs(center.y - 959f) <= 1f)
    }

    @Test fun orientationLabelsKnownRotations() {
        assertEquals("0°", OrientationMonitor.label(Surface.ROTATION_0))
        assertEquals("90°", OrientationMonitor.label(Surface.ROTATION_90))
        assertEquals("180°", OrientationMonitor.label(Surface.ROTATION_180))
        assertEquals("270°", OrientationMonitor.label(Surface.ROTATION_270))
        assertEquals("unknown", OrientationMonitor.label(99))
    }

    @Test fun diagnosticsTracksMirroringUptime() {
        MirrorDiagnostics.reset()
        assertNull(MirrorDiagnostics.currentMirroringUptimeMs(1_000))
        MirrorDiagnostics.onMirroringActiveChanged(true, nowMs = 1_000)
        assertEquals(500L, MirrorDiagnostics.currentMirroringUptimeMs(1_500))
        MirrorDiagnostics.onMirroringActiveChanged(false, nowMs = 1_800)
        assertNull(MirrorDiagnostics.currentMirroringUptimeMs(2_000))
    }

    @Test fun diagnosticsComputesLatencyBetweenEvents() {
        MirrorDiagnostics.reset()
        MirrorDiagnostics.record("consent_received", nowMs = 100)
        MirrorDiagnostics.onMirroringActiveChanged(true, nowMs = 340)
        assertEquals(240L, MirrorDiagnostics.latencyBetween("consent_received", "mirroring_active"))
    }

    @Test fun diagnosticsLatencyIsNullWhenEventsAreMissing() {
        MirrorDiagnostics.reset()
        assertNull(MirrorDiagnostics.latencyBetween("consent_received", "mirroring_active"))
    }

    @Test fun diagnosticsRepeatedActiveTrueIsIdempotent() {
        MirrorDiagnostics.reset()
        MirrorDiagnostics.onMirroringActiveChanged(true, nowMs = 1_000)
        MirrorDiagnostics.onMirroringActiveChanged(true, nowMs = 1_200)
        // The second "active" call must not reset the uptime clock.
        assertEquals(500L, MirrorDiagnostics.currentMirroringUptimeMs(1_500))
    }

    @Test fun surfaceProfileSwitchIsReadBack() {
        val original = SurfaceProfile.active
        try {
            SurfaceProfile.active = SurfaceProfile.FORD_NEXT_GEN
            assertEquals(SurfaceProfile.FORD_NEXT_GEN, SurfaceProfile.active)
            assertEquals(160, SurfaceProfile.active.fallbackDpi)
        } finally {
            SurfaceProfile.active = original
        }
    }

    @Test fun fordProfileIsHonestlyMarkedUnverified() {
        // Guard against silently shipping placeholder Ford values as if they were tuned:
        // DEFAULT is validated, FORD_NEXT_GEN must stay unverified until a real session flips it.
        assertTrue(SurfaceProfile.DEFAULT.hardwareValidated)
        assertFalse(SurfaceProfile.FORD_NEXT_GEN.hardwareValidated)
    }

    @Test fun installedAppsAreDeduplicated() {
        val apps = listOf(
            InstalledApp("com.a", "Alpha"),
            InstalledApp("com.a", "Alpha (duplicate resolve)")
        )
        val prepared = InstalledAppRepository.prepare(apps, excludePackage = "dev.autobridge")
        assertEquals(1, prepared.size)
    }

    @Test fun installedAppsExcludeOwnPackage() {
        val apps = listOf(InstalledApp("dev.autobridge", "AutoBridge"), InstalledApp("com.b", "Bravo"))
        val prepared = InstalledAppRepository.prepare(apps, excludePackage = "dev.autobridge")
        assertEquals(listOf(InstalledApp("com.b", "Bravo")), prepared)
    }

    @Test fun installedAppsAreSortedCaseInsensitively() {
        val apps = listOf(
            InstalledApp("com.z", "zebra"),
            InstalledApp("com.a", "Apple"),
            InstalledApp("com.m", "mango")
        )
        val prepared = InstalledAppRepository.prepare(apps, excludePackage = "dev.autobridge")
        assertEquals(listOf("Apple", "mango", "zebra"), prepared.map { it.label })
    }

    @Test fun favoritesResolveOnlyInstalledApps() {
        val installed = listOf(InstalledApp("com.a", "Alpha"), InstalledApp("com.b", "Bravo"))
        val favorites = setOf("com.a")
        assertEquals(listOf(InstalledApp("com.a", "Alpha")), FavoriteAppsListing.resolve(installed, favorites))
    }

    @Test fun favoritesDropStaleUninstalledPackages() {
        val installed = listOf(InstalledApp("com.a", "Alpha"))
        // "com.uninstalled" was favorited before but is no longer in the installed list.
        val favorites = setOf("com.a", "com.uninstalled")
        assertEquals(listOf(InstalledApp("com.a", "Alpha")), FavoriteAppsListing.resolve(installed, favorites))
    }

    @Test fun noFavoritesResolvesEmpty() {
        val installed = listOf(InstalledApp("com.a", "Alpha"))
        assertEquals(emptyList<InstalledApp>(), FavoriteAppsListing.resolve(installed, emptySet()))
    }

    @Test fun quickAppLaunchIsParkedOnly() {
        assertTrue(QuickAppLauncher.canLaunch(isParked = true))
        assertFalse(QuickAppLauncher.canLaunch(isParked = false))
    }

    @Test fun mediaResolverDetectsHlsAndDash() {
        assertEquals(MediaSourceResolver.SourceType.HLS, MediaSourceResolver.resolve("https://x/y.m3u8")!!.type)
        assertEquals(MediaSourceResolver.SourceType.DASH, MediaSourceResolver.resolve("https://x/y.mpd")!!.type)
        // Adaptive manifests are often served with a query string appended.
        assertEquals(MediaSourceResolver.SourceType.HLS, MediaSourceResolver.resolve("https://x/y.m3u8?token=abc")!!.type)
        assertEquals(MediaSourceResolver.SourceType.DASH, MediaSourceResolver.resolve("https://x/y.MPD#frag")!!.type)
    }

    @Test fun mediaResolverClassifiesProgressiveRemote() {
        val resolved = MediaSourceResolver.resolve("https://x/song.mp3")!!
        assertEquals(MediaSourceResolver.SourceType.PROGRESSIVE, resolved.type)
        assertEquals("https://x/song.mp3", resolved.uri)
    }

    @Test fun mediaResolverNormalizesBareLocalPathToFileUri() {
        val resolved = MediaSourceResolver.resolve("/sdcard/Music/track.mp3")!!
        assertEquals(MediaSourceResolver.SourceType.LOCAL, resolved.type)
        assertEquals("file:///sdcard/Music/track.mp3", resolved.uri)
    }

    @Test fun mediaResolverTreatsFileAndContentSchemesAsLocal() {
        assertEquals(MediaSourceResolver.SourceType.LOCAL, MediaSourceResolver.resolve("file:///a/b.mp4")!!.type)
        assertEquals(MediaSourceResolver.SourceType.LOCAL, MediaSourceResolver.resolve("content://media/1")!!.type)
    }

    @Test fun mediaResolverLocalHlsStillDetectedAsHls() {
        // A local adaptive manifest should still route to the HLS source, not generic local.
        assertEquals(MediaSourceResolver.SourceType.HLS, MediaSourceResolver.resolve("/sdcard/stream.m3u8")!!.type)
    }

    @Test fun mediaResolverRejectsBlankInput() {
        assertNull(MediaSourceResolver.resolve(null))
        assertNull(MediaSourceResolver.resolve("   "))
    }

    @Test fun screenOffOnlySurvivesOnOwnContentPipeline() {
        assertFalse(ScreenOffController.survivesScreenOff(ScreenOffController.PipelineMode.AUTO_MIRROR))
        assertTrue(ScreenOffController.survivesScreenOff(ScreenOffController.PipelineMode.OWN_CONTENT))
    }

    @Test fun screenOffStatusLabelReflectsMode() {
        assertTrue(
            ScreenOffController.statusLabel(ScreenOffController.PipelineMode.AUTO_MIRROR).contains("pauses")
        )
        assertTrue(
            ScreenOffController.statusLabel(ScreenOffController.PipelineMode.OWN_CONTENT).contains("continues")
        )
    }

    @Test fun selfDrawnPipelineIsAvailableButDoesNotPromiseScreenOff() {
        assertTrue(ScreenOffController.isAvailable(ScreenOffController.PipelineMode.SELF_DRAWN))
        assertFalse(ScreenOffController.isAvailable(ScreenOffController.PipelineMode.OWN_CONTENT))
        assertFalse(ScreenOffController.survivesScreenOff(ScreenOffController.PipelineMode.SELF_DRAWN))
    }

    @Test fun frameDiagnosticsSeparatesCapturedDroppedAndRendered() {
        MirrorDiagnostics.resetFrameStats()
        MirrorDiagnostics.recordFrameCaptured()
        MirrorDiagnostics.recordFrameDropped()
        MirrorDiagnostics.recordFrameRendered(latencyMs = 24L, nowMs = 1_000L)
        val stats = MirrorDiagnostics.frameStats()
        assertEquals(1L, stats.captured)
        assertEquals(1L, stats.dropped)
        assertEquals(1L, stats.rendered)
        assertEquals(24L, stats.lastLatencyMs)
    }

    @Test fun perAppLaunchRequiresParkedAndNonDefaultDisplay() {
        // Fail-closed: must be parked AND target a real virtual display (id > 0, not the phone's own display 0).
        assertTrue(PerAppDisplayController.canLaunchOnDisplay(isParked = true, displayId = 2))
        assertFalse(PerAppDisplayController.canLaunchOnDisplay(isParked = false, displayId = 2))
        assertFalse(PerAppDisplayController.canLaunchOnDisplay(isParked = true, displayId = 0))
        assertFalse(PerAppDisplayController.canLaunchOnDisplay(isParked = true, displayId = -1))
    }

    // Build Entry lists directly rather than calling StructuredLog.log(), which touches android.util.Log.
    private fun logEntry(level: StructuredLog.Level, tag: String, message: String) =
        StructuredLog.Entry(level, tag, message, elapsedRealtimeMs = 0)

    @Test fun structuredLogFormatsLevelTagMessage() {
        val source = listOf(
            logEntry(StructuredLog.Level.INFO, "Proj", "ready"),
            logEntry(StructuredLog.Level.ERROR, "Proj", "boom")
        )
        assertEquals("I/Proj: ready\nE/Proj: boom", StructuredLog.format(source))
    }

    @Test fun structuredLogFiltersByMinLevel() {
        val source = listOf(
            logEntry(StructuredLog.Level.DEBUG, "T", "d"),
            logEntry(StructuredLog.Level.WARN, "T", "w")
        )
        assertEquals("W/T: w", StructuredLog.format(source, minLevel = StructuredLog.Level.WARN))
    }

    @Test fun structuredLogTakesMostRecentUpToLimit() {
        val source = (1..5).map { logEntry(StructuredLog.Level.INFO, "T", "m$it") }
        // Newest last, at most `limit` lines.
        assertEquals("I/T: m4\nI/T: m5", StructuredLog.format(source, limit = 2))
    }

    @Test fun structuredLogEmptySourceIsEmptyString() {
        assertEquals("", StructuredLog.format(emptyList()))
    }

    @Test fun settingsCodecRoundTripsKnownEnumNames() {
        assertEquals(SurfaceProfile.FORD_NEXT_GEN, SettingsCodec.surfaceProfile("FORD_NEXT_GEN"))
        assertEquals(SurfaceProfile.DEFAULT, SettingsCodec.surfaceProfile("DEFAULT"))
        assertEquals(
            ScreenOffController.PipelineMode.OWN_CONTENT,
            SettingsCodec.screenOffMode("OWN_CONTENT")
        )
    }

    @Test fun settingsCodecFallsBackOnNullOrUnknown() {
        // Missing (null) or a renamed/unknown persisted value must fall back to the safe default, not throw.
        assertEquals(SurfaceProfile.DEFAULT, SettingsCodec.surfaceProfile(null))
        assertEquals(SurfaceProfile.DEFAULT, SettingsCodec.surfaceProfile("GONE"))
        assertEquals(ScreenOffController.PipelineMode.AUTO_MIRROR, SettingsCodec.screenOffMode(null))
        assertEquals(ScreenOffController.PipelineMode.AUTO_MIRROR, SettingsCodec.screenOffMode("NOPE"))
    }

    @Test fun reconnectClassifiesResumeVsFreshStart() {
        // Projection alive -> reuse (reconnect); no projection -> fresh start.
        assertEquals(ReconnectTracker.Outcome.RESUMED, ReconnectTracker.classify(projectionAlive = true))
        assertEquals(ReconnectTracker.Outcome.FRESH_START, ReconnectTracker.classify(projectionAlive = false))
    }

    @Test fun reconnectCountsOnlyActualResumes() {
        ReconnectTracker.reset()
        assertEquals(0, ReconnectTracker.reconnectCount)
        ReconnectTracker.onSurfaceArrived(projectionAlive = false) // fresh start, no bump
        assertEquals(0, ReconnectTracker.reconnectCount)
        ReconnectTracker.onSurfaceArrived(projectionAlive = true) // reconnect
        ReconnectTracker.onSurfaceArrived(projectionAlive = true) // reconnect
        assertEquals(2, ReconnectTracker.reconnectCount)
        ReconnectTracker.reset()
        assertEquals(0, ReconnectTracker.reconnectCount)
    }
}
