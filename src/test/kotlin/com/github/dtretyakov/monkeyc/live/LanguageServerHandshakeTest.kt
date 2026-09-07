package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.lsp.LanguageServerSettings
import com.github.dtretyakov.monkeyc.lsp.RequiredFieldsFilter
import com.github.dtretyakov.monkeyc.lsp.SdkServerCommands
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.sdk.JavaLocator
import com.github.dtretyakov.monkeyc.testing.IdeTestCase
import com.google.gson.Gson
import com.redhat.devtools.lsp4ij.internal.capabilities.ClientCapabilitiesFactory
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Shakes hands with the language server using exactly what the plugin sends it.
 *
 * The pieces are each covered elsewhere — the settings by unit tests, the server by
 * LanguageServerLiveTest with a client of its own — but not the combination, and it is the
 * combination that broke: LSP4IJ's client capabilities plus our initialization options made the
 * server answer `initialize` with "Internal error", so nothing worked at all.
 */
class LanguageServerHandshakeTest : IdeTestCase() {

    fun testTheServerAcceptsWhatThePluginSends() {
        if (!LiveSdk.enabled) return
        val sdk = ConnectIqSdk.detect() ?: return
        if (!sdk.hasLanguageServer) return

        myFixture.addFileToProject("monkey.jungle", "project.manifest = manifest.xml")
        myFixture.addFileToProject(
            "manifest.xml",
            """
            <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
                <iq:application entry="App" id="0123" minApiLevel="3.2.0" type="watch-app">
                    <iq:products><iq:product id="fenix7"/></iq:products>
                </iq:application>
            </iq:manifest>
            """.trimIndent(),
        )

        val options = LanguageServerSettings.initializationOptions(project)
        val process = ProcessBuilder(SdkServerCommands.languageServer(sdk, JavaLocator.resolve(null))).start()

        try {
            // Through the same filter the plugin puts between LSP4IJ and the server; without it
            // the server throws inside initialize and nothing works at all.
            val launcher = LSPLauncher.createClientLauncher(
                SilentClient(),
                process.inputStream,
                RequiredFieldsFilter(process.outputStream),
            )
            launcher.startListening()

            val params = InitializeParams().apply {
                processId = ProcessHandle.current().pid().toInt()
                // The client capabilities LSP4IJ actually sends, not an approximation of them.
                capabilities = ClientCapabilitiesFactory.create(null)
                workspaceFolders = listOf(WorkspaceFolder("file:///tmp", "probe"))
                initializationOptions = options
            }

            try {
                launcher.remoteProxy.initialize(params).get(60, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                val error = (e.cause as? ResponseErrorException)?.responseError
                fail(
                    "the server refused the handshake: ${error?.message}\n\n" +
                        "options: ${Gson().toJson(options)}\n\n" +
                        "server said:\n${error?.data.toString().take(3000)}",
                )
            }
        } finally {
            process.destroy()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    private class SilentClient : LanguageClient {
        override fun telemetryEvent(any: Any?) = Unit
        override fun publishDiagnostics(params: PublishDiagnosticsParams?) = Unit
        override fun showMessage(params: MessageParams?) = Unit
        override fun logMessage(params: MessageParams?) = Unit
        override fun showMessageRequest(params: ShowMessageRequestParams?): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(null)

        override fun registerCapability(params: RegistrationParams?): CompletableFuture<Void> =
            CompletableFuture.completedFuture(null)

        override fun unregisterCapability(params: UnregistrationParams?): CompletableFuture<Void> =
            CompletableFuture.completedFuture(null)

        override fun createProgress(params: WorkDoneProgressCreateParams?): CompletableFuture<Void> =
            CompletableFuture.completedFuture(null)
    }
}
