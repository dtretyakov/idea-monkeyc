package com.github.dtretyakov.monkeyc.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Collecting both streams of a process without deadlocking on either.
 *
 * This exists because the obvious way to write it is wrong and quietly so. `mtp-rs` writes its JSON
 * result to stdout only when the transfer finishes, and streams progress to stderr throughout — its
 * own docs say "Transfer progress still goes to stderr" even in `--json` mode. Reading stdout to
 * the end first therefore blocks while the child fills the stderr pipe buffer, the child blocks
 * writing, and neither moves again. The timeout cannot rescue it, because `waitFor` is never
 * reached.
 *
 * The child is [ChildProcess] rather than `mtp-rs`, so these run on a machine with neither the tool
 * nor a watch — and rather than `sh`, which Windows has not got, so they now run there too. Each
 * carries a timeout: the failure being guarded against is a hang, and a hanging test that merely
 * runs forever reports nothing.
 */
class MtpToolRunTest {

    @Test
    @Timeout(60)
    fun `a process that floods stderr while stdout stays silent does not deadlock`() {
        // Far more than any pipe buffer, written to stderr before a single byte reaches stdout —
        // which is the shape of a real transfer with progress.
        val outcome = MtpTool.run(ChildProcess.command("flood"), 45, TimeUnit.SECONDS)

        assertEquals(0, outcome.exitCode)
        assertTrue(outcome.output.contains("\"ok\""), "stdout was lost: ${outcome.output.take(80)}")
        assertTrue(outcome.errors.length > 100_000, "stderr was not drained: ${outcome.errors.length} chars")
    }

    @Test
    @Timeout(60)
    fun `both streams come back whole and apart`() {
        // Kept apart on purpose: merging them is the other tempting fix, and it would leave the
        // JSON on stdout unparseable, which is the whole reason the tool separates them.
        val outcome = MtpTool.run(ChildProcess.command("both"), 30, TimeUnit.SECONDS)

        assertEquals("out", outcome.output.trim())
        assertEquals("err", outcome.errors.trim())
    }

    @Test
    @Timeout(60)
    fun `a failing process keeps its exit code and its complaint`() {
        val outcome = MtpTool.run(ChildProcess.command("fail"), 30, TimeUnit.SECONDS)

        // The child spells this code out, because it runs without MtpTool on its classpath. The
        // assertion is what keeps the two in step.
        assertEquals(MtpTool.NO_DEVICE, outcome.exitCode)
        assertTrue(MtpTool.describeFailure(outcome.exitCode, outcome.errors).contains("No Garmin device"))
    }

    @Test
    @Timeout(60)
    fun `a process that never finishes is killed and reported as such`() {
        val outcome = MtpTool.run(ChildProcess.command("hang"), 1, TimeUnit.SECONDS)

        assertEquals(MtpTool.TIMED_OUT, outcome.exitCode)
    }
}
