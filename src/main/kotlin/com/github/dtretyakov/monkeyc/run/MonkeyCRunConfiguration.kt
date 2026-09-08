package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.dap.MonkeyCDebugAdapterFactory
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.dap.configurations.DAPRunConfigurationBase
import com.redhat.devtools.lsp4ij.dap.definitions.DebugAdapterServerDefinition

/**
 * A Connect IQ run configuration.
 *
 * Run and Debug take different routes, and the split is deliberate. Debugging goes through the
 * debug adapter in the SDK, over DAP, which is what LSP4IJ's DAP client is for. Running does not
 * need an adapter at all — `monkeydo` pushes the app and prints what it says — and going through
 * the adapter to avoid a second path would make an ordinary run depend on the debugger working.
 */
class MonkeyCRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : DAPRunConfigurationBase<MonkeyCRunOptions>(project, factory, name) {

    public override fun getOptions(): MonkeyCRunOptions = super.getOptions() as MonkeyCRunOptions

    override fun getOptionsClass(): Class<out RunConfigurationOptions> = MonkeyCRunOptions::class.java

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
        if (executor.id == DefaultDebugExecutor.EXECUTOR_ID) {
            super.getState(executor, environment)
        } else {
            MonkeyCRunState(this)
        }

    /**
     * Only Debug is claimed here. LSP4IJ's DAP runner takes every configuration that says yes, and
     * for Run the platform's own runner and [MonkeyCRunState] are the right pair.
     *
     * Two kinds decline it. A configuration that builds and stops has nothing to debug. And a test
     * run cannot be debugged at all — not by this plugin and not by anything else, because the
     * SDK's own adapter suppresses the `initialized` event for it:
     *
     * ```java
     * if (!this.mRunTests && isForegroundApp) {
     *     mClient.initialized();
     * }
     * ```
     *
     * A DAP client only registers breakpoints after `initialized`, so in a test run there is no
     * moment at which a breakpoint can be set; `stopAtLaunch` is ignored there as well. Offering
     * Debug would offer a button that silently runs the suite and stops nowhere.
     */
    override fun canRun(executorId: String): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID &&
            options.kind.launches &&
            !options.forDevice &&
            !options.kind.isTests

    override fun getDebugAdapterServer(): DebugAdapterServerDefinition? =
        DebugAdapterManager.getInstance().getDebugAdapterServerById(MonkeyCDebugAdapterFactory.SERVER_ID)

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = MonkeyCSettingsEditor(project)

    override fun checkConfiguration() {
        val model = MonkeyCProject.getInstance(project)
        val root = model.primaryRoot()
            ?: throw RuntimeConfigurationError(
                "No Connect IQ project here: none of the content roots holds a manifest.xml.",
            )

        // Caught here as well as at launch, so the dialog can say it before the Run button is
        // pressed: the compiler's own complaint about a barrel run as an app is unreadable.
        val isBarrel = model.manifest(root)?.isBarrel ?: return
        if (isBarrel && !options.kind.barrel) {
            throw RuntimeConfigurationError(
                "This project is a barrel, not an app. Build it with a Connect IQ Barrel " +
                    "configuration, or run its tests with Connect IQ Barrel Tests.",
            )
        }
        if (!isBarrel && options.kind.barrel) {
            throw RuntimeConfigurationError("This project is an app, not a barrel.")
        }
    }
}
