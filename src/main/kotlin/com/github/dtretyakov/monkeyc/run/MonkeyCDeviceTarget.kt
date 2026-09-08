package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.github.dtretyakov.monkeyc.ui.MonkeyCIcons
import com.intellij.execution.ExecutionTarget
import com.intellij.execution.ExecutionTargetProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import javax.swing.Icon

/**
 * One watch, as something to run on.
 *
 * The device is not a preference tucked away in a dialog: it decides what the compiler produces,
 * and switching it — a round face, then a square one — is most of what testing a watch app is.
 * The platform already has a place for exactly this, the target selector next to the Run button,
 * and putting it there costs one extension point and gets the whole widget for free.
 */
class MonkeyCDeviceTarget(private val device: ConnectIqDevice) : ExecutionTarget() {

    val deviceId: String get() = device.id

    override fun getId(): String = "$ID_PREFIX${device.id}"

    override fun getDisplayName(): String = device.displayName

    override fun getIcon(): Icon = MonkeyCIcons.CONNECT_IQ

    override fun canRun(configuration: RunConfiguration): Boolean = configuration is MonkeyCRunConfiguration

    companion object {
        private const val ID_PREFIX = "monkeyc.device:"

        /** The device the user picked, or null when the selection is not one of ours. */
        fun deviceOf(target: ExecutionTarget?): String? = (target as? MonkeyCDeviceTarget)?.deviceId
    }
}

class MonkeyCExecutionTargetProvider : ExecutionTargetProvider() {

    override fun getTargets(project: Project, configuration: RunConfiguration): List<ExecutionTarget> {
        if (configuration !is MonkeyCRunConfiguration) return emptyList()
        val model = MonkeyCProject.getInstance(project)
        val root = model.primaryRoot() ?: return emptyList()
        return model.buildableDevices(root).map { MonkeyCDeviceTarget(it) }
    }
}
