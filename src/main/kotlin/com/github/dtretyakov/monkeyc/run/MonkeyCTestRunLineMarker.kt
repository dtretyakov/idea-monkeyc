package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.lang.MonkeyCTestFunctions
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * The green arrow next to a `(:test)` function.
 *
 * Running one test rather than the whole suite is the difference between a two-second loop and a
 * thirty-second one, and the gutter is where a developer looks for it.
 */
class MonkeyCTestRunLineMarkerContributor : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        val name = MonkeyCTestFunctions.nameOf(element) ?: return null
        return Info(
            AllIcons.RunConfigurations.TestState.Run,
            ExecutorAction.getActions(0),
        ) { "Run '$name'" }
    }
}

/**
 * Makes the gutter's arrow, and Run from inside a test, produce a configuration for that one test.
 *
 * It has to be a producer of its own rather than a branch in [MonkeyCRunConfigurationProducer]:
 * the platform picks between producers by asking each one, and the two answer for different
 * things — this one for a single test, that one for the app.
 */
class MonkeyCTestConfigurationProducer : LazyRunConfigurationProducer<MonkeyCRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(MonkeyCRunConfigurationType::class.java)
            .configurationFactories
            .first { it.name == MonkeyCRunKind.TESTS.display }

    override fun setupConfigurationFromContext(
        configuration: MonkeyCRunConfiguration,
        context: ConfigurationContext,
        source: Ref<PsiElement>,
    ): Boolean {
        val name = testAt(context) ?: return false
        configuration.options.kind = MonkeyCRunKind.TESTS
        configuration.options.testName = name
        configuration.name = name
        return true
    }

    override fun isConfigurationFromContext(
        configuration: MonkeyCRunConfiguration,
        context: ConfigurationContext,
    ): Boolean = configuration.options.kind.isTests && configuration.options.testName == testAt(context)

    /** A single test beats "run the whole app" whenever the caret is actually on one. */
    override fun isPreferredConfiguration(self: ConfigurationFromContext?, other: ConfigurationFromContext?): Boolean =
        runsTheWholeApp(other)

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean =
        runsTheWholeApp(other)

    /** Only the app configuration is displaced; another test configuration is left to stand. */
    private fun runsTheWholeApp(other: ConfigurationFromContext?): Boolean =
        (other?.configuration as? MonkeyCRunConfiguration)?.options?.kind == MonkeyCRunKind.APP

    /**
     * The test the caret is on, if it is on one.
     *
     * Deliberately narrow: the name itself, or the `function` keyword and annotation in front of
     * it. There is no grammar here, so "the test this line is inside" cannot be answered without
     * guessing, and a guess would offer to run the previous test from inside the next one.
     */
    private fun testAt(context: ConfigurationContext): String? {
        val element = context.psiLocation ?: return null
        MonkeyCTestFunctions.nameOf(element)?.let { return it }
        return generateSequence(element) { PsiTreeUtil.nextLeaf(it) }
            .take(DECLARATION_HEAD_TOKENS)
            .firstNotNullOfOrNull { MonkeyCTestFunctions.nameOf(it) }
    }

    private companion object {
        /** `(`, `:test`, `)`, newline, `function`, space, and the name: seven leaves at most. */
        const val DECLARATION_HEAD_TOKENS = 7
    }
}
