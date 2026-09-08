package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.ui.MonkeyCIcons
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import javax.swing.Icon

/**
 * The ways to start a Connect IQ project: the app, its unit tests, and a build that runs nothing.
 *
 * They are one configuration with a [MonkeyCRunKind] rather than several kinds of thing, because
 * everything up to the last argument is identical. Keeping them apart in the New Configuration
 * list is a matter of discoverability, not of design.
 */
class MonkeyCRunConfigurationType : ConfigurationType {

    override fun getId(): String = "MonkeyCRunConfiguration"

    override fun getDisplayName(): String = "Connect IQ"

    override fun getConfigurationTypeDescription(): String =
        "Builds a Monkey C project, and runs it or its unit tests in the Connect IQ simulator. " +
            "An app configuration can also run a complication pair — a second project alongside " +
            "this one — or start the app in native pairing mode."

    override fun getIcon(): Icon = MonkeyCIcons.CONNECT_IQ

    override fun getConfigurationFactories(): Array<ConfigurationFactory> =
        MonkeyCRunKind.entries.map { Factory(this, it) }.toTypedArray()

    class Factory(type: ConfigurationType, private val kind: MonkeyCRunKind) : ConfigurationFactory(type) {

        override fun getId(): String = kind.display

        override fun getName(): String = kind.display

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            MonkeyCRunConfiguration(project, this, name).also { it.options.kind = kind }

        override fun getOptionsClass(): Class<out BaseState> = MonkeyCRunOptions::class.java
    }
}
