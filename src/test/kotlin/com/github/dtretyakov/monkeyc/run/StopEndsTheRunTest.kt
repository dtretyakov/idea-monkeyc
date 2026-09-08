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
    /**
     * The platform contract the run's ending depends on, pinned.
     *
     * This is the shape of a bug that reached a user: pressing Stop put the handler into
     * TERMINATING, our guard treated that as "already finished", the termination was never
     * notified, and the IDE sat on "Waiting for process detach" refusing to close the project. A
     * thread dump showed no plugin code running at all — the run had ended, it had just never said
     * so.
     *
     * A bare ProcessHandler is used rather than the plugin's, because what was misunderstood is
     * the platform's states, and this is where that understanding lives.
     */
    fun `test terminating is not terminated, and a stopped run must still notify`() {
        val destroyed = java.util.concurrent.CountDownLatch(1)
        val handler = object : com.intellij.execution.process.ProcessHandler() {
            override fun destroyProcessImpl() = destroyed.countDown()
            override fun detachProcessImpl() = Unit
            override fun detachIsDefault() = false
            override fun getProcessInput(): java.io.OutputStream? = null

            /** `notifyProcessTerminated` is protected, and this test is about calling it. */
            fun end(exitCode: Int) = notifyProcessTerminated(exitCode)
        }
        handler.startNotify()

        handler.destroyProcess()
        assertTrue("destroyProcessImpl should have run", destroyed.await(5, TimeUnit.SECONDS))

        // The state Stop leaves behind. Anything that treats it as "already done" and skips
        // notifying leaves the platform waiting for ever.
        assertTrue("stopping sets terminating", handler.isProcessTerminating)
        assertFalse("but it is not terminated yet", handler.isProcessTerminated)

        val ended = AtomicInteger()
        handler.addProcessListener(
            object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    ended.incrementAndGet()
                }
            },
        )
        handler.end(0)

        assertTrue("only now is it terminated", handler.isProcessTerminated)
        assertEquals("and the listeners hear it once", 1, ended.get())
    }

}
