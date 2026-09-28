package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import dev.autobridge.browser.CarWebRenderer

/**
 * Find-in-page controls for the native car browser. The [CarWebRenderer] performs the actual native
 * search (findAllAsync / findNext) and keeps highlighting the page underneath; this screen only
 * drives previous/next and shows the live match counter ("2/8"). Kept as a [ListTemplate] because
 * the Car App Library has no inline find bar.
 */
class CarBrowserFindScreen(
    carContext: CarContext,
    private val renderer: CarWebRenderer,
    private val initialQuery: String,
) : Screen(carContext) {

    init {
        // Refresh the counter whenever the renderer reports new match results.
        renderer.onFindResult = { _, _ -> carContext.mainExecutor.execute { invalidate() } }
        if (initialQuery.isNotBlank()) renderer.findInPage(initialQuery)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                // Clearing find highlights when leaving keeps the page clean; the renderer stays alive.
                renderer.clearFind()
                renderer.onFindResult = null
            }
        })
    }

    override fun onGetTemplate(): Template {
        val query = renderer.findQuery.ifBlank { initialQuery }
        val counter = if (renderer.findMatchCount > 0) {
            "${renderer.findActiveMatch}/${renderer.findMatchCount}"
        } else if (query.isNotBlank()) {
            "No matches"
        } else {
            ""
        }

        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(if (query.isBlank()) "Find in page" else "\"$query\"")
                    .addText(if (counter.isBlank()) "Type a term to search this page" else counter)
                    .setBrowsable(true)
                    .setOnClickListener { editQuery() }
                    .build()
            )
            .build()

        // A ListTemplate's ActionStrip accepts at most two actions; a third threw
        // "Action list exceeded max number of 2 actions" and crashed the app the moment a find was
        // submitted. Previous/next are the two that have nowhere else to live — changing the term
        // is already reachable by tapping the row below.
        val controls = ActionStrip.Builder()
            .addAction(iconAction(android.R.drawable.ic_media_previous) { renderer.findNext(false) })
            .addAction(iconAction(android.R.drawable.ic_media_next) { renderer.findNext(true) })
            .build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(if (counter.isBlank()) "Find in page" else "Find  •  $counter")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setActionStrip(controls)
            .setSingleList(list)
            .build()
    }

    private fun editQuery() {
        screenManager.pushForResult(CarBrowserSearchScreen(carContext, renderer.findQuery)) { result ->
            val text = result as? String ?: return@pushForResult
            renderer.findInPage(text)
            invalidate()
        }
    }

    private fun iconAction(resId: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build())
            .setOnClickListener { onClick() }
            .build()
}
