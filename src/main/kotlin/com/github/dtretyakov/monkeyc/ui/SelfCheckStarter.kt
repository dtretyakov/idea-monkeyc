package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.dap.MonkeyCDebugAdapterFactory
import com.github.dtretyakov.monkeyc.lang.ApiMirFileType
import com.github.dtretyakov.monkeyc.lang.JungleFileType
import com.github.dtretyakov.monkeyc.lang.MonkeyCLanguage
import com.github.dtretyakov.monkeyc.lang.MonkeyCLineIndentProvider
import com.github.dtretyakov.monkeyc.lang.MonkeyCFileType
import com.github.dtretyakov.monkeyc.lang.MssFileType
import com.github.dtretyakov.monkeyc.lsp.MonkeyCLanguageServerFactory
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.github.dtretyakov.monkeyc.run.MonkeyCRunConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.github.dtretyakov.monkeyc.navigation.ApiMirGotoClassContributor
import com.github.dtretyakov.monkeyc.navigation.ApiMirGotoDeclarationHandler
import com.github.dtretyakov.monkeyc.navigation.ApiMirGotoSymbolContributor
import com.github.dtretyakov.monkeyc.sdk.ApiMirService
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.ide.highlighter.ArchiveFileType
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.openapi.application.ModernApplicationStarter
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.lang.LanguageExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
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
            "mir" to ApiMirFileType,
        ).forEach { (extension, expected) ->
            val actual = fileTypes.getFileTypeByExtension(extension)
            if (actual != expected) problems += ".$extension is $actual, expected ${expected.name}"
        }
        println("[self-check] file types: .mc .mcgen .jungle .mss .mir")

        problems += barrelProblems()

        val runConfiguration = ConfigurationTypeUtil.findConfigurationType(MonkeyCRunConfigurationType::class.java)
        println("[self-check] run configurations: ${runConfiguration.configurationFactories.joinToString(", ") { it.name }}")

        val languageServer = LanguageServersRegistry.getInstance()
            .getServerDefinition(MonkeyCLanguageServerFactory.SERVER_ID)
        if (languageServer == null) {
            problems += "the language server '${MonkeyCLanguageServerFactory.SERVER_ID}' is not registered with LSP4IJ"
        } else {
            println("[self-check] language server: ${languageServer.displayName}")
        }

        if (FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.none { it.editorTypeId == "monkeyc-manifest" }) {
            problems += "the manifest form editor is not registered"
        } else {
            println("[self-check] manifest form editor")
        }

        val wizard = GeneratorNewProjectWizard.EP_NAME.extensionList.firstOrNull { it.id == "ConnectIQ" }
        if (wizard == null) {
            problems += "the New Project generator is not registered"
        } else {
            println("[self-check] new project wizard: ${wizard.name}")
        }

        problems += editorFeatureProblems()

        val libraries = AdditionalLibraryRootsProvider.EP_NAME.extensionList
            .firstOrNull { it is com.github.dtretyakov.monkeyc.library.ConnectIqLibraryProvider }
        if (libraries == null) {
            problems += "the Connect IQ library provider is not registered"
        } else {
            println("[self-check] library provider: ${libraries.javaClass.simpleName}")
        }

        problems += apiSurfaceProblems()
        problems += typingProblems()

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

        reportUnloadability()

        problems.forEach { println("[self-check] PROBLEM: $it") }
        println(if (problems.isEmpty()) "[self-check] OK" else "[self-check] FAILED")
        exitProcess(if (problems.isEmpty()) 0 else 1)
    }

    /**
     * That a `.barrel` can actually be opened as what it is: a zip of Monkey C source.
     *
     * The library node shows barrels by mounting them through `JarFileSystem`, and whether that
     * works for an extension the platform has never heard of is not something a registration can
     * be read to confirm — it depends on the file type association having taken. So this builds a
     * barrel-shaped zip and mounts it. If the answer is no, every barrel in the tree is one
     * unreadable binary file and nothing anywhere says why.
     */
    /**
     * Whether the platform would let this plugin be unloaded without restarting the IDE.
     *
     * The platform's own verdict rather than a guess of ours: `checkCanUnloadWithoutRestart` is
     * what the IDE consults before it tries, and it is what turns red the moment somebody adds an
     * extension point that is not dynamic. That is the regression this guards — the answer today
     * is yes, and it should keep being yes.
     *
     * A yes here does not promise the IDE will manage it. "Failed to unload modified plugins" can
     * still appear afterwards, from a later step where the platform recomputes the plugin graph and
     * finds the plugin still in it — which is what happens in the development sandbox, and happens
     * to LSP4IJ beside us. So this reports and does not fail: what it can prove is ours to keep
     * right, and what it cannot is not.
     */
    private fun reportUnloadability() {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
        if (descriptor == null) {
            println("[self-check] dynamic unload: the plugin descriptor could not be found")
            return
        }

        // Reflectively, because the method takes a different parameter type in 2026.1 and calling
        // it directly puts a NoSuchMethodError in the shipped jar for every user on that IDE — for
        // the sake of a diagnostic that only ever runs from `runSelfCheck` on a developer's
        // machine. The verifier catches exactly this, which is what `-PverifySince` is for.
        //
        // Boolean, and `true` is the good answer. Worth spelling out: the name reads like a method
        // that returns the reason it cannot, so comparing the result to null compiles, is always
        // false, and reports every plugin as unloadable-no.
        val canUnload = runCatching {
            DynamicPlugins::class.java
                .methods
                .first { it.name == "checkCanUnloadWithoutRestart" && it.parameterCount == 1 }
                .invoke(DynamicPlugins, descriptor) as Boolean
        }.getOrNull()

        println(
            when (canUnload) {
                true -> "[self-check] dynamic unload: yes"
                false -> "[self-check] dynamic unload: no, an extension point is not dynamic"
                null -> "[self-check] dynamic unload: this IDE does not answer the question"
            },
        )
    }

    private fun barrelProblems(): List<String> {
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension("barrel")
        if (fileType !is ArchiveFileType) return listOf(".barrel is $fileType, expected an archive")

        val probe = Files.createTempFile("monkeyc-selfcheck", ".barrel")
        try {
            ZipOutputStream(Files.newOutputStream(probe)).use { zip ->
                zip.putNextEntry(ZipEntry("content/source/Probe.mc"))
                zip.write("module Probe {}".toByteArray())
                zip.closeEntry()
            }

            val local = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(probe)
                ?: return listOf("a .barrel on disk has no VirtualFile")
            val root = JarFileSystem.getInstance().getJarRootForLocalFile(local)
                ?: return listOf("a .barrel cannot be mounted, so barrels would show as one binary file")
            val source = root.findFileByRelativePath("content/source/Probe.mc")
                ?: return listOf("a mounted .barrel has no ${root.children.size} readable children")

            println("[self-check] barrels: mounted, and ${source.name} is ${source.fileType.name}")
            return if (source.fileType == MonkeyCFileType) {
                emptyList()
            } else {
                listOf("source inside a barrel is ${source.fileType.name}, expected Monkey C")
            }
        } finally {
            Files.deleteIfExists(probe)
        }
    }

    /**
     * The two things that answer while the user is typing.
     *
     * Indentation on Enter has no formatter behind it, so it hangs on one registration and nothing
     * says when that registration is absent — the caret simply goes to column one. And the SDK's
     * server attaches `monkeyc.functionCompletion` to every function that takes parameters, which
     * LSP4IJ resolves by looking for an IDE action of that id; without one, completing a call
     * raises an error balloon each time.
     */
    private fun typingProblems(): List<String> {
        val problems = mutableListOf<String>()

        val indent = ExtensionPointName<LineIndentProvider>("com.intellij.lineIndentProvider")
            .extensionList
            .filterIsInstance<MonkeyCLineIndentProvider>()
            .firstOrNull()
        if (indent == null || !indent.isSuitableFor(MonkeyCLanguage)) {
            problems += "Enter will not indent: no line indent provider claims Monkey C"
        } else {
            println("[self-check] typing: Enter indents Monkey C")
        }

        val completion = ActionManager.getInstance().getAction(FUNCTION_COMPLETION_COMMAND)
        if (completion == null) {
            problems += "completing a call will raise an error: no action serves $FUNCTION_COMPLETION_COMMAND"
        } else {
            println("[self-check] typing: $FUNCTION_COMPLETION_COMMAND -> ${completion.javaClass.simpleName}")
        }

        return problems
    }

    /**
     * That the Toybox API can be searched and navigated into.
     *
     * Three registrations and one parser, and if any of them is wrong the result is the same as
     * before any of this existed — F12 does nothing and Ctrl+N finds nothing — which is not a
     * state anything reports. On a machine with an SDK the index is parsed for real, because a
     * registration that resolves over a file that no longer parses is no better than no
     * registration at all.
     */
    private fun apiSurfaceProblems(): List<String> {
        val problems = mutableListOf<String>()

        if (GotoDeclarationHandler.EP_NAME.extensionList.none { it is ApiMirGotoDeclarationHandler }) {
            problems += "go to definition for Toybox symbols is not registered"
        }
        if (ChooseByNameContributor.SYMBOL_EP_NAME.extensionList.none { it is ApiMirGotoSymbolContributor }) {
            problems += "Go to Symbol does not reach the Connect IQ API"
        }
        if (ChooseByNameContributor.CLASS_EP_NAME.extensionList.none { it is ApiMirGotoClassContributor }) {
            problems += "Go to Class does not reach the Connect IQ API"
        }
        if (ActionManager.getInstance().getAction("MonkeyC.OpenApiDocumentation") == null) {
            problems += "the API documentation action is not registered"
        }

        val sdk = ConnectIqSdkService.getInstance().sdk
        if (sdk == null) {
            println("[self-check] api surface: registered; no SDK here to parse")
            return problems
        }

        val index = ApiMirService.getInstance().index(null)
        when {
            index == null -> problems += "the SDK at ${sdk.root} has no readable bin/api.mir"
            index.size < 2_000 -> problems += "api.mir parsed to only ${index.size} symbols, so its format has moved"
            index.exact("Toybox.Graphics.Dc.drawText") == null ->
                problems += "api.mir parsed, but Toybox.Graphics.Dc.drawText is not in it"

            else -> println("[self-check] api surface: ${index.size} symbols from bin/api.mir")
        }

        return problems
    }

    /**
     * The editor features that are wired by naming somebody else's class in `plugin.xml`.
     *
     * Six of them are LSP4IJ's own implementations, which LSP4IJ registers only for `TEXT` and
     * `textmate`; naming them for our languages is the whole of the wiring. That makes two silent
     * failures possible at once — a registration that never took, and a class LSP4IJ renamed in a
     * version bump — and neither produces an error. The editor is simply quieter than it was, in
     * a way nobody notices until they reach for Ctrl+P.
     *
     * So this loads each instance rather than only counting the registrations.
     */
    private fun editorFeatureProblems(): List<String> {
        val problems = mutableListOf<String>()

        EXPECTED.forEach { (endpoint, languages) ->
            val point = ExtensionPointName<LanguageExtensionPoint<Any>>(endpoint)
            languages.forEach { language ->
                val bean = point.extensionList.firstOrNull { it.language == language }
                when {
                    bean == null -> problems += "$endpoint is not registered for $language"
                    runCatching { bean.instance }.isFailure ->
                        problems += "$endpoint for $language names ${bean.implementationClass}, which will not load"

                    else -> println("[self-check] $endpoint: $language -> ${bean.implementationClass}")
                }
            }
        }

        return problems
    }

    private companion object {
        /** Must match `<id>` in plugin.xml; the descriptor is looked up by it. */
        const val PLUGIN_ID = "com.github.dtretyakov.monkeyc"

        /** The command Garmin's server sends after completing a call; see plugin.xml. */
        const val FUNCTION_COMPLETION_COMMAND = "monkeyc.functionCompletion"

        val ALL = listOf("MonkeyC", "Jungle", "MSS")

        /** The SDK's API surface, whose outline and folding come from the index, not the server. */
        val API = listOf("ConnectIqApi")

        val EXPECTED = mapOf(
            "com.intellij.lang.psiStructureViewFactory" to ALL + API,
            "com.intellij.lang.foldingBuilder" to ALL + API,
            "com.intellij.lang.quoteHandler" to ALL,
            "com.intellij.lang.braceMatcher" to ALL,
            "com.intellij.codeInsight.parameterInfo" to listOf("MonkeyC"),
            "com.intellij.codeBlockProvider" to listOf("MonkeyC"),
            "com.intellij.typeHierarchyProvider" to listOf("MonkeyC"),
            "com.intellij.callHierarchyProvider" to listOf("MonkeyC"),
        )
    }
}
