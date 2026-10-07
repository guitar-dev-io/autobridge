package dev.autobridge.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandValidatorTest {

    private fun valid(command: VoiceCommand): ValidatedCommand {
        val result = CommandValidator.validate(command)
        assertTrue("expected valid: $command, got $result", result is Validation.Valid)
        return (result as Validation.Valid).command
    }

    private fun rejected(command: VoiceCommand): Validation.Reason {
        val result = CommandValidator.validate(command)
        assertTrue("expected rejection: $command, got $result", result is Validation.Rejected)
        return (result as Validation.Rejected).reason
    }

    private fun url(raw: String) = VoiceCommand(VoiceAction.OPEN_URL, VoiceTarget.BROWSER, url = raw)

    @Test fun `open app goes to the service home from the streaming catalog`() {
        assertEquals("https://m.youtube.com", valid(VoiceCommand(VoiceAction.OPEN_APP, VoiceTarget.YOUTUBE)).url)
        assertEquals("https://music.youtube.com", valid(VoiceCommand(VoiceAction.OPEN_APP, VoiceTarget.YOUTUBE_MUSIC)).url)
        assertEquals("https://www.tiktok.com", valid(VoiceCommand(VoiceAction.OPEN_APP, VoiceTarget.TIKTOK)).url)
        assertEquals("https://www.iq.com", valid(VoiceCommand(VoiceAction.OPEN_APP, VoiceTarget.IQIYI)).url)
        assertNull(valid(VoiceCommand(VoiceAction.OPEN_APP, VoiceTarget.BROWSER)).url)
    }

    @Test fun `search encodes the query into the service's search page`() {
        val command = valid(VoiceCommand(VoiceAction.SEARCH, VoiceTarget.YOUTUBE, "Bodyslam ขอนแก่น"))
        assertTrue(command.url!!.startsWith("https://m.youtube.com/results?search_query="))
        assertFalse(command.url!!.contains(' '))
        assertEquals(
            "https://music.youtube.com/search?q=Taylor+Swift",
            valid(VoiceCommand(VoiceAction.SEARCH, VoiceTarget.YOUTUBE_MUSIC, "Taylor Swift")).url
        )
    }

    @Test fun `a query cannot inject into the search address`() {
        val command = valid(VoiceCommand(VoiceAction.SEARCH, VoiceTarget.BROWSER, "a&b=c#frag"))
        assertEquals("https://www.google.com/search?q=a%26b%3Dc%23frag", command.url)
    }

    @Test fun `safe actions run without asking, url and send-to-car wait`() {
        assertTrue(valid(VoiceCommand(VoiceAction.OPEN_APP, VoiceTarget.TIKTOK)).autoExecute)
        assertTrue(valid(VoiceCommand(VoiceAction.SEARCH, VoiceTarget.YOUTUBE, "x")).autoExecute)
        assertTrue(valid(VoiceCommand(VoiceAction.PLAY, VoiceTarget.YOUTUBE, "x")).autoExecute)
        assertTrue(valid(VoiceCommand(VoiceAction.GO_HOME)).autoExecute)
        assertTrue(valid(VoiceCommand(VoiceAction.GO_BACK)).autoExecute)
        assertFalse(valid(url("google.com")).autoExecute)
        assertFalse(valid(VoiceCommand(VoiceAction.SEND_TO_CAR, VoiceTarget.YOUTUBE, "x")).autoExecute)
    }

    @Test fun `spoken address becomes https`() {
        assertEquals("https://google.com", valid(url("google.com")).url)
        assertEquals("https://example.org/path", valid(url("http://example.org/path")).url)
        assertEquals("https://example.org/a?b=c", valid(url("https://example.org/a?b=c")).url)
    }

    @Test fun `dangerous schemes are refused`() {
        listOf(
            "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;package=com.evil;end",
            "file:///sdcard/secret",
            "content://com.android.contacts/contacts",
            "data:text/html,hi",
            "market://details?id=x",
            "tel:1234",
            "ftp://example.com"
        ).forEach { raw ->
            assertEquals(raw, Validation.Reason.UNSUPPORTED_SCHEME, rejected(url(raw)))
        }
    }

    @Test fun `local and private hosts are refused`() {
        listOf("localhost", "http://localhost:8080", "192.168.1.1", "https://10.0.0.1/admin", "printer.local", "intranet")
            .forEach { raw -> assertEquals(raw, Validation.Reason.UNSAFE_HOST, rejected(url(raw))) }
    }

    @Test fun `credentials in a url are refused`() {
        assertEquals(Validation.Reason.UNSAFE_HOST, rejected(url("https://user:pass@example.com")))
    }

    @Test fun `malformed urls are refused`() {
        assertEquals(Validation.Reason.MALFORMED_URL, rejected(url("")))
        assertEquals(Validation.Reason.MALFORMED_URL, rejected(url("goo gle.com")))
        assertEquals(Validation.Reason.MALFORMED_URL, rejected(VoiceCommand(VoiceAction.OPEN_URL)))
    }

    @Test fun `unknown and incomplete commands are refused`() {
        assertEquals(Validation.Reason.UNKNOWN_COMMAND, rejected(VoiceCommand(VoiceAction.UNKNOWN, query = "x")))
        assertEquals(Validation.Reason.MISSING_TARGET, rejected(VoiceCommand(VoiceAction.SEARCH, query = "x")))
        assertEquals(Validation.Reason.EMPTY_QUERY, rejected(VoiceCommand(VoiceAction.SEARCH, VoiceTarget.YOUTUBE, "  ")))
        assertEquals(
            Validation.Reason.QUERY_TOO_LONG,
            rejected(VoiceCommand(VoiceAction.SEARCH, VoiceTarget.YOUTUBE, "a".repeat(CommandValidator.MAX_QUERY_LENGTH + 1)))
        )
    }

    @Test fun `send to car resolves what it would open`() {
        val search = valid(VoiceCommand(VoiceAction.SEND_TO_CAR, VoiceTarget.YOUTUBE, "Bodyslam"))
        assertEquals(VoiceAction.SEND_TO_CAR, search.command.action)
        assertTrue(search.url!!.startsWith("https://m.youtube.com/results"))
        val address = valid(VoiceCommand(VoiceAction.SEND_TO_CAR, VoiceTarget.BROWSER, url = "example.com"))
        assertEquals("https://example.com", address.url)
        assertEquals(Validation.Reason.UNSUPPORTED_SCHEME, rejected(VoiceCommand(VoiceAction.SEND_TO_CAR, url = "intent://x")))
    }

    @Test fun `opening a screen runs without asking and needs a screen`() {
        val settings = valid(VoiceCommand(VoiceAction.OPEN_SCREEN, screen = VoiceScreen.SETTINGS))
        assertTrue(settings.autoExecute)
        assertNull(settings.url)
        assertEquals(Validation.Reason.MISSING_TARGET, rejected(VoiceCommand(VoiceAction.OPEN_SCREEN)))
    }

    @Test fun `parser output for the required phrases validates`() {
        listOf(
            "เปิดยูทูบ", "เปิด youtube", "เปิด youtube เพลง bodyslam", "ค้นหา Taylor Swift ใน youtube music",
            "เปิดติ๊กต็อก", "เปิด iQIYI", "กลับหน้าแรก", "ย้อนกลับ", "go back", "เปิดเว็บ google.com",
            "เปิดการตั้งค่า", "เปิดทีวี", "เปิดวิทยุ", "เปิดสภาพอากาศ"
        ).forEach { phrase ->
            assertTrue(phrase, CommandValidator.validate(VoiceCommandParser.parse(phrase)) is Validation.Valid)
        }
    }
}
