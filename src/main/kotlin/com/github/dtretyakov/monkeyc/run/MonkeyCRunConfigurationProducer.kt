package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import kotlin.io.path.name

/**
 * Offers to run the app from anywhere inside a Connect IQ project.
 *
 * Without this the first run means opening the Run/Debug dialog and making a configuration by hand,
 * for a project where there is only ever one obvious thing to run.
 */
class MonkeyCRunConfigurationProducer : LazyRunConfigurationProducer<MonkeyCRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(MonkeyCRunConfigurationType::class.java)
            .configurationFactories
            .first { it.name == MonkeyCRunKind.APP.display }

    override fun setupConfigurationFromContext(
        configuration: MonkeyCRunConfiguration,
        context: ConfigurationContext,
        source: Ref<PsiElement>,
    ): Boolean {
        val root = appRoot(context) ?: return false
        configuration.name = root.name
        return true
    }

    override fun isConfigurationFromContext(
        configuration: MonkeyCRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        // One project, one app: the app configuration is the one for any file in it.
        return appRoot(context) != null && configuration.options.kind == MonkeyCRunKind.APP
    }

    /**
     * The project this file belongs to, when it is an app.
     *
     * A barrel is skipped: it has no entry class and nothing to run, so offering to run it from
     * the editor would only produce a configuration that fails. Building one is a Build menu
     * action instead.
     */
    private fun appRoot(context: ConfigurationContext) =
        projectRoot(context)?.takeIf { root ->
            context.project?.let { MonkeyCProject.getInstance(it).manifest(root)?.isBarrel != true } == true
        }

    /**
     * The project root for whatever the context is — any file, or a directory.
     *
     * Not narrowed to the three file types this plugin has a lexer for, which is what it used to
     * be. The first thing a Connect IQ project shows is as likely to be a README as a `.mc`, and
     * with one of those in the editor the Run toolbar had nothing to offer and the button was
     * simply dead — in a project where there is exactly one obvious thing to run. Selecting the
     * project folder did not help either, because a directory is not one of those types.
     *
     * [MonkeyCProject.rootFor] answers for a directory as readily as for a file, so the question
     * this asks is the one the class doc always claimed: is this inside a Connect IQ project.
     */
    private fun projectRoot(context: ConfigurationContext) =
        context.location?.virtualFile
            ?.let { file -> context.project?.let { MonkeyCProject.getInstance(it).rootFor(file) } }
}
