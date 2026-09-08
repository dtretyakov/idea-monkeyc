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
        true

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean =
        other.configuration is MonkeyCRunConfiguration

    private fun testAt(context: ConfigurationContext): String? {
        val element = context.psiLocation ?: return null
        MonkeyCTestFunctions.nameOf(element)?.let { return it }
        // Run invoked from anywhere inside the declaration, not only from its name.
        return PsiTreeUtil.getDeepestFirst(element)
            .let { MonkeyCTestFunctions.nameOf(it) }
    }
}
