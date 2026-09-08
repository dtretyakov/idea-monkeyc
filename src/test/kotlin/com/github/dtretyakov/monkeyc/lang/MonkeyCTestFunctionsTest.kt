package com.github.dtretyakov.monkeyc.lang

import com.github.dtretyakov.monkeyc.testing.IdeTestCase

/**
 * Which functions the gutter offers to run.
 *
 * There is no grammar to lean on here, only the token stream, so every case is a chance to mark a
 * plain function as a test or to miss a real one. Both are visible mistakes: a green arrow that
 * runs nothing, or no arrow where the whole point of the annotation was to get one.
 */
class MonkeyCTestFunctionsTest : IdeTestCase() {

    fun `test an annotated function is a test`() {
        assertEquals(listOf("passes"), testsIn(
            """
            (:test)
            function passes(logger as Logger) as Boolean {
                return true;
            }
            """.trimIndent(),
        ))
    }

    fun `test a plain function is not`() {
        assertEquals(emptyList<String>(), testsIn("function ordinary() as Void {}"))
    }

    fun `test an annotation that is not test does not count`() {
        assertEquals(emptyList<String>(), testsIn("(:debug)\nfunction onlyInDebug() as Void {}"))
    }

    fun `test test alongside another annotation still counts`() {
        assertEquals(listOf("both"), testsIn("(:test, :debug)\nfunction both(logger) as Boolean { return true; }"))
    }

    fun `test a doc comment between the annotation and the function does not hide it`() {
        assertEquals(listOf("documented"), testsIn(
            """
            (:test)
            //! What this one checks.
            function documented(logger) as Boolean { return true; }
            """.trimIndent(),
        ))
    }

    fun `test the annotation belongs to the next declaration only`() {
        assertEquals(listOf("first"), testsIn(
            """
            (:test)
            function first(logger) as Boolean { return true; }

            function second() as Void {}
            """.trimIndent(),
        ))
    }

    fun `test a class annotated for tests does not make its methods tests`() {
        // The annotation applies to the class; the arrow belongs on the tests inside it, not here.
        assertEquals(emptyList<String>(), testsIn("(:test)\nclass Suite {\n}"))
    }

    fun `test every test in a file is found`() {
        assertEquals(
            listOf("passes", "fails"),
            testsIn(
                """
                (:test)
                function passes(logger) as Boolean { return true; }

                (:test)
                function fails(logger) as Boolean { return false; }
                """.trimIndent(),
            ),
        )
    }

    private fun testsIn(source: String): List<String> {
        val file = myFixture.configureByText("Fixture.mc", source)
        return MonkeyCTestFunctions.testsIn(file).map { it.text }.toList()
    }
}
