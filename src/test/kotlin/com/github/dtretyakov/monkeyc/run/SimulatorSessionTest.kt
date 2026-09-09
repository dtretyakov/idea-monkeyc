package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.intellij.execution.process.ProcessHandler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.OutputStream
import java.nio.file.Path

/**
 * One session on the simulator, and a new one ends the old.
 *
 * The simulator runs one app and its shell takes one client: a second `monkeydo` connecting shows
 * up on the channel as `shellDisconnected` for the first. So a run beside a debug, or Connect IQ
 * App beside Connect IQ Tests, cannot both work — and nothing used to say so. They fought, and the
 * loser failed in a way that read as a broken plugin.
 *
 * The platform's singleton setting does not cover it, because that is per configuration: it stops
 * one configuration being launched twice and has nothing to say about two different ones.
 */
class SimulatorSessionTest {

    /**
     * A handler that records being killed, which is the whole of what a session has to do to it.
     *
     * Started on construction, because the platform ignores `destroyProcess` on a handler that has
     * never been started — it is still in its initial state, and there is nothing to destroy. The
     * termination state is the platform's own; overriding it would test the fake instead.
     */
    private class Fake : ProcessHandler() {
        var destroyed = false

        init {
            startNotify()
        }

        override fun destroyProcessImpl() {
            destroyed = true
            notifyProcessTerminated(0)
        }

        override fun detachProcessImpl() = notifyProcessDetached()
        override fun detachIsDefault(): Boolean = false
        override fun getProcessInput(): OutputStream? = null
    }

    private val sdk = ConnectIqSdk(Path.of("/nowhere"), Path.of("/nowhere"), Path.of("/nowhere"))

    @Test
    fun `claiming the simulator ends whoever held it`() {
        val session = SimulatorSession()
        val first = Fake()
        val second = Fake()

        // No application id: closing an app would reach for a shell that is not there, and what is
        // under test is the handover rather than the close.
        session.claim(sdk, null, first)
        session.claim(sdk, null, second)

        assertTrue(first.destroyed, "the previous session was left running beside the new one")
        assertFalse(second.destroyed, "the new session was stopped by its own claim")
    }

    @Test
    fun `a session that has already ended is not killed again`() {
        val session = SimulatorSession()
        val first = Fake()
        session.claim(sdk, null, first)
        first.destroyProcess()
        first.waitFor(1_000)
        first.destroyed = false

        session.claim(sdk, null, Fake())

        assertFalse(first.destroyed, "a terminated session was destroyed a second time")
    }

    @Test
    fun `releasing a session that no longer holds the simulator changes nothing`() {
        val session = SimulatorSession()
        val first = Fake()
        val second = Fake()
        session.claim(sdk, null, first)
        session.claim(sdk, null, second)

        // The first one's own termination arrives after it has been taken over. Releasing on that
        // must not hand away the simulator the second one now holds, or the third claim would find
        // nothing to end and push straight into a live session.
        session.release(first)

        session.claim(sdk, null, Fake())
        assertTrue(second.destroyed, "the live session was not ended, because a dead one released it")
    }
}
