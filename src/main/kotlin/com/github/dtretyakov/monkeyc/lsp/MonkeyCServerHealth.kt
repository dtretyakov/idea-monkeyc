package com.github.dtretyakov.monkeyc.lsp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.ServerStatus
import java.util.ArrayDeque

/**
 * Whether the language server is alive, and what to do about it when it is not.
 *
 * The server is a second JVM that compiles the whole workspace, and it does not always survive
 * that. What VS Code does when it stops surviving is the thing worth not copying: after five
 * crashes in three minutes it gives up silently, and what the developer is left with is an editor
 * that has quietly stopped completing anything, with no reason anywhere on screen. Every symptom
 * they then report — "autocomplete is terrible", "go to definition doesn't work" — is a symptom of
 * a dead process nobody told them about.
 *
 * So this counts, and the counting exists only so that giving up can be said out loud.
 */
@Service(Service.Level.PROJECT)
class MonkeyCServerHealth(private val project: Project) {

    /** When the server stopped without being asked to, most recent last. */
    private val crashes = ArrayDeque<Long>()

    /** Set while a stop is one the plugin asked for, so it is not counted as a crash. */
    @Volatile
    private var stopExpected = false

    @Volatile
    private var wasRunning = false

    /**
     * Announces a deliberate stop, so the crash count is not raised by our own restarts.
     *
     * Restarting is routine here — the server reads the SDK, the jungles and the type check level
     * once, at `initialize`, so every settings change means a new process.
     */
    fun expectStop() {
        stopExpected = true
    }

    /**
     * Records a status change and says what the user should be told, or null when nothing.
     *
     * Pure enough to test: it takes a status and returns a verdict, and the caller does the
     * talking.
     */
    @Synchronized
    fun statusChanged(status: ServerStatus, now: Long = System.currentTimeMillis()): Verdict? {
        when (status) {
            ServerStatus.started -> {
                wasRunning = true
                stopExpected = false
                return null
            }

            ServerStatus.stopped -> {
                val unexpected = wasRunning && !stopExpected
                wasRunning = false
                stopExpected = false
                if (!unexpected) return null
                return record(now)
            }

            else -> return null
        }
    }

    private fun record(now: Long): Verdict {
        crashes.addLast(now)
        while (crashes.isNotEmpty() && now - crashes.first() > WINDOW_MILLIS) crashes.removeFirst()
        return if (crashes.size >= REPEATED) Verdict.KeepsCrashing(crashes.size) else Verdict.Crashed
    }

    /**
     * Starts over: no crashes on record, and no memory of the server having run.
     *
     * What "Start it again" means. A user who has just fixed something — downloaded the device,
     * moved the project off a path the server could not read — is beginning a new attempt, and
     * carrying the old count into it would report their first hiccup as a continuing crisis.
     */
    @Synchronized
    fun forget() {
        crashes.clear()
        wasRunning = false
        stopExpected = false
    }

    @Synchronized
    fun crashesInWindow(): Int = crashes.size

    /** What the user should be told, if anything. */
    sealed interface Verdict {
        /** It went once. Offer to start it again and say nothing else. */
        object Crashed : Verdict

        /**
         * It keeps going. Restarting is no longer the interesting suggestion — something about
         * this project or this SDK is the cause, and the checklist is where that is found.
         */
        data class KeepsCrashing(val times: Int) : Verdict
    }

    companion object {
        fun getInstance(project: Project): MonkeyCServerHealth = project.service()

        /** Long enough to catch a crash loop, short enough that yesterday's does not count. */
        const val WINDOW_MILLIS = 5 * 60 * 1000L

        /**
         * Three, not five. VS Code's five-in-three-minutes is the point at which it stops trying;
         * the point at which the news stops being "it crashed" and starts being "it will keep
         * crashing" comes earlier than that.
         */
        const val REPEATED = 3
    }
}
