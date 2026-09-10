package com.github.dtretyakov.monkeyc.lang

import com.github.dtretyakov.monkeyc.testing.IdeTestCase
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Which identifier in a file is a class being declared.
 *
 * A file is full of identifiers that are class names in some sense — the one after `extends`, the
 * one in front of a `.new`, the type in an annotation — and only one of them is the declaration.
 * Marking any of the others puts a gutter icon on a line that declares nothing.
 */
class MonkeyCClassesTest : IdeTestCase() {

    fun `test a class declaration is found by its name`() {
        assertEquals(listOf("App"), classesIn("class App {\n}"))
    }

    fun `test the superclass is not a declaration`() {
        assertEquals(listOf("App"), classesIn("class App extends Application.AppBase {\n}"))
    }

    fun `test a module is not a class`() {
        assertEquals(emptyList<String>(), classesIn("module Helpers {\n}"))
    }

    fun `test a comment between the keyword and the name does not hide it`() {
        assertEquals(listOf("App"), classesIn("class /* the entry */ App {\n}"))
    }

    fun `test instantiating a class is not declaring one`() {
        assertEquals(emptyList<String>(), classesIn("function make() { return new FixtureView(); }"))
    }

    fun `test every class in a file is found, in order`() {
        assertEquals(
            listOf("App", "View"),
            classesIn("class App extends Application.AppBase {\n}\n\nclass View extends WatchUi.View {\n}"),
        )
    }

    private fun classesIn(source: String): List<String> {
        val file = myFixture.configureByText("Fixture.mc", source)
        return generateSequence(PsiTreeUtil.getDeepestFirst(file as PsiElement)) { PsiTreeUtil.nextLeaf(it) }
            .mapNotNull { MonkeyCClasses.nameOf(it) }
            .toList()
    }
}
