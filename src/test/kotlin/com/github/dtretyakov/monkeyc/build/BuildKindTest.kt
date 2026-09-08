package com.github.dtretyakov.monkeyc.build

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What each kind of build needs, and what it is allowed to skip.
 *
 * The export case is the one worth a test of its own. Reusing an output that looks current is a
 * convenience everywhere else and a hazard here: an export is signed, published and then found to
 * be yesterday's code, which is the failure Garmin's own tooling is known for.
 */
class BuildKindTest {

    @Test
    fun `an export is never skipped`() {
        assertFalse(BuildKind.EXPORT.mayBeSkipped, "a stale .iq is discovered after it is published")
    }

    @Test
    fun `every other kind may be skipped`() {
        BuildKind.entries.filter { it != BuildKind.EXPORT }.forEach {
            assertTrue(it.mayBeSkipped, "$it rebuilds on the next run, so skipping costs nothing")
        }
    }
}
