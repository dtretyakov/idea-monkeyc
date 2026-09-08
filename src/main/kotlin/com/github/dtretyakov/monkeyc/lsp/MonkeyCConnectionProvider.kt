package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.sdk.SdkVersion
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.server.CannotStartProcessException
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import java.io.OutputStream
import kotlin.io.path.exists

/**
 * Starts the language server that ships with the Connect IQ SDK.
 *
 * This is the same process, started the same way, as the official VS Code extension starts: a
 * plain Java program speaking LSP over stdio. Nothing is bundled with the plugin — if the user
 * switches SDKs in the SDK Manager, the next start picks up the new one.
 */
class MonkeyCConnectionProvider(private val project: Project) : OSProcessStreamConnectionProvider() {

    @Volatile
    private var output: OutputStream? = null

    override fun start() {
        val sdk = ConnectIqSdkService.getInstance().sdkFor(project)
            ?: throw CannotStartProcessException(
                "No Connect IQ SDK found. Install one with Garmin's SDK Manager, " +
                    "or set its location in Settings | Languages & Frameworks | Monkey C.",
            )

        if (!sdk.languageServerJar.exists()) {
            throw CannotStartProcessException(
                "The Connect IQ SDK at ${sdk.root} has no language server. " +
                    "Code intelligence needs SDK ${SdkVersion.LANGUAGE_SERVER_MINIMUM} or newer" +
                    (sdk.version?.let { "; this one is $it." } ?: "."),
            )
        }

        commandLine = GeneralCommandLine(SdkServerCommands.languageServer(sdk, ConnectIqSdkService.getInstance().java()))
            .withWorkingDirectory(project.guessProjectDir()?.toNioPath())

        super.start()
        output = super.getOutputStream()?.let { RequiredFieldsFilter(it) }
    }

    /**
     * See [RequiredFieldsFilter] for why requests do not go straight to the process.
     *
     * One filter per started process, not one per call: the filter holds the bytes of a message
     * that has arrived only in part, and a second instance would start with an empty buffer and
     * interleave its writes with whatever the first one is still holding.
     */
    override fun getOutputStream(): OutputStream? = output

    override fun stop() {
        output = null
        super.stop()
    }

    override fun getInitializationOptions(rootUri: VirtualFile?): Any =
        LanguageServerSettings.initializationOptions(project)
}
