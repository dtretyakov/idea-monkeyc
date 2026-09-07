package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.dap.MonkeyCDebugAdapterFactory
import com.github.dtretyakov.monkeyc.lang.JungleFileType
import com.github.dtretyakov.monkeyc.lang.MonkeyCFileType
import com.github.dtretyakov.monkeyc.lang.MssFileType
import com.github.dtretyakov.monkeyc.lsp.MonkeyCLanguageServerFactory
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.run.MonkeyCRunConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.application.ModernApplicationStarter
import com.intellij.openapi.fileTypes.FileTypeManager
import com.redhat.devtools.lsp4ij.dap.DebugAdapterManager
import com.redhat.devtools.lsp4ij.LanguageServersRegistry
import kotlin.system.exitProcess

/**
 * A headless check that the plugin is whole inside a real IDE.
 *
 *     ./gradlew runSelfCheck
 *
 * Unit tests cannot see any of this: whether `plugin.xml` names a class that exists, whether the
 * extensions LSP4IJ owns actually took, whether the file types are bound to the right languages.
 * Those failures are silent at runtime — a language server that was never registered simply never
 * starts, and the user sees an editor with no completion and no error to report.
 */
class SelfCheckStarter : ModernApplicationStarter() {

    override val isHeadless: Boolean get() = true

    override suspend fun start(args: List<String>) {
        val problems = mutableListOf<String>()

        val fileTypes = FileTypeManager.getInstance()
        mapOf(
            "mc" to MonkeyCFileType,
            "mcgen" to MonkeyCFileType,
            "jungle" to JungleFileType,
            "mss" to MssFileType,
        ).forEach { (extension, expected) ->
            val actual = fileTypes.getFileTypeByExtension(extension)
            if (actual != expected) problems += ".$extension is $actual, expected ${expected.name}"
        }
        println("[self-check] file types: .mc .mcgen .jungle .mss")

        val runConfiguration = ConfigurationTypeUtil.findConfigurationType(MonkeyCRunConfigurationType::class.java)
        println("[self-check] run configurations: ${runConfiguration.configurationFactories.joinToString(", ") { it.name }}")

        val languageServer = LanguageServersRegistry.getInstance()
            .getServerDefinition(MonkeyCLanguageServerFactory.SERVER_ID)
        if (languageServer == null) {
            problems += "the language server '${MonkeyCLanguageServerFactory.SERVER_ID}' is not registered with LSP4IJ"
        } else {
            println("[self-check] language server: ${languageServer.displayName}")
        }

        val debugAdapter = DebugAdapterManager.getInstance()
            .getDebugAdapterServerById(MonkeyCDebugAdapterFactory.SERVER_ID)
        if (debugAdapter == null) {
            problems += "the debug adapter '${MonkeyCDebugAdapterFactory.SERVER_ID}' is not registered with LSP4IJ"
        } else {
            println("[self-check] debug adapter: ${debugAdapter.displayName}")
        }

        // A missing SDK is the machine's business, not the plugin's, so it is reported rather than
        // failed: the plugin has to install and behave on a machine that has no Connect IQ at all.
        val sdk = ConnectIqSdkService.getInstance().sdk
        if (sdk == null) {
            println("[self-check] no Connect IQ SDK on this machine")
        } else {
            println("[self-check] SDK ${sdk.version} at ${sdk.root}")
            println("[self-check] devices downloaded: ${ConnectIqSdkService.getInstance().devices().size}")
            if (!sdk.hasLanguageServer) println("[self-check] this SDK has no LanguageServer.jar")
        }

        problems.forEach { println("[self-check] PROBLEM: $it") }
        println(if (problems.isEmpty()) "[self-check] OK" else "[self-check] FAILED")
        exitProcess(if (problems.isEmpty()) 0 else 1)
    }
}
