package com.github.dtretyakov.monkeyc.lang

import com.github.dtretyakov.monkeyc.sdk.ApiMirDoc
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex.Declaration
import com.github.dtretyakov.monkeyc.sdk.ApiMirService
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.InlineDocumentation
import com.intellij.platform.backend.documentation.InlineDocumentationProvider
import com.intellij.psi.PsiFile

/**
 * Renders the API file's documentation in place, the way JDK sources are rendered.
 *
 * `api.mir` is more comment than code: a class of a hundred lines carries four hundred lines of
 * `//!` above it, and read as plain text that is mostly punctuation. This is the same content the
 * SDK's HTML reference is generated from, and the platform already has a place to put it — the
 * rendered documentation the IDE shows for javadoc, which any file can supply through this
 * extension point.
 *
 * The index has already found every declaration's comment while reading the file, so nothing here
 * scans anything: it hands over ranges it was given and renders one only when the editor asks.
 */
class ApiMirInlineDocumentationProvider : InlineDocumentationProvider {

    override fun inlineDocumentationItems(file: PsiFile?): Collection<InlineDocumentation> {
        val declarations = documented(file) ?: return emptyList()
        return declarations.map { ApiMirInlineDocumentation(it, file!!) }
    }

    override fun findInlineDocumentation(file: PsiFile, textRange: TextRange): InlineDocumentation? {
        val declarations = documented(file) ?: return null
        return declarations
            .firstOrNull { it.doc!!.first == textRange.startOffset }
            ?.let { ApiMirInlineDocumentation(it, file) }
    }

    /** Declarations of this file that have a comment, or null when this is not the API file. */
    private fun documented(file: PsiFile?): List<Declaration>? {
        if (file == null || file.fileType != ApiMirFileType) return null
        val index = ApiMirService.getInstance().index(file.project) ?: return null

        // The index is built from the file on disk. A document longer or shorter than it belongs
        // to something else, and ranges taken from one would land anywhere in the other.
        val length = file.textLength
        return index.all().filter { it.doc != null && it.doc!!.last <= length }
    }
}

private class ApiMirInlineDocumentation(
    private val declaration: Declaration,
    private val file: PsiFile,
) : InlineDocumentation {

    override fun getDocumentationRange(): TextRange =
        TextRange(declaration.doc!!.first, declaration.doc!!.last)

    /**
     * The declaration the comment belongs to, up to the end of its own line.
     *
     * The bookkeeping annotation between the two is deliberately included: rendering the comment
     * and leaving that line stranded above the declaration reads worse than either alone.
     */
    override fun getDocumentationOwnerRange(): TextRange {
        val start = declaration.annotation?.first ?: declaration.offset
        return TextRange(start, maxOf(start, declaration.endOffset))
    }

    override fun renderText(): String? =
        file.text
            .substring(declaration.doc!!.first, minOf(declaration.doc!!.last, file.textLength))
            .let { ApiMirDoc.toHtml(it) }
            .takeIf { it.isNotBlank() }

    /** Nothing extra to show in the popup: the rendered text is the whole of what is known. */
    override fun getOwnerTarget(): DocumentationTarget? = null
}
