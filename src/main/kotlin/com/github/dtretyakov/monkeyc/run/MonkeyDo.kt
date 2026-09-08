package com.github.dtretyakov.monkeyc.run

import com.intellij.execution.configurations.GeneralCommandLine

/**
 * The command that pushes a built app into the running simulator and starts it.
 *
 * This is what the SDK's `monkeydo` script does, without the script: it is a Java program, and
 * going straight to it means the plugin works the same on Windows, where the script is a `.bat`.
 */
object MonkeyDo {

    private const val MAIN = "com.garmin.monkeybrains.monkeydodeux.MonkeyDoDeux"

    /**
     * What it says when it cannot reach the simulator's debug shell.
     *
     * Confirmed against SDK 9.2.0: with nothing listening, `monkeydo` writes exactly this one
     * sentence to standard error, prints nothing at all to standard output, and exits 2.
     */
    private const val REFUSED = "Unable to connect to simulator"

    /**
     * Whether this run failed because the simulator would not take the app, rather than because
     * the app itself did anything.
     *
     * The distinction is the whole point: the simulator leaks two pipes per run and stops
     * accepting connections after a few dozen, so this failure is expected, transient and worth
     * retrying — while every other failure is the user's to see once and act on. Matched on the
     * sentence rather than on the exit code, because 2 is also what an app can exit with, and
     * running someone's failing app three times to find that out would be its own bug.
     */
    fun simulatorRefused(exitCode: Int, standardError: String): Boolean =
        exitCode != 0 && standardError.contains(REFUSED)

    fun commandLine(launch: PreparedLaunch, java: String, options: MonkeyCRunOptions): GeneralCommandLine {
        val arguments = buildList {
            add("-classpath")
            add(launch.sdk.monkeybrainsJar.toString())
            add(MAIN)
            add("-f")
            add(launch.prg.toString())
            add("-d")
            add(launch.device)
            // The simulator's debug shell, which is what actually receives the app.
            add("-s")
            add(launch.sdk.shell.toString())
            if (options.runNativePairing) add("-n")
            if (options.runTests) {
                // `-t` alone runs every test; each name after it narrows the run to those.
                add("-t")
                addAll(options.testNames)
            }
        }
        return GeneralCommandLine(listOf(java) + arguments).withWorkingDirectory(launch.root)
    }
}
