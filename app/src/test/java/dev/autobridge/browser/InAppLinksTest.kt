package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InAppLinksTest {
    @Test fun anAppLinkWithAFallbackPageOpensThatPage() {
        assertEquals(
            "https://m.youtube.com/watch?v=abc",
            InAppLinks.webPage(
                "intent://www.youtube.com/watch?v=abc#Intent;package=com.google.android.youtube;" +
                    "scheme=https;S.browser_fallback_url=https%3A%2F%2Fm.youtube.com%2Fwatch%3Fv%3Dabc;end"
            ),
        )
    }

    @Test fun anHttpsAppLinkWithoutAFallbackOpensItsOwnAddress() {
        assertEquals(
            "https://www.facebook.com/somepage",
            InAppLinks.webPage("intent://www.facebook.com/somepage#Intent;scheme=https;package=com.facebook.katana;end"),
        )
    }

    @Test fun anAppLinkWithNoWebPageStaysUnopened() {
        assertNull(InAppLinks.webPage("intent://scan/#Intent;scheme=zxing;package=com.example;end"))
        assertNull(InAppLinks.webPage("intent:#Intent;package=com.example;end"))
    }

    @Test fun anInsecureOrForeignFallbackIsRefused() {
        assertNull(InAppLinks.webPage("intent://x#Intent;S.browser_fallback_url=http%3A%2F%2Fexample.com;end"))
        assertNull(InAppLinks.webPage("intent://x#Intent;S.browser_fallback_url=javascript%3Aalert(1);end"))
    }

    @Test fun ordinaryLinksAreNotAppLinks() {
        assertNull(InAppLinks.webPage("https://example.com"))
        assertNull(InAppLinks.webPage("market://details?id=x"))
    }
}
