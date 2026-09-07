package com.github.dtretyakov.monkeyc.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProjectGeneratorTest {

    @Test
    fun `a class name keeps only what the compiler accepts`() {
        assertEquals("Sun_riseFace", ProjectGenerator.classNameOf("Sun-riseFace"))
        assertEquals("My_App_", ProjectGenerator.classNameOf("My.App!"))
        assertEquals("Simple", ProjectGenerator.classNameOf("Simple"))
    }

    @Test
    fun `a name that starts with a digit gets a leading underscore`() {
        // `class 2Fast` does not parse; `class _2Fast` does.
        assertEquals("_2Fast", ProjectGenerator.classNameOf("2Fast"))
    }

    @Test
    fun `letters outside ASCII are names, not punctuation`() {
        assertEquals("Часы", ProjectGenerator.classNameOf("Часы"))
    }
}
