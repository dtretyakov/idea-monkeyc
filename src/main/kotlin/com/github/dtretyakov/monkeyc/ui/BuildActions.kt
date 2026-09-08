package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.run.MonkeyCRunKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadActionBlocking
import java.nio.file.Path

/**
 * Building, from the Build menu, which is where anyone would look for it.
 *
 * Both of these were reachable before — as a run configuration the user had to create by hand,
 * and in the second case by then finding a checkbox inside its editor. Installing your own app on
 * your own watch is not an advanced gesture, and it was two non-obvious steps deep.
 */
abstract class BuildAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val root = project?.let { runReadActionBlocking { MonkeyCProject.getInstance(it).primaryRoot() } }

        // Hidden for a barrel: it has no app to build, and the Build menu's export item already
        // relabels itself to "Build Connect IQ Barrel" for that case.
        event.presentation.isEnabledAndVisible = root != null && !isBarrel(event, root)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        // The destination is the target, not a hidden flag on a configuration. Setting it here is
        // what makes the menu item honest: afterwards the chip beside Run says "on the watch", so
        // the next Run does the same thing this item just did, and the user can see why.
        val settings = MonkeyCSettings.getInstance(project)
        if (settings.targetOnWatch != forDevice) {
            settings.targetOnWatch = forDevice
            project.messageBus.syncPublisher(MonkeyCSettings.TOPIC).settingsChanged(project)
        }
        ConnectIqRunConfigurations.run(project, MonkeyCRunKind.BUILD)
    }

    protected abstract val forDevice: Boolean

    private fun isBarrel(event: AnActionEvent, root: Path): Boolean =
        event.project?.let { MonkeyCProject.getInstance(it).manifest(root)?.isBarrel } == true
}

/** A simulator build: the same `.prg` a Run would produce, without starting anything. */
class BuildAppAction : BuildAction() {
    override val forDevice: Boolean = false
}

/**
 * A build for the watch itself.
 *
 * The compiler is told `fenix7` rather than `fenix7_sim`, and the two are not interchangeable —
 * this is the only way to get a `.prg` that can be copied to `GARMIN/APPS` over USB.
 */
class BuildForWatchAction : BuildAction() {
    override val forDevice: Boolean = true
}
