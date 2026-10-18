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
                Kind.BUY_ME_A_COFFEE to "https://buymeacoffee.com/a",
                Kind.GITHUB_SPONSORS to "https://github.com/sponsors/a",
                Kind.KO_FI to "https://ko-fi.com/a",
            )
        )
        assertEquals(
            listOf(Kind.BUY_ME_A_COFFEE, Kind.GITHUB_SPONSORS, Kind.KO_FI, Kind.PROMPTPAY),
            channels.map { it.kind }
        )
    }

    @Test
    fun `the safe build never offers PromptPay`() {
        val kinds = SupportChannels.available(safeBuild = true).map { it.kind }
        assertEquals(listOf(Kind.BUY_ME_A_COFFEE), kinds.filter { it == Kind.BUY_ME_A_COFFEE })
        assert(Kind.PROMPTPAY !in kinds)
    }

    @Test
    fun `a blank link hides its channel`() {
        val kinds = SupportChannels.available(
            safeBuild = true,
            urls = mapOf(Kind.BUY_ME_A_COFFEE to "https://buymeacoffee.com/a", Kind.KO_FI to "  ")
        ).map { it.kind }
        assertEquals(listOf(Kind.BUY_ME_A_COFFEE), kinds)
    }

    @Test
    fun `the printed link drops the scheme and PromptPay has none`() {
        val channels = SupportChannels.available(
            safeBuild = false,
            urls = mapOf(Kind.BUY_ME_A_COFFEE to "https://buymeacoffee.com/guitar.story/")
        )
        assertEquals("buymeacoffee.com/guitar.story", channels.first().displayUrl)
        assertNull(channels.last().displayUrl)
    }
}
