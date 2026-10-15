package dev.autobridge.iptv

import dev.autobridge.radio.FakeRadioContext
import dev.autobridge.radio.RadioSourceType
import dev.autobridge.radio.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The six radio presets: save/recall/clear round-trips, slot clamping, the FM/blank-URL reject
 * paths that leave a slot unchanged, and the corrupt/blank slot that reads back as empty.
 *
 * The store goes through [dev.autobridge.core.crypto.SecretPrefs]; on the JVM the Keystore is
 * unavailable, so SecretPrefs keeps values in its own in-memory fallback keyed by prefs file + slot
 * key. That is a static map, so each test first empties all six slots to start from a known state.
 */
class RadioPresetsStoreTest {

    private val context = FakeRadioContext()

    private fun internet(url: String, title: String = url) =
        RadioStation(RadioSourceType.INTERNET_RADIO, id = url, title = title, streamUrl = url)

    @Before fun resetSlots() {
        (1..RadioPresetsStore.SLOT_COUNT).forEach { RadioPresetsStore.clear(context, it) }
    }

    @Test fun `an untouched store has six empty slots`() {
        val presets = RadioPresetsStore.presets(context)
        assertEquals(RadioPresetsStore.SLOT_COUNT, presets.size)
        assertTrue(presets.all { it == null })
    }

    @Test fun `save then recall round-trips a station`() {
        val station = internet("http://a", "Alpha").copy(logo = "http://a/logo.png")
        val result = RadioPresetsStore.save(context, 2, station)

        assertEquals(RadioPresetsStore.SaveResult.Saved, result)
        val recalled = requireNotNull(RadioPresetsStore.presets(context)[1])
        assertEquals("http://a", recalled.streamUrl)
        assertEquals("http://a", recalled.id)
        assertEquals("Alpha", recalled.title)
        assertEquals("http://a/logo.png", recalled.logo)
        assertEquals(RadioSourceType.INTERNET_RADIO, recalled.sourceType)
        // Frequency is never persisted.
        assertNull(recalled.frequencyKHz)
    }

    @Test fun `clear empties a slot`() {
        RadioPresetsStore.save(context, 3, internet("http://c"))
        assertTrue(RadioPresetsStore.presets(context)[2] != null)

        RadioPresetsStore.clear(context, 3)
        assertNull(RadioPresetsStore.presets(context)[2])
    }

    @Test fun `slot numbers are clamped into one to six`() {
        RadioPresetsStore.save(context, 0, internet("http://low"))
        RadioPresetsStore.save(context, 99, internet("http://high"))

        val presets = RadioPresetsStore.presets(context)
        assertEquals("http://low", presets[0]?.streamUrl)   // clamped to slot 1
        assertEquals("http://high", presets[5]?.streamUrl)  // clamped to slot 6
    }

    @Test fun `saving an FM station is rejected and the slot is unchanged`() {
        RadioPresetsStore.save(context, 1, internet("http://keep", "Keep"))
        val fm = RadioStation(RadioSourceType.HARDWARE_FM, id = "fm:95.5", title = "95.5", streamUrl = "")

        val result = RadioPresetsStore.save(context, 1, fm)

        assertTrue(result is RadioPresetsStore.SaveResult.Rejected)
        assertEquals("http://keep", RadioPresetsStore.presets(context)[0]?.streamUrl)
    }

    @Test fun `saving a blank-url station is rejected and the slot is unchanged`() {
        RadioPresetsStore.save(context, 4, internet("http://keep"))
        val blank = internet("http://x").copy(streamUrl = "")

        val result = RadioPresetsStore.save(context, 4, blank)

        assertTrue(result is RadioPresetsStore.SaveResult.Rejected)
        assertEquals("http://keep", RadioPresetsStore.presets(context)[3]?.streamUrl)
    }

    @Test fun `a corrupt slot reads back as empty`() {
        context.putRaw("autobridge_radio_presets", "slot_5", "{ this is not json")
        assertNull(RadioPresetsStore.presets(context)[4])
    }

    @Test fun `a slot whose json has a blank url reads back as empty`() {
        context.putRaw("autobridge_radio_presets", "slot_6", """{"title":"t","url":"","sourceType":"INTERNET_RADIO"}""")
        assertNull(RadioPresetsStore.presets(context)[5])
    }

    @Test fun `a saved station survives a fresh presets read`() {
        RadioPresetsStore.save(context, 1, internet("http://a"))
        RadioPresetsStore.save(context, 6, internet("http://f"))
        val presets = RadioPresetsStore.presets(context)
        assertEquals("http://a", presets[0]?.streamUrl)
        assertNull(presets[1])
        assertEquals("http://f", presets[5]?.streamUrl)
    }
}
