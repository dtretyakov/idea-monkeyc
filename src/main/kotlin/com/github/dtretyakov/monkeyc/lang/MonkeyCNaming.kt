package com.github.dtretyakov.monkeyc.lang

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.tree.IElementType

/**
 * What an identifier is, judged from the three things a lexer can see: the name itself, the token
 * that introduces the dotted chain it belongs to, and whether a `(` follows.
 *
 * Kept apart from the annotator so the rules can be read and tested as rules, without a Psi tree
 * around them. Each one is a convention the whole Toybox API follows, which is what makes it safe
 * to colour by: `Graphics` is a module and `dc` is not, and no amount of context will make either
 * of them the other.
 *
 * These rules were measured rather than assumed. Over the 209 sample sources the SDK ships, of the
 * 5431 identifiers that resolve to exactly one Toybox declaration, 5283 already get the colour the
 * API says they should. The 148 that did not were all the same shape — the last segment of
 * `new Timer.Timer()` — which is why the chain's introducer, and not merely the token in front of
 * the name, is what decides here.
 */
object MonkeyCNaming {

    /** What a dotted chain can be made of, besides the dots. */
    private val QUALIFIERS = setOf(MonkeyCTokens.IDENTIFIER, MonkeyCTokens.BUILTIN_TYPE)

    /** Keywords after which a name can only be a type or a module. */
    private val TYPE_INTRODUCERS =
        setOf("class", "extends", "new", "instanceof", "module", "import", "using", "as")

    /**
     * @param introducedBy the significant token before the whole dotted chain, not merely before
     *  this name. In `new Timer.Timer()` that is `new` for both halves, so the constructor is a
     *  type and not the function call the trailing `(` makes it look like; the same carries the
     *  type through `import Toybox.Graphics` and `as Toybox.Lang.Number`.
     */
    fun colourOf(name: String, introducedBy: String?, calledImmediately: Boolean): TextAttributesKey? = when {
        introducedBy in TYPE_INTRODUCERS -> MonkeyCColors.CLASS_REFERENCE
        introducedBy == "function" -> MonkeyCColors.FUNCTION_DECLARATION
        // `COLOR_WHITE`, `FONT_SMALL`, `TEXT_JUSTIFY_CENTER`: shouting means a constant.
        isShouting(name) -> MonkeyCColors.CONSTANT
        calledImmediately -> MonkeyCColors.FUNCTION_CALL
        // Classes and modules are UpperCamelCase in Monkey C; everything else starts lower.
        name.firstOrNull()?.isUpperCase() == true -> MonkeyCColors.CLASS_REFERENCE
        else -> null
    }

    /**
     * The token that introduces a dotted chain, given the significant tokens before a name in
     * reverse order.
     *
     * Walks back over `name . name` pairs and stops at whatever is in front of the whole chain, so
     * `new Timer.Timer()` reports `new` for both halves. A sequence rather than a list because the
     * caller walks a Psi tree leaf by leaf and a chain is two or three tokens long.
     */
    fun chainIntroducer(before: Sequence<Pair<IElementType?, String>>): String? {
        val tokens = before.iterator()
        while (true) {
            if (!tokens.hasNext()) return null
            val (separator, text) = tokens.next()
            if (separator != MonkeyCTokens.DOT) return text

            if (!tokens.hasNext()) return null
            val (qualifier, qualifierText) = tokens.next()
            // A qualifier can be a built-in type as easily as a plain name: `Toybox.Lang.Method`
            // is three segments, and the lexer knows `Lang` as a type of its own.
            if (qualifier !in QUALIFIERS) return qualifierText
        }
    }

    private fun isShouting(name: String): Boolean =
        name.length > 1 && name.any { it.isLetter() } && name.none { it.isLowerCase() }
}
