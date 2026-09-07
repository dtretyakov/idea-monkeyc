package com.github.dtretyakov.monkeyc.build

import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import java.nio.file.Path

/**
 * Builds the command line for one compiler run.
 *
 * Kept as a pure function of the SDK, the settings and the request so it can be read and tested
 * without an IDE — this is the part where a wrong flag costs an afternoon.
 */
object CompilerCommand {

    /** `monkeyc` itself, for apps and exports. */
    private const val COMPILER_MAIN = "com.garmin.monkeybrains.Monkeybrains"

    /** `barrelbuild`: builds a `.barrel` rather than a `.prg`. */
    private const val BARREL_MAIN = "com.garmin.monkeybrains.MonkeyBarrelEntry"

    /** `barreltest`: builds a barrel's tests into a runnable `.prg`. */
    private const val BARREL_TEST_MAIN = "com.garmin.monkeybrains.MonkeyBarrelRunNoEvil"

    fun arguments(
        sdk: ConnectIqSdk,
        java: Path,
        settings: MonkeyCSettings,
        spec: BuildSpec,
    ): List<String> = buildList {
        add(java.toString())
        // The compiler's own launcher scripts set these: a gigabyte up front because the compiler
        // allocates heavily, UTF-8 because source and resources are, and UIElement so a build does
        // not bounce a Java icon in the macOS dock.
        if (spec.kind != BuildKind.BARREL && spec.kind != BuildKind.BARREL_TESTS) add("-Xms1g")
        add("-Dfile.encoding=UTF-8")
        add("-Dapple.awt.UIElement=true")
        add("-cp")
        add(sdk.monkeybrainsJar.toString())
        add(
            when (spec.kind) {
                BuildKind.BARREL -> BARREL_MAIN
                BuildKind.BARREL_TESTS -> BARREL_TEST_MAIN
                else -> COMPILER_MAIN
            },
        )

        add("-o")
        add(spec.output.toString())
        add("-f")
        // One argument, the paths joined: that is what the compiler's own `-f` parsing expects.
        add(spec.jungleFiles.joinToString(";"))

        if (spec.kind.needsDeveloperKey) {
            spec.developerKey?.let {
                add("-y")
                add(it.toString())
            }
        }

        if (spec.kind.needsDevice) {
            spec.device?.let {
                add("-d")
                add(if (spec.simulator) "${it}_sim" else it)
            }
        }

        if (spec.kind == BuildKind.EXPORT) {
            // Every declared device, packaged and release-signed.
            add("-e")
            add("-r")
        }

        if (spec.kind == BuildKind.TESTS) add("--unit-test")

        addAll(settingsArguments(sdk, settings, spec.root))
        addAll(spec.extraArguments)
    }

    /**
     * The compiler flags that come from the settings rather than from the request.
     *
     * Type checking and optimization are refused by older SDKs, so they are only passed to one that
     * understands them; sending them anyway turns a working build into a confusing failure.
     */
    private fun settingsArguments(sdk: ConnectIqSdk, settings: MonkeyCSettings, root: Path): List<String> =
        buildList {
            if (settings.compilerWarnings) add("-w")

            settings.typeCheck.flag?.takeIf { sdk.supportsTypeChecking }?.let {
                add("-l")
                add(it)
            }

            settings.optimization.flag?.takeIf { sdk.supportsOptimization }?.let {
                add("-O")
                add(it)
            }

            settings.debugLog.flag?.let {
                add("--debug-log-level")
                add(it)
                add("--debug-log-output")
                add(root.resolve(ProjectLayout.OUTPUT_DIRECTORY).resolve("log.zip").toString())
            }

            settings.compilerOptions.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { add(it) }
        }
}
