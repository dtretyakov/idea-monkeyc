package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.project.MonkeyCTarget
import com.github.dtretyakov.monkeyc.run.GarminTarget
import com.github.dtretyakov.monkeyc.run.MonkeyCRunConfiguration
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.github.dtretyakov.monkeyc.ui.manifest.ManifestModel
import com.intellij.icons.AllIcons
import com.intellij.execution.RunManager
import com.intellij.execution.ui.TogglePopupAction
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.ui.GotItTooltip
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.JBInsets
import java.nio.file.Path
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
        val visible = project != null && runReadAction { MonkeyCProject.getInstance(project).primaryRoot() != null }
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

    /**
     * The popup, built to show the rows that cannot be chosen.
     *
     * [TogglePopupAction] asks for `showDisabledActions = false`, and a popup built that way drops
     * every disabled row before it is drawn. That is wrong for this list twice over. A watch that
     * is plugged in but not among the project's products has to be visible — being told it was
     * seen, and why it is not a target, is the whole reason the row exists — and the empty-state
     * sentence [DeviceMenu] composes had never reached the screen either: it is a disabled row,
     * so the popup for a project with nothing to build for showed two trailing actions and no
     * explanation at all.
     *
     * Everything else is what the superclass passes, so the popup still looks and behaves like the
     * run configuration one beside it.
     */
    override fun createPopup(
        actionGroup: ActionGroup,
        e: AnActionEvent,
        disposeCallback: () -> Unit,
    ): ListPopup = JBPopupFactory.getInstance().createActionGroupPopup(
        null,
        actionGroup,
        e.dataContext,
        false,
        true,
        false,
        { disposeCallback() },
        -1,
        null,
    )

    override fun getActionGroup(e: AnActionEvent): ActionGroup? {
        val project = e.project ?: return null
        val model = MonkeyCProject.getInstance(project)
        val root = runReadAction { model.primaryRoot() } ?: return null

        val devices = model.buildableDevices(root)

        // The popup is also where the list is changed, not only where one of it is picked — the
        // same shape Flutter's device selector uses, where "Open iOS Simulator" sits below the
        // list. Without it the empty state is a dead end at exactly the moment the user is looking.
        // Which door to offer is [DeviceMenu]'s question: this list comes from the manifest, so
        // Edit Products is always the way to change it and the SDK Manager only sometimes is.
        val offer = DeviceMenu.of(
            declared = model.manifest(root)?.devices.orEmpty(),
            installed = ConnectIqSdkService.getInstance().devices(),
            buildable = devices,
        )
        val trailing = listOfNotNull(EditProducts(), Acquire(project).takeIf { offer.sdkManager })

        // Before the early return, not after it. A project with nothing to build for is exactly
        // when someone plugs a watch in, and with the look below the return that was the one case
        // where nothing ever looked.
        //
        // Asked for now, answered for next time. Looking walks the mount points and starts a
        // subprocess, and this runs while the popup is being built — on the UI thread — so it
        // reads what was found last time and sets a fresh look going behind it. Tying the look to
        // the toolbar's `update` instead would spawn a process every few seconds for as long as a
        // project is open, to answer a question nobody is asking.
        ApplicationManager.getApplication().executeOnPooledThread { GarminTarget.refreshAttached() }

        val watch = attachedSection(project, root, devices)

        if (devices.isEmpty()) {
            val head = listOf(Unavailable(offer.empty ?: "No devices are downloaded"))
            val connected = if (watch.isEmpty()) emptyList() else listOf(Separator("Connected Watch")) + watch
            return DefaultActionGroup(head + connected + listOf(Separator.getInstance()) + trailing)
        }

        val simulator = devices.map {
            Select(project, MonkeyCTarget(it.id, MonkeyCTarget.Destination.SIMULATOR), "${it.displayName}  (${it.id})")
        }

        // One section needs no heading; two do.
        if (watch.isEmpty()) {
            return DefaultActionGroup(simulator + listOf(Separator.getInstance()) + trailing)
        }
        return DefaultActionGroup(
            listOf(Separator("Simulator")) + simulator +
                listOf(Separator("Connected Watch")) + watch +
                listOf(Separator.getInstance()) + trailing,
        )
    }

    /**
     * Every watch that is plugged in, whether or not it can be built for.
     *
     * A watch the project does not declare used to be dropped from this list entirely, which is
     * the opposite of what a device picker is for: you plug a watch in, open the one control that
     * is about devices, and it is not there — with nothing to say it was seen, and no hint that
     * the manifest is what decides. The list is a fact about the USB bus; whether a build can be
     * made for one is a separate fact, and belongs beside it rather than in place of it.
     *
     * The same shape Android Studio's device dropdown uses, where a device below the app's
     * `minSdk` is listed and disabled with the reason instead of being hidden.
     */
    private fun attachedSection(
        project: Project,
        root: Path,
        buildable: List<ConnectIqDevice>,
    ): List<AnAction> = GarminTarget.attachedWatches().map { found ->
        val device = found.deviceId?.let { id -> buildable.firstOrNull { it.id == id } }
        val known = found.deviceId?.let { id -> ConnectIqSdkService.getInstance().device(id) }
        when {
            // Declared and downloaded: an ordinary target, and the reason this list exists.
            device != null ->
                Select(
                    project,
                    MonkeyCTarget(device.id, MonkeyCTarget.Destination.WATCH),
                    "${device.displayName}  (${device.id})",
                )

            // Known to the SDK but not to this project. An enabled row whose click is the remedy,
            // because a disabled one cannot carry an affordance: the popup draws no inline action
            // on a row it has disabled, so the add had nothing to be clicked on and the row read
            // as a dead end. The icon and the note beside it are what say this one acts rather
            // than selects.
            known != null -> AddProduct(project, root, known)

            // Either the model could not be read from the device or it is not among the downloaded
            // ones, and from here those look the same. Nothing to offer: adding a product needs an
            // id, and there is none. The SDK Manager below is the way out of the second case.
            else -> Unavailable(found.name, secondary = "model unknown")
        }
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

    /**
     * The door to the list itself, which is the manifest.
     *
     * Delegating to the registered action rather than opening the dialog here: it is the same
     * command as Tools | Connect IQ | Edit Connect IQ Devices, and two entry points that construct
     * the dialog separately are two places to fix when it changes.
     */
    private class EditProducts : AnAction("Edit Devices") {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(event: AnActionEvent) {
            val products = ActionManager.getInstance().getAction("MonkeyC.EditProducts") ?: return
            // Through the platform rather than by calling `actionPerformed`, which is annotated
            // OverrideOnly: it is there to be implemented, not invoked, and calling it skips the
            // update, the data context and the listeners that make an action an action.
            ActionUtil.invokeAction(products, event.dataContext, event.place, null, null)
        }
    }

    /**
     * Adds an attached watch to the manifest's products, and targets it.
     *
     * One edit through the same writer the manifest form uses, so it is one undo and the text tab
     * shows it at once. The manifest is the one the build reads rather than `manifest.xml` by
     * name: a jungle can point `project.manifest` somewhere else, and editing the file nobody
     * compiles would look like the click did nothing.
     *
     * It also becomes the target. Clicking this means "build for these watches", and adding the
     * product and then having to pick it would be two clicks for one intention.
     */
    private class AddProduct(
        private val project: Project,
        private val root: Path,
        private val device: ConnectIqDevice,
    ) : AnAction(
        "${device.displayName}  (${device.id})",
        "Declares ${device.displayName} in the manifest and targets it",
        AllIcons.General.Add,
    ) {
        init {
            // The row has to read as an action rather than as one more target, because clicking it
            // edits the manifest instead of choosing something. The icon and this note are what
            // say so; a disabled row with the note alone said "seen, and nothing you can do".
            templatePresentation.putClientProperty(ActionUtil.SECONDARY_TEXT, "add to products")
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(event: AnActionEvent) {
            val model = MonkeyCProject.getInstance(project)
            val path = model.manifestPath(root)

            // Every way this can fail is said out loud. A click that silently does nothing is the
            // worst of the three outcomes: the user cannot tell it from a control that is not a
            // control, which is exactly what the disabled row before it turned out to be.
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            if (file == null) return complain("There is no manifest at $path to add it to.")

            val document = FileDocumentManager.getInstance().getDocument(file)
            if (document == null) return complain("${path.fileName} could not be opened for editing.")

            val manifest = ManifestModel(project, document)
            val declared = manifest.read()?.devices
                ?: return complain(
                    "${path.fileName} could not be read. It is probably mid-edit; fix the XML and try again.",
                )

            if (device.id !in declared) {
                manifest.setDevices(declared + device.id)
                if (manifest.read()?.devices?.contains(device.id) != true) {
                    return complain("${device.id} could not be added to ${path.fileName}.")
                }
                // Saved, not left dirty. The compiler is a subprocess and reads the manifest off
                // disk, and nothing on the way to a build saves documents — so an edit that
                // stayed in memory would pass the plugin's own device check, which reads the
                // document, and then be compiled against a file that never heard of this watch.
                FileDocumentManager.getInstance().saveDocument(document)
            }

            // The click means "build for these", so it becomes the target too: adding the product
            // and then having to pick it would be two clicks for one intention.
            MonkeyCSettings.getInstance(project)
                .setTarget(MonkeyCTarget(device.id, MonkeyCTarget.Destination.WATCH))
            project.messageBus.syncPublisher(MonkeyCSettings.TOPIC).settingsChanged(project)
        }

        private fun complain(detail: String) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Monkey C")
                .createNotification("Could not add ${device.displayName}", detail, NotificationType.ERROR)
                .notify(project)
        }
    }

    /** The way out of an empty list: the application that downloads devices. */
    private class Acquire(private val project: Project) : AnAction(OpenSdkManager.label()) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(event: AnActionEvent) = OpenSdkManager.invoke(project)
    }

    /**
     * A row that says something and cannot be chosen.
     *
     * [secondary] is the platform's own right-aligned note — `SECONDARY_TEXT`, the same key the
     * run configuration popup beside this one uses — and here it carries the reason.
     *
     * Only for rows with nothing to offer. A row that has a remedy must not be disabled: the
     * popup draws no inline action on a disabled row, so the remedy would have nothing to be
     * clicked on. Those are actions of their own, and look like it.
     */
    private class Unavailable(text: String, secondary: String? = null) : AnAction(text) {
        init {
            secondary?.let { templatePresentation.putClientProperty(ActionUtil.SECONDARY_TEXT, it) }
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        // Disabled, not hidden: the row exists to say that the watch was seen and why it is not a
        // target. Its inline actions carry their own state and stay clickable.
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
