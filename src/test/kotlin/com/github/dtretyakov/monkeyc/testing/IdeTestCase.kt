package com.github.dtretyakov.monkeyc.testing

import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable

/**
 * A platform test that does not fail on the IDE's own startup noise.
 *
 * Opening a project in a test runs every post-startup activity in the distribution, and one of the
 * Ultimate ones cannot be constructed outside a real IDE:
 *
 * ```
 * Cannot create extension (class=B.B.B.B.s) [Plugin: com.intellij.modules.ultimate]
 * ```
 *
 * A platform test turns any logged error into a failure, so that one error fails every test here
 * for a reason that has nothing to do with this plugin. Errors from anything else — this plugin
 * included — still fail the test, which is the point of the mechanism.
 */
abstract class IdeTestCase : BasePlatformTestCase() {

    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        LoggedErrorProcessor.executeWith<Throwable>(
            object : LoggedErrorProcessor() {
                override fun processError(
                    category: String,
                    message: String,
                    details: Array<out String>,
                    t: Throwable?,
                ): Set<Action> = if (isIdeStartupNoise(message)) Action.NONE else Action.ALL
            },
        ) {
            super.runBare(testRunnable)
        }
    }

    private fun isIdeStartupNoise(message: String): Boolean =
        message.contains("[Plugin: com.intellij.") && !message.contains("dtretyakov")
}
