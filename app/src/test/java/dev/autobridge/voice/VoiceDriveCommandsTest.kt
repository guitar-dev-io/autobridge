package dev.autobridge.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Next, previous, pause and navigate: what a driver says without looking. */
class VoiceDriveCommandsTest {
    private fun action(text: String) = VoiceCommandParser.parse(text).action

    @Test fun nextInThaiAndEnglish() {
        listOf(
            "ถัดไป", "เพลงถัดไป", "ช่องถัดไป", "สถานีถัดไป", "เพลงต่อไป", "ขอเพลงถัดไป", "เปลี่ยนช่องถัดไป",
            "ข้าม", "ข้ามเพลง", "next", "Next song", "next channel", "skip", "skip this", "เพลงถัดไปครับ"
        ).forEach { assertEquals(it, VoiceAction.MEDIA_NEXT, action(it)) }
    }

    @Test fun previousInThaiAndEnglish() {
        listOf(
            "เพลงก่อนหน้า", "ช่องก่อนหน้า", "เพลงที่แล้ว", "ขอเพลงเมื่อกี้", "ย้อนเพลง", "previous",
            "previous song", "last channel"
        ).forEach { assertEquals(it, VoiceAction.MEDIA_PREVIOUS, action(it)) }
    }

    @Test fun pauseInThaiAndEnglish() {
        listOf("หยุด", "หยุดเพลง", "หยุดชั่วคราว", "พักเพลง", "ปิดเพลง", "pause", "stop the music", "Stop")
            .forEach { assertEquals(it, VoiceAction.MEDIA_PAUSE, action(it)) }
    }

    @Test fun navigateTakesEverythingAfterTheVerbAsTheDestination() {
        mapOf(
            "นำทางไปเซ็นทรัลเวิลด์" to "เซ็นทรัลเวิลด์",
            "นำทางไปที่ สนามบินสุวรรณภูมิ" to "สนามบินสุวรรณภูมิ",
            "พาไปปั๊ม ปตท ที่ใกล้ที่สุด" to "ปั๊ม ปตท ที่ใกล้ที่สุด",
            "ขับไปบ้าน" to "บ้าน",
            "navigate to Siam Paragon" to "Siam Paragon",
            "take me to the nearest hospital" to "the nearest hospital",
            "นำทางไปเซ็นทรัลลาดพร้าวครับ" to "เซ็นทรัลลาดพร้าว"
        ).forEach { (said, place) ->
            val command = VoiceCommandParser.parse(said)
            assertEquals(said, VoiceAction.NAVIGATE, command.action)
            assertEquals(said, place, command.query)
        }
    }

    /** These must keep meaning what they meant before. */
    @Test fun lookalikesAreNotHijacked() {
        assertEquals(VoiceAction.SEARCH, action("ค้นหา เพลงถัดไปของ Bodyslam ใน YouTube"))
        assertEquals(VoiceAction.GO_BACK, action("ย้อนกลับ"))
        assertEquals(VoiceAction.GO_HOME, action("กลับหน้าแรก"))
        assertNotEquals(VoiceAction.NAVIGATE, action("ไปที่ youtube.com"))
        assertNotEquals(VoiceAction.MEDIA_PAUSE, action("หยุดรถตรงไหนดี"))
        assertNotEquals(VoiceAction.MEDIA_NEXT, action("next to the station"))
        assertEquals(VoiceAction.OPEN_SCREEN, action("เปิดวิทยุ"))
    }

    @Test fun aNavigationVerbWithNoPlaceIsNotNavigation() {
        assertNotEquals(VoiceAction.NAVIGATE, action("นำทาง"))
    }

    @Test fun mediaCommandsRunWithoutAConfirmationTap() {
        listOf("เพลงถัดไป", "เพลงก่อนหน้า", "หยุด").forEach { said ->
            val v = CommandValidator.validate(VoiceCommandParser.parse(said))
            assertTrue(said, v is Validation.Valid && v.command.autoExecute)
        }
    }

    /** A misheard place starts a route somewhere else, so on the phone it waits for a tap. */
    @Test fun navigationWaitsForATapAndOpensNoUrl() {
        val v = CommandValidator.validate(VoiceCommandParser.parse("นำทางไปเซ็นทรัลเวิลด์"))
        assertTrue(v is Validation.Valid)
        val valid = (v as Validation.Valid).command
        assertFalse(valid.autoExecute)
        assertEquals(null, valid.url)
        assertEquals("เซ็นทรัลเวิลด์", valid.command.query)
    }
}
