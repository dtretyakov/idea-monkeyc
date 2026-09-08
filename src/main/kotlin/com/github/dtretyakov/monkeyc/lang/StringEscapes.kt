package com.github.dtretyakov.monkeyc.lang

/**
 * The escape sequences inside a string or a character literal.
 *
 * The lexer hands the literal over as one token, which is the right shape for everything else and
 * the wrong shape for this: a `\n` reads as part of the text rather than as the newline it is, and
 * a `\q` that the compiler will reject looks exactly like one that it will not. Both are worth
 * saying in the editor, and both are decided by the two characters themselves.
 *
 * Garmin's own grammar spells the set out — `MonkeyC2020.g4` allows `\u` and four hex digits, and
 * the single characters below. Anything else is an error the compiler reports; here it is only
 * coloured as one.
 */
object StringEscapes {

    /** One escape found in a literal: where it is, and whether the compiler will accept it. */
    data class Escape(val start: Int, val end: Int, val valid: Boolean)

    private const val SINGLE = "nrtbf\"'\\"
    private const val UNICODE_DIGITS = 4

    fun of(text: CharSequence): List<Escape> {
        val escapes = mutableListOf<Escape>()
        var at = 0

        while (at < text.length - 1) {
            if (text[at] != '\\') {
                at++
                continue
            }

            val next = text[at + 1]
            val escape = when {
                next == 'u' -> unicode(text, at)
                next in SINGLE -> Escape(at, at + 2, valid = true)
                else -> Escape(at, at + 2, valid = false)
            }
            escapes.add(escape)
            at = escape.end
        }

        return escapes
    }

    /** `\uXXXX`, and only with all four digits: `\u12` is the compiler's error, not a shorter escape. */
    private fun unicode(text: CharSequence, at: Int): Escape {
        val end = at + 2 + UNICODE_DIGITS
        val digits = (at + 2 until minOf(end, text.length)).count { isHex(text[it]) }
        return Escape(at, minOf(end, text.length), valid = digits == UNICODE_DIGITS)
    }

    private fun isHex(c: Char): Boolean = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'
}
