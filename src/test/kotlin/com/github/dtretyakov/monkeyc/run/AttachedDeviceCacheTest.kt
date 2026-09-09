package com.github.dtretyakov.monkeyc.run

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Reading which watches are attached must never be the slow part.
 *
 * The first click on the device chip froze the IDE for several seconds, because the popup — built
 * on the UI thread — asked a question whose answer means walking the mount points and starting a
 * subprocess that is allowed twenty seconds to reply. The read and the look are separate now, and
 * only the look is slow.
 */
class AttachedDeviceCacheTest {

    @Test
    @Timeout(2)
    fun `reading the answer is instant, however long looking for it takes`() {
        // A popup opens on the UI thread and may ask this thousands of times while it is being
        // built; it must never be the thing that pauses.
        repeat(1_000) { GarminTarget.attachedDeviceIds() }
    }

    @Test
    @Timeout(2)
    fun `reading never triggers a look`() {
        // The regression this guards is one line: making the read compute when the cache is stale.
        // There is no watch on a build machine, so a read that looked would spend the mount-point
        // walk and a subprocess spawn here, and blow the timeout.
        val started = System.currentTimeMillis()
        repeat(50) { GarminTarget.attachedDeviceIds() }

        assertTrue(
            System.currentTimeMillis() - started < 500,
            "reading appears to be doing the work of looking",
        )
    }
}
