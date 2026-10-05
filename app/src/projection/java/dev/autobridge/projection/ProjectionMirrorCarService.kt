package dev.autobridge.projection

import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/**
 * Second projection entry point, beside [ProjectionCarService]: Android Auto lists one icon per
 * CATEGORY_PROJECTION service, so the mirror needs its own rather than sharing the browser's.
 */
class ProjectionMirrorCarService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> = ProjectionMirrorActivity::class.java
}
