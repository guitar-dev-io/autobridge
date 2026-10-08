package dev.autobridge.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceShortcutsTest {
    private val news = VoiceShortcut("ข่าวเช้า", VoiceShortcut.Kind.URL, "news.example.com")
    private val tv = VoiceShortcut("ดูทีวี", VoiceShortcut.Kind.SCREEN, VoiceScreen.TV.name)
    private val song = VoiceShortcut("เพลงประจำ", VoiceShortcut.Kind.COMMAND, "เล่น Bodyslam บน YouTube Music")
    private val all = listOf(news, tv, song)

    @Test fun aPhraseMatchesWhateverWayWhisperSpacesIt() {
        assertEquals(news, VoiceShortcutMatcher.match("ข่าว เช้า", all))
        assertEquals(news, VoiceShortcutMatcher.match("ข่าวเช้าครับ", all))
    }

    @Test fun aPhraseInsideASentenceStillMatches() {
        assertEquals(news, VoiceShortcutMatcher.match("ขอข่าวเช้าหน่อย", all))
    }

    @Test fun nothingMatchesWordsThatWereNotTaught() {
        assertNull(VoiceShortcutMatcher.match("เปิด YouTube", all))
    }

    @Test fun aShortcutWinsOverTheBuiltInRules() {
        val screen = VoiceCommandParser.parse("ดูทีวี", all)
        assertEquals(VoiceAction.OPEN_SCREEN, screen.action)
        assertEquals(VoiceScreen.TV, screen.screen)

        val url = VoiceCommandParser.parse("ข่าวเช้า", all)
        assertEquals(VoiceAction.OPEN_URL, url.action)
        assertEquals("https://news.example.com", url.url)

        val command = VoiceCommandParser.parse("เพลงประจำ", all)
        assertEquals(VoiceCommandParser.parse("เล่น Bodyslam บน YouTube Music").action, command.action)
    }

    @Test fun theStoreRoundTripsAndDropsBrokenEntries() {
        assertEquals(all, VoiceShortcutStore.decode(VoiceShortcutStore.encode(all)))
        assertEquals(emptyList<VoiceShortcut>(), VoiceShortcutStore.decode("""[{"phrase":"x","kind":"NOPE","value":"y"}]"""))
        assertEquals(emptyList<VoiceShortcut>(), VoiceShortcutStore.decode("not json"))
    }
}
