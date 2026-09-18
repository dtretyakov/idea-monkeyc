package com.github.dtretyakov.monkeyc.run

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import kotlin.io.path.name

/**
 * Offers to put a build on the watch that is attached, and explains what happens next.
 *
 * The explanation is the half that is easy to leave out and expensive to. On current devices the
 * `.prg` is moved somewhere the file browser cannot reach the moment the watch is unplugged and
 * plugged in again — "current-gen watches used to hide certain types of PRGs, but now they hide
 * all PRGs" — so a developer who checks their work finds an empty folder and concludes the copy
 * failed. It did not. Saying so costs a sentence.
 */
object MonkeyCInstallNotice {

    /**
     * What to do next, which is not obvious and is not optional.
     *
     * The cable comes out first: a watch on USB is in transfer mode and runs nothing, so a
     * developer who installs and then looks for the app finds a watch that is not listening. Both
     * places that report an install say this, and say it identically.
     */
    const val NEXT_STEP = "Unplug the cable, then open it on the watch."

    /** Why the file is not there afterwards. One clause: it goes on the end of a line. */
    const val HIDDEN_AFTERWARDS = "the watch hides every .prg from GARMIN/APPS once it has taken it."

    fun offer(project: Project, built: BuiltArtifact, targets: List<GarminTarget>) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Monkey C")
                .createNotification(
                    "A Garmin device is connected",
                    detail(built, targets),
                    NotificationType.INFORMATION,
                )
            targets.forEach { target ->
                notification.addAction(
                    NotificationAction.createSimple(label(targets, target)) {
                        install(project, built, target)
                    },
                )
            }
            notification.notify(project)
        }
    }

    /**
     * Says what will be installed where, and warns when the build is for another watch.
     *
     * The warning is the useful part: a `.prg` built for the wrong device installs and then does
     * nothing, which reads as a broken app rather than a wrong target. Only an MTP device reports
     * its model, so this is silent for a watch that mounts as a disk — silence being the right
     * answer when there is no way to tell.
     */
    private fun detail(built: BuiltArtifact, targets: List<GarminTarget>): String {
        val where = targets.joinToString { it.name }
        val head = "${built.output.name} was built for ${built.device} and can be installed on $where."

        val mismatch = targets.firstNotNullOfOrNull { target ->
            (target as? GarminTarget.Mtp)?.let {
                ConnectedWatch.mismatch(built.device, it.device, it.model)
            }
        }
        return if (mismatch == null) head else "$head\n\n$mismatch"
    }

    /** One device needs no name on the button; two do. */
    private fun label(targets: List<GarminTarget>, target: GarminTarget): String =
        if (targets.size == 1) "Install" else "Install on ${target.name}"

    private fun install(project: Project, built: BuiltArtifact, target: GarminTarget) {
        // On a background task with a progress bar: an MTP transfer is a real transfer over USB,
        // and a notification action runs on the UI thread.
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Installing ${built.output.name} on ${target.name}", false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    report(project, target, runCatching { target.install(built.output) })
                }
            },
        )
    }

    private fun report(project: Project, target: GarminTarget, result: Result<java.nio.file.Path>) {
        result.fold(
            onSuccess = { destination ->
                notify(
                    project,
                    "Installed on ${target.name}",
                    "${destination.name} is on ${target.name}. $NEXT_STEP " +
                        "Looking for it over USB will not find it: $HIDDEN_AFTERWARDS",
                    NotificationType.INFORMATION,
                )
            },
            onFailure = { failure ->
                notify(
                    project,
                    "Could not install on ${target.name}",
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
