package com.github.dtretyakov.monkeyc.navigation

/**
 * The qualified name under the caret, read straight out of the text.
 *
 * There is no syntax tree to ask — the plugin keeps a flat PSI on purpose — and for this one job
 * the text is enough: a Monkey C reference is a run of identifiers joined by dots, and what the
 * user means by pressing F12 is the whole run up to and including the name they are on.
 *
 * Trailing segments are deliberately dropped. On the `Graphics` of `Graphics.COLOR_WHITE` the
 * answer is `Graphics`, not the constant: the caret is what says which part of the chain is being
 * asked about.
 */
object DottedChain {

    fun at(text: CharSequence, offset: Int): String? {
        if (offset < 0 || offset > text.length) return null

        var end = offset
        while (end < text.length && text[end].isNamePart()) end++

        var start = offset
        while (start > 0 && text[start - 1].isNamePart()) start--

        // The caret can sit just past a name, or on the dot after it; anywhere else means there is
        // nothing under it to go to.
        if (start == end) return null

        // Walk back over `Name.` pairs to pick up the qualifier the reference was written with.
        var chainStart = start
        while (chainStart > 0 && text[chainStart - 1] == '.') {
            var before = chainStart - 1
            while (before > 0 && text[before - 1].isNamePart()) before--
            if (before == chainStart - 1) break
            chainStart = before
        }

        return text.subSequence(chainStart, end).toString().takeIf { it.isNotEmpty() }
    }

    private fun Char.isNamePart(): Boolean = isLetterOrDigit() || this == '_'
}
