package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.intellij.execution.ui.TogglePopupAction
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * The target watch, in the run widget beside the run configuration.
 *
 * A Connect IQ executable is built for one device, so this is not a preference tucked away in a
 * dialog: it decides what the compiler produces, and switching it — a round face, then a square
 * one — is most of what testing a watch app is.
 *
 * The platform's own `ExecutionTargets` combo would have been the obvious home for this, and it is
 * not usable: something else already contributes `DefaultExecutionTarget` to every configuration
 * and it sorts first, so the active target never becomes one of ours — and that combo hides itself
 * precisely when the active target is the default one. So the choice is kept where the rest of the
 * plugin already reads it from, the project's settings, which also keeps the language server and
 * the build looking at the same device.
 *
 * Built the same way the run configuration chip next to it is built — a [TogglePopupAction] drawn
 * by an [ActionButtonWithText] with a drop-down arrow — rather than as a `ComboBoxAction`, which
 * brings a bordered button of its own and reads as a foreign object among flat neighbours.
 */
class SelectDeviceAction : TogglePopupAction(), CustomComponentAction, DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        super.update(event)
        val project = event.project
        val presentation = event.presentation

        // Deliberately cheap: this runs on every toolbar update, and reading the manifest and the
        // device catalogue here would put a file read on the IDE's pulse. The list is built only
        // when the popup is actually opened.
        val visible = project != null && MonkeyCProject.getInstance(project).primaryRoot() != null
        presentation.isEnabledAndVisible = visible
        if (!visible) return

        val device = MonkeyCSettings.getInstance(project!!).targetDevice
        presentation.setText(device.ifEmpty { "No device" }, false)
        presentation.icon = MonkeyCIcons.CONNECT_IQ
        presentation.description = "The watch that Build, Run and Debug target"
    }

    override fun getActionGroup(event: AnActionEvent): ActionGroup? {
        val project = event.project ?: return null
        val model = MonkeyCProject.getInstance(project)
        val root = model.primaryRoot() ?: return null

        val devices = model.buildableDevices(root)
        if (devices.isEmpty()) {
            return DefaultActionGroup(
                Unavailable("None of the devices this project declares is downloaded"),
            )
        }
        return DefaultActionGroup(
            devices.map { Select(project, it.id, "${it.displayName}  (${it.id})") },
        )
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        DeviceButton(this, presentation, place)

    private class Select(
        private val project: Project,
        private val device: String,
        text: String,
    ) : AnAction(text) {

        override fun actionPerformed(event: AnActionEvent) {
            val settings = MonkeyCSettings.getInstance(project)
            if (settings.targetDevice == device) return
            settings.targetDevice = device
            // The language server is told the device at initialize and caches it, so it has to be
            // told again; everything else reads the setting at the moment it runs.
            project.messageBus.syncPublisher(MonkeyCSettings.TOPIC).settingsChanged(project)
        }
    }

    private class Unavailable(text: String) : AnAction(text) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = false
        }

        override fun actionPerformed(event: AnActionEvent) = Unit
    }
}

/** The chip: icon, name, drop-down arrow, laid out like the run configuration one beside it. */
private class DeviceButton(
    action: AnAction,
    presentation: Presentation,
    place: String,
) : ActionButtonWithText(action, presentation, place, JBUI.size(MINIMUM_WIDTH, MINIMUM_HEIGHT)) {

    init {
        setHorizontalTextAlignment(SwingConstants.LEFT)
    }

    override fun getMargins(): Insets = JBInsets(0, MARGIN_LEFT, 0, MARGIN_RIGHT)

    override fun shallPaintDownArrow(): Boolean = true

    private companion object {
        const val MINIMUM_WIDTH = 24
        const val MINIMUM_HEIGHT = 26

        /** The same asymmetry the run configuration chip uses: room for the icon, less for the arrow. */
        const val MARGIN_LEFT = 10
        const val MARGIN_RIGHT = 6
    }
}
