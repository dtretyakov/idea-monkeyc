package com.github.dtretyakov.monkeyc.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * What F12 thinks you are pointing at.
 *
 * Where the caret sits inside a chain decides the answer, and getting that wrong is worse than
 * doing nothing: it takes the user to a symbol they did not ask about.
 */
class DottedChainTest {

    @Test
    fun `the caret picks how much of the chain is meant`() {
        val text = "dc.setColor(Graphics.COLOR_WHITE, 0);"

        assertEquals("Graphics", at(text, "Graphics"))
        assertEquals("Graphics.COLOR_WHITE", at(text, "COLOR_WHITE"))
    }

    @Test
    fun `a chain of three keeps everything up to the caret and nothing after`() {
        val text = "var x = Toybox.Graphics.Dc;"

        assertEquals("Toybox", at(text, "Toybox"))
        assertEquals("Toybox.Graphics", at(text, "Graphics"))
        assertEquals("Toybox.Graphics.Dc", at(text, "Dc"))
    }

    @Test
    fun `the caret at either end of a name still means that name`() {
        val text = "Menu2 m;"

        assertEquals("Menu2", DottedChain.at(text, 0))
        assertEquals("Menu2", DottedChain.at(text, 5))
    }

    @Test
    fun `punctuation and whitespace hold nothing`() {
        assertNull(DottedChain.at("a + b", 2))
        assertNull(DottedChain.at("   ", 1))
        assertNull(DottedChain.at("", 0))
    }

    @Test
    fun `a member call keeps the receiver, which is how the reference was written`() {
        val text = "WatchUi.pushView(view, delegate, WatchUi.SLIDE_UP);"

        assertEquals("WatchUi.pushView", at(text, "pushView"))
        assertEquals("WatchUi.SLIDE_UP", at(text, "SLIDE_UP"))
    }

    @Test
    fun `the absolute form the IR itself uses is read too`() {
        // api.mir writes references as `$.Toybox.Lang.Numeric`; the leading marker is not a name
        // part, so the chain starts after it and the index trims what is left.
        val text = "function f(x as \$.Toybox.Lang.Numeric) as Void {}"

        assertEquals("Toybox.Lang.Numeric", at(text, "Numeric"))
    }

    @Test
    fun `an offset past the end is not a crash`() {
        assertNull(DottedChain.at("abc", 99))
        assertNull(DottedChain.at("abc", -1))
    }

    private fun at(text: String, word: String): String? =
        DottedChain.at(text, text.indexOf(word) + word.length)
}
