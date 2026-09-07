package com.github.dtretyakov.monkeyc.lang

import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonkeyCLexerTest {

    @Test
    fun `keywords, built-in types and plain names are told apart`() {
        assertEquals(
            "KEYWORD:class IDENTIFIER:Face KEYWORD:extends IDENTIFIER:WatchFace",
            lex("class Face extends WatchFace"),
        )
        assertEquals("BUILTIN_TYPE:Number BUILTIN_TYPE:String IDENTIFIER:Dc", lex("Number String Dc"))
    }

    @Test
    fun `a symbol is one token, and so is an annotation`() {
        assertEquals("SYMBOL::test", lex(":test"))
        assertEquals("LPAREN:( SYMBOL::background RPAREN:)", lex("(:background)"))
        // A colon that is not part of a symbol stays an operator, as in a ternary.
        assertEquals("IDENTIFIER:a OPERATOR:? IDENTIFIER:b OPERATOR:: IDENTIFIER:c", lex("a ? b : c"))
    }

    @Test
    fun `numbers keep their type suffix`() {
        assertEquals("NUMBER:1l", lex("1l"))
        assertEquals("NUMBER:0.5f", lex("0.5f"))
        assertEquals("NUMBER:0x1Fa", lex("0x1Fa"))
        assertEquals("NUMBER:1.5e-3d", lex("1.5e-3d"))
        // A dot that follows a number belongs to it; a dot that follows a name does not.
        assertEquals("IDENTIFIER:x DOT:. IDENTIFIER:y", lex("x.y"))
    }

    @Test
    fun `documentation comments are told from ordinary ones`() {
        assertEquals("DOC_COMMENT:/** doc */", lex("/** doc */"))
        assertEquals("BLOCK_COMMENT:/* plain */", lex("/* plain */"))
        assertEquals("BLOCK_COMMENT:/**/", lex("/**/"))
        assertEquals("LINE_COMMENT:// to the end", lex("// to the end"))
    }

    @Test
    fun `an unterminated string stops at the line break`() {
        // Otherwise one missing quote would colour the rest of the file as a string.
        assertEquals("STRING:\"oops IDENTIFIER:next", lex("\"oops\nnext"))
    }

    @Test
    fun `the longest operator wins`() {
        assertEquals("IDENTIFIER:a OPERATOR:>>= NUMBER:2", lex("a >>= 2"))
        assertEquals("IDENTIFIER:a OPERATOR:== IDENTIFIER:b", lex("a == b"))
    }

    @Test
    fun `every character of the input lands in exactly one token`() {
        val text = """
            import Toybox.Lang;
            class C { function f() { return "x" + 1.0f; } } // done
        """.trimIndent()

        val lexer: Lexer = MonkeyCLexer()
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
        val lexer = MonkeyCLexer()
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
