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
import dev.autobridge.R
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
            Tile(carContext.getString(R.string.car_driving_browser), dev.autobridge.R.drawable.ic_car_panel) {
                CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) }
            },
            Tile(carContext.getString(R.string.car_driving_mirror), dev.autobridge.R.drawable.ic_car_home) {
                CarNavigation.open(screenManager, "MirrorCarScreen") { MirrorCarScreen(carContext) }
            },
            Tile(carContext.getString(R.string.car_driving_media), dev.autobridge.R.drawable.ic_car_panel) {
                CarNavigation.open(screenManager, "CarMediaCenterScreen") { CarMediaCenterScreen(carContext) }
            },
            Tile(carContext.getString(R.string.car_driving_agent), dev.autobridge.R.drawable.ic_car_panel) {
                CarNavigation.open(screenManager, "CarAgentScreen") { CarAgentScreen(carContext) }
            },
            Tile(carContext.getString(R.string.car_driving_resume), dev.autobridge.R.drawable.ic_car_panel) {
                AgentCommandRouter.execute(
                    this, carContext,
                    AgentCommandRouter.Command(AgentCommandRouter.AgentAction.RESUME_MEDIA)
                )
            },
            Tile(carContext.getString(R.string.car_driving_recent), dev.autobridge.R.drawable.ic_car_panel) {
                CarNavigation.open(screenManager, "CarRecentScreen") { CarRecentScreen(carContext) }
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
                    .setTitle(carContext.getString(R.string.car_driving_title))
                    .setStartHeaderAction(Action.APP_ICON)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.APPS))
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
