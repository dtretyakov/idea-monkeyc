package com.github.dtretyakov.monkeyc.build

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.ProgressBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt

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
            StartBuildEvent.builder(title, DefaultBuildDescriptor(id, "Connect IQ", spec.root.toString(), started))
                .build(),
        )

        val result = try {
            MonkeyCBuilder.run(
                project,
                spec,
                onOutput = { text, isError -> view.onEvent(id, output(id, text, isError)) },
                onProgress = { progress ->
                    view.onEvent(
                        id,
                        ProgressBuildEvent.builder(Any(), "${progress.built} of ${progress.total} devices built")
                            .withParentId(id)
                            .withTime(System.currentTimeMillis())
                            .withTotal(progress.total.toLong())
                            .withProgress(progress.built.toLong())
                            .withUnit("devices")
                            .build(),
                    )
                },
            )
        } catch (e: Throwable) {
            // Not orEmpty(): an exception with no message would leave the Build window reporting a
            // failure with nothing written next to it.
            val reason = e.message ?: "the build stopped with ${e.javaClass.simpleName}"
            view.onEvent(id, finish(id, reason, succeeded = false))
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
                view.onEvent(id, output(id, "Memory: $it\n"))
            }
        }

        // How long it took, in the window where it happened. On its own this is a nicety; beside
        // the JVM named in the setup checklist it is the pair of facts a developer on the forums
        // had to assemble by hand before finding that changing the JRE took their export from four
        // hours to two minutes.
        if (result.succeeded) {
            view.onEvent(id, output(id, "Took ${elapsed(System.currentTimeMillis() - started)}\n"))
        }

        view.onEvent(id, finish(id, if (result.succeeded) "successful" else "failed", result.succeeded))

        return result
    }

    /** A duration in the coarsest unit that still says something: 0.8 s, 12 s, 4 min 30 s. */
    internal fun elapsed(millis: Long): String {
        val seconds = millis / 1000.0
        return when {
            seconds < 10 -> "%.1f s".format(Locale.ROOT, seconds)
            seconds < 90 -> "${seconds.roundToInt()} s"
            else -> "${(seconds / 60).toInt()} min ${(seconds % 60).roundToInt()} s"
        }
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
            ?: return MessageEvent.builder(message.text, kind).withParentId(id).withGroup(group).build()

        // The Build window counts lines and columns from one; the compiler counts columns from
        // zero, and says nothing at all when it has no column.
        // `toFile()`, because the constructor taking a `Path` is newer than the oldest IDE this
        // plugin supports, and a build that reports one warning would die on it. The same goes for
        // `FileMessageEvent.builder`: 2026.2 deprecates it for `MessageEvent.builder(…)
        // .withFilePosition(…)`, which 2026.1 does not have.
        val position = FilePosition(file.toFile(), (message.line ?: 1) - 1, message.column ?: 0)
        return FileMessageEvent.builder(message.text, kind, position).withParentId(id).withGroup(group).build()
    }

    /*
     * The events are made through the builders on their public interfaces. The `…EventImpl`
     * constructors this used to call are internal API, which the Marketplace refuses a plugin for.
     */

    private fun output(id: Any, text: String, isError: Boolean = false): BuildEvent =
        OutputBuildEvent.builder(text)
            .withParentId(id)
            .withOutputType(if (isError) ProcessOutputType.STDERR else ProcessOutputType.STDOUT)
            .build()

    private fun finish(id: Any, message: String, succeeded: Boolean): BuildEvent =
        FinishBuildEvent.builder(id, message, if (succeeded) SuccessResultImpl() else FailureResultImpl())
            .withTime(System.currentTimeMillis())
            .build()

    /** A build for the simulator asks for `fenix7_sim`; the catalogue only knows `fenix7`. */
    private const val SIMULATOR_SUFFIX = "_sim"
}
