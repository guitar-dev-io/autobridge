package dev.autobridge.ui

import dev.autobridge.ui.PhoneNav.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNavTest {

    @Test fun bottomBarHasExactlyFourTabsInOrder() {
        assertEquals(
            listOf(Route.HOME, Route.CONTROL, Route.APPS, Route.SETTINGS),
            PhoneNav.tabs.map { it.route }
        )
        assertEquals(listOf("Home", "Control", "Apps", "Settings"), PhoneNav.tabs.map { it.label })
    }

    @Test fun everyRouteHighlightsOneOfTheTabs() {
        val tabRoutes = PhoneNav.tabs.map { it.route }.toSet()
        Route.entries.forEach { route ->
            assertTrue("$route lights no tab", PhoneNav.tabFor(route) in tabRoutes)
        }
    }

    @Test fun drillDownsKeepTheirParentTabLit() {
        assertEquals(Route.SETTINGS, PhoneNav.tabFor(Route.CAR_CONNECTION))
        assertEquals(Route.SETTINGS, PhoneNav.tabFor(Route.AGENT_COMMANDS))
        assertEquals(Route.SETTINGS, PhoneNav.tabFor(Route.DEBUG))
        assertEquals(Route.CONTROL, PhoneNav.tabFor(Route.CONTROL_HISTORY))
        assertEquals(Route.APPS, PhoneNav.tabFor(Route.PROFILE))
        assertEquals(Route.HOME, PhoneNav.tabFor(Route.HOME_MORE))
    }

    @Test fun savedNamesFromTheOldLayoutStillRestore() {
        assertEquals(Route.CONTROL, PhoneNav.parse("REMOTE"))
        assertEquals(Route.CAR_CONNECTION, PhoneNav.parse("DEVICES"))
        assertEquals(Route.CONTROL, PhoneNav.parse("CONTROL_CENTER"))
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

    @Test fun selectingATabClearsTheStack() {
        val stack = PhoneNav.BackStack()
        stack.onNavigate(Route.SETTINGS, Route.ABOUT)
        stack.onNavigate(Route.ABOUT, Route.APPS)
        assertTrue(stack.entries.isEmpty())
        assertEquals(Route.HOME, stack.back(Route.APPS))
    }

    @Test fun backReturnsToWhereAChildWasOpenedFrom() {
        val stack = PhoneNav.BackStack()
        stack.onNavigate(Route.SETTINGS, Route.AGENT_COMMANDS)
        stack.onNavigate(Route.AGENT_COMMANDS, Route.CONTROL_HISTORY)
        assertEquals(Route.AGENT_COMMANDS, stack.back(Route.CONTROL_HISTORY))
    }

    @Test fun reopeningAPageOnTheStackUnwindsInsteadOfLooping() {
        val stack = PhoneNav.BackStack()
        stack.onNavigate(Route.SETTINGS, Route.PROFILES)
        stack.onNavigate(Route.PROFILES, Route.PROFILE)
        stack.onNavigate(Route.PROFILE, Route.PROFILES)
        assertEquals(listOf(Route.SETTINGS), stack.entries)
    }

    @Test fun anOrphanedChildFallsBackToItsParent() {
        val stack = PhoneNav.BackStack()
        assertEquals(Route.ADVANCED, stack.back(Route.DEBUG))
        assertEquals(Route.SETTINGS, stack.back(Route.CAR_CONNECTION))
        assertEquals(Route.CONTROL, stack.back(Route.CONTROL_HISTORY))
    }

    @Test fun onlyRootTabsAreRoots() {
        assertTrue(PhoneNav.isRoot(Route.CONTROL))
        assertFalse(PhoneNav.isRoot(Route.CAR_CONNECTION))
        assertFalse(PhoneNav.isRoot(Route.HOME_MORE))
    }
}
