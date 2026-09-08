package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.testing.IdeTestCase

/**
 * What a watch target means for the runs that cannot use one.
 *
 * The destination used to be a checkbox on one configuration, so these combinations could not
 * arise: nothing else could be pointed at a watch. Now that the target is one control beside the
 * Run button, they can, and each has to be refused in a sentence rather than by quietly building
 * the wrong thing — which is what "run the tests" would otherwise do, since Run No Evil only ever
 * runs in the simulator.
 */
class WatchTargetRefusalTest : IdeTestCase() {

    private val settings get() = MonkeyCSettings.getInstance(project)

    override fun tearDown() {
        settings.targetDevice = ""
        settings.targetOnWatch = false
        super.tearDown()
    }

    fun `test a watch target is carried by the toolbar setting`() {
        settings.targetDevice = "venu2"
        settings.targetOnWatch = true

        val target = settings.target!!
        assertEquals("venu2", target.device)
        assertTrue("the destination has to survive the round trip", target.onWatch)
        assertEquals("venu2 on the watch", target.describe())
    }

    fun `test a configuration that pins nothing follows the toolbar`() {
        settings.targetDevice = "venu2"
        settings.targetOnWatch = true

        // A run configuration with no device of its own: the whole point of the chip is that this
        // is the common case and it decides.
        assertTrue(MonkeyCLaunch.onWatch(project, MonkeyCRunOptions()))
    }

    fun `test a configuration pinned to the simulator is not dragged onto the watch`() {
        settings.targetDevice = "venu2"
        settings.targetOnWatch = true

        val pinned = MonkeyCRunOptions().apply {
            device = "fenix7"
            forDevice = false
        }

        assertFalse("a pin means it, in both halves", MonkeyCLaunch.onWatch(project, pinned))
    }

    fun `test a configuration pinned to the watch keeps its destination`() {
        settings.targetDevice = "fenix7"
        settings.targetOnWatch = false

        val pinned = MonkeyCRunOptions().apply {
            device = "venu2"
            forDevice = true
        }

        assertTrue(MonkeyCLaunch.onWatch(project, pinned))
    }
}
