package dev.autobridge.obd.profile

import dev.autobridge.obd.Gauge
import dev.autobridge.obd.ObdPids
import dev.autobridge.obd.Transmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {
    private fun parse(text: String) = ProfileCodec.decode(text)

    private val minimal = """
        id: my_car
        name: My car
        pid: rpm | RPM | 010C | 2 | - | (A*256+B)/4 | rpm | 0..5000 | 0..9000 | 600,4500,300,5500,lh
    """.trimIndent()

    @Test fun aProfileRoundTripsThroughItsText() {
        for (profile in BuiltInProfiles.raw) {
            val again = parse(ProfileCodec.encode(profile)).profile
            assertEquals(profile.id, profile, again)
        }
    }

    @Test fun everyBuiltInIsSoundAndResolvesToAFullSet() {
        assertEquals(4, BuiltInProfiles.all.size)
        for (profile in BuiltInProfiles.all) {
            assertTrue("${profile.id}: ${ProfileValidator.check(profile)}", ProfileValidator.check(profile).isEmpty())
            for (gauge in listOf("rpm", "speed", "coolant", "load", "battery")) {
                assertTrue("${profile.id} lacks $gauge", profile.candidates(gauge).isNotEmpty())
            }
        }
    }

    @Test fun theGenericProfileMatchesTheHardCodedPids() {
        val generic = BuiltInProfiles.find(BuiltInProfiles.GENERIC_ID)!!
        val samples = listOf(intArrayOf(0x13, 0x88), intArrayOf(0x00, 0x00), intArrayOf(0xFF, 0xFF), intArrayOf(0x37, 0x78))
        val pairs = listOf(
            "rpm" to ObdPids.RPM, "speed" to ObdPids.SPEED, "coolant" to ObdPids.COOLANT, "load" to ObdPids.LOAD,
            "intake" to ObdPids.INTAKE, "map" to ObdPids.MAP, "baro" to ObdPids.BARO, "throttle" to ObdPids.THROTTLE,
            "fuel" to ObdPids.FUEL_LEVEL, "ambient" to ObdPids.AMBIENT, "battery" to ObdPids.BATTERY_STANDARD,
        )
        for ((id, pid) in pairs) {
            val def = generic.candidates(id).single()
            assertEquals("$id request", pid.request, def.request)
            assertEquals("$id reply", pid.reply, def.reply)
            assertEquals("$id bytes", pid.bytes, def.bytes)
            val formula = Formula.parse(def.formula)!!
            for (sample in samples) {
                if (sample.size < pid.bytes) continue
                val data = sample.copyOf(pid.bytes)
                assertEquals("$id ${data.toList()}", pid.decode(data), formula.evaluate(data)!!, 0.001f)
            }
        }
    }

    @Test fun theFordProfilesMatchTheHardCodedGearboxOilOrder() {
        for ((profileId, transmission) in listOf(
            "ford_ranger_pjpk" to Transmission.T5,
            "ford_ranger_px" to Transmission.T6,
            "ford_ranger_next_gen" to Transmission.NEXT_GEN,
        )) {
            val profile = BuiltInProfiles.all.first { it.id == profileId }
            val expected = ObdPids.atf(transmission)
            val actual = profile.candidates("atf")
            assertEquals(profileId, expected.map { it.request to it.header }, actual.map { it.request to it.header })
            val data = intArrayOf(0x05, 0xA0)
            for ((pid, def) in expected.zip(actual)) {
                assertEquals(pid.decode(data), Formula.parse(def.formula)!!.evaluate(data)!!, 0.001f)
            }
        }
    }

    @Test fun aBaseIsMergedSoOnlyTheDifferencesAreListed() {
        val nextGen = BuiltInProfiles.all.first { it.id == "ford_ranger_next_gen" }
        // Replaced in the base's place, not appended.
        assertEquals(BuiltInProfiles.find(BuiltInProfiles.GENERIC_ID)!!.gauges + "atf", nextGen.gauges)
        assertEquals(106f, nextGen.candidates("coolant").single().zone!!.safeMax, 0f)
        // Untouched gauges come from the base.
        assertEquals("010D", nextGen.candidates("speed").single().request)
        // The battery has the standard PID first and Ford's as the fallback.
        assertEquals(listOf("0142", "221172"), nextGen.candidates("battery").map { it.request })
    }

    @Test fun onlyReadRequestsAreAccepted() {
        for (ok in listOf("010C", "0105", "221E1C", "221674")) assertTrue(ok, ProfileValidator.isReadRequest(ok))
        // Clear codes, reset, security access, write data, routine control, session control, reset ECU.
        for (bad in listOf("04", "11", "1003", "2701", "2E1234", "2F1234", "3101", "10C0", "3B00", "2A01", "0902", "03", "07", "2200", "22123", "221E1C00", "01", "010")) {
            assertFalse("'$bad' must be refused", ProfileValidator.isReadRequest(bad))
        }
        for (bad in listOf("2E1E1C", "310203", "2701", "04")) {
            val result = parse(minimal.replace("010C", bad))
            assertNull("$bad was accepted", result.profile)
            assertTrue(result.issues.any { it.problem.contains("not allowed") })
        }
    }

    @Test fun badFormulasAndFieldsAreReportedWithTheirLine() {
        val result = parse(minimal.replace("(A*256+B)/4", "sqrt(A)"))
        assertNull(result.profile)
        assertTrue(result.issues.single().toString().contains("formula"))

        assertTrue(parse(minimal.replace("(A*256+B)/4", "A+B+C")).issues.any { it.problem.contains("byte C") })
        assertTrue(parse(minimal.replace("| 2 |", "| 9 |")).issues.any { it.problem.contains("bytes") })
        assertTrue(parse(minimal.replace("| - |", "| 7E0X |")).issues.any { it.problem.contains("header") })
        assertTrue(parse(minimal + "\nbogus line").issues.any { it.where == "line 4" })
        assertTrue(parse(minimal + "\ncolour: red").issues.any { it.problem.contains("unknown key") })
        assertTrue(parse(minimal.replace("0..5000", "5000..0")).issues.any { it.problem.contains("min must be below max") })
        assertTrue(parse(minimal.replace("600,4500,300,5500", "600,4500,700,5500")).issues.any { it.problem.contains("caution") })
        assertTrue(parse("id: ab\nname: x\n$minimal").issues.any { it.problem.contains("twice") })
    }

    @Test fun garbageNeverThrows() {
        for (text in listOf("", "   ", "\u0000\u0001", "pid:", "pid: |||||||||", "id:\nname:\n", "x".repeat(ProfileValidator.MAX_TEXT + 1))) {
            assertNull(parse(text).profile)
        }
    }

    @Test fun aGoodProfileParsesWithAllItsFields() {
        val profile = parse(minimal + "\nmake: Toyota\nmodel: Hilux\nyears: 2016-\nverified: yes\nnotes: checked on a truck").profile
        assertNotNull(profile)
        assertEquals("my_car", profile!!.id)
        assertEquals("Hilux", profile.model)
        assertTrue(profile.verified)
        val pid = profile.pids.single()
        assertEquals("41 0C".replace(" ", ""), pid.reply)
        assertEquals(600f, pid.zone!!.safeMin, 0f)
        assertTrue(pid.zone!!.lowIsBad && pid.zone!!.highIsBad)
        assertEquals(9000f, pid.plausibleMax!!, 0f)
        assertEquals("62", ProfileCodec.decode(minimal.replace("010C", "221E1C")).profile!!.pids.single().reply.take(2))
    }

    @Test fun aProfileMayNameABaseAndChangeNothingElse() {
        val profile = parse("id: my_ranger\nname: Mine\nbase: ford_ranger_px").profile
        assertNotNull(profile)
        val resolved = ProfileResolver.resolve(profile!!, BuiltInProfiles::findResolved)
        assertEquals(BuiltInProfiles.all.first { it.id == "ford_ranger_px" }.pids, resolved.pids)
    }

    @Test fun aMissingBaseLeavesTheProfileAlone() {
        val profile = parse("$minimal\nbase: nothing_like_it").profile!!
        assertEquals(profile, ProfileResolver.resolve(profile, BuiltInProfiles::findResolved))
    }

    @Test fun gaugeEnumStillCoversTheBuiltInIds() {
        // The wiring to data-driven gauges comes later; until then every built-in id must have a twin.
        val twins = mapOf(
            "rpm" to Gauge.RPM, "speed" to Gauge.SPEED, "coolant" to Gauge.COOLANT, "load" to Gauge.LOAD,
            "intake" to Gauge.INTAKE, "map" to Gauge.MAP, "baro" to Gauge.BARO, "throttle" to Gauge.THROTTLE,
            "fuel" to Gauge.FUEL_LEVEL, "ambient" to Gauge.AMBIENT, "battery" to Gauge.BATTERY, "atf" to Gauge.ATF,
        )
        for (profile in BuiltInProfiles.all) assertTrue(profile.gauges.all { it in twins })
    }
}
