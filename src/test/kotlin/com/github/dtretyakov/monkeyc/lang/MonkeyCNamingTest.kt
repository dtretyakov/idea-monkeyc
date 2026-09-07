package com.github.dtretyakov.monkeyc.lang

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
        assertEquals(MonkeyCColors.FUNCTION_CALL, colour("toString", after = ".", called = true))
    }

    @Test
    fun `an upper camel name is a class, a lower one is nothing in particular`() {
        assertEquals(MonkeyCColors.CLASS_REFERENCE, colour("Graphics", after = "."))
        assertNull(colour("dc"))
        assertNull(colour("counter"))
    }

    @Test
    fun `a shouting name is a constant even where a call would be expected`() {
        // Toybox has none, but a macro-looking call must not be coloured as an ordinary one.
        assertEquals(MonkeyCColors.CONSTANT, colour("ASSERT", called = true))
    }

    private fun colour(name: String, after: String? = null, called: Boolean = false) =
        MonkeyCNaming.colourOf(name, after, called)
}
