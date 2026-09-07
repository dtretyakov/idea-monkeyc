package com.github.dtretyakov.monkeyc.lang

import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * What an identifier is, judged from the three things a lexer can see: the name itself, the token
 * before it, and whether a `(` follows.
 *
 * Kept apart from the annotator so the rules can be read and tested as rules, without a Psi tree
 * around them. Each one is a convention the whole Toybox API follows, which is what makes it safe
 * to colour by: `Graphics` is a module and `dc` is not, and no amount of context will make either
 * of them the other.
 */
object MonkeyCNaming {

    /** Keywords after which a name can only be a type or a module. */
    private val TYPE_INTRODUCERS =
        setOf("class", "extends", "new", "instanceof", "module", "import", "using", "as")

    fun colourOf(name: String, precededBy: String?, calledImmediately: Boolean): TextAttributesKey? = when {
        precededBy in TYPE_INTRODUCERS -> MonkeyCColors.CLASS_REFERENCE
        precededBy == "function" -> MonkeyCColors.FUNCTION_DECLARATION
        // `COLOR_WHITE`, `FONT_SMALL`, `TEXT_JUSTIFY_CENTER`: shouting means a constant.
        isShouting(name) -> MonkeyCColors.CONSTANT
        calledImmediately -> MonkeyCColors.FUNCTION_CALL
        // Classes and modules are UpperCamelCase in Monkey C; everything else starts lower.
        name.firstOrNull()?.isUpperCase() == true -> MonkeyCColors.CLASS_REFERENCE
        else -> null
    }

    private fun isShouting(name: String): Boolean =
        name.length > 1 && name.any { it.isLetter() } && name.none { it.isLowerCase() }
}
