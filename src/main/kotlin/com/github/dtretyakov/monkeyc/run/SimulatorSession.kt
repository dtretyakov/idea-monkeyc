package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger

/**
 * The one Connect IQ session, and who owns it.
 *
 * The simulator runs one app at a time, and its shell has room for one client: connecting a second
 * one disconnects the first, which is visible on the channel as `shellDisconnected` the moment
 * another `monkeydo` attaches. So two Connect IQ launches cannot coexist — not two run
 * configurations, and not a run beside a debug — and until now nothing said so. They simply fought,
 * and the loser failed in a way that read as a broken plugin: an app that stopped drawing, a
 * debugger that could not connect, output arriving in the wrong console.
 *
 * The platform's own singleton setting is not enough, because it is per configuration: it stops
 * Connect IQ App being launched twice, and has nothing to say about Connect IQ App and Connect IQ
 * Tests being launched once each. The thing that admits only one owner is the simulator, so the
 * rule lives here.
 *
 * A new launch therefore ends the previous one rather than racing it, which is the same bargain the
 * IDE offers for a singleton configuration — start the new one, and the old one goes.
 */
@Service(Service.Level.APP)
class SimulatorSession {

    private class Live(val handler: ProcessHandler, val applicationId: String?)

    @Volatile
    private var live: Live? = null

    /**
     * Takes the simulator over, ending whatever held it.
     *
     * Blocking and bounded, and never to be called on the EDT: it waits for the previous session's
     * process to die and for its app to be closed, because the point is that the next push happens
     * after all of that and not during it.
     */
    fun claim(sdk: ConnectIqSdk, applicationId: String?, handler: ProcessHandler) {
        previousOwner()?.let { previous ->
            LOG.info("Ending the previous Connect IQ session before starting another")
            previous.handler.destroyProcess()
            previous.handler.waitFor(HANDOVER_MILLIS)
            // The app it pushed outlives the process that pushed it, so ending the session is two
            // things and not one.
            previous.applicationId?.let { SimulatorApp.close(sdk, it) }
        }

        // And a close the previous Stop started on its own. Overtaking it means pushing an app and
        // having it closed a moment later by a message meant for its predecessor: they share an id.
        SimulatorApp.awaitClose()

        live = Live(handler, applicationId)
    }

    /** Gives the simulator up, if this is still the session holding it. */
    fun release(handler: ProcessHandler) {
        if (live?.handler === handler) live = null
    }

    /** The live session other than a dead one, whose handler has already terminated. */
    private fun previousOwner(): Live? = live?.takeIf { !it.handler.isProcessTerminated }

    companion object {
        /** How long the previous session gets to die before the next one goes ahead regardless. */
        private const val HANDOVER_MILLIS = 5_000L

        private val LOG = logger<SimulatorSession>()

        fun getInstance(): SimulatorSession =
            ApplicationManager.getApplication()?.getService(SimulatorSession::class.java) ?: standalone

        /** Outside an IDE — the live tests — there is no service container and none is needed. */
        private val standalone by lazy { SimulatorSession() }
    }
}
