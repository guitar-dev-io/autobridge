package dev.autobridge

import dev.autobridge.apps.DefaultAppProfiles
import dev.autobridge.apps.QuickApp
import dev.autobridge.apps.QuickAppsCatalog
import dev.autobridge.apps.SmartMode
import dev.autobridge.apps.SmartModeResolver
import dev.autobridge.core.datastore.SessionRestoreCodec
import dev.autobridge.core.model.AppProfile
import dev.autobridge.core.model.AudioMode
import dev.autobridge.core.model.AutoBridgeMode
import dev.autobridge.core.model.Environment
import dev.autobridge.core.model.Feature
import dev.autobridge.core.model.Insets
import dev.autobridge.core.model.RuntimeContext
import dev.autobridge.core.model.VehicleState
import dev.autobridge.core.policy.FeaturePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.autobridge.apps.InstalledApp

/**
 * The parked-only gate is built but not armed: AutoBridge ships with
 * [dev.autobridge.safety.SafetyEnforcement] reporting rather than blocking, so
 * `FeaturePolicy()` allows a parked-only feature on a moving car.
 *
 * The tests below therefore come in pairs - what the gate decides when it is enforcing
 * (`enforceParkedOnly = true`, the branch that would run if the flag were flipped) and what the
 * shipped default does with the same context. Asserting only the default would leave the denial
 * branch uncovered, which is how it rotted the last time it was turned off.
 */
class AppProfileRegressionTest {
    @Test
    fun featurePolicyExposesFailClosedReasonsAndRealCarLabBoundary() {
        val policy = FeaturePolicy(enforceParkedOnly = true)
        val blocked = policy.decide(
            Feature.MIRROR,
            RuntimeContext(AutoBridgeMode.PERSONAL, Environment.DHU, VehicleState.UNKNOWN)
        )
        assertFalse(blocked.allowed)
        assertTrue(blocked.requiresParked)
        assertTrue(blocked.reason.contains("requires PARKED"))

        val realCarLab = policy.decide(
            Feature.MIRROR,
            RuntimeContext(AutoBridgeMode.LAB, Environment.REAL_CAR, VehicleState.PARKED)
        )
        assertTrue(realCarLab.allowed)
        assertEquals("LAB on real car; real vehicle state enforced", realCarLab.reason)
    }

    @Test
    fun personalPolicyEnablesNonParkedMediaButNotParkedOnlyFeatures() {
        val policy = FeaturePolicy(enforceParkedOnly = true)
        val context = RuntimeContext(AutoBridgeMode.PERSONAL, Environment.DHU, VehicleState.MOVING)

        assertTrue(policy.isAvailable(Feature.MEDIA, context))
        assertFalse(policy.isAvailable(Feature.VIDEO, context))
        assertFalse(policy.isAvailable(Feature.TOUCH, context))
        assertTrue(policy.enabledFeatures(context).contains(Feature.MEDIA))
        assertFalse(policy.enabledFeatures(context).contains(Feature.MIRROR))
    }

    /**
     * The shipped default. A moving car still allows the feature, but the decision has to say so
     * out loud: `requiresParked` stays true and the reason names the state, because the Vehicle
     * status row and the mirror diagnostics are what the driver is left with.
     */
    @Test
    fun reportingPolicyAllowsParkedOnlyFeaturesButStillNamesTheMovingVehicle() {
        val policy = FeaturePolicy(enforceParkedOnly = false)
        val moving = RuntimeContext(AutoBridgeMode.PERSONAL, Environment.REAL_CAR, VehicleState.MOVING)

        val decision = policy.decide(Feature.VIDEO, moving)
        assertTrue(decision.allowed)
        assertTrue(decision.requiresParked)
        assertTrue(decision.reason.contains("moving"))
        assertTrue(decision.reason.contains("not blocking"))

        // Mode is a separate boundary and is still enforced while the speed gate only reports.
        val safeMoving = RuntimeContext(AutoBridgeMode.SAFE, Environment.REAL_CAR, VehicleState.MOVING)
        assertFalse(policy.isAvailable(Feature.MIRROR, safeMoving))
        assertTrue(policy.isAvailable(Feature.MEDIA, safeMoving))
    }

    @Test
    fun smartModeNormalizesPackagesAndPrefersAudioFirstRules() {
        val decision = SmartModeResolver.resolve(
            "Com.Example.MusicPlayer",
            AppProfile(packageName = "Com.Example.MusicPlayer", autoMirror = true)
        )

        assertEquals(SmartMode.MEDIA, decision.mode)
        assertEquals("audio-first package", decision.reason)
    }

    @Test
    fun smartModeUsesExplicitMediaProfileForUnknownPackage() {
        val decision = SmartModeResolver.resolve(
            "com.example.player",
            AppProfile(packageName = "com.example.player", audioMode = AudioMode.MEDIA)
        )

        assertEquals(SmartMode.MEDIA, decision.mode)
        assertEquals("profile prefers native MediaSession", decision.reason)
    }

    @Test
    fun smartModeDistinguishesBrowserMirrorPreference() {
        val mirrored = SmartModeResolver.resolve(
            "com.android.chrome",
            AppProfile(packageName = "com.android.chrome", autoMirror = true)
        )
        val browser = SmartModeResolver.resolve(
            "com.android.chrome",
            AppProfile(packageName = "com.android.chrome", autoMirror = false)
        )

        assertEquals(SmartMode.MIRROR, mirrored.mode)
        assertEquals(SmartMode.BROWSER, browser.mode)
    }

    @Test
    fun smartModeMapsVideoAndNavigationProfiles() {
        val video = SmartModeResolver.resolve(
            "com.example.video",
            AppProfile(packageName = "com.example.video")
        )
        val mirroredMaps = SmartModeResolver.resolve(
            "com.google.android.apps.maps",
            AppProfile(packageName = "com.google.android.apps.maps", autoMirror = true)
        )
        val nativeMaps = SmartModeResolver.resolve(
            "com.google.android.apps.maps",
            AppProfile(packageName = "com.google.android.apps.maps", autoMirror = false)
        )

        assertEquals(SmartMode.MIRROR, video.mode)
        assertEquals(SmartMode.MIRROR, mirroredMaps.mode)
        assertEquals(SmartMode.NATIVE_CAR, nativeMaps.mode)
    }

    @Test
    fun smartModeFallsBackToProfileThenNativeCar() {
        val mirrored = SmartModeResolver.resolve(
            "com.example.reader",
            AppProfile(packageName = "com.example.reader", autoMirror = true, audioMode = AudioMode.OFF)
        )
        val native = SmartModeResolver.resolve(
            "com.example.reader",
            AppProfile(packageName = "com.example.reader", audioMode = AudioMode.OFF)
        )

        assertEquals(SmartMode.MIRROR, mirrored.mode)
        assertEquals("profile requests mirror", mirrored.reason)
        assertEquals(SmartMode.NATIVE_CAR, native.mode)
    }

    @Test
    fun defaultProfilesContainExpectedSafePresets() {
        val youtube = DefaultAppProfiles.forPackage("com.google.android.youtube")
        val spotify = DefaultAppProfiles.forPackage("com.spotify.music")
        val unknown = DefaultAppProfiles.forPackage("com.example.unknown")

        assertTrue(youtube.autoMirror)
        assertEquals(AudioMode.MIRROR, youtube.audioMode)
        assertEquals(60, youtube.preferredFps)
        assertEquals(AudioMode.MEDIA, spotify.audioMode)
        assertFalse(spotify.autoMirror)
        assertFalse(unknown.autoMirror)
    }

    @Test
    fun quickAppsSortByExplicitOrderThenCaseInsensitiveLabel() {
        val sorted = QuickAppsCatalog.sort(
            listOf(
                QuickApp("com.z", "zulu", sortOrder = 1),
                QuickApp("com.b", "Bravo", sortOrder = 0),
                QuickApp("com.a", "alpha", sortOrder = 0)
            )
        )

        assertEquals(listOf("alpha", "Bravo", "zulu"), sorted.map { it.label })
    }

    @Test
    fun quickAppsFilterEnabledAndInstalledRecordsOnly() {
        val records = listOf(
            QuickApp("com.a", "Alpha", enabled = true, sortOrder = 1),
            QuickApp("com.b", "Bravo", enabled = false, sortOrder = 0),
            QuickApp("com.missing", "Missing", enabled = true, sortOrder = 2)
        )
        val installed = listOf(InstalledApp("com.a", "Alpha"), InstalledApp("com.b", "Bravo"))

        assertEquals(listOf("com.a"), QuickAppsCatalog.installedApps(records, installed).map { it.packageName })
    }

    @Test
    fun sessionEnumCodecFallsBackWithoutThrowing() {
        assertEquals(SmartMode.NATIVE_CAR, SessionRestoreCodec.smartMode("renamed"))
        assertEquals(Feature.MEDIA, SessionRestoreCodec.feature("renamed", Feature.MEDIA))
        assertEquals(dev.autobridge.core.model.ScaleMode.FIT, SessionRestoreCodec.scaleMode(null))
        assertEquals(dev.autobridge.core.model.RotationMode.AUTO, SessionRestoreCodec.rotationMode("gone"))
        assertEquals(AudioMode.MEDIA, SessionRestoreCodec.audioMode("gone"))
    }

    @Test
    fun safePolicyAllowsMediaButNotMirroring() {
        val policy = FeaturePolicy()
        val context = RuntimeContext(AutoBridgeMode.SAFE, Environment.DHU, VehicleState.PARKED)

        assertTrue(policy.isAvailable(Feature.MEDIA, context))
        assertTrue(policy.isAvailable(Feature.QUICK_APPS, context))
        assertFalse(policy.isAvailable(Feature.MIRROR, context))
        assertFalse(policy.isAvailable(Feature.BROWSER, context))
    }

    @Test
    fun personalAndLabStillRespectMovingVehicleGate() {
        val policy = FeaturePolicy(enforceParkedOnly = true)
        val personalMoving = RuntimeContext(AutoBridgeMode.PERSONAL, Environment.DHU, VehicleState.MOVING)
        val labBenchParked = RuntimeContext(AutoBridgeMode.LAB, Environment.TEST_BENCH, VehicleState.PARKED)
        val labRealCarMoving = RuntimeContext(AutoBridgeMode.LAB, Environment.REAL_CAR, VehicleState.MOVING)

        assertFalse(policy.isAvailable(Feature.MIRROR, personalMoving))
        assertTrue(policy.isAvailable(Feature.MIRROR, labBenchParked))
        assertFalse(policy.isAvailable(Feature.MIRROR, labRealCarMoving))
        assertTrue(policy.isAvailable(Feature.MEDIA, personalMoving))
    }
}

/**
 * Regression: the Applications screen's Quick Apps chip restyled itself but the list kept showing
 * every installed app, so the filter was a dead control.
 */
class AppListFilterTest {
    private val apps = listOf(
        dev.autobridge.apps.InstalledApp("com.example.maps", "Maps"),
        dev.autobridge.apps.InstalledApp("com.example.music", "Music"),
        dev.autobridge.apps.InstalledApp("com.other.notes", "Notes"),
    )
    private val quick = setOf("com.example.maps")

    @org.junit.Test fun quickOnlyKeepsOnlyQuickApps() {
        val shown = dev.autobridge.apps.AppListFilter.apply(apps, quick, quickOnly = true, query = "")
        org.junit.Assert.assertEquals(listOf("Maps"), shown.map { it.label })
    }

    @org.junit.Test fun allAppsKeepsEverything() {
        val shown = dev.autobridge.apps.AppListFilter.apply(apps, quick, quickOnly = false, query = "")
        org.junit.Assert.assertEquals(3, shown.size)
    }

    @org.junit.Test fun searchMatchesLabelOrPackageAndIsCaseInsensitive() {
        org.junit.Assert.assertEquals(
            listOf("Music"),
            dev.autobridge.apps.AppListFilter.apply(apps, quick, false, "mUs").map { it.label }
        )
        org.junit.Assert.assertEquals(
            listOf("Notes"),
            dev.autobridge.apps.AppListFilter.apply(apps, quick, false, "com.other").map { it.label }
        )
    }

    @org.junit.Test fun searchAppliesWithinTheQuickAppsScope() {
        org.junit.Assert.assertTrue(
            dev.autobridge.apps.AppListFilter.apply(apps, quick, quickOnly = true, query = "Music").isEmpty()
        )
    }

    @org.junit.Test fun emptyMessageExplainsWhyTheListIsEmpty() {
        org.junit.Assert.assertTrue(
            dev.autobridge.apps.AppListFilter.emptyMessage(false, "zz", true).contains("zz")
        )
        org.junit.Assert.assertTrue(
            dev.autobridge.apps.AppListFilter.emptyMessage(true, "", false).contains("☆")
        )
    }
}
