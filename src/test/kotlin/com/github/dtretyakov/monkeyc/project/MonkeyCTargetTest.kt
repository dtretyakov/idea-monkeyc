package com.github.dtretyakov.monkeyc.project

import com.github.dtretyakov.monkeyc.project.MonkeyCTarget.Destination
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which target a run actually uses.
 *
 * The rule matters because the chip beside the Run button shows the answer. Showing the project
 * setting instead was the bug: a configuration pinned to one watch built that watch while the chip
 * named another, and a control the platform puts next to Run is supposed to describe the next
 * click.
 */
class MonkeyCTargetTest {

    private val simulator = MonkeyCTarget("fenix7", Destination.SIMULATOR)
    private val watch = MonkeyCTarget("venu2", Destination.WATCH)

    @Test
    fun `a configuration that names a device wins over the toolbar`() {
        // Pinning a configuration to one watch is the reason that field exists.
        assertEquals(watch, MonkeyCTarget.resolve(pinned = watch, chosen = simulator, default = "fr955"))
    }

    @Test
    fun `otherwise the toolbar decides`() {
        assertEquals(simulator, MonkeyCTarget.resolve(pinned = null, chosen = simulator, default = "fr955"))
    }

    @Test
    fun `an empty pin is not a pin`() {
        val unpinned = MonkeyCTarget("", Destination.WATCH)

        assertEquals(simulator, MonkeyCTarget.resolve(pinned = unpinned, chosen = simulator, default = null))
    }

    @Test
    fun `nothing chosen yet falls back to the default, in the simulator`() {
        // The first run of a fresh project: a device is picked for the user, and it goes to the
        // simulator, because that is the only destination that needs no hardware.
        val resolved = MonkeyCTarget.resolve(pinned = null, chosen = null, default = "fr955")!!

        assertEquals("fr955", resolved.device)
        assertEquals(Destination.SIMULATOR, resolved.destination)
    }

    @Test
    fun `with nothing anywhere there is no target`() {
        assertNull(MonkeyCTarget.resolve(pinned = null, chosen = null, default = null))
        assertNull(MonkeyCTarget.resolve(pinned = null, chosen = null, default = ""))
    }

    @Test
    fun `a configuration saved before destinations keeps meaning what it meant`() {
        // `forDevice` was a checkbox. A configuration that had it ticked is a watch target now.
        assertEquals(
            MonkeyCTarget("venu2", Destination.WATCH),
            MonkeyCTarget.ofOptions(device = "venu2", forDevice = true),
        )
        assertEquals(
            MonkeyCTarget("venu2", Destination.SIMULATOR),
            MonkeyCTarget.ofOptions(device = "venu2", forDevice = false),
        )
    }

    @Test
    fun `a configuration with no device of its own has no target of its own`() {
        // Even with the old checkbox ticked: without a device it follows the toolbar, and the
        // toolbar carries a destination too.
        assertNull(MonkeyCTarget.ofOptions(device = "", forDevice = true))
    }

    @Test
    fun `the destination is said out loud, because it changes what comes out`() {
        assertEquals("fenix 7", simulator.describe("fenix 7"))
        assertEquals("Venu 2 on the watch", watch.describe("Venu 2"))
        assertTrue(watch.onWatch)
    }
}
