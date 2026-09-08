package com.github.dtretyakov.monkeyc.navigation

import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex.Declaration
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex.Kind

/**
 * Which Toybox declaration a piece of code is pointing at.
 *
 * [ApiMirIndex.resolve] answers for a name written as a path — `WatchUi.Menu2`, `Graphics.Dc`.
 * That covers types and module members and misses the most common thing in the file: a call on an
 * instance. `logger.debug(...)` names a variable and a method, and no amount of matching against
 * qualified names will turn `logger` into `Toybox.Test.Logger`.
 *
 * Working out the type properly means type inference, which is the compiler's job and the reason
 * the language server exists — but that server declines this question, since its definition
 * handler never looks in the API. So the receiver's type is read off the source instead. Monkey C
 * annotates types in the text, `logger as Logger`, and idiomatic code annotates parameters and
 * fields, so the annotation is usually a few lines up from the call.
 *
 * When even that fails, the member name alone is matched against the API. That answer can be
 * several declarations wide and the platform shows the choice; past a point a list stops being an
 * answer, so a very common name gives nothing rather than everything.
 */
object ToyboxReference {

    /** Beyond this a chooser is a haystack; `initialize` is declared on most classes in the API. */
    private const val TOO_MANY = 20

    fun resolve(index: ApiMirIndex, text: CharSequence, offset: Int): List<Declaration> {
        val chain = DottedChain.at(text, offset) ?: return emptyList()

        index.resolve(chain).takeIf { it.isNotEmpty() }?.let { return it }

        val receiver = chain.substringBeforeLast('.', "")
        val member = chain.substringAfterLast('.')
        if (receiver.isEmpty()) return emptyList()

        // `logger` is a name in this file; what it holds is written beside it.
        declaredType(text, receiver.substringBefore('.'), offset)?.let { type ->
            val rest = receiver.substringAfter('.', "")
            val qualified = listOf(type, rest, member).filter { it.isNotEmpty() }.joinToString(".")
            index.resolve(qualified).takeIf { it.isNotEmpty() }?.let { return it }
        }

        return membersNamed(index, member)
    }

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
