package com.github.dtretyakov.monkeyc.ui.wizard

import com.github.dtretyakov.monkeyc.ui.MonkeyCIcons
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import javax.swing.Icon

/**
 * "Connect IQ" in the New Project dialog.
 *
 * The templates are the SDK's own — the same files Garmin's VS Code extension starts a project
 * from — so a project made here is one their tools, and anyone else on the team, will recognise.
 */
class ConnectIqNewProjectWizard : GeneratorNewProjectWizard {

    override val id: String = "ConnectIQ"

    override val name: String = "Connect IQ"

    override val icon: Icon = MonkeyCIcons.CONNECT_IQ

    override val description: String =
        "A Monkey C project for Garmin devices, from one of the templates in the Connect IQ SDK."

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context)
            .nextStep(::NewProjectWizardBaseStep)
            .nextStep(::ConnectIqWizardStep)
}
