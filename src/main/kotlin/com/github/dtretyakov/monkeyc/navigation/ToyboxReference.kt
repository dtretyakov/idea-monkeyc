package com.github.dtretyakov.monkeyc.navigation

import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex.Declaration
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex.Kind

/**
 * Which Toybox declaration a piece of code is pointing at, worked out from the text alone.
 *
 * Two different jobs, kept apart because one is certain and the other is not.
 *
 * [byName] reads the name as written and looks it up — `WatchUi.Menu2`, `Graphics.COLOR_WHITE`.
 * That is not a guess: the API declares exactly one thing by that path.
 *
 * [byGuess] is for a call on an instance, where the name says nothing: `logger.debug` names a
 * variable and a method, and nothing about `logger` matches `Toybox.Test.Logger`. The right answer
 * comes from the language server, which infers the receiver's type — see [ServerSymbolLocation].
 * This is what is left when the server cannot be asked, and it is worth having for that: the SDK
 * is still building its index for the first half-minute after a project opens, and the API does
 * not change while it does.
 */
object ToyboxReference {

    /** Beyond this a chooser is a haystack; `initialize` is declared on most classes in the API. */
    private const val TOO_MANY = 20

    /** The name as written, resolved against the API. Nothing inferred. */
    fun byName(index: ApiMirIndex, text: CharSequence, offset: Int): List<Declaration> {
        val chain = DottedChain.at(text, offset) ?: return emptyList()
        return index.resolve(chain)
    }

    /**
     * The receiver's type read off the source, and failing that the member name on its own.
     *
     * Monkey C writes types in the text — `logger as Logger` — and idiomatic code annotates
     * parameters and fields, so the annotation is usually a few lines above the call. When there
     * is none, the member name alone is matched across the API; that answer can be several
     * declarations wide and the platform shows the choice.
     */
    fun byGuess(index: ApiMirIndex, text: CharSequence, offset: Int): List<Declaration> {
        val chain = DottedChain.at(text, offset) ?: return emptyList()

        val receiver = chain.substringBeforeLast('.', "")
        val member = chain.substringAfterLast('.')
        if (receiver.isEmpty()) return emptyList()

        declaredType(text, receiver.substringBefore('.'), offset)?.let { type ->
            val rest = receiver.substringAfter('.', "")
            val qualified = listOf(type, rest, member).filter { it.isNotEmpty() }.joinToString(".")
            index.resolve(qualified).takeIf { it.isNotEmpty() }?.let { return it }
        }

        return membersNamed(index, member)
    }

    /** Either answer, name first; for callers that only want the best available. */
    fun resolve(index: ApiMirIndex, text: CharSequence, offset: Int): List<Declaration> =
        byName(index, text, offset).ifEmpty { byGuess(index, text, offset) }

    /**
     * The type written for a name in this file, preferring the nearest one above the caret.
     *
     * Nearest because a name is reused — `dc` is a parameter of every `onUpdate` in a project —
     * and the declaration in scope is the one just above the use, not the first in the file.
     */
    fun declaredType(text: CharSequence, name: String, offset: Int): String? {
        if (name.isEmpty()) return null

        val annotation = Regex("""\b${Regex.escape(name)}\s+as\s+(\$?\.?[A-Za-z_][A-Za-z0-9_.]*)""")
        val matches = annotation.findAll(text).toList().ifEmpty { return null }

        val above = matches.lastOrNull { it.range.first < offset }
        return (above ?: matches.first()).groupValues[1].trim('$', '.')
    }

    /**
     * Every member of the API with this name.
     *
     * The last resort, for a receiver whose type is nowhere in the text — a local assigned from a
     * call, most often. Types are excluded: a bare name that matches a class was already tried as
     * a path above, and reaching here means it did not.
     */
    private fun membersNamed(index: ApiMirIndex, name: String): List<Declaration> {
        val found = index.all().filter { it.simpleName == name && it.kind in MEMBERS }
        return if (found.size > TOO_MANY) emptyList() else found
    }

    private val MEMBERS = setOf(Kind.FUNCTION, Kind.VARIABLE, Kind.CONSTANT)
}
