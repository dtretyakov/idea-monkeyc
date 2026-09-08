package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.testing.IdeTestCase
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * That a run always ends, exactly once.
 *
 * The retry loop made this easy to get wrong, and did. Killing the process is
 * `destroyProcessImpl`'s job; ending the run is not, because the run is the loop and the loop may
 * have another attempt to make. When that was overlooked, the loop could return without ending the
 * run and leave the Run window holding a live process with a spinning Stop button for the rest of
 * the session — no error, no exit code, nothing to click.
 *
 * There is no simulator here, so the run fails immediately. That is the point: the invariant is
 * that *every* way out ends the run, and a failure is the way out a test can reach.
 */
class StopEndsTheRunTest : IdeTestCase() {

    fun `test a run that cannot start still ends, once`() {
        // No manifest anywhere, so the launch fails as early as it can.
        myFixture.addFileToProject("src/Main.java", "class Main {}")
        val handler = MonkeyCLaunchProcessHandler(project, MonkeyCRunOptions())

        val endings = AtomicInteger()
        val ended = CountDownLatch(1)
        handler.addProcessListener(
            object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    endings.incrementAndGet()
                    ended.countDown()
                }
            },
        )

        handler.startNotify()

        assertTrue("the run never ended", ended.await(30, TimeUnit.SECONDS))
        // Settle, so a second ending arriving late is still caught rather than missed.
        Thread.sleep(200)
        assertEquals("the run ended more than once", 1, endings.get())
        assertTrue(handler.isProcessTerminated)
    }
}
