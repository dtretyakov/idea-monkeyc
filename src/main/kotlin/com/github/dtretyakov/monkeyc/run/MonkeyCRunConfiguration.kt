package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.dap.MonkeyCDebugAdapterFactory
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.ui.MonkeyCConfigurable
import com.github.dtretyakov.monkeyc.ui.OpenSdkManager
import com.intellij.openapi.options.ShowSettingsUtil
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

    /**
     * Everything that would stop this configuration, said before the Run button is pressed.
     *
     * It used to check two things, so a run with no SDK, no key or no downloaded device showed a
     * green button, started, and reported the refusal to a console the user had to open. The
     * platform renders these with a clickable fix, so each one arrives with the button that
     * resolves it rather than the name of a dialog to go and find.
     */
    override fun checkConfiguration() {
        val model = MonkeyCProject.getInstance(project)
        val root = model.primaryRoot()
            ?: throw RuntimeConfigurationError(
                "No Connect IQ project here: none of the content roots holds a manifest.xml.",
            )

        checkKindSuitsProject(model, root)
        checkEnvironment(model, root)
    }

    private fun checkKindSuitsProject(model: MonkeyCProject, root: java.nio.file.Path) {
        // Caught here as well as at launch, so the dialog can say it before the Run button is
        // pressed: the compiler's own complaint about a barrel run as an app is unreadable.
        val isBarrel = model.manifest(root)?.isBarrel
            ?: throw RuntimeConfigurationError(model.manifestProblem(root))
        if (isBarrel && !options.kind.barrel) {
            throw RuntimeConfigurationError(
                "This project is a barrel, not an app. Build it with a Connect IQ Barrel " +
                    "configuration, or run its tests with Connect IQ Barrel Tests.",
            )
        }
        if (!isBarrel && options.kind.barrel) {
            throw RuntimeConfigurationError(
                "This project is an app, not a barrel: its manifest declares an application, " +
                    "so there is no <iq:barrel> to build.",
            )
        }
    }

    private fun checkEnvironment(model: MonkeyCProject, root: java.nio.file.Path) {
        val openSdkManager = Runnable { OpenSdkManager.invoke(project) }

        if (ConnectIqSdkService.getInstance().sdkFor(project) == null) {
            throw RuntimeConfigurationError(
                "No Connect IQ SDK found. The compiler, the simulator and the devices all come " +
                    "from it.",
                openSdkManager,
            )
        }

        if (options.kind.buildKind.needsDevice && model.buildableDevices(root).isEmpty()) {
            throw RuntimeConfigurationError(
                "None of the devices this project declares is downloaded, and an app is built " +
                    "for one device.",
                openSdkManager,
            )
        }

        if (options.kind.buildKind.needsDeveloperKey) {
            model.developerKeyProblem()?.let { problem ->
                val openSettings = Runnable {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, MonkeyCConfigurable::class.java)
                }
                throw RuntimeConfigurationError(problem, openSettings)
            }
        }
    }

}
