package com.github.dtretyakov.monkeyc.run

import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptionsBase

/**
 * What one run configuration remembers.
 *
 * It extends LSP4IJ's DAP options because the Debug executor hands the whole options object to the
 * debug adapter descriptor; the fields below are ours, the ones it inherits are the DAP client's.
 */
class MonkeyCRunOptions : DAPRunConfigurationOptionsBase() {

    private val kindOption = string(MonkeyCRunKind.APP.name).provideDelegate(this, "kind")

    /** Empty means "whatever is selected next to the Run button". */
    private val deviceOption = string("").provideDelegate(this, "device")
    private val testsOption = string("").provideDelegate(this, "tests")
    private val stopAtLaunchOption = property(false).provideDelegate(this, "stopAtLaunch")
    private val nativePairingOption = property(false).provideDelegate(this, "runNativePairing")
    private val forDeviceOption = property(false).provideDelegate(this, "forDevice")
    private val compilerArgumentsOption = string("").provideDelegate(this, "compilerArguments")
    private val outputPathOption = string("").provideDelegate(this, "outputPath")
    private val pairedProjectOption = string("").provideDelegate(this, "pairedProject")

    var kind: MonkeyCRunKind
        get() = MonkeyCRunKind.of(kindOption.getValue(this))
        set(value) = kindOption.setValue(this, value.name)

    var device: String
        get() = deviceOption.getValue(this).orEmpty()
        set(value) = deviceOption.setValue(this, value)

    val runTests: Boolean get() = kind.isTests

    /**
     * The tests to run, by name, separated by spaces. Empty runs every test in the project.
     *
     * A list rather than one name because that is what the runner takes — `monkeydo -t` accepts
     * any number of names — and it is what "run the tests in this file" needs.
     */
    var tests: String
        get() = testsOption.getValue(this).orEmpty()
        set(value) = testsOption.setValue(this, value)

    val testNames: List<String>
        get() = tests.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Break as soon as the app starts, before its first line runs. Debug only. */
    var stopAtLaunch: Boolean
        get() = stopAtLaunchOption.getValue(this)
        set(value) = stopAtLaunchOption.setValue(this, value)

    /** Run as a natively paired app, the way a device that owns the app would run it. */
    var runNativePairing: Boolean
        get() = nativePairingOption.getValue(this)
        set(value) = nativePairingOption.setValue(this, value)

    /**
     * Build for the watch rather than for the simulator.
     *
     * The compiler is told `fenix7` instead of `fenix7_sim`, and the two are not interchangeable:
     * a simulator build refuses to start on the watch. This is the only way to get a `.prg` that
     * can be copied to `GARMIN/APPS` over USB.
     */
    var forDevice: Boolean
        get() = forDeviceOption.getValue(this)
        set(value) = forDeviceOption.setValue(this, value)

    var compilerArguments: String
        get() = compilerArgumentsOption.getValue(this).orEmpty()
        set(value) = compilerArgumentsOption.setValue(this, value)

    /**
     * Where an export or a barrel is written. Empty means the project's own `out` directory.
     *
     * Only these two kinds have it: everything else goes to `bin`, where the simulator and the
     * debugger already look for it by name.
     */
    var outputPath: String
        get() = outputPathOption.getValue(this).orEmpty()
        set(value) = outputPathOption.setValue(this, value)

    /**
     * The other half of a complication pair: a second Connect IQ project, run alongside this one.
     *
     * A complication is two apps that only mean anything together — one publishes a value, the
     * other displays it — and testing either alone tests nothing. The simulator can hold both,
     * but only the debug adapter knows how to put them there.
     */
    var pairedProject: String
        get() = pairedProjectOption.getValue(this).orEmpty()
        set(value) = pairedProjectOption.setValue(this, value)
}
