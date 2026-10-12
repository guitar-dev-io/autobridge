package dev.autobridge.profile

import dev.autobridge.car.CarDisplayInfo
import dev.autobridge.core.model.Insets
import dev.autobridge.profile.CarProfileCodec.Reason
import dev.autobridge.profile.CarProfileCodec.Result
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarProfileCodecTest {
    private val ranger = CarProfile(
        id = "local-1",
        name = "Ford Ranger SYNC 4A 10.1",
        vehicle = "Ford Ranger 2023",
        headUnit = "SYNC 4A",
        fallbackDpi = 160,
        safeInsets = Insets(0, 80, 0, 0),
        touchOffsetX = 1.5f,
        touchOffsetY = -2f,
        preferredFps = 30,
        screen = CarProfile.Screen(
            800, 1280, 160,
            stable = CarDisplayInfo.Area(0, 80, 800, 1280),
            visible = CarDisplayInfo.Area(0, 80, 800, 1200)
        ),
        measuredAt = "2026-10-12",
        appVersion = "0.4.58-rc5"
    )

    private fun ok(text: String, id: String = "imported"): CarProfile =
        (CarProfileCodec.decode(text, id) as Result.Ok).profile

    private fun reason(text: String): Reason = (CarProfileCodec.decode(text, "x") as Result.Error).reason

    private fun doc(vararg fields: Pair<String, Any?>) = JSONObject().apply {
        put("format", CarProfileCodec.FORMAT)
        put("version", 1)
        put("name", "Car")
        fields.forEach { (k, v) -> if (v == null) remove(k) else put(k, v) }
    }.toString()

    @Test fun aProfileSurvivesTheRoundTripWithTheImportersId() {
        val back = ok(CarProfileCodec.encode(ranger), id = "imported-7")
        assertEquals(ranger.copy(id = "imported-7"), back)
    }

    @Test fun anImportIsNeverVerifiedWhateverTheFileSays() {
        assertFalse(ok(doc("verified" to true)).verified)
        assertFalse(ok(CarProfileCodec.encode(ranger.copy(verified = true))).verified)
    }

    @Test fun notAProfileIsRefusedForWhatItIs() {
        assertEquals(Reason.NOT_JSON, reason("not json at all"))
        assertEquals(Reason.NOT_A_PROFILE, reason("{\"format\":\"something.else\",\"version\":1,\"name\":\"x\"}"))
        assertEquals(Reason.NOT_A_PROFILE, reason(doc("version" to 0)))
        assertEquals(Reason.NEWER_VERSION, reason(doc("version" to 2)))
        assertEquals(Reason.MISSING_NAME, reason(doc("name" to null)))
        assertEquals(Reason.MISSING_NAME, reason(doc("name" to "   ")))
    }

    @Test fun anOversizedFileIsRefusedBeforeParsing() {
        assertEquals(Reason.TOO_LARGE, reason(doc("vehicle" to "x".repeat(CarProfileCodec.MAX_BYTES))))
    }

    @Test fun outOfRangeNumbersFallBackToSafeDefaults() {
        val p = ok(doc(
            "fallbackDpi" to 5000,
            "touchOffsetX" to 9999.0,
            "touchOffsetY" to Double.NaN.toString(),
            "preferredFps" to 240,
            "safeInsets" to JSONObject(mapOf("left" to -10, "top" to 50000, "right" to 1.5, "bottom" to 40))
        ))
        assertEquals(160, p.fallbackDpi)
        assertEquals(0f, p.touchOffsetX, 0f)
        assertEquals(0f, p.touchOffsetY, 0f)
        assertNull(p.preferredFps)
        assertEquals(Insets(0, 0, 0, 40), p.safeInsets)
    }

    @Test fun aScreenOutsideRealSizesIsDroppedAndBadAreasAreDropped() {
        assertNull(ok(doc("screen" to JSONObject(mapOf("width" to 10, "height" to 480, "dpi" to 160)))).screen)
        val screen = ok(doc("screen" to JSONObject(mapOf(
            "width" to 800, "height" to 480, "dpi" to 160,
            "stable" to JSONObject(mapOf("left" to 0, "top" to 0, "right" to 900, "bottom" to 480)),
            "visible" to JSONObject(mapOf("left" to 100, "top" to 0, "right" to 100, "bottom" to 480))
        )))).screen!!
        assertNull("outside the screen", screen.stable)
        assertNull("no width", screen.visible)
    }

    @Test fun textIsCleanedAndCut() {
        val p = ok(doc("name" to "Ranger\n\u0000 SYNC   4A", "vehicle" to "v".repeat(500)))
        assertEquals("Ranger SYNC 4A", p.name)
        assertEquals(CarProfileCodec.MAX_TEXT, p.vehicle.length)
    }

    @Test fun unknownFieldsAreIgnoredNotInterpreted() {
        val p = ok(doc("bypass" to true, "url" to "https://example.com", "enforceParkedOnly" to false))
        assertEquals(CarProfile(id = "imported", name = "Car"), p)
    }

    @Test fun capturingACarRecordsItsScreenAndInventsNothing() {
        val fingerprint = CarDisplayInfo.Fingerprint(
            CarDisplayInfo.Surface(800, 1280, 160),
            CarDisplayInfo.Area(0, 80, 800, 1280),
            null
        )
        val p = CarProfile.capture("c1", "My Ranger", "Ford Ranger", fingerprint, "2026-10-12", "0.4.58")!!
        assertEquals(CarProfile.Screen(800, 1280, 160, CarDisplayInfo.Area(0, 80, 800, 1280), null), p.screen)
        assertEquals(Insets.ZERO, p.safeInsets)
        assertEquals(0f, p.touchOffsetX, 0f)
        assertNull(p.preferredFps)
        assertFalse(p.verified)
        assertNull("nothing connected, nothing to capture",
            CarProfile.capture("c2", "x", "", CarDisplayInfo.Fingerprint(null, null, null), "", ""))
    }

    @Test fun matchingIsOnSizeAndDpi() {
        assertTrue(ranger.matches(CarDisplayInfo.Surface(800, 1280, 160)))
        assertFalse(ranger.matches(CarDisplayInfo.Surface(800, 1280, 240)))
        assertFalse(ranger.matches(null))
        assertFalse(ranger.copy(screen = null).matches(CarDisplayInfo.Surface(800, 1280, 160)))
    }
}
