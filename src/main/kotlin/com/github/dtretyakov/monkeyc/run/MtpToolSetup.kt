package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.MonkeyCAppSettings
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.io.HttpRequests
import java.nio.file.Path

/**
 * The one button that gets `mtp-rs` onto this machine, wherever it is offered.
 *
 * Offered from three places — the watch list beside Run, the settings field, and the notification
 * after a build for a watch that could not be found — and the same thing happens from each: the
 * pinned release is downloaded in the background, checked, put in the plugin's own directory and
 * asked its version, and whoever offered it is told so they can look for the watch again.
 *
 * The same shape as the platform's own "Download JDK": the IDE finds a missing prerequisite, says
 * so where it is in the way, and installs it into a directory it manages when asked.
 */
object MtpToolSetup {

    /**
     * What the button says, whatever the reason for it. It never touches a copy the user installed —
     * an old or a newer one stays where it is, for whatever else uses it — and only adds the
     * plugin's own beside it, so "install" is what it does in every case.
     */
    fun label(): String = "Install mtp-rs ${MtpRelease.VERSION}"

    /** Why the button is there, in a few words for beside it. */
    fun reason(shortfall: MtpLocator.Shortfall): String = when (shortfall) {
        MtpLocator.Shortfall.MISSING -> "to see a watch over USB"
        MtpLocator.Shortfall.OUTDATED -> "the one found is too old"
        MtpLocator.Shortfall.UNTESTED -> "the one found is newer than this plugin knows"
    }

    /**
     * Installs the tool, then runs [then] if it worked — on the background thread that did the work.
     *
     * Not on the UI thread through `onSuccess`: started from the settings dialog, which is modal,
     * that would wait for the dialog to close, and the line it was meant to rewrite would be gone.
     * Each caller hops to whichever thread it needs.
     *
     * Cancellable, because it is a download: a slow connection is the user's to give up on.
     */
    fun install(project: Project?, then: (Path) -> Unit = {}) {
        val asset = MtpRelease.forPlatform() ?: return notify(
            project,
            "mtp-rs is not available for this computer",
            "There is no mtp-rs ${MtpRelease.VERSION} build for ${System.getProperty("os.name")} on " +
                "${System.getProperty("os.arch")}. See ${MtpLocator.INSTALL_URL}.",
            NotificationType.ERROR,
        )
        val windows = System.getProperty("os.name").startsWith("Windows")

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Installing mtp-rs ${MtpRelease.VERSION}", true) {
                private var tool: Path? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Downloading ${asset.file}"
                    val installed = MtpInstaller.install(asset, MtpInstaller.directory(), windows) { url, to ->
                        HttpRequests.request(url).productNameAsUserAgent().saveToFile(to, indicator)
                    }
                    indicator.text = "Checking mtp-rs"
                    val version = MtpLocator.readVersion(installed)
                    check(version == MtpRelease.VERSION) {
                        "mtp-rs was installed at $installed but answered ${version ?: "nothing"} when asked its version."
                    }
                    tool = installed
                    then(installed)
                }

                override fun onSuccess() {
                    val installed = tool ?: return
                    notify(project, "mtp-rs ${MtpRelease.VERSION} is installed", afterwards(installed), NotificationType.INFORMATION)
                }

                override fun onThrowable(error: Throwable) {
                    notify(
                        project,
                        "Could not install mtp-rs",
                        (error.message ?: error.javaClass.simpleName) +
                            " To install it yourself, see ${MtpLocator.INSTALL_URL}.",
                        NotificationType.ERROR,
                    )
                }
            },
        )
    }

    /**
     * Offers the tool after a build for a watch that could not be found, and finishes the job if
     * it is taken: looks for the watch again, and offers to install [built] on it.
     *
     * Looking again is the point. Without it the click installs a tool and leaves the user where
     * they were — a build, a watch on the desk, and the run to start over.
     */
    fun offerAfterBuild(project: Project, built: BuiltArtifact, shortfall: MtpLocator.Shortfall) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val (title, detail) = when (shortfall) {
                MtpLocator.Shortfall.MISSING -> "Installing on a watch needs mtp-rs" to
                    "A current Garmin watch connects over MTP, and the IDE reaches it through mtp-rs."
                MtpLocator.Shortfall.OUTDATED -> "mtp-rs is out of date" to
                    "The mtp-rs found is older than ${MtpRelease.MINIMUM}, the version this plugin is built " +
                    "against. Installing ${MtpRelease.VERSION} for the IDE leaves that one where it is."
                MtpLocator.Shortfall.UNTESTED -> "mtp-rs is newer than this plugin knows" to
                    "The mtp-rs found is past the ${MtpRelease.MINIMUM} line this plugin reads, and may " +
                    "answer differently. Installing ${MtpRelease.VERSION} for the IDE leaves that one where it is."
            }
            val notification = group().createNotification(title, detail, NotificationType.WARNING)
            notification.addAction(
                NotificationAction.createSimpleExpiring(label()) {
                    install(project) { lookAgain(project, built) }
                },
            )
            notification.notify(project)
        }
    }

    private fun lookAgain(project: Project, built: BuiltArtifact) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val targets = GarminTarget.attached()
            if (targets.isNotEmpty()) {
                MonkeyCInstallNotice.offer(project, built, targets)
            } else {
                notify(
                    project,
                    "No watch is connected",
                    "Connect the watch with its USB cable, then run again.",
                    NotificationType.INFORMATION,
                )
            }
        }
    }

    /**
     * What to know once it is in. Only one thing: a path set in the settings still wins, because
     * a setting is the user's explicit choice and is never overridden behind their back.
     */
    private fun afterwards(tool: Path): String {
        val configured = MonkeyCAppSettings.getInstance().mtpToolPath.trim()
        return if (configured.isEmpty()) {
            "Installed at $tool."
        } else {
            "Installed at $tool, but Settings | Languages & Frameworks | Monkey C names another one, " +
                "which is still the one used. Clear that field to use this one."
        }
    }

    private fun notify(project: Project?, title: String, detail: String, type: NotificationType) {
        group().createNotification(title, detail, type).notify(project)
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("Monkey C")
}
