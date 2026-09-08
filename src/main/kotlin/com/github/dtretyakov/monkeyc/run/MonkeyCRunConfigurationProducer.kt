package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.lang.MonkeyCFileType
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
            .first { (it as MonkeyCRunConfigurationType.Factory).name == MonkeyCRunKind.APP.display }

    override fun setupConfigurationFromContext(
        configuration: MonkeyCRunConfiguration,
        context: ConfigurationContext,
        source: Ref<PsiElement>,
    ): Boolean {
        val root = projectRoot(context) ?: return false
        configuration.name = root.name
        return true
    }

    override fun isConfigurationFromContext(
        configuration: MonkeyCRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        // One project, one app: the app configuration is the one for any file in it.
        return projectRoot(context) != null && configuration.options.kind == MonkeyCRunKind.APP
    }

    private fun projectRoot(context: ConfigurationContext) =
        context.location?.virtualFile
            ?.takeIf { it.fileType == MonkeyCFileType || it.name == "manifest.xml" || it.extension == "jungle" }
            ?.let { context.project?.let { project -> MonkeyCProject.getInstance(project).rootFor(it) } }
}
