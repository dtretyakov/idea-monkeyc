package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.SdkVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Why the product list is empty, when it is.
 *
 * Garmin's own extension has this on file as a bug: a user who had downloaded their watch found
 * Edit Products empty, and nothing said why — the cause was a minimum API level they could not see.
 * There are now three ways to end up with nothing to tick and each has a different remedy, so the
 * reason given has to be the filter that actually emptied the list.
 */
class EmptyReasonTest {

    private val minimum = SdkVersion.parse("5.0.0")

    @Test
    fun `a list with something in it needs no explanation`() {
        assertEquals(
            EmptyReason.None,
            EmptyReason.of(installed = 10, newEnough = 8, eligible = 3, minimum = minimum, appType = "datafield"),
        )
    }

    @Test
    fun `nothing downloaded is the first thing to say`() {
        assertEquals(
            EmptyReason.NothingDownloaded,
            EmptyReason.of(installed = 0, newEnough = 0, eligible = 0, minimum = minimum, appType = "datafield"),
        )
    }

    @Test
    fun `devices that are all too old say so, and name the level`() {
        assertEquals(
            EmptyReason.AllBelowMinimum(installed = 10, minimum = minimum!!),
            EmptyReason.of(installed = 10, newEnough = 0, eligible = 0, minimum = minimum, appType = "datafield"),
        )
    }

    @Test
    fun `devices that are new enough but run another kind of app say that instead`() {
        // The filter that emptied the list is the one to report: these devices are new enough, they
        // simply do not run data fields, and lowering the API level would not add one of them.
        assertEquals(
            EmptyReason.NoneRunsThisKind(installed = 8, appType = "datafield"),
            EmptyReason.of(installed = 10, newEnough = 8, eligible = 0, minimum = minimum, appType = "datafield"),
        )
    }

    @Test
    fun `a project with no app type falls back rather than blaming the type`() {
        assertEquals(
            EmptyReason.NothingDownloaded,
            EmptyReason.of(installed = 10, newEnough = 8, eligible = 0, minimum = null, appType = null),
        )
    }
}
