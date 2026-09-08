package com.github.dtretyakov.monkeyc.project

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Per-project Connect IQ settings.
 *
 * These are the ones that belong to the code rather than to the machine — which devices it targets,
 * how strictly it should be type checked — so they are stored with the project and can be shared.
 * Where the SDK and the JDK live is a property of the machine and lives in [MonkeyCAppSettings].
 */
@Service(Service.Level.PROJECT)
@State(
    name = "MonkeyC",
    storages = [Storage("monkeyc.xml")],
    category = SettingsCategory.CODE,
)
class MonkeyCSettings : PersistentStateComponent<MonkeyCSettings> {

    /** Path to the developer key (`.der`). Empty means the one the SDK Manager generated. */
    var developerKeyPath: String = ""

    /** Device the run configurations build for; empty means "ask, then remember". */
    var targetDevice: String = ""

    /** `;`-separated jungle files, relative to the project root. Empty means `monkey.jungle`. */
    var jungleFiles: String = ""

    var typeCheckLevel: String = TypeCheckLevel.DEFAULT.display
    var optimizationLevel: String = OptimizationLevel.DEFAULT.display
    var debugLogLevel: String = DebugLogLevel.DEFAULT.display

    /** Pass `-w` to the compiler and ask the language server to publish warnings. */
    var compilerWarnings: Boolean = true

    /**
     * Whether to run the SDK's language server at all.
     *
     * On by default, because with it off there is no completion and no navigation. It can be
     * turned off because the server is a second JVM that compiles the whole workspace in the
     * background and does not always survive it: the same switch has been an open request against
     * Garmin's VS Code extension since January 2025, from people working in containers, on very
     * large projects, or with a jungle layout the server floods with errors that the compiler is
     * perfectly happy with. Building, running, debugging and the lexer are unaffected — this is
     * the analysis, not the plugin.
     */
    var liveAnalysis: Boolean = true

    /** Extra compiler arguments, split on whitespace. */
    var compilerOptions: String = ""

    /**
     * Whether the source and output directories have been marked once already.
     *
     * Recorded so that a developer who rearranges the roots afterwards does not find them
     * rearranged back the next time the project opens.
     */
    var rootsConfigured: Boolean = false

    val typeCheck: TypeCheckLevel get() = TypeCheckLevel.of(typeCheckLevel)
    val optimization: OptimizationLevel get() = OptimizationLevel.of(optimizationLevel)
    val debugLog: DebugLogLevel get() = DebugLogLevel.of(debugLogLevel)

    override fun getState(): MonkeyCSettings = this

    override fun loadState(state: MonkeyCSettings) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        fun getInstance(project: Project): MonkeyCSettings = project.service()

        /**
         * Fired after any of these change. The language server has to be told (it caches the
         * settings from `initialize`), and the device widget has to redraw.
         */
        @Topic.ProjectLevel
        val TOPIC: Topic<MonkeyCSettingsListener> =
            Topic.create("Monkey C settings", MonkeyCSettingsListener::class.java)
    }
}

fun interface MonkeyCSettingsListener {
    fun settingsChanged(project: Project)
}
