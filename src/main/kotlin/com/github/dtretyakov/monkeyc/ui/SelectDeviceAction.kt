package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.project.MonkeyCTarget
import com.github.dtretyakov.monkeyc.run.GarminTarget
import com.github.dtretyakov.monkeyc.run.MonkeyCRunConfiguration
import com.intellij.execution.RunManager
import com.intellij.execution.ui.TogglePopupAction
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.ui.GotItTooltip
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

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        val presentation = e.presentation

        // Deliberately cheap: this runs on every toolbar update, and reading the manifest and the
        // device catalogue here would put a file read on the IDE's pulse. The list is built only
        // when the popup is actually opened.
        // Under a read action: `update` runs on a background thread, and the content roots this
        // walks are project model state, which may not be read without one.
        val visible = project != null && runReadActionBlocking { MonkeyCProject.getInstance(project).primaryRoot() != null }
        presentation.isEnabledAndVisible = visible
        if (!visible) return

        // The effective target of the configuration that is selected, not the project setting.
        // Showing the setting was misleading: a configuration pinned to one watch built that watch
        // while this said another, and a control the platform puts beside Run has to describe the
        // next click.
        val target = effectiveTarget(project)
        presentation.setText(target?.describe()?.ifEmpty { null } ?: "No device", false)
        presentation.icon = MonkeyCIcons.CONNECT_IQ
        presentation.description = if (pinnedByConfiguration(project)) {
            "Pinned by the selected run configuration"
        } else {
            "Where Build, Run and Debug put the app"
        }
    }

    override fun getActionGroup(e: AnActionEvent): ActionGroup? {
        val project = e.project ?: return null
        val model = MonkeyCProject.getInstance(project)
        val root = runReadActionBlocking { model.primaryRoot() } ?: return null

        val devices = model.buildableDevices(root)

        // The popup is also where a device is acquired, not only where one is picked — the same
        // shape Flutter's device selector uses, where "Open iOS Simulator" sits below the list.
        // Without it the empty state is a dead end at exactly the moment the user is looking.
        val acquire = Acquire(project)

        if (devices.isEmpty()) {
            return DefaultActionGroup(
                Unavailable("No devices are downloaded"),
                Separator.getInstance(),
                acquire,
            )
        }

        // Which of them is plugged in, from a short-lived cache: this runs when the popup opens,
        // and finding out means walking mount points and starting a subprocess.
        val attached = GarminTarget.attachedDeviceIds()

        val simulator = devices.map {
            Select(project, MonkeyCTarget(it.id, MonkeyCTarget.Destination.SIMULATOR), "${it.displayName}  (${it.id})")
        }
        // Every device, not only the attached ones: building a `.prg` for a watch you do not own
        // is ordinary, and it is how you hand one to somebody else. The attached ones are marked
        // because for those an install will be offered rather than just a file.
        val watch = devices.map {
            val connected = if (it.id in attached) "  · connected" else ""
            Select(
                project,
                MonkeyCTarget(it.id, MonkeyCTarget.Destination.WATCH),
                "${it.displayName}  (${it.id})$connected",
            )
        }

        return DefaultActionGroup(
            listOf(Separator("Simulator")) + simulator +
                listOf(Separator("Watch")) + watch +
                listOf(Separator.getInstance(), acquire),
        )
    }

    /** The target the selected run configuration will actually use. */
    private fun effectiveTarget(project: Project): MonkeyCTarget? = MonkeyCTarget.resolve(
        pinned = pinnedTarget(project),
        chosen = MonkeyCSettings.getInstance(project).target,
        default = null,
    )

    private fun pinnedByConfiguration(project: Project): Boolean = pinnedTarget(project) != null

    /**
     * The target the selected configuration pins itself to, if it pins one.
     *
     * Read straight off the selected configuration rather than kept in sync with it: the toolbar
     * repaints when the selection changes, so asking then is both current and free of listeners
     * that could go stale.
     */
    private fun pinnedTarget(project: Project): MonkeyCTarget? {
        val options = (RunManager.getInstance(project).selectedConfiguration?.configuration
            as? MonkeyCRunConfiguration)?.options ?: return null
        return MonkeyCTarget.ofOptions(options.device, options.forDevice)
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        // Shown once, the first time the chip appears, and never again — the platform remembers by
        // id. This is what the Got It tooltip is for by the guideline: a small toolbar control that
        // is easy to overlook, introducing a concept the user has not met. It is not for explaining
        // that an SDK is missing; that is a banner, and there is one.
        GotItTooltip(
            "monkeyc.device.chip",
            "A Connect IQ app is built for one watch at a time. This is the one that Build, Run " +
                "and Debug use — switching it here rebuilds for the other screen.",
            null,
        )
            .withHeader("Choose the watch")
            .withPosition(Balloon.Position.below)
            .assignTo(presentation, GotItTooltip.BOTTOM_MIDDLE)

        return DeviceButton(this, presentation, place)
    }

    private class Select(
        private val project: Project,
        private val target: MonkeyCTarget,
        text: String,
    ) : AnAction(text) {

        override fun actionPerformed(event: AnActionEvent) {
            val settings = MonkeyCSettings.getInstance(project)
            if (settings.target == target) return
            settings.setTarget(target)
            // The language server is told the device at initialize and caches it, so it has to be
            // told again; everything else reads the setting at the moment it runs.
            project.messageBus.syncPublisher(MonkeyCSettings.TOPIC).settingsChanged(project)
        }
    }

    /** The way out of an empty list: the application that downloads devices. */
    private class Acquire(private val project: Project) : AnAction(OpenSdkManager.label()) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(event: AnActionEvent) = OpenSdkManager.invoke(project)
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
