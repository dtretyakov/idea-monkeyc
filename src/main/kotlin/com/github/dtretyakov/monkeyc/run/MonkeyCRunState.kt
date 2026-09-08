package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.run.test.MONKEY_C_TEST_FRAMEWORK
import com.github.dtretyakov.monkeyc.run.test.MonkeyCTestConsoleProperties
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView

/** Run, as opposed to Debug: the app goes to the simulator through `monkeydo`, with no adapter. */
class MonkeyCRunState(
    private val configuration: MonkeyCRunConfiguration,
    private val target: String?,
) : RunProfileState {

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = configuration.project
        val options = configuration.options
        val handler = MonkeyCLaunchProcessHandler(project, options, target)

        if (!options.kind.isTests) {
            val console = TextConsoleBuilderFactory.getInstance()
                .createBuilder(project)
                .apply { addFilter(MonkeyCCompilerFilter(project)) }
                .console
            console.attachToProcess(handler)
            return DefaultExecutionResult(console, handler)
        }

        // A test run reports through the tree instead: the process handler has already turned the
        // runner's own output into the events the tree is built from.
        val properties = MonkeyCTestConsoleProperties(configuration, executor)
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(
            MONKEY_C_TEST_FRAMEWORK,
            handler,
            properties,
        ) as SMTRunnerConsoleView

        return DefaultExecutionResult(console, handler).apply {
            properties.createRerunFailedTestsAction(console)?.let { setRestartActions(it) }
        }
    }
}
