package com.github.dtretyakov.monkeyc.lang

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Default
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * The colours the three languages use.
 *
 * Every key inherits from a platform default, so Monkey C picks up whatever scheme the user has
 * chosen without the plugin shipping colours of its own.
 */
object MonkeyCColors {
    val LINE_COMMENT = key("MONKEYC_LINE_COMMENT", Default.LINE_COMMENT)
    val BLOCK_COMMENT = key("MONKEYC_BLOCK_COMMENT", Default.BLOCK_COMMENT)
    val DOC_COMMENT = key("MONKEYC_DOC_COMMENT", Default.DOC_COMMENT)
    val KEYWORD = key("MONKEYC_KEYWORD", Default.KEYWORD)
    val BUILTIN_TYPE = key("MONKEYC_BUILTIN_TYPE", Default.CLASS_NAME)
    val STRING = key("MONKEYC_STRING", Default.STRING)
    val NUMBER = key("MONKEYC_NUMBER", Default.NUMBER)
    val SYMBOL = key("MONKEYC_SYMBOL", Default.METADATA)
    val IDENTIFIER = key("MONKEYC_IDENTIFIER", Default.IDENTIFIER)
    val OPERATOR = key("MONKEYC_OPERATOR", Default.OPERATION_SIGN)
    val SEMICOLON = key("MONKEYC_SEMICOLON", Default.SEMICOLON)
    val COMMA = key("MONKEYC_COMMA", Default.COMMA)
    val DOT = key("MONKEYC_DOT", Default.DOT)
    val PARENTHESES = key("MONKEYC_PARENTHESES", Default.PARENTHESES)
    val BRACES = key("MONKEYC_BRACES", Default.BRACES)
    val BRACKETS = key("MONKEYC_BRACKETS", Default.BRACKETS)
    val BAD_CHARACTER = key("MONKEYC_BAD_CHARACTER", com.intellij.openapi.editor.HighlighterColors.BAD_CHARACTER)

    val JUNGLE_COMMENT = key("JUNGLE_COMMENT", Default.LINE_COMMENT)
    val JUNGLE_KEY = key("JUNGLE_KEY", Default.KEYWORD)
    val JUNGLE_VARIABLE = key("JUNGLE_VARIABLE", Default.INSTANCE_FIELD)
    val JUNGLE_STRING = key("JUNGLE_STRING", Default.STRING)
    val JUNGLE_OPERATOR = key("JUNGLE_OPERATOR", Default.OPERATION_SIGN)

    val MSS_COMMENT = key("MSS_COMMENT", Default.LINE_COMMENT)
    val MSS_PROPERTY = key("MSS_PROPERTY", Default.INSTANCE_FIELD)
    val MSS_NUMBER = key("MSS_NUMBER", Default.NUMBER)
    val MSS_STRING = key("MSS_STRING", Default.STRING)
    val MSS_BRACES = key("MSS_BRACES", Default.BRACES)

    private fun key(name: String, fallback: TextAttributesKey) = createTextAttributesKey(name, fallback)
}

class MonkeyCSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = MonkeyCLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(
        when (tokenType) {
            MonkeyCTokens.LINE_COMMENT -> MonkeyCColors.LINE_COMMENT
            MonkeyCTokens.BLOCK_COMMENT -> MonkeyCColors.BLOCK_COMMENT
            MonkeyCTokens.DOC_COMMENT -> MonkeyCColors.DOC_COMMENT
            MonkeyCTokens.KEYWORD -> MonkeyCColors.KEYWORD
            MonkeyCTokens.BUILTIN_TYPE -> MonkeyCColors.BUILTIN_TYPE
            MonkeyCTokens.STRING, MonkeyCTokens.CHARACTER -> MonkeyCColors.STRING
            MonkeyCTokens.NUMBER -> MonkeyCColors.NUMBER
            MonkeyCTokens.SYMBOL -> MonkeyCColors.SYMBOL
            MonkeyCTokens.IDENTIFIER -> MonkeyCColors.IDENTIFIER
            MonkeyCTokens.OPERATOR -> MonkeyCColors.OPERATOR
            MonkeyCTokens.SEMICOLON -> MonkeyCColors.SEMICOLON
            MonkeyCTokens.COMMA -> MonkeyCColors.COMMA
            MonkeyCTokens.DOT -> MonkeyCColors.DOT
            MonkeyCTokens.LPAREN, MonkeyCTokens.RPAREN -> MonkeyCColors.PARENTHESES
            MonkeyCTokens.LBRACE, MonkeyCTokens.RBRACE -> MonkeyCColors.BRACES
            MonkeyCTokens.LBRACKET, MonkeyCTokens.RBRACKET -> MonkeyCColors.BRACKETS
            TokenType.BAD_CHARACTER -> MonkeyCColors.BAD_CHARACTER
            else -> null
        },
    )
}

class JungleSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = JungleLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(
        when (tokenType) {
            JungleTokens.COMMENT -> MonkeyCColors.JUNGLE_COMMENT
            JungleTokens.IDENTIFIER -> MonkeyCColors.JUNGLE_KEY
            JungleTokens.VARIABLE -> MonkeyCColors.JUNGLE_VARIABLE
            JungleTokens.STRING -> MonkeyCColors.JUNGLE_STRING
            JungleTokens.OPERATOR, JungleTokens.SEPARATOR -> MonkeyCColors.JUNGLE_OPERATOR
            else -> null
        },
    )
}

class MssSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = MssLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = pack(
        when (tokenType) {
            MssTokens.LINE_COMMENT, MssTokens.BLOCK_COMMENT -> MonkeyCColors.MSS_COMMENT
            MssTokens.IDENTIFIER -> MonkeyCColors.MSS_PROPERTY
            MssTokens.NUMBER -> MonkeyCColors.MSS_NUMBER
            MssTokens.STRING -> MonkeyCColors.MSS_STRING
            MssTokens.LBRACE, MssTokens.RBRACE -> MonkeyCColors.MSS_BRACES
            else -> null
        },
    )
}

class MonkeyCSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        MonkeyCSyntaxHighlighter()
}

class JungleSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        JungleSyntaxHighlighter()
}

class MssSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        MssSyntaxHighlighter()
}
