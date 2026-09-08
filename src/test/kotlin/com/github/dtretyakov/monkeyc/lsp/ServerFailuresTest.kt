package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.project.ConnectIqEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Answering the question the server's own message declines to.
 *
 * `Failed to load SDK supplementary files: null.` is a real line from a real bug report, and the
 * `null` is the exception's message. What it means was eventually established by a forum thread:
 * the device data could not be read. The plugin can see that without being told.
 */
class ServerFailuresTest {

    private val healthy = ServerFailures.Environment(
        devicesDownloaded = 173,
        unreadableDevices = emptyList(),
        awkwardPaths = emptyList(),
    )

    @Test
    fun `an ordinary log line is not a failure`() {
        // Called for every line the server logs, and it logs a great many.
        assertNull(ServerFailures.explain("Full workspace build successful", healthy))
        assertNull(ServerFailures.explain("Indexing /Users/x/App.mc", healthy))
    }

    @Test
    fun `no devices downloaded is named as the cause`() {
        val cause = ServerFailures.explain(
            "ERROR: Unable to load devices: null",
            healthy.copy(devicesDownloaded = 0),
        )

        assertNotNull(cause)
        assertEquals(ConnectIqEnvironment.Fix.SDK_MANAGER, cause!!.fix)
        assertTrue(cause.detail.contains("none are downloaded"), cause.detail)
    }

    @Test
    fun `devices that cannot be read are named, and counted`() {
        val cause = ServerFailures.explain(
            "Failed to load SDK supplementary files: null.",
            healthy.copy(unreadableDevices = listOf("fenix7", "fr965")),
        )!!

        assertTrue(cause.detail.contains("fenix7"), cause.detail)
        assertEquals(ConnectIqEnvironment.Fix.SDK_MANAGER, cause.fix)
    }

    @Test
    fun `a path outside ASCII is offered only when there is nothing better to say`() {
        val awkward = healthy.copy(awkwardPaths = listOf("/Users/Дмитрий/watch"))

        val cause = ServerFailures.explain("Unable to load devices: null", awkward)!!
        assertTrue(cause.detail.contains("Дмитрий"), cause.detail)

        // A device that cannot be read is a better answer than a suspicious path, so it wins.
        val both = awkward.copy(unreadableDevices = listOf("fenix7"))
        assertTrue(ServerFailures.explain("Unable to load devices: null", both)!!.detail.contains("fenix7"))
    }

    @Test
    fun `a healthy machine still gets an answer rather than the server's silence`() {
        val cause = ServerFailures.explain("Unable to load devices: null", healthy)!!

        assertEquals(ConnectIqEnvironment.Fix.SDK_MANAGER, cause.fix)
        assertTrue(cause.detail.contains("no reason"), cause.detail)
    }

    @Test
    fun `a jungle it cannot read is its own cause`() {
        val cause = ServerFailures.explain("Failed to load jungle files", healthy)!!

        assertTrue(cause.detail.contains("relative path"), cause.detail)
        assertNull(cause.fix, "the SDK Manager has nothing to do with a jungle file")
    }

    @Test
    fun `a file the server does not recognise points at the path it was opened by`() {
        val cause = ServerFailures.explain("Could not find file context.", healthy)!!

        assertTrue(cause.detail.contains("symlink"), cause.detail)
    }

    @Test
    fun `only characters outside ASCII count as awkward`() {
        // A space is the normal case on Windows; pointing at it would send most people chasing
        // something that is almost certainly not their problem.
        assertFalse(ServerFailures.isAwkward("C:\\Users\\John Smith\\watch"))
        assertFalse(ServerFailures.isAwkward("/Users/dmitry/projects/watch"))
        assertTrue(ServerFailures.isAwkward("/Users/Дмитрий/watch"))
    }
}
