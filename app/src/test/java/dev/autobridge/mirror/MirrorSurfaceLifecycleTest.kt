package dev.autobridge.mirror

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.car.app.SurfaceContainer
import dev.autobridge.car.ConnectionTimeline
import dev.autobridge.car.ConnectionTimeline.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The car-surface half of mirroring, as Android Auto drives it: a surface arrives, arrives again,
 * changes size, is replaced, and is destroyed with or without telling us which one.
 *
 * These pin today's behaviour before stability work changes anything around it. No projection is
 * attached (MediaProjection cannot be built in a JVM test), so every arrival is a fresh start and
 * the coordinator never creates a display; what is asserted is the surface bookkeeping and the
 * events [ConnectionTimeline] receives, which is what a car log is read from.
 */
class MirrorSurfaceLifecycleTest {
    private lateinit var controller: MirrorSurfaceController

    @Before fun setUp() {
        reset()
        controller = MirrorSurfaceController()
    }

    @After fun tearDown() = reset()

    private fun reset() {
        MirrorCoordinator.detachCarSurface(null)
        ReconnectTracker.reset()
        ConnectionTimeline.clear()
    }

    private fun surface() = Surface(SurfaceTexture(0))
    private fun container(surface: Surface?, width: Int = 800, height: Int = 480, dpi: Int = 160) =
        SurfaceContainer(surface, width, height, dpi)

    private fun kinds() = ConnectionTimeline.recent().map { it.kind }

    @Test fun aFirstSurfaceIsAvailableAndLoggedAsAnAttach() {
        val s = surface()
        assertTrue(controller.onSurfaceAvailable(container(s)))
        val state = controller.state.value
        assertEquals(SurfaceLifecycle.AVAILABLE, state.lifecycle)
        assertSame(s, state.surface)
        assertEquals(800, state.width)
        assertEquals(listOf(Kind.SURFACE_ATTACHED), kinds())
    }

    @Test fun aRepeatedIdenticalCallbackIsNotANewEvent() {
        val s = surface()
        controller.onSurfaceAvailable(container(s))
        controller.onSurfaceAvailable(container(s))
        assertEquals(SurfaceLifecycle.AVAILABLE, controller.state.value.lifecycle)
        assertEquals(listOf(Kind.SURFACE_ATTACHED), kinds())
        assertEquals(0, ReconnectTracker.reconnectCount)
    }

    @Test fun aNewSizeOnTheSameSurfaceIsAChangeNotADrop() {
        val s = surface()
        controller.onSurfaceAvailable(container(s))
        controller.onSurfaceAvailable(container(s, width = 1280, height = 720))
        val state = controller.state.value
        assertEquals(SurfaceLifecycle.CHANGED, state.lifecycle)
        assertEquals(1280, state.width)
        assertEquals(listOf(Kind.SURFACE_ATTACHED), kinds())
    }

    @Test fun aReplacementSurfaceIsLoggedAsDetachThenAttach() {
        controller.onSurfaceAvailable(container(surface()))
        val replacement = surface()
        controller.onSurfaceAvailable(container(replacement))
        assertSame(replacement, controller.state.value.surface)
        assertEquals(listOf(Kind.SURFACE_ATTACHED, Kind.SURFACE_DETACHED, Kind.SURFACE_ATTACHED), kinds())
        assertEquals("replaced", ConnectionTimeline.recent()[1].detail)
    }

    @Test fun destroyingTheTrackedSurfaceDetachesIt() {
        val s = surface()
        controller.onSurfaceAvailable(container(s))
        assertTrue(controller.onSurfaceDestroyed(container(s)))
        val state = controller.state.value
        assertEquals(SurfaceLifecycle.DESTROYED, state.lifecycle)
        assertNull(state.surface)
        assertFalse(state.isAvailable)
        assertEquals(listOf(Kind.SURFACE_ATTACHED, Kind.SURFACE_DETACHED), kinds())
    }

    @Test fun aStaleDestroyForAnotherSurfaceIsIgnored() {
        val current = surface()
        controller.onSurfaceAvailable(container(current))
        assertFalse(controller.onSurfaceDestroyed(container(surface())))
        assertSame(current, controller.state.value.surface)
        assertEquals(listOf(Kind.SURFACE_ATTACHED), kinds())
    }

    /** Android Auto sends this when the driver switches apps; the tracked surface must be let go. */
    @Test fun aDestroyWithoutIdentityDetachesWhatIsTracked() {
        controller.onSurfaceAvailable(container(surface()))
        assertTrue(controller.onSurfaceDestroyed(container(null)))
        assertNull(controller.state.value.surface)
        assertEquals(listOf(Kind.SURFACE_ATTACHED, Kind.SURFACE_DETACHED), kinds())
    }

    @Test fun aDestroyWithoutIdentityAndNothingTrackedDoesNothing() {
        assertFalse(controller.onSurfaceDestroyed(container(null)))
        assertTrue(kinds().isEmpty())
    }

    @Test fun aSurfaceAfterADestroyStartsAgainAndBumpsTheGeneration() {
        val first = surface()
        controller.onSurfaceAvailable(container(first))
        val generation = controller.state.value.generation
        controller.onSurfaceDestroyed(container(first))
        controller.onSurfaceAvailable(container(surface()))
        val state = controller.state.value
        assertEquals(SurfaceLifecycle.AVAILABLE, state.lifecycle)
        assertTrue(state.generation > generation + 1)
        assertEquals(
            listOf(Kind.SURFACE_ATTACHED, Kind.SURFACE_DETACHED, Kind.SURFACE_ATTACHED),
            kinds()
        )
    }

    @Test fun anArrivalWithoutASurfaceIsRefused() {
        assertFalse(controller.onSurfaceAvailable(container(null)))
        assertEquals(SurfaceLifecycle.UNAVAILABLE, controller.state.value.lifecycle)
        assertTrue(kinds().isEmpty())
    }
}
