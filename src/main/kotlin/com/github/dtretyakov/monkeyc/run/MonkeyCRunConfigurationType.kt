package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.ui.MonkeyCIcons
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import javax.swing.Icon

/**
 * Two ways to start a Connect IQ project: the app, and its unit tests.
 *
 * They are one configuration with a flag rather than two kinds of thing, because everything up to
 * the last argument is identical — same compiler, same simulator, same push. Keeping them apart in
 * the New Configuration list is a matter of discoverability, not of design.
 */
class MonkeyCRunConfigurationType : ConfigurationType {

    override fun getId(): String = "MonkeyCRunConfiguration"

    override fun getDisplayName(): String = "Connect IQ"

    override fun getConfigurationTypeDescription(): String =
        "Runs a Monkey C app, or its unit tests, in the Connect IQ simulator"

    override fun getIcon(): Icon = MonkeyCIcons.CONNECT_IQ

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(App(this), Tests(this))

    class App(type: ConfigurationType) : Base(type) {
        override fun getId(): String = "Connect IQ App"
        override fun getName(): String = "Connect IQ App"
    }

    class Tests(type: ConfigurationType) : Base(type) {
        override fun getId(): String = "Connect IQ Tests"
        override fun getName(): String = "Connect IQ Tests"
        override fun configure(configuration: MonkeyCRunConfiguration) {
            configuration.options.runTests = true
        }
    }

    abstract class Base(type: ConfigurationType) : ConfigurationFactory(type) {

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            MonkeyCRunConfiguration(project, this, name).also { configure(it) }

        override fun getOptionsClass(): Class<out BaseState> = MonkeyCRunOptions::class.java

        protected open fun configure(configuration: MonkeyCRunConfiguration) = Unit
    }
}
