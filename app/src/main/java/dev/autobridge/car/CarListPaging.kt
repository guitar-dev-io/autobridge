package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager

/**
 * Head units cap how many rows a list template may carry, and an IPTV account can return tens of
 * thousands of channels. This splits a list into host-sized pages and leaves one row free for the
 * "Show more" entry, so a large catalog stays browsable instead of being rejected or truncated.
 */
internal object CarListPaging {
    /** Rows a list template may contain on this host, with a conservative fallback. */
    fun limit(carContext: CarContext): Int = runCatching {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(DEFAULT_LIMIT).coerceIn(6, 200)

    /** One page of [items], leaving room for the trailing "Show more" row when one is needed. */
    fun <T> page(carContext: CarContext, items: List<T>, page: Int): Page<T> {
        val pageSize = (limit(carContext) - 1).coerceAtLeast(1)
        val start = (page * pageSize).coerceIn(0, items.size)
        val visible = items.subList(start, (start + pageSize).coerceAtMost(items.size))
        return Page(visible, start, start + visible.size < items.size)
    }

    data class Page<T>(val items: List<T>, val startIndex: Int, val hasMore: Boolean)

    private const val DEFAULT_LIMIT = 100
}
