package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.testing.IdeTestCase
import com.redhat.devtools.lsp4ij.ServerStatus

/**
 * Telling a crash apart from a restart, and a bad minute apart from a bad afternoon.
 *
 * The counting exists for one sentence: "it keeps stopping, so starting it again is unlikely to be
 * the answer." VS Code reaches that conclusion and then says nothing, which is why every symptom
 * of a dead server gets reported as a missing feature instead.
 */
class MonkeyCServerHealthTest : IdeTestCase() {

    private val health get() = MonkeyCServerHealth.getInstance(project)

    // The light fixture hands every test in this class the same project, and therefore the same
    // service. Without this, one test's crashes are the next one's history.
    override fun setUp() {
        super.setUp()
        health.forget()
    }

    fun `test a server that stops without being asked has crashed`() {
        health.statusChanged(ServerStatus.started)

        assertTrue(health.statusChanged(ServerStatus.stopped) is MonkeyCServerHealth.Verdict.Crashed)
    }

    fun `test a stop we asked for is not a crash`() {
        // Every settings change restarts the server, because it reads them once at initialize.
        // Counting those would report our own housekeeping as the server falling over.
        health.statusChanged(ServerStatus.started)
        health.expectStop()

        assertNull(health.statusChanged(ServerStatus.stopped))
        assertEquals(0, health.crashesInWindow())
    }

    fun `test a server that never started is not a crash when it stops`() {
        assertNull(health.statusChanged(ServerStatus.stopped))
    }

    fun `test three crashes in the window change what there is to say`() {
        val verdicts = (1..3).map {
            health.statusChanged(ServerStatus.started)
            health.statusChanged(ServerStatus.stopped)
        }

        assertTrue(verdicts[0] is MonkeyCServerHealth.Verdict.Crashed)
        assertTrue(verdicts[1] is MonkeyCServerHealth.Verdict.Crashed)
        val last = verdicts[2]
        assertTrue(last is MonkeyCServerHealth.Verdict.KeepsCrashing)
        assertEquals(3, (last as MonkeyCServerHealth.Verdict.KeepsCrashing).times)
    }

    fun `test crashes outside the window are forgotten`() {
        val morning = 1_000_000L
        repeat(3) {
            health.statusChanged(ServerStatus.started, morning)
            health.statusChanged(ServerStatus.stopped, morning)
        }

        // Hours later, one crash is one crash again — not the fourth of a burst that ended long ago.
        val afternoon = morning + MonkeyCServerHealth.WINDOW_MILLIS * 10
        health.statusChanged(ServerStatus.started, afternoon)

        val verdict = health.statusChanged(ServerStatus.stopped, afternoon)
        assertTrue(verdict is MonkeyCServerHealth.Verdict.Crashed)
        assertEquals(1, health.crashesInWindow())
    }

    fun `test starting it again by hand clears the history`() {
        repeat(3) {
            health.statusChanged(ServerStatus.started)
            health.statusChanged(ServerStatus.stopped)
        }
        health.forget()
        health.statusChanged(ServerStatus.started)

        assertTrue(health.statusChanged(ServerStatus.stopped) is MonkeyCServerHealth.Verdict.Crashed)
    }
}
