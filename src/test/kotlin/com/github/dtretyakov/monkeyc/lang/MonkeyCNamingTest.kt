package com.github.dtretyakov.monkeyc.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The rules that decide how an identifier is coloured.
 *
 * They are conventions, not facts the compiler guarantees, so each one is written down as a case:
 * a wrong colour reads worse than no colour, and these are what stop a rule quietly widening.
 */
class MonkeyCNamingTest {

    @Test
    fun `a name after a type keyword is a type, whatever it looks like`() {
        assertEquals(MonkeyCColors.CLASS_REFERENCE, colour("FixtureView", after = "class"))
        assertEquals(MonkeyCColors.CLASS_REFERENCE, colour("AppBase", after = "extends"))
        assertEquals(MonkeyCColors.CLASS_REFERENCE, colour("FixtureView", after = "new", called = true))
        assertEquals(MonkeyCColors.CLASS_REFERENCE, colour("Toybox", after = "import"))
        // `as Dc` is a type annotation, and its name is a type even in lower case.
        assertEquals(MonkeyCColors.CLASS_REFERENCE, colour("dictionary", after = "as"))
    }

    @Test
    fun `a name after function is being declared, not called`() {
        assertEquals(MonkeyCColors.FUNCTION_DECLARATION, colour("onUpdate", after = "function", called = true))
    }

    @Test
    fun `a name in capitals is a constant`() {
        assertEquals(MonkeyCColors.CONSTANT, colour("COLOR_WHITE"))
        assertEquals(MonkeyCColors.CONSTANT, colour("FONT_SMALL"))
        assertEquals(MonkeyCColors.CONSTANT, colour("TEXT_JUSTIFY_CENTER"))
        // A single capital is a name, not a shout — `X` and `Y` are ordinary locals.
        assertEquals(MonkeyCColors.CLASS_REFERENCE, colour("X"))
    }

    @Test
    fun `a name before a parenthesis is a call`() {
        assertEquals(MonkeyCColors.FUNCTION_CALL, colour("setColor", called = true))
        assertEquals(MonkeyCColors.FUNCTION_CALL, colour("toString", called = true))
    }

    @Test
    fun `an upper camel name is a class, a lower one is nothing in particular`() {
        assertEquals(MonkeyCColors.CLASS_REFERENCE, colour("Graphics"))
        assertNull(colour("dc"))
        assertNull(colour("counter"))
    }

    @Test
    fun `a shouting name is a constant even where a call would be expected`() {
        // Toybox has none, but a macro-looking call must not be coloured as an ordinary one.
        assertEquals(MonkeyCColors.CONSTANT, colour("ASSERT", called = true))
    }

    @Test
    fun `a qualified constructor is a type all the way through`() {
        // `new Timer.Timer()` - the second half is preceded by a dot and followed by a paren, so
        // on its own it looks exactly like a call. The chain says otherwise, and this is the one
        // shape where the API index disagreed with these rules: 148 times in the SDK's samples.
        assertEquals("new", introducer("new Timer.Timer()", "Timer.Timer()"))
        assertEquals(MonkeyCColors.CLASS_REFERENCE, colour("Timer", after = "new", called = true))
    }

    @Test
    fun `the chain carries the type through an import and an annotation`() {
        assertEquals("import", introducer("import Toybox.Graphics;", "Graphics;"))
        assertEquals("as", introducer("var x as Toybox.Lang.Number;", "Number;"))
    }

    @Test
    fun `a plain name is introduced by whatever is in front of it`() {
        assertEquals("=", introducer("var x = counter;", "counter;"))
        assertEquals("(", introducer("f(counter)", "counter)"))
        assertNull(introducer("counter", "counter"))
    }

    @Test
    fun `a chain hanging off a call is introduced by the paren, not by what came before it`() {
        // `getInfo().position` - the walk stops at `)`, because a chain starts at a name.
        assertEquals(")", introducer("var p = getInfo().position;", "position;"))
    }

    /** The introducer of the name that starts at [tail], found by lexing [line] for real. */
    private fun introducer(line: String, tail: String): String? {
        val at = line.length - tail.length
        val lexer = MonkeyCLexer()
        lexer.start(line)

        val before = mutableListOf<Pair<IElementType?, String>>()
        while (lexer.tokenType != null) {
            if (lexer.tokenStart >= at) break
            if (lexer.tokenType != TokenType.WHITE_SPACE && lexer.tokenType !in MonkeyCTokens.COMMENTS.types) {
                before.add(lexer.tokenType to line.substring(lexer.tokenStart, lexer.tokenEnd))
            }
            lexer.advance()
        }

        return MonkeyCNaming.chainIntroducer(before.asReversed().asSequence())
    }

    private fun colour(name: String, after: String? = null, called: Boolean = false) =
        MonkeyCNaming.colourOf(name, after, called)
}
