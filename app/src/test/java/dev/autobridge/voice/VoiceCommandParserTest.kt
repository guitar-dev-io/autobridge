package dev.autobridge.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phrases the parser must get right, written the ways Whisper actually writes them: Thai,
 * English, both in one sentence, with or without spaces, with punctuation and polite particles.
 */
class VoiceCommandParserTest {

    private fun parse(text: String) = VoiceCommandParser.parse(text)

    private fun assertCommand(
        text: String,
        action: VoiceAction,
        target: VoiceTarget? = null,
        query: String? = null
    ) {
        val command = parse(text)
        assertEquals("action for \"$text\"", action, command.action)
        assertEquals("target for \"$text\"", target, command.target)
        assertEquals("query for \"$text\"", query, command.query)
    }

    // --- The required table ---------------------------------------------------------------------

    @Test fun `open youtube in Thai script`() = assertCommand("เปิดยูทูบ", VoiceAction.OPEN_APP, VoiceTarget.YOUTUBE)

    @Test fun `open youtube in Latin script`() = assertCommand("เปิด youtube", VoiceAction.OPEN_APP, VoiceTarget.YOUTUBE)

    @Test fun `open YouTube as Whisper capitalises it`() = assertCommand("เปิด YouTube", VoiceAction.OPEN_APP, VoiceTarget.YOUTUBE)

    @Test fun `youtube song search keeps only the artist`() =
        assertCommand("เปิด youtube เพลง bodyslam", VoiceAction.SEARCH, VoiceTarget.YOUTUBE, "bodyslam")

    @Test fun `youtube song search keeps the artist's case`() =
        assertCommand("เปิด YouTube เพลง Bodyslam", VoiceAction.SEARCH, VoiceTarget.YOUTUBE, "Bodyslam")

    @Test fun `search in youtube music is not read as youtube`() =
        assertCommand("ค้นหา Taylor Swift ใน youtube music", VoiceAction.SEARCH, VoiceTarget.YOUTUBE_MUSIC, "Taylor Swift")

    @Test fun `search in YouTube Music capitalised`() =
        assertCommand("ค้นหา Taylor Swift ใน YouTube Music", VoiceAction.SEARCH, VoiceTarget.YOUTUBE_MUSIC, "Taylor Swift")

    @Test fun `open youtube music with an artist`() =
        assertCommand("เปิด YouTube Music Taylor Swift", VoiceAction.SEARCH, VoiceTarget.YOUTUBE_MUSIC, "Taylor Swift")

    @Test fun `open tiktok in Thai script`() = assertCommand("เปิดติ๊กต็อก", VoiceAction.OPEN_APP, VoiceTarget.TIKTOK)

    @Test fun `open TikTok`() = assertCommand("เปิด TikTok", VoiceAction.OPEN_APP, VoiceTarget.TIKTOK)

    @Test fun `open iQIYI`() = assertCommand("เปิด iQIYI", VoiceAction.OPEN_APP, VoiceTarget.IQIYI)

    @Test fun `open iQIYI in Thai script`() = assertCommand("เปิดอ้ายฉีอี้", VoiceAction.OPEN_APP, VoiceTarget.IQIYI)

    @Test fun `back to the first page is home`() = assertCommand("กลับหน้าแรก", VoiceAction.GO_HOME)

    @Test fun `back in Thai`() = assertCommand("ย้อนกลับ", VoiceAction.GO_BACK)

    @Test fun `back in English`() = assertCommand("go back", VoiceAction.GO_BACK)

    @Test fun `spoken web address opens it`() {
        val command = parse("เปิดเว็บ google.com")
        assertEquals(VoiceAction.OPEN_URL, command.action)
        assertEquals(VoiceTarget.BROWSER, command.target)
        assertEquals("google.com", command.url)
    }

    // --- Aliases and Whisper's habits -------------------------------------------------------------

    @Test fun `alternative Thai spellings of youtube`() {
        assertCommand("เปิดยูทูป", VoiceAction.OPEN_APP, VoiceTarget.YOUTUBE)
        assertCommand("เปิด ยูทู้ป", VoiceAction.OPEN_APP, VoiceTarget.YOUTUBE)
    }

    @Test fun `youtube music in Thai script`() =
        assertCommand("เปิดยูทูบมิวสิก", VoiceAction.OPEN_APP, VoiceTarget.YOUTUBE_MUSIC)

    @Test fun `punctuation and polite particles are ignored`() =
        assertCommand("เปิด YouTube, เพลง Bodyslam ครับ.", VoiceAction.SEARCH, VoiceTarget.YOUTUBE, "Bodyslam")

    @Test fun `no spaces between Thai and English`() =
        assertCommand("เปิดYouTubeเพลงBodyslam", VoiceAction.SEARCH, VoiceTarget.YOUTUBE, "Bodyslam")

    @Test fun `english sentence`() =
        assertCommand("search Taylor Swift on YouTube Music", VoiceAction.SEARCH, VoiceTarget.YOUTUBE_MUSIC, "Taylor Swift")

    @Test fun `english open`() = assertCommand("Open TikTok", VoiceAction.OPEN_APP, VoiceTarget.TIKTOK)

    @Test fun `play verb plays`() =
        assertCommand("เล่นเพลง Bodyslam ใน YouTube", VoiceAction.PLAY, VoiceTarget.YOUTUBE, "Bodyslam")

    @Test fun `a song with no service plays on youtube`() =
        assertCommand("เปิดเพลง Bodyslam", VoiceAction.PLAY, VoiceTarget.YOUTUBE, "Bodyslam")

    @Test fun `search without a service searches the web`() =
        assertCommand("ค้นหา ร้านกาแฟใกล้ฉัน", VoiceAction.SEARCH, VoiceTarget.BROWSER, "ร้านกาแฟใกล้ฉัน")

    @Test fun `english word inside another word is not a service`() {
        // "youtuber" is not YouTube.
        val command = parse("search best youtuber")
        assertEquals(VoiceAction.SEARCH, command.action)
        assertEquals(VoiceTarget.BROWSER, command.target)
    }

    @Test fun `home in English`() = assertCommand("go home", VoiceAction.GO_HOME)

    @Test fun `back inside a song title is not a command`() {
        val command = parse("เปิด YouTube เพลง กลับ")
        assertEquals(VoiceAction.SEARCH, command.action)
        assertEquals("กลับ", command.query)
    }

    @Test fun `send to car wraps a search`() {
        val command = parse("ส่ง YouTube เพลง Bodyslam ไปที่รถ")
        assertEquals(VoiceAction.SEND_TO_CAR, command.action)
        assertEquals(VoiceTarget.YOUTUBE, command.target)
        assertEquals("Bodyslam", command.query)
    }

    @Test fun `send to car in English`() {
        val command = parse("send TikTok to the car")
        assertEquals(VoiceAction.SEND_TO_CAR, command.action)
        assertEquals(VoiceTarget.TIKTOK, command.target)
    }

    // --- Opening screens ---------------------------------------------------------------------------

    private fun assertScreen(text: String, screen: VoiceScreen) {
        val command = parse(text)
        assertEquals("action for \"$text\"", VoiceAction.OPEN_SCREEN, command.action)
        assertEquals("screen for \"$text\"", screen, command.screen)
    }

    @Test fun `open settings`() {
        assertScreen("เปิดการตั้งค่า", VoiceScreen.SETTINGS)
        assertScreen("ไปที่หน้าตั้งค่า", VoiceScreen.SETTINGS)
        assertScreen("open settings", VoiceScreen.SETTINGS)
        assertScreen("ตั้งค่า ครับ", VoiceScreen.SETTINGS)
    }

    @Test fun `open tv and radio`() {
        assertScreen("เปิดทีวี", VoiceScreen.TV)
        assertScreen("ดูทีวี", VoiceScreen.TV)
        assertScreen("open TV", VoiceScreen.TV)
        assertScreen("เปิดวิทยุ", VoiceScreen.RADIO)
        assertScreen("radio", VoiceScreen.RADIO)
    }

    @Test fun `open weather, streaming, favorites, playlists, gallery`() {
        assertScreen("เปิดสภาพอากาศ", VoiceScreen.WEATHER)
        assertScreen("พยากรณ์อากาศ", VoiceScreen.WEATHER)
        assertScreen("show the weather", VoiceScreen.WEATHER)
        assertScreen("เปิดสตรีมมิ่ง", VoiceScreen.STREAMING)
        assertScreen("เปิดรายการโปรด", VoiceScreen.FAVORITES)
        assertScreen("เปิดเพลย์ลิสต์", VoiceScreen.PLAYLISTS)
        assertScreen("เปิดรูปภาพ", VoiceScreen.GALLERY)
        assertScreen("open gallery", VoiceScreen.GALLERY)
    }

    @Test fun `a screen name inside a sentence is not a menu command`() {
        // A question about the weather is not "open Weather".
        assertEquals(VoiceAction.UNKNOWN, parse("วันนี้อากาศเป็นยังไง").action)
        // A search that mentions TV stays a search.
        val search = parse("ค้นหา ทีวี ราคาถูก")
        assertEquals(VoiceAction.SEARCH, search.action)
        assertEquals(VoiceTarget.BROWSER, search.target)
        // YouTube TV is a YouTube search, not the TV screen.
        assertEquals(VoiceAction.SEARCH, parse("เปิด YouTube ทีวี").action)
    }

    @Test fun `music still plays rather than opening a menu`() =
        assertCommand("เปิดเพลง Bodyslam", VoiceAction.PLAY, VoiceTarget.YOUTUBE, "Bodyslam")

    // --- What stays the Agent's -------------------------------------------------------------------

    @Test fun `resume keeps meaning resume`() {
        // The Agent parser owns "เล่นต่อ"; the voice grammar must not read it as "play ต่อ".
        assertEquals(VoiceAction.UNKNOWN, parse("เล่นต่อ").action)
    }

    @Test fun `mirror stays the Agent's`() = assertEquals(VoiceAction.UNKNOWN, parse("เปิดมิเรอร์").action)

    @Test fun `unrecognised text is unknown and keeps the text`() {
        val command = parse("วันนี้อากาศเป็นยังไง")
        assertEquals(VoiceAction.UNKNOWN, command.action)
        assertTrue(command.text.isNotEmpty())
    }

    @Test fun `empty input is unknown`() {
        val command = parse("   ")
        assertEquals(VoiceAction.UNKNOWN, command.action)
        assertNull(command.target)
    }

    @Test fun `confident on a clear command`() = assertTrue(parse("เปิด YouTube").confidence >= 0.9f)
}
