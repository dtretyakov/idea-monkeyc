package com.github.dtretyakov.monkeyc.lang

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType

/**
 * Finds the functions the test runner can run: the ones annotated `(:test)`.
 *
 * There is no grammar to ask, only the flat token stream the lexer leaves behind, so this walks
 * backwards from a name over the shape a test declaration has:
 *
 * ```
 * (:test)
 * function fixturePasses(logger as Logger) as Boolean {
 * ```
 *
 * Which is less fragile than it sounds. The annotation has to be immediately before the
 * declaration for the compiler to honour it, so anything this misses would not have been a test.
 */
object MonkeyCTestFunctions {

    private const val TEST_SYMBOL = ":test"

    /** The test's name, when [element] is the identifier naming a `(:test)` function. */
    fun nameOf(element: PsiElement?): String? {
        if (element == null || element.elementType != MonkeyCTokens.IDENTIFIER) return null

        val function = meaningfulBefore(element) ?: return null
        if (function.elementType != MonkeyCTokens.KEYWORD || function.text != "function") return null
        if (!hasTestAnnotation(function)) return null

        return element.text
    }

    /** Every `(:test)` function in scope with this name; usually one, occasionally none. */
    fun find(project: Project, scope: GlobalSearchScope, name: String): List<PsiElement> {
        val manager = PsiManager.getInstance(project)
        return FileTypeIndex.getFiles(MonkeyCFileType, scope)
            .asSequence()
            .mapNotNull { manager.findFile(it) }
            .flatMap { file -> testsIn(file) }
            .filter { it.text == name }
            .toList()
    }

    /** Every `(:test)` function declared in one file, as the identifiers that name them. */
    fun testsIn(file: PsiElement): Sequence<PsiElement> =
        generateSequence(PsiTreeUtil.getDeepestFirst(file)) { PsiTreeUtil.nextLeaf(it) }
            .filter { nameOf(it) != null }

    /**
     * Whether the declaration at [function] is preceded by an annotation list holding `:test`.
     *
     * A list, not a single symbol: `(:test, :debug)` is legal and common, and a test that also
     * carries another annotation is still a test.
     */
    private fun hasTestAnnotation(function: PsiElement): Boolean {
        var current = meaningfulBefore(function) ?: return false
        if (current.elementType != MonkeyCTokens.RPAREN) return false

        var found = false
        while (true) {
            current = meaningfulBefore(current) ?: return false
            when (current.elementType) {
                MonkeyCTokens.SYMBOL -> if (current.text == TEST_SYMBOL) found = true
                MonkeyCTokens.COMMA -> Unit
                MonkeyCTokens.LPAREN -> return found
                // Anything else means this was not an annotation list at all.
                else -> return false
            }
        }
    }

    /**
     * The previous token that carries meaning: whitespace and comments are not it.
     *
     * `prevLeaf`'s own flag skips *empty* elements, not whitespace — a distinction that costs an
     * hour if you assume otherwise, because every lookback then stops at the newline.
     */
    private fun meaningfulBefore(element: PsiElement): PsiElement? {
        var previous = PsiTreeUtil.prevLeaf(element, true)
        while (previous is PsiWhiteSpace || previous?.elementType in MonkeyCTokens.COMMENTS) {
            previous = PsiTreeUtil.prevLeaf(previous ?: return null, true)
        }
        return previous
    }
}
