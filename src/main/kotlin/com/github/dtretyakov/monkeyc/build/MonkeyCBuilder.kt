package com.github.dtretyakov.monkeyc.build

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.awaitExit
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories

data class BuildResult(val exitCode: Int, val messages: List<CompilerMessage>) {
    val succeeded: Boolean get() = exitCode == 0
    val errors: List<CompilerMessage>
        get() = messages.filter { it.severity == CompilerMessage.Severity.ERROR }
}

/**
 * Runs the Connect IQ compiler and reads what it says.
 *
 * Diagnostics arrive on standard error, one per line; the assembly listing and `BUILD SUCCESSFUL`
 * arrive on standard output. Both are echoed as they come, because a build for every device takes
 * long enough that silence looks like a hang.
 */
object MonkeyCBuilder {

    fun run(
        project: Project,
        spec: BuildSpec,
        onOutput: (text: String, isError: Boolean) -> Unit,
    ): BuildResult {
        val sdkService = ConnectIqSdkService.getInstance()
        val sdk = sdkService.sdk ?: error("No Connect IQ SDK found.")

        spec.output.parent?.createDirectories()

        val command = GeneralCommandLine(
            CompilerCommand.arguments(sdk, sdkService.java(), MonkeyCSettings.getInstance(project), spec),
        ).withWorkingDirectory(spec.root)

        onOutput(command.commandLineString + "\n\n", false)

        val messages = mutableListOf<CompilerMessage>()
        val handler = OSProcessHandler(command)
        handler.addProcessListener(
            object : ProcessListener {
                private val partialErrorLine = StringBuilder()

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val isError = outputType === com.intellij.execution.process.ProcessOutputTypes.STDERR
                    onOutput(event.text, isError)
                    if (isError) collectDiagnostics(event.text)
                }

                /** Standard error arrives in chunks, and a diagnostic is only readable whole. */
                private fun collectDiagnostics(text: String) {
                    partialErrorLine.append(text)
                    var newline = partialErrorLine.indexOf("\n")
                    while (newline >= 0) {
                        CompilerOutputParser.parseLine(partialErrorLine.substring(0, newline))
                            ?.let { messages += it }
                        partialErrorLine.delete(0, newline + 1)
                        newline = partialErrorLine.indexOf("\n")
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    CompilerOutputParser.parseLine(partialErrorLine.toString())?.let { messages += it }
                }
            },
        )

        handler.startNotify()
        val exitCode = runBlocking { handler.process.awaitExit() }
        handler.waitFor()

        return BuildResult(exitCode, messages)
    }

    /** A one-line summary for a failure notification, preferring the compiler's own words. */
    fun describeFailure(result: BuildResult): String =
        result.errors.firstOrNull()?.let { error ->
            val where = error.file?.let { "${FileUtil.getNameWithoutExtension(it)}${error.line?.let { l -> ":$l" }.orEmpty()}: " }
            "${where.orEmpty()}${error.text}"
        } ?: "The build failed with exit code ${result.exitCode}."
}
