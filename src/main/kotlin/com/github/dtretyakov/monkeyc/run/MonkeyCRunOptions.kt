package com.github.dtretyakov.monkeyc.run

import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationOptionsBase

/**
 * What one run configuration remembers.
 *
 * It extends LSP4IJ's DAP options because the Debug executor hands the whole options object to the
 * debug adapter descriptor; the fields below are ours, the ones it inherits are the DAP client's.
 */
class MonkeyCRunOptions : DAPRunConfigurationOptionsBase() {

    /** Empty means "the project's target device", and failing that, ask. */
    private val deviceOption = string("").provideDelegate(this, "device")
    private val runTestsOption = property(false).provideDelegate(this, "runTests")
    private val testNameOption = string("").provideDelegate(this, "testName")
    private val stopAtLaunchOption = property(false).provideDelegate(this, "stopAtLaunch")
    private val nativePairingOption = property(false).provideDelegate(this, "runNativePairing")
    private val compilerArgumentsOption = string("").provideDelegate(this, "compilerArguments")

    var device: String
        get() = deviceOption.getValue(this).orEmpty()
        set(value) = deviceOption.setValue(this, value)

    var runTests: Boolean
        get() = runTestsOption.getValue(this)
        set(value) = runTestsOption.setValue(this, value)

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

    var compilerArguments: String
        get() = compilerArgumentsOption.getValue(this).orEmpty()
        set(value) = compilerArgumentsOption.setValue(this, value)
}
