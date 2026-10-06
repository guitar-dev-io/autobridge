package dev.autobridge.ui

import dev.autobridge.ui.PhoneNav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNavTest {

    @Test fun homeIsTheOnlyRoot() {
        assertTrue(PhoneNav.isRoot(Route.HOME))
        Route.entries.filter { it != Route.HOME }.forEach { route ->
            assertFalse("$route should not be a root", PhoneNav.isRoot(route))
        }
    }

    @Test fun everyRouteHasAParentThatEventuallyReachesHome() {
        Route.entries.forEach { route ->
            var current = route
            var hops = 0
            while (current != Route.HOME) {
                current = PhoneNav.parentOf(current)
                hops++
                assertTrue("$route's parent chain did not reach Home", hops <= Route.entries.size)
            }
        }
    }

    @Test fun appsMovedUnderSettings() {
        assertEquals(Route.SETTINGS, PhoneNav.parentOf(Route.APPS))
        assertEquals(Route.APPS, PhoneNav.parentOf(Route.PROFILE))
    }

    @Test fun savedNamesFromOlderLayoutsStillRestore() {
        assertEquals(Route.CONTROL, PhoneNav.parse("REMOTE"))
        assertEquals(Route.CAR_CONNECTION, PhoneNav.parse("DEVICES"))
        assertEquals(Route.CONTROL, PhoneNav.parse("CONTROL_CENTER"))
        assertEquals(Route.APPS, PhoneNav.parse("PROFILES"))
        assertEquals(Route.HOME, PhoneNav.parse(null))
        assertEquals(Route.HOME, PhoneNav.parse("NOT_A_SCREEN"))
        Route.entries.forEach { assertEquals(it, PhoneNav.parse(it.name)) }
    }

    @Test fun backWalksTheDrillDownThenHomeThenExits() {
        val stack = PhoneNav.BackStack()
        stack.onNavigate(null, Route.HOME)
        stack.onNavigate(Route.HOME, Route.SETTINGS)
        stack.onNavigate(Route.SETTINGS, Route.ADVANCED)
        stack.onNavigate(Route.ADVANCED, Route.DEBUG)
        assertEquals(Route.ADVANCED, stack.back(Route.DEBUG))
        assertEquals(Route.SETTINGS, stack.back(Route.ADVANCED))
        assertEquals(Route.HOME, stack.back(Route.SETTINGS))
        assertNull(stack.back(Route.HOME))
    }

    @Test fun everyChildIsReachableFromHomeAndBackReturnsToHome() {
        Route.entries.filter { it != Route.HOME }.forEach { route ->
            val stack = PhoneNav.BackStack()
            stack.onNavigate(Route.HOME, route)
            assertEquals("$route should return to Home", Route.HOME, stack.back(route))
        }
    }

    @Test fun navigatingHomeClearsTheStack() {
        val stack = PhoneNav.BackStack()
        stack.onNavigate(Route.SETTINGS, Route.ABOUT)
        stack.onNavigate(Route.ABOUT, Route.HOME)
        assertTrue(stack.entries.isEmpty())
        assertNull(stack.back(Route.HOME))
    }

    @Test fun backReturnsToWhereAChildWasOpenedFrom() {
        val stack = PhoneNav.BackStack()
        stack.onNavigate(Route.SETTINGS, Route.AGENT_COMMANDS)
        stack.onNavigate(Route.AGENT_COMMANDS, Route.CONTROL_HISTORY)
        assertEquals(Route.AGENT_COMMANDS, stack.back(Route.CONTROL_HISTORY))
    }

    @Test fun reopeningAPageOnTheStackUnwindsInsteadOfLooping() {
        val stack = PhoneNav.BackStack()
        stack.onNavigate(Route.SETTINGS, Route.APPS)
        stack.onNavigate(Route.APPS, Route.PROFILE)
        stack.onNavigate(Route.PROFILE, Route.APPS)
        assertEquals(listOf(Route.SETTINGS), stack.entries)
    }

    @Test fun anOrphanedChildFallsBackToItsParent() {
        val stack = PhoneNav.BackStack()
        assertEquals(Route.ADVANCED, stack.back(Route.DEBUG))
        assertEquals(Route.SETTINGS, stack.back(Route.CAR_CONNECTION))
        assertEquals(Route.CONTROL, stack.back(Route.CONTROL_HISTORY))
        assertEquals(Route.SETTINGS, stack.back(Route.APPS))
    }
}
