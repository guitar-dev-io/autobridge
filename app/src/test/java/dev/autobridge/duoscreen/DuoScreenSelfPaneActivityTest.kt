package dev.autobridge.duoscreen

import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The other half of :duoscreen's `DuoScreenSelfPaneTest`, which has to live here.
 *
 * `DuoScreenSelfPane.ACTIVITY` names the one activity of ours a Duo Screen pane may run, as a
 * string, because the module builds a ComponentName from the runtime package and cannot depend on
 * :app to name the class directly. That makes a rename of `BrowserActivity` invisible to the
 * compiler: it would surface as a blank pane on the head unit and nowhere else. This is the test
 * that fails instead.
 *
 * It reads the constant back by reflection rather than duplicating the literal, so there is still
 * one source of truth — a copy here would simply be renamed alongside the real one and keep
 * passing. :duoscreen is only on the classpath for the personal and lab flavors, so on safe there
 * is nothing to check and the test skips.
 */
class DuoScreenSelfPaneActivityTest {
    @Test fun theActivityTheModuleNamesForItsOwnPaneStillExists() {
        val loader = javaClass.classLoader!!
        val selfPane = runCatching {
            Class.forName("dev.autobridge.duoscreen.system.DuoScreenSelfPane", false, loader)
        }.getOrNull()
        assumeTrue("Duo Screen is not in this flavor", selfPane != null)

        val activity = selfPane!!.getDeclaredField("ACTIVITY").apply { isAccessible = true }
            .get(null) as String
        assertNotNull(
            "DuoScreenSelfPane.ACTIVITY names $activity, which no longer exists",
            Class.forName(activity, false, loader)
        )
    }
}
