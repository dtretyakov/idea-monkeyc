package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlin.io.path.name

/**
 * Offers to put a build on the watch that is plugged in, and explains what happens next.
 *
 * The explanation is the half that is easy to leave out and expensive to. On current devices the
 * `.prg` is moved somewhere the file browser cannot reach the moment the watch is unplugged and
 * plugged in again — "current-gen watches used to hide certain types of PRGs, but now they hide
 * all PRGs" — so a developer who checks their work finds an empty folder and concludes the copy
 * failed. It did not. Saying so costs a sentence.
 */
object MonkeyCInstallNotice {

    fun offer(project: Project, built: BuiltArtifact, volumes: List<GarminVolume>) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Monkey C")
                .createNotification(
                    "A Garmin device is connected",
                    "${built.output.name} was built for ${built.device} and can be installed on " +
                        volumes.joinToString { it.name } + ".",
                    NotificationType.INFORMATION,
                )
            volumes.forEach { volume ->
                notification.addAction(
                    NotificationAction.createSimple(label(volumes, volume)) {
                        install(project, built, volume)
                    },
                )
            }
            notification.notify(project)
        }
    }

    /** One device needs no name on the button; two do. */
    private fun label(volumes: List<GarminVolume>, volume: GarminVolume): String =
        if (volumes.size == 1) "Install" else "Install on ${volume.name}"

    private fun install(project: Project, built: BuiltArtifact, volume: GarminVolume) {
        val settings = ProjectLayout.settingsJson(built.output)
        val result = runCatching { volume.install(built.output, settings) }

        result.fold(
            onSuccess = { destination ->
                notify(
                    project,
                    "Installed on ${volume.name}",
                    "${destination.name} is in ${volume.apps}. It will not be there the next time " +
                        "the device is plugged in — current devices hide every .prg once they have " +
                        "taken it, and that is the install working rather than failing. Remove it " +
                        "from the watch's own Connect IQ list, or with Garmin Express.",
                    NotificationType.INFORMATION,
                )
            },
            onFailure = { failure ->
                notify(
                    project,
                    "Could not install on ${volume.name}",
                    failure.message ?: "The copy failed with ${failure.javaClass.simpleName}.",
                    NotificationType.ERROR,
                )
            },
        )
    }

    private fun notify(project: Project, title: String, detail: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Monkey C")
            .createNotification(title, detail, type)
            .notify(project)
    }
}
