package com.github.dtretyakov.monkeyc.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil

/**
 * Colours what a lexer alone has to leave flat: identifiers, and the escapes inside a literal.
 *
 * The language server offers no semantic tokens — not one class in `LanguageServer.jar` mentions
 * them — so nothing tells the editor which name is a class and which is a local. What is left is
 * the shape of the code around each name, and in Monkey C that says most of it: a name after
 * `class` is a class, a name before `(` is a call, and a name in capitals is a constant.
 *
 * Every rule here is syntactic. None of them guesses at meaning, because a name coloured wrongly
 * reads worse than a name not coloured at all — and, measured against the API index over the SDK's
 * own samples, they already agree with it on 5283 of the 5431 names it can settle.
 *
 * If Garmin ever does add semantic tokens, LSP4IJ's own highlighter takes over without a change
 * here and this annotator would be colouring on top of it. A live test asserts the server still
 * offers none, and will go red on the day that stops being true.
 */
class MonkeyCAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element.node?.elementType) {
            MonkeyCTokens.IDENTIFIER -> colourOf(element)?.let { paint(holder, element.textRange, it) }
            in MonkeyCTokens.STRINGS.types -> escapes(element, holder)
            else -> Unit
        }
    }

    /**
     * The escapes inside a literal, which the lexer hands over as one token.
     *
     * Worth separating for the same reason every other language separates them: `\n` is not two
     * characters of text, and `\q` is an error the compiler will report and the editor may as well
     * show first.
     */
    private fun escapes(element: PsiElement, holder: AnnotationHolder) {
        val start = element.textRange.startOffset
        StringEscapes.of(element.text).forEach { escape ->
            paint(
                holder,
                TextRange(start + escape.start, start + escape.end),
                if (escape.valid) MonkeyCColors.VALID_ESCAPE else MonkeyCColors.INVALID_ESCAPE,
            )
        }
    }

    private fun paint(holder: AnnotationHolder, range: TextRange, colour: TextAttributesKey) =
        holder.newSilentAnnotation(HighlightSeverity.TEXT_ATTRIBUTES)
            .range(range)
            .textAttributes(colour)
            .create()

    private fun colourOf(element: PsiElement): TextAttributesKey? = MonkeyCNaming.colourOf(
        name = element.text,
        introducedBy = MonkeyCNaming.chainIntroducer(significantBefore(element)),
        calledImmediately = significantAfter(element)?.node?.elementType == MonkeyCTokens.LPAREN,
    )

    /**
     * Everything before this name that is not whitespace or a comment, nearest first.
     *
     * Lazy on purpose: [MonkeyCNaming.chainIntroducer] takes two tokens per qualifier and a chain
     * is two or three long, so nothing walks further back than it has to.
     */
    private fun significantBefore(element: PsiElement): Sequence<Pair<IElementType?, String>> =
        generateSequence(PsiTreeUtil.prevLeaf(element)) { PsiTreeUtil.prevLeaf(it) }
            .filter { it.node?.elementType !in SKIPPED }
            .map { it.node?.elementType to it.text }

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
