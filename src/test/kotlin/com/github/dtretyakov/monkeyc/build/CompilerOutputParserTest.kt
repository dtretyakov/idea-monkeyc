package com.github.dtretyakov.monkeyc.build

import com.github.dtretyakov.monkeyc.build.CompilerMessage.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The samples here are real output from monkeyc 9.1.0, not invented. */
class CompilerOutputParserTest {

    @Test
    fun `an error with a line and a column`() {
        val message = CompilerOutputParser.parseLine(
            "ERROR: fenix7: /app/source/FixtureApp.mc:30,8: mismatched input 'dc' expecting ';'",
        )!!

        assertEquals(Severity.ERROR, message.severity)
        assertEquals("fenix7", message.device)
        assertEquals("/app/source/FixtureApp.mc", message.file)
        assertEquals(30, message.line)
        assertEquals(8, message.column)
        assertEquals("mismatched input 'dc' expecting ';'", message.text)
    }

    @Test
    fun `a warning with a line but no column`() {
        val message = CompilerOutputParser.parseLine(
            "WARNING: fenix7: /app/source/FixtureApp.mc:20: Parameter 1 is untyped.",
        )!!

        assertEquals(Severity.WARNING, message.severity)
        assertEquals(20, message.line)
        assertNull(message.column)
        assertEquals("Parameter 1 is untyped.", message.text)
    }

    @Test
    fun `a message with no device and no file still comes through`() {
        // This is the shape of a build that failed before it got to the source.
        val message = CompilerOutputParser.parseLine("ERROR: Unable to find the developer key.")!!

        assertNull(message.device)
        assertNull(message.file)
        assertNull(message.line)
        assertEquals("Unable to find the developer key.", message.text)
    }

    @Test
    fun `a path containing a dot is not mistaken for the end of the file name`() {
        val message = CompilerOutputParser.parseLine(
            "ERROR: fenix7: /Users/me/my.projects/app/source/App.mc:7,2: no",
        )!!

        assertEquals("/Users/me/my.projects/app/source/App.mc", message.file)
        assertEquals(7, message.line)
    }

    @Test
    fun `ordinary build output is not a diagnostic`() {
        assertNull(CompilerOutputParser.parseLine("BUILD SUCCESSFUL"))
        assertNull(CompilerOutputParser.parseLine(""))
        assertNull(CompilerOutputParser.parseLine("3 OUT OF 12 DEVICES BUILT"))
    }

    @Test
    fun `reads a whole build's output`() {
        val output = """
            WARNING: fenix7: /app/source/App.mc:32,8: Local variable 'unused' is not used.
            ERROR: fenix7: /app/source/App.mc:20: Function return is untyped.
            BUILD FAILED
        """.trimIndent()

        val messages = CompilerOutputParser.parse(output)

        assertEquals(2, messages.size)
        assertEquals(listOf(Severity.WARNING, Severity.ERROR), messages.map { it.severity })
    }

    @Test
    fun `an export reports how many devices it has built`() {
        val progress = CompilerOutputParser.progressOf("2 OUT OF 6 DEVICES BUILT")

        assertEquals(BuildProgress(2, 6), progress)
    }

    @Test
    fun `a line that merely mentions devices is not progress`() {
        // The compiler says plenty about devices; only this one shape is a count.
        assertNull(CompilerOutputParser.progressOf("Building 6 DEVICES"))
        assertNull(CompilerOutputParser.progressOf("WARNING: fenix7: 2 OUT OF 6 DEVICES BUILT"))
        assertNull(CompilerOutputParser.progressOf("BUILD SUCCESSFUL"))
    }
}
