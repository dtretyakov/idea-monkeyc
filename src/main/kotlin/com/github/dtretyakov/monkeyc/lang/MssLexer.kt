package com.github.dtretyakov.monkeyc.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Lexer for Monkey style sheets.
 *
 * An `.mss` file reads like CSS without selectors: named blocks of `property = value;`. The SDK
 * ships one per device as `personality.mss`, and projects add their own.
 */
class MssLexer : ScanningLexer() {

    override fun scan(): IElementType {
        val c = peek()
        return when {
            isSpace(c) -> {
                skipWhile(::isSpace)
                TokenType.WHITE_SPACE
            }

            c == '/' && peek(1) == '/' -> {
                skipToLineEnd()
                MssTokens.LINE_COMMENT
            }

            c == '/' && peek(1) == '*' -> {
                skipBlockComment()
                MssTokens.BLOCK_COMMENT
            }

            c == '"' -> {
                skipQuoted('"')
                MssTokens.STRING
            }

            c.isDigit() || (c == '-' && peek(1).isDigit()) -> {
                position++
                skipWhile { it.isDigit() || it == '.' || it == '%' }
                MssTokens.NUMBER
            }

            isIdentifierStart(c) -> {
                skipWhile { isIdentifierPart(it) || it == '-' }
                MssTokens.IDENTIFIER
            }

            c == '{' -> {
                position++
                MssTokens.LBRACE
            }

            c == '}' -> {
                position++
                MssTokens.RBRACE
            }

            c == ';' -> {
                position++
                MssTokens.SEMICOLON
            }

            c == '=' || c == ':' || c == ',' || c == '.' || c == '#' -> {
                position++
                MssTokens.OPERATOR
            }

            else -> {
                position++
                TokenType.BAD_CHARACTER
            }
        }
    }
}
