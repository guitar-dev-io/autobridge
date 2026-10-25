package dev.autobridge.support

import dev.autobridge.support.SupportChannels.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportChannelsTest {

    @Test
    fun `sideload builds offer every configured channel in tab order`() {
        val channels = SupportChannels.available(
            safeBuild = false,
            urls = mapOf(
                Kind.GITHUB_SPONSORS to "https://github.com/sponsors/a",
                Kind.KO_FI to "https://ko-fi.com/a",
            )
        )
        assertEquals(
            listOf(Kind.GITHUB_SPONSORS, Kind.KO_FI, Kind.PROMPTPAY),
            channels.map { it.kind }
        )
    }

    @Test
    fun `GitHub Sponsors is the tab the card opens on`() {
        assertEquals(Kind.GITHUB_SPONSORS, SupportChannels.available(safeBuild = false).first().kind)
    }

    @Test
    fun `the safe build never offers PromptPay`() {
        val kinds = SupportChannels.available(safeBuild = true).map { it.kind }
        assertEquals(listOf(Kind.KO_FI), kinds.filter { it == Kind.KO_FI })
        assert(Kind.PROMPTPAY !in kinds)
    }

    @Test
    fun `a blank link hides its channel`() {
        val kinds = SupportChannels.available(
            safeBuild = true,
            urls = mapOf(Kind.GITHUB_SPONSORS to "https://github.com/sponsors/a", Kind.KO_FI to "  ")
        ).map { it.kind }
        assertEquals(listOf(Kind.GITHUB_SPONSORS), kinds)
    }

    @Test
    fun `the printed link drops the scheme and PromptPay has none`() {
        val channels = SupportChannels.available(
            safeBuild = false,
            urls = mapOf(Kind.KO_FI to "https://ko-fi.com/guitardev/")
        )
        assertEquals("ko-fi.com/guitardev", channels.first().displayUrl)
        assertNull(channels.last().displayUrl)
    }
}
