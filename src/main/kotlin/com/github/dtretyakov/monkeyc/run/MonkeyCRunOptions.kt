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
    private val testNameOption = string("").provideDelegate(this, "testName")
    private val stopAtLaunchOption = property(false).provideDelegate(this, "stopAtLaunch")
    private val nativePairingOption = property(false).provideDelegate(this, "runNativePairing")
    private val forDeviceOption = property(false).provideDelegate(this, "forDevice")
    private val compilerArgumentsOption = string("").provideDelegate(this, "compilerArguments")
    private val outputPathOption = string("").provideDelegate(this, "outputPath")

    var kind: MonkeyCRunKind
        get() = MonkeyCRunKind.of(kindOption.getValue(this))
        set(value) = kindOption.setValue(this, value.name)

    var device: String
        get() = deviceOption.getValue(this).orEmpty()
        set(value) = deviceOption.setValue(this, value)

    val runTests: Boolean get() = kind.isTests

    /** A single test to run, rather than all of them. */
    var testName: String
        get() = testNameOption.getValue(this).orEmpty()
        set(value) = testNameOption.setValue(this, value)

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
}
