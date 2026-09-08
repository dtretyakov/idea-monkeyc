package com.github.dtretyakov.monkeyc.run.test

import com.intellij.execution.testframework.sm.ServiceMessageBuilder

/**
 * Turns what the test runner prints into the events IntelliJ's test tree is built from.
 *
 * The Connect IQ test runner reports like this, and there is no machine-readable form of it:
 *
 * ```
 * ------------------------------------------------------------------------------
 * Executing test MyModule.MyTests.aPassingTest...
 * DEBUG (02:06): something the test logged
 * PASS
 * ```
 *
 * followed by a summary table. So the tree is built by reading that: `Executing test X...` opens a
 * test, a line that is exactly `PASS`, `FAIL` or `ERROR` closes it, and everything in between is
 * that test's output. Anything outside a test is passed through to the console untouched, which is
 * what keeps the summary readable.
 *
 * Dotted names become suites, so `MyModule.MyTests.aPassingTest` sits under `MyModule.MyTests`
 * rather than filling the tree with one long row each.
 */
class MonkeyCTestMessages {

    private val incomplete = StringBuilder()
    private var suite: String? = null
    private var test: String? = null
    private val captured = StringBuilder()

    /** Translates a chunk of output, which may end mid-line. */
    fun translate(chunk: String): String {
        val out = StringBuilder()
        incomplete.append(chunk)

        var newline = incomplete.indexOf("\n")
        while (newline >= 0) {
            out.append(line(incomplete.substring(0, newline)))
            incomplete.delete(0, newline + 1)
            newline = incomplete.indexOf("\n")
        }
        return out.toString()
    }

    /**
     * Closes whatever is still open, at the end of the run.
     *
     * A test still open here never reported a result, which happens when the app crashes partway
     * through. Left open, the tree would show it spinning forever.
     */
    fun flush(): String {
        val out = StringBuilder()
        if (incomplete.isNotEmpty()) {
            out.append(line(incomplete.toString()))
            incomplete.clear()
        }
        test?.let { out.append(close(it, Outcome.ERROR, "The test never reported a result.")) }
        out.append(closeSuite())
        return out.toString()
    }

    private fun line(raw: String): String {
        val text = raw.trimEnd('\r')
        val trimmed = text.trim()

        STARTING.matchEntire(trimmed)?.let { match ->
            val name = match.groupValues[1]
            val out = StringBuilder()
            test?.let { out.append(close(it, Outcome.ERROR, "The test never reported a result.")) }
            out.append(openSuite(name.substringBeforeLast('.', "")))
            test = name
            captured.setLength(0)
            out.append(message(ServiceMessageBuilder.testStarted(leaf(name)).addAttribute("locationHint", "$PROTOCOL://$name")))
            return out.toString()
        }

        val open = test
        if (open != null) {
            OUTCOMES[trimmed]?.let { outcome ->
                test = null
                return close(open, outcome, captured.toString().trim())
            }
            if (isSeparator(trimmed)) return ""
            captured.append(text).append('\n')
            return message(
                ServiceMessageBuilder.testStdOut(leaf(open)).addAttribute("out", "$text\n"),
            )
        }

        // Outside a test: the header, the summary table, whatever the app printed on its own.
        return "$text\n"
    }

    private fun close(name: String, outcome: Outcome, details: String): String {
        val out = StringBuilder()
        if (outcome != Outcome.PASS) {
            out.append(
                message(
                    ServiceMessageBuilder.testFailed(leaf(name))
                        .addAttribute("message", if (outcome == Outcome.ERROR) "Errored" else "Failed")
                        .addAttribute("details", details),
                ),
            )
        }
        out.append(message(ServiceMessageBuilder.testFinished(leaf(name))))
        return out.toString()
    }

    private fun openSuite(name: String): String {
        if (name == suite) return ""
        val out = StringBuilder(closeSuite())
        if (name.isNotEmpty()) {
            out.append(message(ServiceMessageBuilder.testSuiteStarted(name)))
            suite = name
        } else {
            suite = null
        }
        return out.toString()
    }

    private fun closeSuite(): String {
        val open = suite ?: return ""
        suite = null
        return message(ServiceMessageBuilder.testSuiteFinished(open))
    }

    /** What to show as the test's own name, the suite having taken the rest. */
    private fun leaf(name: String): String = name.substringAfterLast('.')

    private fun message(builder: ServiceMessageBuilder): String = "${builder}\n"

    private fun isSeparator(text: String): Boolean =
        text.length > 3 && (text.all { it == '-' } || text.all { it == '=' })

    private enum class Outcome { PASS, FAIL, ERROR }

    private companion object {
        const val PROTOCOL = "monkeyc"

        val STARTING = Regex("""Executing test (\S+)\.\.\.""")

        val OUTCOMES = mapOf(
            "PASS" to Outcome.PASS,
            "FAIL" to Outcome.FAIL,
            "ERROR" to Outcome.ERROR,
        )
    }
}
