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
