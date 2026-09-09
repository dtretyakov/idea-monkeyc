package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ConnectIqEnvironment
import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * The setup checklist: every prerequisite, its state, and a button for the ones that are missing.
 *
 * Rebuilt wholesale rather than updated in place. The list changes shape — the language server line
 * only exists once there is an SDK to hold it — and after the SDK Manager has run, almost every
 * line has a different answer; redrawing is both simpler and more obviously correct than tracking
 * which rows survived.
 */
class EnvironmentPanel(
    private val project: Project?,
    private val onGenerateKey: () -> Unit,
) : JPanel(VerticalLayout(JBUI.scale(2))) {

    init {
        refresh()
    }

    fun refresh() {
        removeAll()
        ConnectIqEnvironment.check(project).forEach { add(line(it)) }
        revalidate()
        repaint()
    }

    private fun line(item: ConnectIqEnvironment.Item): JPanel {
        val ready = item.status == ConnectIqEnvironment.Status.READY
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))

        row.add(JBLabel(if (ready) AllIcons.General.InspectionsOK else AllIcons.General.Warning))
        row.add(JBLabel("${item.name}:"))
        row.add(JBLabel(item.detail).apply { foreground = UIUtil.getContextHelpForeground() })

        fixFor(item)?.let { row.add(it) }
        return row
    }

    private fun fixFor(item: ConnectIqEnvironment.Item): ActionLink? = when (item.fix) {
        ConnectIqEnvironment.Fix.SDK_MANAGER -> ActionLink(OpenSdkManager.label()) {
            OpenSdkManager.invoke(project)
            // The manager runs alongside the IDE; what it changed is only visible after a re-read.
            ConnectIqSdkService.getInstance().refresh()
            refresh()
        }

        ConnectIqEnvironment.Fix.GENERATE_KEY -> ActionLink("Generate…") {
            onGenerateKey()
            refresh()
        }

        null -> null
    }
}
