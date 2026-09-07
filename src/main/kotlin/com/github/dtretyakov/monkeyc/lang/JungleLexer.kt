package com.github.dtretyakov.monkeyc.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Lexer for jungle files.
 *
 * A jungle is a list of assignments — `base.sourcePath = source` — where the left side is a dotted
 * qualifier and the right side is a list of paths that may interpolate other entries with
 * `$(name)`. Comments start with `#`. Paths are not quoted, so anything that is not punctuation,
 * a comment or a variable is plain text.
 */
class JungleLexer : ScanningLexer() {

    override fun scan(): IElementType {
        val c = peek()
        return when {
            isSpace(c) -> {
                skipWhile(::isSpace)
                TokenType.WHITE_SPACE
            }

            c == '#' -> {
                skipToLineEnd()
                JungleTokens.COMMENT
            }

            c == '"' -> {
                skipQuoted('"')
                JungleTokens.STRING
            }

            c == '$' && peek(1) == '(' -> {
                position += 2
                skipWhile { it != ')' && it != '\n' && it != '\r' }
                if (peek() == ')') position++
                JungleTokens.VARIABLE
            }

            c == '=' -> {
                position++
                JungleTokens.OPERATOR
            }

            c == ';' || c == ',' -> {
                position++
                JungleTokens.SEPARATOR
            }

            isIdentifierStart(c) -> {
                skipWhile { isIdentifierPart(it) || it == '.' || it == '-' }
                JungleTokens.IDENTIFIER
            }

            else -> {
                // A path fragment: everything up to whatever would start another token.
                skipWhile { !isSpace(it) && it !in "#=;,\"$" }
                JungleTokens.TEXT
            }
        }
    }
}
