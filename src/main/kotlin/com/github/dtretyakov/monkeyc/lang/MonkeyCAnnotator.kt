package com.github.dtretyakov.monkeyc.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil

/**
 * Colours the identifiers a lexer alone has to leave grey.
 *
 * The language server offers no semantic tokens — it registers seventeen capabilities and that is
 * not among them — so nothing tells the editor which name is a class and which is a local. What is
 * left is the shape of the code around each name, and in Monkey C that says most of it: a name
 * after `class` is a class, a name before `(` is a call, and a name in capitals is a constant.
 *
 * Every rule here is syntactic. None of them guesses at meaning, because a name coloured wrongly
 * reads worse than a name not coloured at all.
 */
class MonkeyCAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.node?.elementType != MonkeyCTokens.IDENTIFIER) return

        val colour = colourOf(element) ?: return
        holder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
            .range(element)
            .textAttributes(colour)
            .create()
    }

    private fun colourOf(element: PsiElement): TextAttributesKey? = MonkeyCNaming.colourOf(
        name = element.text,
        precededBy = significantBefore(element)?.text,
        calledImmediately = significantAfter(element)?.node?.elementType == MonkeyCTokens.LPAREN,
    )

    private fun significantBefore(element: PsiElement): PsiElement? =
        generateSequence(PsiTreeUtil.prevLeaf(element)) { PsiTreeUtil.prevLeaf(it) }
            .firstOrNull { it.node?.elementType !in SKIPPED }

    private fun significantAfter(element: PsiElement): PsiElement? =
        generateSequence(PsiTreeUtil.nextLeaf(element)) { PsiTreeUtil.nextLeaf(it) }
            .firstOrNull { it.node?.elementType !in SKIPPED }

    private companion object {
        val SKIPPED: Set<IElementType?> = setOf(
            TokenType.WHITE_SPACE,
            MonkeyCTokens.LINE_COMMENT,
            MonkeyCTokens.BLOCK_COMMENT,
            MonkeyCTokens.DOC_COMMENT,
        )
    }
}
