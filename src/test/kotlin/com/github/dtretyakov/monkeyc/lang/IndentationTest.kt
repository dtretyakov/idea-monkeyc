package com.github.dtretyakov.monkeyc.lang

import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where Enter puts the caret.
 *
 * The lines here are lexed for real rather than hand-built out of token constants, because half
 * the point of counting brackets by token is that a brace inside a string or a comment is part of
 * that token and never reaches the count.
 */
class IndentationTest {

    @Test
    fun `a line that opens a block indents the next one`() {
        assertEquals(1, opened("function fails(logger as Logger) as Boolean {"))
        assertEquals(1, opened("if (x > 0) {"))
        assertEquals(1, opened("class Face extends WatchUi.WatchFace {"))
    }

    @Test
    fun `a line that opens and closes in equal measure moves nothing`() {
        assertEquals(0, opened("logger.debug(\"a test that fails\");"))
        assertEquals(0, opened("var a = [1, 2, 3];"))
        assertEquals(0, opened("return f(g(x));"))
    }

    @Test
    fun `a line that only closes does not pull the next one further out`() {
        // Its own indentation already says where the block went; subtracting again would climb
        // out twice for one brace.
        assertEquals(0, opened("}"))
        assertEquals(0, opened("});"))
    }

    @Test
    fun `a brace inside a string or a comment is not a brace`() {
        assertEquals(0, opened("logger.debug(\"{\");"))
        assertEquals(0, opened("var s = \"a { b [ c (\";"))
        assertEquals(0, opened("// opens nothing: {"))
        assertEquals(0, opened("/* nor this { */"))
        // And one real brace beside a decorative one still counts once.
        assertEquals(1, opened("if (s.equals(\"}\")) {"))
    }

    @Test
    fun `an unclosed call indents its arguments`() {
        assertEquals(1, opened("dc.drawText("))
    }

    @Test
    fun `what follows the caret decides whether the closing brace stays put`() {
        assertTrue(Indentation.startsWithCloser(lex("}")))
        assertTrue(Indentation.startsWithCloser(lex("   }")))
        assertTrue(Indentation.startsWithCloser(lex(")")))
        assertFalse(Indentation.startsWithCloser(lex("return true;")))
        assertFalse(Indentation.startsWithCloser(emptyList<String>().let { lex("") }))
    }

    @Test
    fun `the levels add up`() {
        // Inside a body: keep the previous line's indent, plus one for the brace it opened.
        assertEquals(2, Indentation.levels(previousIndent = 1, opened = 1, closerAhead = false))
        // Enter between `{` and `}`: the brace goes back to where the block began.
        assertEquals(1, Indentation.levels(previousIndent = 1, opened = 1, closerAhead = true))
        // Plain continuation.
        assertEquals(1, Indentation.levels(previousIndent = 1, opened = 0, closerAhead = false))
        // Never past the left margin.
        assertEquals(0, Indentation.levels(previousIndent = 0, opened = 0, closerAhead = true))
    }

    private fun opened(line: String): Int = Indentation.opened(lex(line))

    private fun lex(line: String): List<IElementType> {
        val lexer: Lexer = MonkeyCLexer()
        lexer.start(line)
        val tokens = mutableListOf<IElementType>()
        while (lexer.tokenType != null) {
            lexer.tokenType?.takeIf { it != TokenType.WHITE_SPACE }?.let { tokens.add(it) }
            lexer.advance()
        }
        return tokens
    }
}
