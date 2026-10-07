package dev.autobridge.agent

import dev.autobridge.agent.AgentCommandRouter.AgentAction
import dev.autobridge.agent.AgentCommandRouter.Command
import dev.autobridge.entertainment.ContentAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phrases as a Thai/English speech recognizer actually returns them, including the polite
 * particles and spoken "ดอท" that typed input never has.
 */
class AgentCommandParserTest {

    private fun parse(text: String) = AgentCommandParser.parse(text)
    private fun action(text: String) = parse(text)?.action

    @Test
    fun `a spoken fill-up is a fuel entry and keeps what was said`() {
        val command = parse("เติม 40 ลิตร 1,400 บาท")!!
        assertEquals(AgentAction.LOG_FUEL, command.action)
        assertEquals("เติม 40 ลิตร 1,400 บาท", command.argument)
        assertEquals(AgentAction.LOG_FUEL, action("ชาร์จ 30 kWh 200 บาทครับ"))
    }

    @Test
    fun `a request that only mentions fuel is still a search`() {
        assertEquals(AgentAction.OPEN_URL, action("ค้นหาปั๊มน้ำมันใกล้ฉัน"))
        assertEquals(AgentAction.OPEN_URL, action("เติมน้ำมัน 40 ลิตร"))
    }

    @Test
    fun `blank input is not a command`() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("ครับ"))
    }

    @Test
    fun `thai open is not mistaken for thai close`() {
        // "เปิด" contains "ปิด"; a plain contains() flipped these two.
        assertEquals(AgentAction.ENTER_FULLSCREEN, action("เปิดเต็มจอ"))
        assertEquals(AgentAction.ENABLE_DESKTOP, action("เปิดโหมดเดสก์ท็อป"))
        assertEquals(AgentAction.EXIT_FULLSCREEN, action("ปิดเต็มจอ"))
        assertEquals(AgentAction.DISABLE_DESKTOP, action("ปิดโหมดเดสก์ท็อปครับ"))
    }

    @Test
    fun `fullscreen and desktop in english`() {
        assertEquals(AgentAction.ENTER_FULLSCREEN, action("fullscreen"))
        assertEquals(AgentAction.EXIT_FULLSCREEN, action("exit fullscreen"))
        assertEquals(AgentAction.EXIT_FULLSCREEN, action("ออกจากเต็มจอ"))
        assertEquals(AgentAction.ENABLE_DESKTOP, action("desktop mode"))
        assertEquals(AgentAction.DISABLE_DESKTOP, action("turn off desktop"))
        assertEquals(AgentAction.DISABLE_DESKTOP, action("โหมดมือถือ"))
    }

    @Test
    fun `feature shortcuts`() {
        assertEquals(AgentAction.OPEN_MIRROR, action("เปิดมิเรอร์หน่อย"))
        assertEquals(AgentAction.OPEN_MIRROR, action("แชร์หน้าจอ"))
        assertEquals(AgentAction.RESUME_MEDIA, action("เล่นเพลงต่อ"))
        assertEquals(AgentAction.RESUME_MEDIA, action("resume"))
        assertEquals(AgentAction.OPEN_RECENT, action("ล่าสุด"))
        assertEquals(AgentAction.OPEN_MEDIA, action("เปิดเพลง"))
        assertEquals(AgentAction.OPEN_MEDIA, action("music"))
    }

    @Test
    fun `spoken domains open the site`() {
        assertEquals(Command(AgentAction.OPEN_URL, "https://google.com"), parse("เปิด google.com"))
        assertEquals(Command(AgentAction.OPEN_URL, "https://google.com"), parse("เปิด google ดอท คอม ครับ"))
        assertEquals(Command(AgentAction.OPEN_URL, "https://pantip.com"), parse("go to pantip dot com"))
        assertEquals(Command(AgentAction.OPEN_URL, "https://youtube.com"), parse("YouTube.com"))
        assertEquals(Command(AgentAction.OPEN_URL, "https://example.com/a"), parse("http://Example.com/a"))
    }

    @Test
    fun `youtube opens home or searches`() {
        assertEquals(Command(AgentAction.OPEN_URL, "https://m.youtube.com"), parse("เปิดยูทูป"))
        assertEquals(Command(AgentAction.OPEN_URL, "https://m.youtube.com"), parse("open youtube"))
        assertEquals(
            Command(AgentAction.OPEN_URL, ContentAddress.youtubeSearch("เพลงสบายๆ")),
            parse("เปิดยูทูปเพลงสบายๆ")
        )
        assertEquals(
            Command(AgentAction.OPEN_URL, ContentAddress.youtubeSearch("ข่าวเช้า")),
            parse("ค้นหาข่าวเช้าในยูทูปครับ")
        )
    }

    @Test
    fun `search phrases go to google without the lead word`() {
        assertEquals(
            Command(AgentAction.OPEN_URL, ContentAddress.webSearch("ร้านกาแฟใกล้ฉัน")),
            parse("ค้นหาร้านกาแฟใกล้ฉัน")
        )
        assertEquals(
            Command(AgentAction.OPEN_URL, ContentAddress.webSearch("weather bangkok")),
            parse("search for weather bangkok")
        )
        // Lead words are only stripped at the edges, never from inside a Thai word.
        assertEquals(
            Command(AgentAction.OPEN_URL, ContentAddress.webSearch("วิธีแก้ปัญหา")),
            parse("ค้นหาวิธีแก้ปัญหา")
        )
    }

    @Test
    fun `open words alone open the browser`() {
        assertEquals(AgentAction.OPEN_BROWSER, action("เปิดเบราว์เซอร์"))
        assertEquals(AgentAction.OPEN_BROWSER, action("open browser"))
        assertEquals(AgentAction.OPEN_BROWSER, action("ค้นหา"))
    }

    @Test
    fun `anything else becomes a search`() {
        assertEquals(
            Command(AgentAction.OPEN_URL, ContentAddress.webSearch("ราคาน้ำมันวันนี้")),
            parse("ราคาน้ำมันวันนี้ครับ")
        )
    }
}
