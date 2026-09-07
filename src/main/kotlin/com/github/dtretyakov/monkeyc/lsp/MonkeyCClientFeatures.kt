package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.intellij.psi.PsiFile
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.client.features.LSPHoverFeature
import org.eclipse.lsp4j.MarkupContent

/**
 * The places where the Monkey C server needs the client to meet it halfway.
 *
 * Everything else is stock LSP4IJ. What is here is here because the server, written for one
 * editor, assumes that editor: it sends malformed file URIs and documentation links that are VS
 * Code commands.
 */
class MonkeyCClientFeatures : LSPClientFeatures() {

    init {
        setFileUriSupport(MonkeyCFileUriSupport)
        setHoverFeature(MonkeyCHoverFeature())
    }
}

private class MonkeyCHoverFeature : LSPHoverFeature() {

    override fun getContent(content: MarkupContent, file: PsiFile): String? {
        val rewritten = MarkupContent(
            content.kind,
            ApiDocumentationLinks.rewrite(content.value, ConnectIqSdkService.getInstance().sdk),
        )
        return super.getContent(rewritten, file)
    }
}
