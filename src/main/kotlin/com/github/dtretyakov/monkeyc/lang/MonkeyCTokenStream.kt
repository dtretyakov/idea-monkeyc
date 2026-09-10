package com.github.dtretyakov.monkeyc.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType

/**
 * The previous token that carries meaning: whitespace and comments are not it.
 *
 * `prevLeaf`'s own flag skips *empty* elements, not whitespace — a distinction that costs an hour
 * if you assume otherwise, because every lookback then stops at the newline.
 *
 * Shared, because reading declarations out of a flat token stream is what this plugin does instead
 * of having a grammar, and every such reading starts by stepping backwards over the things that
 * are allowed to be in the way.
 */
internal fun meaningfulBefore(element: PsiElement): PsiElement? {
    var previous = PsiTreeUtil.prevLeaf(element, true)
    while (previous is PsiWhiteSpace || previous?.elementType in MonkeyCTokens.COMMENTS) {
        previous = PsiTreeUtil.prevLeaf(previous ?: return null, true)
    }
    return previous
}
