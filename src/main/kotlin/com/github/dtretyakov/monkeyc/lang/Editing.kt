package com.github.dtretyakov.monkeyc.lang

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class MonkeyCCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

/** Jungle files take shell-style comments and have no block form. */
class JungleCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "#"
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class MssCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

class MonkeyCBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, next: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(
            // Only braces are structural: a brace closing off screen is the one worth naming in
            // the gutter, and parentheses and brackets never are.
            BracePair(MonkeyCTokens.LBRACE, MonkeyCTokens.RBRACE, true),
            BracePair(MonkeyCTokens.LPAREN, MonkeyCTokens.RPAREN, false),
            BracePair(MonkeyCTokens.LBRACKET, MonkeyCTokens.RBRACKET, false),
        )
    }
}

class MssBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, next: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(BracePair(MssTokens.LBRACE, MssTokens.RBRACE, true))
    }
}

/**
 * Bracket matching for jungle.
 *
 * The bracket group is the only nesting the format has, and it is exactly where a jungle gets hard
 * to read: a per-device qualifier list several paths long, on one line.
 */
class JungleBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, next: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val PAIRS = arrayOf(BracePair(JungleTokens.LBRACKET, JungleTokens.RBRACKET, false))
    }
}

/**
 * Closing the quote for you, in all three languages.
 *
 * `SimpleTokenSetQuoteHandler` needs only to be told which tokens are literals; it recognises both
 * `"` and `'` itself, which is what Monkey C wants — a character literal is single-quoted.
 */
class MonkeyCQuoteHandler : SimpleTokenSetQuoteHandler(MonkeyCTokens.STRINGS)

class JungleQuoteHandler : SimpleTokenSetQuoteHandler(JungleTokens.STRINGS)

class MssQuoteHandler : SimpleTokenSetQuoteHandler(MssTokens.STRINGS)
