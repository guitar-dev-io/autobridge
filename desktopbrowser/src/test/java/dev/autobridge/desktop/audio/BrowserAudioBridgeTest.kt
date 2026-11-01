package dev.autobridge.desktop.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAudioBridgeTest {
    @Test fun onlyWholeStereoFramesAreUsable() {
        assertEquals(0, BrowserAudioBridge.usableBytes(0))
        assertEquals(0, BrowserAudioBridge.usableBytes(3))
        assertEquals(4, BrowserAudioBridge.usableBytes(4))
        assertEquals(3840, BrowserAudioBridge.usableBytes(3842))
        assertEquals(3840, BrowserAudioBridge.usableBytes(3843))
    }
}
