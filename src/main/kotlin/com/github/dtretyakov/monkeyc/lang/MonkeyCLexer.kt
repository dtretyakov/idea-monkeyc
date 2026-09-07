package com.github.dtretyakov.monkeyc.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Lexer for Monkey C.
 *
 * Monkey C reads like Java with dynamic typing bolted on, so most of this is unsurprising. The two
 * things worth knowing are symbols — `:foo`, which is also how annotations such as `(:test)` are
 * spelled — and the numeric suffixes `l`, `d` and `f`, which are what tell a Long or a Double from
 * a Number.
 */
class MonkeyCLexer : ScanningLexer() {

    override fun scan(): IElementType {
        val c = peek()
        return when {
            isSpace(c) -> {
                skipWhile(::isSpace)
                TokenType.WHITE_SPACE
            }

            c == '/' && peek(1) == '/' -> {
                skipToLineEnd()
                MonkeyCTokens.LINE_COMMENT
            }

            c == '/' && peek(1) == '*' -> {
                // `/** … */` documents the declaration below it; `/**/` is just an empty comment.
                val isDoc = peek(2) == '*' && peek(3) != '/'
                skipBlockComment()
                if (isDoc) MonkeyCTokens.DOC_COMMENT else MonkeyCTokens.BLOCK_COMMENT
            }

            c == '"' -> {
                skipQuoted('"')
                MonkeyCTokens.STRING
            }

            c == '\'' -> {
                skipQuoted('\'')
                MonkeyCTokens.CHARACTER
            }

            c.isDigit() -> {
                skipNumber()
                MonkeyCTokens.NUMBER
            }

            c == ':' && isIdentifierStart(peek(1)) -> {
                position++
                skipWhile(::isIdentifierPart)
                MonkeyCTokens.SYMBOL
            }

            isIdentifierStart(c) -> {
                val start = position
                skipWhile(::isIdentifierPart)
                when (buffer.subSequence(start, position).toString()) {
                    in MonkeyCTokens.KEYWORDS -> MonkeyCTokens.KEYWORD
                    in MonkeyCTokens.BUILTIN_TYPES -> MonkeyCTokens.BUILTIN_TYPE
                    else -> MonkeyCTokens.IDENTIFIER
                }
            }

            else -> punctuation(c)
        }
    }

    private fun punctuation(c: Char): IElementType {
        SINGLE[c]?.let {
            position++
            return it
        }

        // Longest match first, so `>>=` never comes out as `>` followed by `>=`.
        for (operator in OPERATORS) {
            if (matches(operator)) {
                position += operator.length
                return MonkeyCTokens.OPERATOR
            }
        }

        position++
        return TokenType.BAD_CHARACTER
    }

    private fun matches(text: String): Boolean {
        if (position + text.length > limit) return false
        for (i in text.indices) {
            if (buffer[position + i] != text[i]) return false
        }
        return true
    }

    private fun skipNumber() {
        if (peek() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            position += 2
            skipWhile { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        } else {
            skipWhile { it.isDigit() }
            if (peek() == '.' && peek(1).isDigit()) {
                position++
                skipWhile { it.isDigit() }
            }
            if (peek() == 'e' || peek() == 'E') {
                val signLength = if (peek(1) == '+' || peek(1) == '-') 1 else 0
                if (peek(1 + signLength).isDigit()) {
                    position += 1 + signLength
                    skipWhile { it.isDigit() }
                }
            }
        }
        // `1l`, `1.5d`, `1.5f` — the literal's type.
        if (peek() in "lLdDfF") position++
    }

    private companion object {
        val SINGLE = mapOf(
            '(' to MonkeyCTokens.LPAREN,
            ')' to MonkeyCTokens.RPAREN,
            '{' to MonkeyCTokens.LBRACE,
            '}' to MonkeyCTokens.RBRACE,
            '[' to MonkeyCTokens.LBRACKET,
            ']' to MonkeyCTokens.RBRACKET,
            ';' to MonkeyCTokens.SEMICOLON,
            ',' to MonkeyCTokens.COMMA,
            '.' to MonkeyCTokens.DOT,
        )

        val OPERATORS = listOf(
            "<<=", ">>=",
            "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "<<", ">>", "++", "--",
            "+", "-", "*", "/", "%", "=", "<", ">", "!", "&", "|", "^", "~", "?", ":",
        )
    }
}
