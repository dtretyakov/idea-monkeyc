package com.github.dtretyakov.monkeyc.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The escapes inside a literal, which the lexer hands over as one token.
 *
 * The set is Garmin's, spelled out in the `MonkeyC2020.g4` the SDK ships: `\u` and four hex
 * digits, or one of a short list of characters. Anything else the compiler rejects, and colouring
 * it as an error is the whole point of telling them apart.
 */
class StringEscapesTest {

    @Test
    fun `the ordinary escapes are found and accepted`() {
        val found = StringEscapes.of(""""a\nb\tc\\d\"e"""")

        assertEquals(4, found.size)
        assertTrue(found.all { it.valid })
        assertEquals(listOf(2, 5, 8, 11), found.map { it.start })
    }

    @Test
    fun `a unicode escape needs all four digits`() {
        // Ordinary strings rather than raw ones here: the thing under test is a backslash
        // followed by a `u`, and a raw string makes that easy to write and hard to read.
        assertTrue(StringEscapes.of("\"\\u00e9\"").single().valid)
        assertTrue(StringEscapes.of("\"\\uFFFF\"").single().valid)
        // `\u12` is the compiler's error, not a two-digit escape.
        assertEquals(false, StringEscapes.of("\"\\u12\"").single().valid)
        assertEquals(false, StringEscapes.of("\"\\u\"").single().valid)
        assertEquals(false, StringEscapes.of("\"\\u00g9\"").single().valid)
    }

    @Test
    fun `an escape the compiler rejects is reported as one`() {
        val found = StringEscapes.of(""""a\qb"""")

        assertEquals(1, found.size)
        assertEquals(false, found.single().valid)
        assertEquals(2 to 4, found.single().start to found.single().end)
    }

    @Test
    fun `a backslash that escapes a backslash does not escape what follows it`() {
        // `"\\n"` is a backslash and the letter n, not a newline.
        val found = StringEscapes.of(""""\\n"""")

        assertEquals(1, found.size)
        assertTrue(found.single().valid)
        assertEquals(3, found.single().end)
    }

    @Test
    fun `a literal with nothing to escape yields nothing`() {
        assertTrue(StringEscapes.of(""""plain"""").isEmpty())
        assertTrue(StringEscapes.of("").isEmpty())
        // A trailing backslash cannot escape anything; it must not run off the end either.
        assertTrue(StringEscapes.of("""\""").isEmpty())
    }

    @Test
    fun `character literals are the same shape`() {
        assertTrue(StringEscapes.of("""'\n'""").single().valid)
        assertEquals(false, StringEscapes.of("""'\z'""").single().valid)
    }
}
