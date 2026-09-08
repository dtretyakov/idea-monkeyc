package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.lsp.CanonicalPaths
import com.github.dtretyakov.monkeyc.lsp.InitializationOptions
import com.github.dtretyakov.monkeyc.lsp.MonkeyCFileUriSupport
import com.github.dtretyakov.monkeyc.lsp.SdkServerCommands
import com.github.dtretyakov.monkeyc.lsp.RequiredFieldsFilter
import com.github.dtretyakov.monkeyc.lsp.WorkspaceSettings
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.github.dtretyakov.monkeyc.sdk.JavaLocator
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.CallHierarchyCapabilities
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.CompletionCapabilities
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DeclarationCapabilities
import org.eclipse.lsp4j.DefinitionCapabilities
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlightCapabilities
import org.eclipse.lsp4j.FoldingRangeCapabilities
import org.eclipse.lsp4j.HoverCapabilities
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.DidChangeConfigurationCapabilities
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.SignatureHelpCapabilities
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SynchronizationCapabilities
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TypeDefinitionCapabilities
import org.eclipse.lsp4j.TypeHierarchyCapabilities
import org.eclipse.lsp4j.TypeHierarchyPrepareParams
import org.eclipse.lsp4j.SymbolCapabilities
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WindowClientCapabilities
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

/**
 * Drives the SDK's language server the way the plugin does.
 *
 * These are the assumptions the plugin is built on, checked against the server rather than against
 * a memory of it: that it starts from the jar we point at, accepts the settings in the shape we
 * send them, and misbehaves in exactly the two ways the client works around. If Garmin fixes either
 * one, a test here goes red and the workaround can go.
 */
class LanguageServerLiveTest {

    @Test
    fun `answers completion, definition and signature help on a real project`(@TempDir temp: Path) {
        val sdk = LiveSdk.require()
        // The plugin canonicalises everything it hands the server, and so must this: the fixture
        // lives under a temp directory that macOS reaches through a symlink, and the server
        // silently finds nothing for a document it cannot match to a compiled file.
        val project = CanonicalPaths.of(LiveSdk.fixture(temp))
        val device = LiveSdk.device(sdk, listOf("fenix7", "fenix6"))
        val source = project.resolve("source/FixtureApp.mc")
        val text = source.readText()

        val stderr = temp.resolve("language-server.err").toFile()
        val process = ProcessBuilder(SdkServerCommands.languageServer(sdk, JavaLocator.resolve(null)))
            .directory(project.toFile())
            .redirectError(stderr)
            .start()

        val client = RecordingClient()
        try {
            // The same filter the plugin puts between LSP4IJ and the server.
            val launcher = LSPLauncher.createClientLauncher(
                client,
                process.inputStream,
                RequiredFieldsFilter(process.outputStream),
            )
            val server = launcher.remoteProxy
            launcher.startListening()

            initialize(server, project, device)
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(source.toUri().toString(), "monkeyc", 1, text)),
            )
            client.awaitBuild()

            val document = TextDocumentIdentifier(source.toUri().toString())

            // "Full workspace build successful" is not the end of it: the per-file context the
            // completion needs is built after that, and the server announces nothing when it is
            // ready. So ask until it answers.
            val labels = eventually("completion after 'dc.'") {
                val completion = server.textDocumentService
                    .completion(CompletionParams(document, after(text, "dc.setColor", 3)))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                (completion.right?.items.orEmpty() + completion.left.orEmpty())
                    .map { it.label }
                    .takeIf { it.isNotEmpty() }
            }
            assertTrue(labels.contains("setColor"), "expected Dc members, got ${labels.take(10)}")

            assertMalformedDefinitionUri(server, document, after(text, "new FixtureView", 6), source)
            assertOnlyHierarchyReachesTheApi(server, document, after(text, "dc.setColor", 5), sdk.root)
            assertNoSemanticTokens(client)
            assertSignatureHelpNeedsTheFilter(server, document, after(text, "dc.drawText(", 12))
            assertHoverIsWholeAndSelfContained(server, document, after(text, "dc.setColor", 5))
            assertConstructorGoesToTheClass(server, document, after(text, "new FixtureView", 6), text)
            assertResourceXmlHasNoNavigation(server, project)
        } catch (e: Throwable) {
            // The server reports its own failures on stderr, and they say far more than
            // "Internal error." does.
            throw AssertionError(
                "${e.message}\n\nserver log:\n" + client.log.joinToString("\n").take(4000) +
                    "\n\nstderr:\n" + stderr.readText().take(2000),
                e,
            )
        } finally {
            process.destroy()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    /**
     * That the hover is about the symbol asked for and nothing else.
     *
     * Worth pinning down, because the equivalent third-party tooling has this wrong: its hover for
     * `Activity.Info.timerTime` shows one correct line and then the whole of the *next* entry in
     * Garmin's documentation HTML, which took its reporter a while even to notice. The language
     * server does not — it answers structured Markdown assembled from the API rather than scraped
     * out of a page — and this is the tripwire for the day that changes.
     */
    private fun assertHoverIsWholeAndSelfContained(
        server: LanguageServer,
        document: TextDocumentIdentifier,
        position: Position,
    ) {
        val hover = server.textDocumentService
            .hover(HoverParams(document, position))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val content = hover?.contents?.right?.value.orEmpty()
        assertTrue(content.isNotEmpty(), "the server answered no hover at all")
        assertTrue(content.contains("setColor"), "the hover is not about setColor:\n$content")

        // One signature, not two: a second `public function` heading would be the next API entry
        // having run on into this one.
        assertEquals(
            1,
            Regex("public function").findAll(content).count(),
            "the hover carries more than one declaration:\n$content",
        )

        // The links are VS Code commands, which is what ApiDocumentationLinks rewrites. If they
        // ever become ordinary URLs, that rewriting is dead code.
        assertTrue(
            content.contains("command:monkeyc.viewApiDocumentation"),
            "the server no longer sends VS Code command links; ApiDocumentationLinks can go",
        )
    }

    /**
     * That `new FixtureView()` still goes to the class rather than to its `initialize`.
     *
     * The same complaint exists against the third-party tooling, and it is Garmin's server that
     * decides: asked about a constructor call it answers with the class declaration. Landing on
     * the class is adjacent and not wrong, and redirecting to `initialize` would mean this plugin
     * knowing what `new` means — which is the one thing its design says it will not do.
     *
     * So it is recorded rather than worked around, in the shape this project uses for everything
     * the SDK gets slightly wrong: a test that goes red the day it is fixed, so the note can be
     * removed rather than outliving the problem.
     */
    private fun assertConstructorGoesToTheClass(
        server: LanguageServer,
        document: TextDocumentIdentifier,
        position: Position,
        text: String,
    ) {
        val locations = server.textDocumentService
            .definition(DefinitionParams(document, position))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .left.orEmpty()
        assertTrue(locations.isNotEmpty(), "no definition for a constructor call")

        val line = locations.first().range.start.line
        val declaration = text.lines().getOrNull(line).orEmpty().trim()
        assertTrue(
            declaration.startsWith("class FixtureView"),
            "the server now sends a constructor call somewhere other than the class ($declaration); " +
                "if that is `initialize`, this note and this test can go",
        )
    }

    /**
     * That the server still answers nothing inside a resource XML file.
     *
     * The plugin routes the project's XML to the server — `ConnectIqXmlMatcher` — on the strength
     * of it offering completion there. Navigation it does not: asked where a drawable id is
     * declared, it returns nothing at all. Building that ourselves would mean a model of the
     * resource references, which is the kind of thing this plugin exists not to maintain.
     *
     * Recorded so the day it starts answering is a red test rather than an unnoticed improvement.
     */
    private fun assertResourceXmlHasNoNavigation(server: LanguageServer, project: Path) {
        val resource = project.resolve("resources/drawables.xml")
        val text = resource.readText()
        val uri = resource.toUri().toString()
        server.textDocumentService.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "xml", 1, text)),
        )
        val locations = runCatching {
            server.textDocumentService
                .definition(DefinitionParams(TextDocumentIdentifier(uri), after(text, "LauncherIcon", 4)))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .left.orEmpty()
        }.getOrElse { emptyList() }
        assertTrue(
            locations.isEmpty(),
            "the server now navigates inside resource XML (${locations.firstOrNull()?.uri}); " +
                "LSPGotoDeclarationHandler can be registered for XML and this test removed",
        )
    }

    /**
     * The server answers `file:/abs/path` where the protocol wants `file:///abs/path`, so nothing
     * downstream can resolve it. This is what [MonkeyCFileUriSupport] repairs.
     */
    private fun assertMalformedDefinitionUri(
        server: LanguageServer,
        document: TextDocumentIdentifier,
        position: Position,
        source: Path,
    ) {
        val definition = server.textDocumentService
            .definition(DefinitionParams(document, position))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val locations = definition.left.orEmpty()
        assertTrue(locations.isNotEmpty(), "no definition was found")

        val uri = locations.first().uri
        assertFalse(
            uri.startsWith("file://"),
            "the server has started sending well-formed URIs; MonkeyCFileUriSupport can go",
        )
        assertTrue(
            Path.of(java.net.URI(MonkeyCFileUriSupport.repair(uri))).toRealPath() == source.toRealPath(),
            "the repaired URI must point at the source file, but was $uri",
        )
    }

    /**
     * That the server still offers no semantic tokens.
     *
     * Colouring in this plugin is syntactic — a lexer and a handful of positional rules — because
     * there is nothing better on offer. The day there is, LSP4IJ's own highlighter takes over on
     * its own, with no registration from us, and the annotator would then be painting on top of
     * it. Nothing else in the project would notice, so this is the tripwire.
     */
    private fun assertNoSemanticTokens(client: RecordingClient) {
        assertFalse(
            client.registered.any { it.contains("semanticTokens", ignoreCase = true) },
            "the server now offers semantic tokens; MonkeyCAnnotator must step aside for LSP4IJ",
        )
    }

    /**
     * How a call on an instance is resolved, which is not through `definition`.
     *
     * `dc.setColor` is the shape most Monkey C code is made of, and the two requests an IDE would
     * reach for both come back empty: `DefinitionContext` and `TypeDefinitionContext` resolve the
     * symbol perfectly well — that is the same inference completion runs on — and then look the
     * location up through `WorkspaceContext`, which only knows the project's own files.
     *
     * The hierarchy requests do not go through that path. They answer with a location inside the
     * SDK's `api.mir`, and `detail` carries the fully qualified name. That is why go-to-definition
     * in this plugin asks the server for a hierarchy rather than for a definition, and it is worth
     * a test: if Garmin ever teaches `definition` about the API, the first two assertions here go
     * red and a whole layer of the plugin can go with them.
     */
    private fun assertOnlyHierarchyReachesTheApi(
        server: LanguageServer,
        document: TextDocumentIdentifier,
        position: Position,
        sdkRoot: Path,
    ) {
        val definition = server.textDocumentService
            .definition(DefinitionParams(document, position))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue(
            definition.left.orEmpty().isEmpty() && definition.right.orEmpty().isEmpty(),
            "the server now answers definition for an API member; the hierarchy detour can go",
        )

        val call = eventually("prepareCallHierarchy on dc.setColor") {
            server.textDocumentService
                .prepareCallHierarchy(CallHierarchyPrepareParams(document, position))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?.takeIf { it.isNotEmpty() }
        }
        val member = call.first()
        assertTrue(member.uri.contains("api.mir"), "expected api.mir, got ${member.uri}")
        assertTrue(member.uri.contains(sdkRoot.fileName.toString()), "expected this SDK, got ${member.uri}")
        assertTrue(
            member.detail == "\$.Toybox.Graphics.Dc.setColor",
            "expected the qualified name in detail, got ${member.detail}",
        )

        val type = server.textDocumentService
            .prepareTypeHierarchy(TypeHierarchyPrepareParams(document, position))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue(
            type.orEmpty().any { it.uri.contains("api.mir") },
            "expected the receiver's type in api.mir, got ${type.orEmpty().map { it.uri }}",
        )
    }

    /**
     * Without a context the server throws; the request here carries none, and only arrives with one
     * because [RequiredFieldsFilter] is in the way. A successful answer is the proof.
     */
    private fun assertSignatureHelpNeedsTheFilter(
        server: LanguageServer,
        document: TextDocumentIdentifier,
        position: Position,
    ) {
        val help = server.textDocumentService
            .signatureHelp(SignatureHelpParams(document, position))
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertTrue(
            help.signatures.any { it.label.startsWith("drawText(") },
            "expected drawText's signature, got ${help.signatures.map { it.label }}",
        )
    }

    private fun initialize(server: LanguageServer, project: Path, device: String) {
        val params = InitializeParams().apply {
            processId = ProcessHandle.current().pid().toInt()
            @Suppress("DEPRECATION")
            rootUri = project.toUri().toString()
            workspaceFolders = listOf(WorkspaceFolder(project.toUri().toString(), project.fileName.toString()))
            capabilities = clientCapabilities()
            initializationOptions = InitializationOptions(
                publishWarnings = true,
                compilerOptions = "",
                typeCheckMsgDisplayed = true,
                workspaceSettings = listOf(
                    WorkspaceSettings(
                        path = project.toString(),
                        jungleFiles = ProjectLayout.jungleFiles(project, null).map { it.toString() },
                        options = listOf("Gradual", "Default", device),
                    ),
                ),
            )
        }
        server.initialize(params).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        server.initialized(InitializedParams())
    }

    /**
     * The server registers everything dynamically — its `initialize` reply names only the two
     * hierarchy providers — and throws if a capability it wants to register is not declared
     * dynamic. These are the ones it asks for.
     */
    private fun clientCapabilities() = ClientCapabilities().apply {
        textDocument = TextDocumentClientCapabilities().apply {
            synchronization = SynchronizationCapabilities().apply { dynamicRegistration = true }
            completion = CompletionCapabilities().apply { dynamicRegistration = true }
            hover = HoverCapabilities().apply { dynamicRegistration = true }
            signatureHelp = SignatureHelpCapabilities().apply { dynamicRegistration = true }
            declaration = DeclarationCapabilities().apply { dynamicRegistration = true }
            definition = DefinitionCapabilities().apply { dynamicRegistration = true }
            typeDefinition = TypeDefinitionCapabilities().apply { dynamicRegistration = true }
            typeHierarchy = TypeHierarchyCapabilities().apply { dynamicRegistration = true }
            callHierarchy = CallHierarchyCapabilities().apply { dynamicRegistration = true }
            implementation = ImplementationCapabilities().apply { dynamicRegistration = true }
            documentHighlight = DocumentHighlightCapabilities().apply { dynamicRegistration = true }
            foldingRange = FoldingRangeCapabilities().apply {
                dynamicRegistration = true
                lineFoldingOnly = true
            }
            publishDiagnostics = PublishDiagnosticsCapabilities()
        }
        workspace = WorkspaceClientCapabilities().apply {
            workspaceFolders = true
            configuration = true
            symbol = SymbolCapabilities().apply { dynamicRegistration = true }
            didChangeConfiguration = DidChangeConfigurationCapabilities().apply { dynamicRegistration = true }
            didChangeWatchedFiles = DidChangeWatchedFilesCapabilities().apply { dynamicRegistration = true }
        }
        window = WindowClientCapabilities().apply { workDoneProgress = true }
    }

    /** Retries [attempt] until it produces something, because the server has no "ready" signal. */
    private fun <T : Any> eventually(what: String, attempt: () -> T?): T {
        val deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000
        while (System.currentTimeMillis() < deadline) {
            attempt()?.let { return it }
            Thread.sleep(500)
        }
        throw AssertionError("the server never answered $what")
    }

    /** The position [offset] characters past the start of [needle]. */
    private fun after(text: String, needle: String, offset: Int): Position {
        val index = text.indexOf(needle)
        check(index >= 0) { "the fixture no longer contains '$needle'" }
        val before = text.substring(0, index + offset)
        return Position(before.count { it == '\n' }, before.length - before.lastIndexOf('\n') - 1)
    }

    private class RecordingClient : LanguageClient {
        private val built = CompletableFuture<Unit>()
        val diagnostics = mutableListOf<PublishDiagnosticsParams>()

        /** The server asks the client to flush unsaved files before it compiles. */
        @JsonRequest("custom/save")
        fun save(root: String): CompletableFuture<Map<String, Any>> =
            CompletableFuture.completedFuture(mapOf("savedFiles" to emptyList<String>(), "error" to false))

        override fun telemetryEvent(any: Any?) = Unit
        override fun publishDiagnostics(params: PublishDiagnosticsParams) {
            diagnostics += params
        }

        override fun showMessage(params: MessageParams?) = Unit
        override fun showMessageRequest(params: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(null)

        /** Everything the server registered dynamically, which is how it registers everything. */
        val registered = mutableListOf<String>()

        // The server registers every one of its capabilities dynamically and asks for its
        // configuration; lsp4j's defaults throw, which aborts the whole registration.
        override fun registerCapability(params: RegistrationParams?): CompletableFuture<Void> {
            params?.registrations?.forEach { registered += it.method }
            return CompletableFuture.completedFuture(null)
        }

        override fun unregisterCapability(params: UnregistrationParams?): CompletableFuture<Void> =
            CompletableFuture.completedFuture(null)

        override fun configuration(params: ConfigurationParams): CompletableFuture<MutableList<Any>> =
            CompletableFuture.completedFuture(params.items.map { Any() }.toMutableList())

        override fun createProgress(params: WorkDoneProgressCreateParams?): CompletableFuture<Void> =
            CompletableFuture.completedFuture(null)

        override fun notifyProgress(params: ProgressParams?) = Unit

        override fun applyEdit(params: ApplyWorkspaceEditParams?): CompletableFuture<ApplyWorkspaceEditResponse> =
            CompletableFuture.completedFuture(ApplyWorkspaceEditResponse(true))

        val log = mutableListOf<String>()

        override fun logMessage(params: MessageParams) {
            log += params.message
            // "Full workspace build successful" is the server saying its index is ready; before
            // that, completion and definition answer from nothing.
            if (params.message.contains("build successful", ignoreCase = true)) built.complete(Unit)
        }

        fun awaitBuild() = built.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 120L
    }
}
