package com.github.dtretyakov.monkeyc.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project

/** Run, as opposed to Debug: the app goes to the simulator through `monkeydo`, with no adapter. */
class MonkeyCRunState(
    private val project: Project,
    private val options: MonkeyCRunOptions,
) : RunProfileState {

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .apply { addFilter(MonkeyCCompilerFilter(project)) }
            .console

        val handler = MonkeyCLaunchProcessHandler(project, options)
        console.attachToProcess(handler)

        return DefaultExecutionResult(console, handler)
    }
}
