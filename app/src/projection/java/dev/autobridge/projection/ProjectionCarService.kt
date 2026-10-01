package dev.autobridge.projection

import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/** Entry point Android Auto binds for the projection route; hosts [ProjectionBrowserActivity]. */
class ProjectionCarService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> = ProjectionBrowserActivity::class.java
}
