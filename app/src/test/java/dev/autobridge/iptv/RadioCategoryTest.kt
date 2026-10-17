package dev.autobridge.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The stable cache keys the fetcher keys its cache and stale-guard on. */
class RadioCategoryTest {
    @Test fun `in-country key is stable`() {
        assertEquals("in_country", RadioCategory.IN_COUNTRY.cacheKey())
    }

    @Test fun `world key is stable`() {
        assertEquals("world", RadioCategory.WORLD.cacheKey())
    }

    @Test fun `local key carries the state`() {
        assertEquals("local:Chiang Mai", RadioCategory.LOCAL.cacheKey("Chiang Mai"))
    }

    @Test fun `local key with no state is empty-scoped`() {
        assertEquals("local:", RadioCategory.LOCAL.cacheKey())
    }

    @Test fun `podcasts key is the joined tag set`() {
        assertEquals("podcasts:talk+news+podcast", RadioCategory.PODCASTS.cacheKey())
    }

    @Test fun `favorites has no fetch key`() {
        assertNull(RadioCategory.FAVORITES.cacheKey())
    }

    @Test fun `display order is in-country local world podcasts favorites`() {
        assertEquals(
            listOf(
                RadioCategory.IN_COUNTRY,
                RadioCategory.LOCAL,
                RadioCategory.WORLD,
                RadioCategory.PODCASTS,
                RadioCategory.FAVORITES
            ),
            RadioCategory.entries.toList()
        )
    }
}
