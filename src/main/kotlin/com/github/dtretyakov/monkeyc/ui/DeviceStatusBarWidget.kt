package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.widget.StatusBarEditorBasedWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

/**
 * The target device, in the status bar.
 *
 * A Connect IQ executable is built for one device, so this is not a preference tucked away in a
 * dialog — it changes what every build produces, and it is the setting a developer switches most.
 */
class DeviceStatusBarWidgetFactory : StatusBarEditorBasedWidgetFactory() {

    override fun getId(): String = ID

    override fun getDisplayName(): String = "Connect IQ Device"

    override fun isAvailable(project: Project): Boolean =
        MonkeyCProject.getInstance(project).primaryRoot() != null

    override fun createWidget(project: Project): StatusBarWidget = DeviceStatusBarWidget(project)

    companion object {
        const val ID = "MonkeyCDevice"
    }
}

private class DeviceStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.MultipleTextValuesPresentation {

    private var statusBar: StatusBar? = null

    override fun ID(): String = DeviceStatusBarWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        project.messageBus.connect(this).subscribe(
            MonkeyCSettings.TOPIC,
            com.github.dtretyakov.monkeyc.project.MonkeyCSettingsListener { statusBar.updateWidget(ID()) },
        )
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getSelectedValue(): String {
        val device = MonkeyCSettings.getInstance(project).targetDevice
        return if (device.isEmpty()) "No device" else device
    }

    override fun getTooltipText(): String = "Connect IQ target device"

    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    /** The devices the manifest declares and the machine has. */
    override fun getPopup(): JBPopup {
        val model = MonkeyCProject.getInstance(project)
        val root = model.primaryRoot()
        val devices = root?.let { model.buildableDevices(it) }.orEmpty()

        if (devices.isEmpty()) {
            return JBPopupFactory.getInstance().createMessage(
                "None of the devices this project declares is downloaded. Get them with the SDK Manager.",
            )
        }

        val labels = devices.map { "${it.displayName} (${it.id})" }
        return JBPopupFactory.getInstance()
            .createPopupChooserBuilder(labels)
            .setTitle("Target Device")
            .setItemChosenCallback { chosen -> select(devices[labels.indexOf(chosen)].id) }
            .createPopup()
    }

    private fun select(device: String) {
        MonkeyCSettings.getInstance(project).targetDevice = device
        // The language server is told the device at initialize and caches it.
        project.messageBus.syncPublisher(MonkeyCSettings.TOPIC).settingsChanged(project)
        statusBar?.updateWidget(ID())
    }
}
