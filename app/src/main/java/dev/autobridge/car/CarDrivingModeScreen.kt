package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import dev.autobridge.agent.AgentCommandRouter
import dev.autobridge.core.state.UiModeStore

/**
 * Driving-aware UI mode. This is a PRESENTATION mode only: it shows larger, decluttered controls
 * and prioritizes key actions. It does NOT restrict, hide, lock, or disable any feature — every
 * AutoBridge feature stays reachable (directly here, and fully via "Show all"). It never touches
 * playback, mirror sessions, browser state, or the platform safety machinery. Entering/leaving this
 * screen does not destroy any feature's live state because each feature owns its own state stores
 * and screens; this screen only navigates to them.
 */
class CarDrivingModeScreen(carContext: CarContext) : Screen(carContext) {

    private data class Tile(val title: String, val iconRes: Int, val onClick: () -> Unit)

    override fun onGetTemplate(): Template {
        val tiles = listOf(
            Tile("Browser", dev.autobridge.R.drawable.ic_car_panel) {
                screenManager.push(CarBrowserScreen(carContext))
            },
            Tile("Mirror", dev.autobridge.R.drawable.ic_car_home) {
                screenManager.push(MirrorCarScreen(carContext))
            },
            Tile("Media", dev.autobridge.R.drawable.ic_car_panel) {
                screenManager.push(CarMediaCenterScreen(carContext))
            },
            Tile("Agent", dev.autobridge.R.drawable.ic_car_panel) {
                screenManager.push(CarAgentScreen(carContext))
            },
            Tile("Resume", dev.autobridge.R.drawable.ic_car_panel) {
                AgentCommandRouter.execute(
                    this, carContext,
                    AgentCommandRouter.Command(AgentCommandRouter.AgentAction.RESUME_MEDIA)
                )
            },
            Tile("Recent", dev.autobridge.R.drawable.ic_car_panel) {
                screenManager.push(CarRecentScreen(carContext))
            }
        )

        val grid = ItemList.Builder()
        tiles.forEach { tile ->
            grid.addItem(
                GridItem.Builder()
                    .setTitle(tile.title)
                    .setImage(icon(tile.iconRes), GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener { tile.onClick() }
                    .build()
            )
        }

        return GridTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("AutoBridge • Driving Mode")
                    .setStartHeaderAction(Action.APP_ICON)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setTitle("Show all")
                            .setOnClickListener {
                                // "Show all" returns to the full home; Driving Mode never removes
                                // access to any feature, it only re-presents the important ones.
                                UiModeStore.setDriving(false)
                                screenManager.push(CarHomeDashboardScreen(carContext))
                            }
                            .build()
                    )
                    .build()
            )
            .setSingleList(grid.build())
            .build()
    }

    private fun icon(resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()
}
