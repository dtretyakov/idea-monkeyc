package com.github.dtretyakov.monkeyc.lsp

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

class MonkeyCLanguageServerFactory : LanguageServerFactory {

    override fun createConnectionProvider(project: Project): StreamConnectionProvider =
        MonkeyCConnectionProvider(project)

    override fun createLanguageClient(project: Project): LanguageClientImpl =
        MonkeyCLanguageClient(project)

    override fun createClientFeatures(): LSPClientFeatures = MonkeyCClientFeatures()

    companion object {
        /** Must match the `id` of the `server` extension in plugin.xml. */
        const val SERVER_ID = "monkeyc"
    }
}
