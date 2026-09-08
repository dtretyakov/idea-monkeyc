package com.github.dtretyakov.monkeyc.lang

import com.intellij.psi.TokenType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The bracket group, which is the only nesting a jungle has.
 *
 * It used to be swallowed into the path text beside it, which cost both the colour and any hope of
 * matching the pair — and a per-device qualifier list on one line is exactly where that matters.
 */
class JungleLexerTest {

    @Test
    fun `brackets are their own tokens, not part of the path beside them`() {
        // The path fragments are split the way this lexer has always split them - it does not tell
        // the left of an assignment from the right, so a fragment that starts with a letter comes
        // out as an identifier. What changed is only that `[` and `]` are no longer glued on.
        assertEquals(
            "IDENTIFIER:base.barrelPath OPERATOR:= IDENTIFIER:a.barrel SEPARATOR:; " +
                "LBRACKET:[ IDENTIFIER:b TEXT:/round.jungle SEPARATOR:; " +
                "IDENTIFIER:b TEXT:/rect.jungle RBRACKET:]",
            lex("base.barrelPath = a.barrel;[b/round.jungle;b/rect.jungle]"),
        )
    }

    @Test
    fun `an unbracketed assignment is unchanged`() {
        assertEquals(
            "IDENTIFIER:base.sourcePath OPERATOR:= IDENTIFIER:source",
            lex("base.sourcePath = source"),
        )
    }

    @Test
    fun `the whole input is consumed`() {
        val text = "# a jungle\nbase.barrelPath = [x;y]\nbase.lang = \"en\"\nb = \$(base.sourcePath)\n"
        val lexer = JungleLexer()
        lexer.start(text)
        var offset = 0
        while (lexer.tokenType != null) {
            assertEquals(offset, lexer.tokenStart, "tokens must be contiguous")
            offset = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(text.length, offset, "the lexer must consume the whole input")
    }

    private fun lex(text: String): String {
        val lexer = JungleLexer()
        lexer.start(text)
        return buildString {
            while (lexer.tokenType != null) {
                if (lexer.tokenType != TokenType.WHITE_SPACE) {
                    if (isNotEmpty()) append(' ')
                    append(lexer.tokenType).append(':').append(text.substring(lexer.tokenStart, lexer.tokenEnd))
                }
                lexer.advance()
            }
        }
    }
}
