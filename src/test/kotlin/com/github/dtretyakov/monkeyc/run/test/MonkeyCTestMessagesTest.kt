package com.github.dtretyakov.monkeyc.run.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The translation from what the Connect IQ test runner prints to what the test tree is built from.
 *
 * The input here is real: it was captured from `monkeydo -t` on a fixture with one passing and one
 * failing test, which is why the odd details — the rules of separators, the summary table that
 * must not be mistaken for results — are worth keeping honest.
 */
class MonkeyCTestMessagesTest {

    private val realRun = """
        ------------------------------------------------------------------------------
        Executing test fixturePasses...
        DEBUG (02:06): the test that passes
        PASS
        ------------------------------------------------------------------------------
        Executing test fixtureFails...
        DEBUG (02:06): the test that fails
        FAIL

        ==============================================================================
        RESULTS
        Test:                               Status:
        fixturePasses                       PASS
        fixtureFails                        FAIL
        Ran 2 tests

        FAILED (passed=1, failed=1, errors=0)

    """.trimIndent().trimStart()

    @Test
    fun `a whole run becomes one started and one finished event per test`() {
        val output = translate(realRun)

        assertEquals(1, output.count { it.startsWith("##teamcity[testStarted name='fixturePasses'") }, output.joinToString("\n"))
        assertEquals(1, output.count { it.startsWith("##teamcity[testStarted name='fixtureFails'") }, output.joinToString("\n"))
        assertEquals(2, output.count { it.startsWith("##teamcity[testFinished") }, output.joinToString("\n"))
    }

    @Test
    fun `only the failing test is reported as failed`() {
        val failures = translate(realRun).filter { it.startsWith("##teamcity[testFailed") }

        assertEquals(1, failures.size, failures.toString())
        assertTrue(failures.single().contains("name='fixtureFails'"), failures.single())
    }

    @Test
    fun `the summary table is not mistaken for results`() {
        // "fixturePasses    PASS" is a row of the table, not an outcome: three tests would appear
        // if a line merely containing PASS were treated as one.
        val started = translate(realRun).filter { it.startsWith("##teamcity[testStarted") }

        assertEquals(2, started.size, started.toString())
    }

    @Test
    fun `the summary itself still reaches the console`() {
        val output = translate(realRun)

        assertTrue(output.any { it == "Ran 2 tests" }, output.joinToString("\n"))
        assertTrue(output.any { it.startsWith("FAILED (passed=1") }, output.joinToString("\n"))
    }

    @Test
    fun `what a test logs is attached to that test`() {
        val out = translate(realRun).filter { it.startsWith("##teamcity[testStdOut") }

        assertEquals(2, out.size, out.toString())
        assertTrue(out.first().contains("the test that passes"), out.first())
    }

    @Test
    fun `a dotted name becomes a suite`() {
        val output = translate("Executing test MyModule.MyTests.aTest...\nPASS\n")

        assertTrue(
            output.any { it.startsWith("##teamcity[testSuiteStarted name='MyModule.MyTests'") },
            output.joinToString("\n"),
        )
        assertTrue(output.any { it.contains("testStarted name='aTest'") }, output.joinToString("\n"))
    }

    @Test
    fun `a test that never reports is closed as an error`() {
        val messages = MonkeyCTestMessages()
        val output = (messages.translate("Executing test crashes...\nhalfway through\n") + messages.flush())
            .lines()

        assertTrue(output.any { it.startsWith("##teamcity[testFailed name='crashes'") }, output.joinToString("\n"))
        assertTrue(output.any { it.startsWith("##teamcity[testFinished name='crashes'") }, output.joinToString("\n"))
    }

    @Test
    fun `output split mid-line is still one line`() {
        val messages = MonkeyCTestMessages()
        val first = messages.translate("Executing test fixt")
        val second = messages.translate("urePasses...\nPASS\n")

        assertFalse(first.contains("testStarted"), "half a line is not an event yet")
        assertTrue(second.contains("testStarted name='fixturePasses'"), second)
    }

    private fun translate(text: String): List<String> {
        val messages = MonkeyCTestMessages()
        return (messages.translate(text) + messages.flush()).lines().filter { it.isNotBlank() }
    }
}
