package com.github.dtretyakov.monkeyc.lang

import com.intellij.psi.TokenType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The third lexer, and the one that had no test.
 *
 * `.mc` and `.jungle` were both covered; `.mss` was not, which meant the colouring of every
 * personality file in the SDK rested on nothing. The cases below are the ones an `.mss` actually
 * contains — the SDK's own `personality.mss` is little else.
 */
class MssLexerTest {

    @Test
    fun `a block of properties comes out as identifiers, values and punctuation`() {
        // The leading `.` of a block name is punctuation rather than part of the name, and so is
        // the one in a qualified value. The lexer does not tell the two apart, and does not need
        // to: colouring is all that reads this, and both are punctuation on screen.
        assertEquals(
            "OPERATOR:. IDENTIFIER:button LBRACE:{ IDENTIFIER:color OPERATOR:= IDENTIFIER:Graphics " +
                "OPERATOR:. IDENTIFIER:COLOR_WHITE SEMICOLON:; RBRACE:}",
            lex(".button { color = Graphics.COLOR_WHITE; }"),
        )
    }

    @Test
    fun `a hyphen inside a name belongs to the name, and one before a digit opens a number`() {
        // CSS-shaped names are the norm here, so `font-size` must not lex as three tokens.
        assertEquals("IDENTIFIER:font-size OPERATOR:= NUMBER:-2", lex("font-size = -2"))
    }

    @Test
    fun `a number keeps its unit-shaped tail`() {
        assertEquals("NUMBER:1.5 NUMBER:80%", lex("1.5 80%"))
    }

    @Test
    fun `both comment styles and a string are single tokens`() {
        assertEquals(
            "LINE_COMMENT://  a note BLOCK_COMMENT:/* and\n another */ STRING:\"text\"",
            lex("//  a note\n/* and\n another */ \"text\""),
        )
    }

    @Test
    fun `an unterminated string ends at the end of the file rather than running away`() {
        val text = """color = "never closed"""
        assertTrue(lex(text).endsWith("""STRING:"never closed"""), lex(text))
        assertConsumesEverything(text)
    }

    @Test
    fun `something the language has no place for is one bad character, not a lost lexer`() {
        assertEquals("IDENTIFIER:a BAD_CHARACTER:@ IDENTIFIER:b", lex("a @ b"))
    }

    @Test
    fun `the whole input is consumed`() {
        assertConsumesEverything(
            "// personality\n.label {\n  font = Graphics.FONT_SMALL;\n  color = 0xFFFFFF;\n}\n/* end */\n",
        )
    }

    private fun assertConsumesEverything(text: String) {
        val lexer = MssLexer()
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
        val lexer = MssLexer()
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
