package dev.autobridge.input

import android.graphics.Rect
import dev.autobridge.core.model.Insets
import dev.autobridge.core.model.RotationMode
import dev.autobridge.core.model.ScaleMode
import kotlin.math.max
import kotlin.math.min

/** Compatibility name retained for existing settings and tests. */
enum class DisplayProfile {
    FIT,
    FILL,
    STRETCH,
    ONE_TO_ONE;

    fun toScaleMode(): ScaleMode = when (this) {
        FIT -> ScaleMode.FIT
        FILL -> ScaleMode.FILL
        STRETCH -> ScaleMode.STRETCH
        ONE_TO_ONE -> ScaleMode.ONE_TO_ONE
    }

    companion object {
        fun fromScaleMode(mode: ScaleMode): DisplayProfile = when (mode) {
            ScaleMode.FIT -> FIT
            ScaleMode.FILL -> FILL
            ScaleMode.STRETCH -> STRETCH
            ScaleMode.ONE_TO_ONE -> ONE_TO_ONE
        }
    }
}

/** A floating-point rectangle in car-surface coordinates. */
data class ContentBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isValid: Boolean get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && width > 0f && height > 0f

    fun contains(x: Float, y: Float): Boolean =
        isValid && x >= left && x <= right && y >= top && y <= bottom

    fun intersect(other: ContentBounds): ContentBounds? {
        val result = ContentBounds(
            left = max(left, other.left),
            top = max(top, other.top),
            right = min(right, other.right),
            bottom = min(bottom, other.bottom)
        )
        return result.takeIf { it.isValid }
    }

    companion object {
        fun surface(width: Int, height: Int): ContentBounds? =
            if (width > 0 && height > 0) ContentBounds(0f, 0f, width.toFloat(), height.toFloat()) else null

        fun fromRect(rect: Rect): ContentBounds =
            ContentBounds(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
    }
}

/**
 * Immutable transform information shared by mirror geometry and touch mapping.
 *
 * The car coordinates are first constrained to [viewportBounds], then interpreted against
 * [renderedBounds]. FIT and ONE_TO_ONE reject letterbox bars; FILL accepts the viewport and maps
 * the cropped content; STRETCH maps independently on each axis. Rotation converts the oriented
 * logical phone coordinates back to the raw phone display coordinates used by input injection.
 */
data class DisplayTransformInfo(
    val viewportBounds: ContentBounds,
    val renderedBounds: ContentBounds,
    val phoneWidth: Int,
    val phoneHeight: Int,
    val orientedPhoneWidth: Int,
    val orientedPhoneHeight: Int,
    val scaleX: Float,
    val scaleY: Float,
    val rotationQuarterTurns: Int,
    val profile: DisplayProfile,
    val touchOffsetX: Float = 0f,
    val touchOffsetY: Float = 0f
) {
    val isValid: Boolean
        get() = viewportBounds.isValid && renderedBounds.isValid &&
            phoneWidth > 0 && phoneHeight > 0 && orientedPhoneWidth > 0 && orientedPhoneHeight > 0 &&
            scaleX.isFinite() && scaleY.isFinite() && scaleX > 0f && scaleY > 0f

    fun mapPoint(carX: Float, carY: Float): CoordinateMapper.Point? {
        if (!isValid || !carX.isFinite() || !carY.isFinite() || !viewportBounds.contains(carX, carY)) {
            return null
        }
        if (profile == DisplayProfile.FIT || profile == DisplayProfile.ONE_TO_ONE) {
            if (!renderedBounds.contains(carX, carY)) return null
        }

        val orientedX = ((carX - renderedBounds.left) / scaleX)
            .coerceIn(0f, orientedPhoneWidth - 1f)
        val orientedY = ((carY - renderedBounds.top) / scaleY)
            .coerceIn(0f, orientedPhoneHeight - 1f)
        val raw = when (rotationQuarterTurns.mod(4)) {
            0 -> CoordinateMapper.Point(orientedX, orientedY)
            // Raw display rotated clockwise into the oriented surface.
            1 -> CoordinateMapper.Point(orientedY, phoneHeight - 1f - orientedX)
            2 -> CoordinateMapper.Point(phoneWidth - 1f - orientedX, phoneHeight - 1f - orientedY)
            // Raw display rotated counter-clockwise into the oriented surface.
            else -> CoordinateMapper.Point(phoneWidth - 1f - orientedY, orientedX)
        }
        return CoordinateMapper.Point(
            x = (raw.x + touchOffsetX).coerceIn(0f, phoneWidth - 1f),
            y = (raw.y + touchOffsetY).coerceIn(0f, phoneHeight - 1f)
        )
    }

    fun debugMap(carX: Float, carY: Float): DebugCoordinates? {
        val point = mapPoint(carX, carY) ?: return null
        val normalizedX = ((carX - viewportBounds.left) / viewportBounds.width).coerceIn(0f, 1f)
        val normalizedY = ((carY - viewportBounds.top) / viewportBounds.height).coerceIn(0f, 1f)
        return DebugCoordinates(carX, carY, normalizedX, normalizedY, point.x, point.y)
    }
}

data class DebugCoordinates(
    val carX: Float,
    val carY: Float,
    val normalizedX: Float,
    val normalizedY: Float,
    val phoneX: Float,
    val phoneY: Float
)

/**
 * Single source of truth for mirror and input geometry. The direct AUTO_MIRROR renderer currently
 * has an OS-owned FIT transform; the richer resolver is ready for an own-content renderer and is
 * already used for exact touch/diagnostic mapping.
 */
object DisplayTransform {
    @Volatile
    var activeProfile: DisplayProfile = DisplayProfile.FIT
        private set

    @Volatile
    var activeRotationMode: RotationMode = RotationMode.AUTO
        private set

    @Volatile
    private var activeVisibleBounds: ContentBounds? = null

    @Volatile
    private var activeSafeInsets: Insets = Insets.ZERO

    /**
     * Visual card margin applied on top of [activeSafeInsets], used only by the SELF_DRAWN
     * pipeline to render the mirrored phone as an inset rounded card (Fermata-style) rather than a
     * full-bleed image. AUTO_MIRROR never sets this: its pixels are placed by the OS directly onto
     * the surface with no margin, so a nonzero value here would desync touch mapping from what is
     * actually drawn. [SelfDrawnMirrorEngine] is solely responsible for setting/clearing it.
     */
    @Volatile
    private var activeCardMargin: Insets = Insets.ZERO

    @Volatile
    private var activeTouchOffsetX: Float = 0f

    @Volatile
    private var activeTouchOffsetY: Float = 0f

    fun setScaleMode(mode: ScaleMode) {
        activeProfile = DisplayProfile.fromScaleMode(mode)
    }

    fun setScaleMode(profile: DisplayProfile) {
        activeProfile = profile
    }

    fun setRotationMode(mode: RotationMode) {
        activeRotationMode = mode
    }

    fun setVisibleBounds(bounds: ContentBounds?) {
        activeVisibleBounds = bounds?.takeIf { it.isValid }
    }

    fun setSafeInsets(insets: Insets) {
        activeSafeInsets = insets
    }

    /** Sets/clears the SELF_DRAWN card margin. See [activeCardMargin]. */
    fun setCardMargin(insets: Insets) {
        activeCardMargin = insets
    }

    /** Current card margin, so renderer and touch mapping share one source of truth. */
    val cardMargin: Insets get() = activeCardMargin

    fun setTouchOffsets(x: Float, y: Float) {
        activeTouchOffsetX = x.takeIf { it.isFinite() } ?: 0f
        activeTouchOffsetY = y.takeIf { it.isFinite() } ?: 0f
    }

    fun resetSurfaceState() {
        activeVisibleBounds = null
        activeSafeInsets = Insets.ZERO
        activeCardMargin = Insets.ZERO
        activeTouchOffsetX = 0f
        activeTouchOffsetY = 0f
    }

    fun resolve(
        carWidth: Int,
        carHeight: Int,
        phoneWidth: Int,
        phoneHeight: Int,
        profile: DisplayProfile = activeProfile,
        rotationMode: RotationMode = activeRotationMode,
        safeInsets: Insets = activeSafeInsets,
        cardMargin: Insets = activeCardMargin,
        visibleBounds: ContentBounds? = activeVisibleBounds,
        touchOffsetX: Float = activeTouchOffsetX,
        touchOffsetY: Float = activeTouchOffsetY
    ): DisplayTransformInfo? {
        if (carWidth <= 0 || carHeight <= 0 || phoneWidth <= 0 || phoneHeight <= 0) return null
        val full = ContentBounds.surface(carWidth, carHeight) ?: return null
        val insetViewport = ContentBounds(
            left = safeInsets.left.toFloat() + cardMargin.left.toFloat(),
            top = safeInsets.top.toFloat() + cardMargin.top.toFloat(),
            right = carWidth - safeInsets.right.toFloat() - cardMargin.right.toFloat(),
            bottom = carHeight - safeInsets.bottom.toFloat() - cardMargin.bottom.toFloat()
        ).intersect(full) ?: return null
        val viewport = visibleBounds?.let { insetViewport.intersect(it) } ?: insetViewport
        if (!viewport.isValid) return null

        val orientation = orientedDimensions(phoneWidth, phoneHeight, rotationMode)
        val orientedWidth = orientation.first
        val orientedHeight = orientation.second
        val quarterTurns = orientation.third
        val viewportWidth = viewport.width
        val viewportHeight = viewport.height

        val scaleX: Float
        val scaleY: Float
        val renderedWidth: Float
        val renderedHeight: Float
        when (profile) {
            DisplayProfile.FIT -> {
                val scale = min(viewportWidth / orientedWidth, viewportHeight / orientedHeight)
                scaleX = scale
                scaleY = scale
                renderedWidth = orientedWidth * scale
                renderedHeight = orientedHeight * scale
            }
            DisplayProfile.FILL -> {
                val scale = max(viewportWidth / orientedWidth, viewportHeight / orientedHeight)
                scaleX = scale
                scaleY = scale
                renderedWidth = orientedWidth * scale
                renderedHeight = orientedHeight * scale
            }
            DisplayProfile.STRETCH -> {
                scaleX = viewportWidth / orientedWidth
                scaleY = viewportHeight / orientedHeight
                renderedWidth = viewportWidth
                renderedHeight = viewportHeight
            }
            DisplayProfile.ONE_TO_ONE -> {
                scaleX = 1f
                scaleY = 1f
                renderedWidth = orientedWidth.toFloat()
                renderedHeight = orientedHeight.toFloat()
            }
        }

        val rendered = ContentBounds(
            left = viewport.left + (viewportWidth - renderedWidth) / 2f,
            top = viewport.top + (viewportHeight - renderedHeight) / 2f,
            right = viewport.left + (viewportWidth - renderedWidth) / 2f + renderedWidth,
            bottom = viewport.top + (viewportHeight - renderedHeight) / 2f + renderedHeight
        )
        return DisplayTransformInfo(
            viewportBounds = viewport,
            renderedBounds = rendered,
            phoneWidth = phoneWidth,
            phoneHeight = phoneHeight,
            orientedPhoneWidth = orientedWidth,
            orientedPhoneHeight = orientedHeight,
            scaleX = scaleX,
            scaleY = scaleY,
            rotationQuarterTurns = quarterTurns,
            profile = profile,
            touchOffsetX = touchOffsetX,
            touchOffsetY = touchOffsetY
        )
    }

    fun mapPoint(
        carX: Float,
        carY: Float,
        carWidth: Int,
        carHeight: Int,
        phoneWidth: Int,
        phoneHeight: Int,
        profile: DisplayProfile = activeProfile
    ): CoordinateMapper.Point? =
        resolve(
            carWidth = carWidth,
            carHeight = carHeight,
            phoneWidth = phoneWidth,
            phoneHeight = phoneHeight,
            profile = profile
        )?.mapPoint(carX, carY)

    private fun orientedDimensions(
        phoneWidth: Int,
        phoneHeight: Int,
        rotationMode: RotationMode
    ): Triple<Int, Int, Int> = when (rotationMode) {
        RotationMode.AUTO, RotationMode.PHONE -> Triple(phoneWidth, phoneHeight, 0)
        RotationMode.PORTRAIT -> if (phoneWidth <= phoneHeight) {
            Triple(phoneWidth, phoneHeight, 0)
        } else {
            Triple(phoneHeight, phoneWidth, 3)
        }
        RotationMode.LANDSCAPE -> if (phoneWidth >= phoneHeight) {
            Triple(phoneWidth, phoneHeight, 0)
        } else {
            Triple(phoneHeight, phoneWidth, 1)
        }
    }
}
