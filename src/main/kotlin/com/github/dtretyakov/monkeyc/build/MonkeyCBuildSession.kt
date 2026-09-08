package com.github.dtretyakov.monkeyc.build

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.FileMessageEventImpl
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.MessageEventImpl
import com.intellij.build.events.impl.OutputBuildEventImpl
import com.intellij.build.events.impl.ProgressBuildEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Runs a build and reports it in the Build tool window.
 *
 * That is where a compile belongs in this IDE: the errors become a tree the user can click through,
 * and the Run window is left to the app's own output. It is also the one place both Run and Debug
 * can report to — Debug has no console of its own until the adapter is up, which is after the
 * build has already had to succeed.
 */
object MonkeyCBuildSession {

    fun run(
        project: Project,
        spec: BuildSpec,
        title: String,
        /** Off for a rebuild the user asked for by name, where "nothing to do" is the wrong answer. */
        skipWhenUpToDate: Boolean = true,
    ): BuildResult {
        // Checked before the tab is opened: a run with nothing to compile should leave the Build
        // window exactly as the last real build left it. An export is never skipped, whatever the
        // caller asked for — see BuildKind.mayBeSkipped.
        if (skipWhenUpToDate && spec.kind.mayBeSkipped && MonkeyCBuilder.isUpToDate(project, spec)) {
            return BuildResult(exitCode = 0, messages = emptyList(), upToDate = true)
        }

        val id = Any()
        val view = project.getService(BuildViewManager::class.java)
        val started = System.currentTimeMillis()

        view.onEvent(
            id,
            StartBuildEventImpl(
                DefaultBuildDescriptor(id, "Connect IQ", spec.root.toString(), started),
                title,
            ),
        )

        val result = try {
            MonkeyCBuilder.run(
                project,
                spec,
                onOutput = { text, isError -> view.onEvent(id, OutputBuildEventImpl(id, text, !isError)) },
                onProgress = { progress ->
                    view.onEvent(
                        id,
                        ProgressBuildEventImpl(
                            Any(),
                            id,
                            System.currentTimeMillis(),
                            "${progress.built} of ${progress.total} devices built",
                            progress.total.toLong(),
                            progress.built.toLong(),
                            "devices",
                        ),
                    )
                },
            )
        } catch (e: Throwable) {
            // Not orEmpty(): an exception with no message would leave the Build window reporting a
            // failure with nothing written next to it.
            val reason = e.message ?: "the build stopped with ${e.javaClass.simpleName}"
            view.onEvent(id, FinishBuildEventImpl(id, null, System.currentTimeMillis(), reason, FailureResultImpl()))
            throw e
        }

        result.messages.forEach { view.onEvent(id, event(id, it)) }

        // Said here rather than only on a warning, because the number is the point: knowing an app
        // sits at 40% of a watch's memory is what stops it reaching 100% on a device the developer
        // does not own. And said on a failure too when the failure is about the limit — one of the
        // compiler's two ways of reporting that names the size and the budget, and the other names
        // neither, which the forums have called obscure for years.
        if (result.succeeded || result.messages.any { it.isAboutTheLimit() }) {
            memoryReport(project, spec)?.let {
                view.onEvent(id, OutputBuildEventImpl(id, "Memory: $it\n", true))
            }
        }

        view.onEvent(
            id,
            FinishBuildEventImpl(
                id,
                null,
                System.currentTimeMillis(),
                if (result.succeeded) "successful" else "failed",
                if (result.succeeded) SuccessResultImpl() else FailureResultImpl(),
            ),
        )

        return result
    }

    /**
     * A diagnostic about the memory budget that does not say what the budget was.
     *
     * SDK 9.2.0 has two of these. One reads "exceeds the PRG size limit of app type 'x' for device
     * id 'y': n bytes used out of m available bytes", which needs nothing from us. The other is
     * "exceeds the memory limit of 'x' applications for device id 'y'", which names no number at
     * all — and that is the one the forums keep asking about.
     */
    private fun CompilerMessage.isAboutTheLimit(): Boolean =
        text.contains("memory limit") && !text.contains("bytes used out of")

    /**
     * The built program measured against what the target device allows it, in words.
     *
     * Null whenever any part of the question is open — an export builds for many devices, a barrel
     * for none — and silence is the right answer then.
     */
    private fun memoryReport(project: Project, spec: BuildSpec): String? {
        val device = spec.device?.removeSuffix(SIMULATOR_SUFFIX)
            ?.let { ConnectIqSdkService.getInstance().device(it) }
            ?: return null
        val appType = MonkeyCProject.getInstance(project).manifest(spec.root)?.appType
        return MemoryBudget.of(spec.output, device, appType)?.let { MemoryBudget.describe(it) }
    }

    private fun event(id: Any, message: CompilerMessage): MessageEvent {
        val kind = when (message.severity) {
            CompilerMessage.Severity.ERROR -> MessageEvent.Kind.ERROR
            CompilerMessage.Severity.WARNING -> MessageEvent.Kind.WARNING
        }
        // Diagnostics are grouped by device: a build for several reports the same file more than
        // once, and which device complained is usually the point.
        val group = message.device ?: "Compiler"
        val file = message.file?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: return MessageEventImpl(id, kind, group, message.text, null)

        return FileMessageEventImpl(
            id,
            kind,
            group,
            message.text,
            null,
            // The Build window counts lines and columns from one; the compiler counts columns
            // from zero, and says nothing at all when it has no column.
            FilePosition(file, (message.line ?: 1) - 1, message.column ?: 0),
        )
    }

    /** A build for the simulator asks for `fenix7_sim`; the catalogue only knows `fenix7`. */
    private const val SIMULATOR_SUFFIX = "_sim"
}
