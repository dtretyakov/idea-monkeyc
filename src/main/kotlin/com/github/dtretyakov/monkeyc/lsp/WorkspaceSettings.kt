package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.intellij.openapi.project.Project

/**
 * What the Monkey C language server is told at `initialize`, and again whenever a setting changes.
 *
 * The shape is the server's, not ours: `WorkspaceSettingsParams` reads `options` positionally as
 * type check level, debug log level and target device, and `LSClientUtils$InitializationOptions`
 * expects exactly these four fields. Names and order both matter.
 */
data class InitializationOptions(
    val publishWarnings: Boolean,
    val compilerOptions: String,
    val typeCheckMsgDisplayed: Boolean,
    val workspaceSettings: List<WorkspaceSettings>?,
)

data class WorkspaceSettings(
    /** The project root, as a plain filesystem path — not a URI. */
    val path: String,
    /**
     * Absolute paths. A relative one is silently dropped: the server logs
     * `Jungle file 'monkey.jungle' ... does not exist` and then indexes nothing.
     */
    val jungleFiles: List<String>,
    /** `[typeCheckLevel, debugLogLevel, targetDevice]`, read by position. */
    val options: List<String?>,
)

object LanguageServerSettings {

    /**
     * Builds the settings for every Connect IQ project in this IDE project.
     *
     * The type check and debug log levels are sent by name — `Gradual`, `Verbose` — because the
     * server resolves them with `TypeCheckLevel.fromName`, which upper-cases and looks up the enum.
     * The compiler, given the same settings, wants a digit instead.
     */
    fun initializationOptions(project: Project): InitializationOptions {
        val settings = MonkeyCSettings.getInstance(project)
        return InitializationOptions(
            publishWarnings = settings.compilerWarnings,
            compilerOptions = settings.compilerOptions,
            // We show the type check level in the settings dialog, so the server does not need to
            // offer its own one-time prompt about it.
            typeCheckMsgDisplayed = true,
            workspaceSettings = workspaceSettings(project).takeIf { it.isNotEmpty() },
        )
    }

    fun workspaceSettings(project: Project): List<WorkspaceSettings> {
        val settings = MonkeyCSettings.getInstance(project)
        val model = MonkeyCProject.getInstance(project)
        return model.roots().map { root ->
            WorkspaceSettings(
                path = root.toString(),
                jungleFiles = model.jungleFiles(root).map { it.toString() },
                options = listOf(
                    settings.typeCheck.display,
                    settings.debugLog.display,
                    settings.targetDevice.takeIf { it.isNotEmpty() },
                ),
            )
        }
    }
}
