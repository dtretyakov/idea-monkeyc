package com.github.dtretyakov.monkeyc.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType

/**
 * Finds where a class is declared.
 *
 * One token of lookback is the whole rule: an identifier is a class name when the word before it
 * is `class`. There is no grammar to ask, and none is needed here — `class` cannot be anything but
 * a declaration, and what follows it cannot be anything but the name.
 *
 * Narrow on purpose. The name in `extends Application.AppBase` is a class too, and marking it
 * would put a gutter icon on a class declared somewhere in the SDK.
 */
object MonkeyCClasses {

    private const val CLASS = "class"

    /** The class's name, when [element] is the identifier that names a class declaration. */
    fun nameOf(element: PsiElement?): String? {
        if (element == null || element.elementType != MonkeyCTokens.IDENTIFIER) return null

        val keyword = meaningfulBefore(element) ?: return null
        if (keyword.elementType != MonkeyCTokens.KEYWORD || keyword.text != CLASS) return null

        return element.text
    }
}
