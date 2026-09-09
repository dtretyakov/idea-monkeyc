package com.github.dtretyakov.monkeyc.project

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.sdk.SdkManagerApp
import com.github.dtretyakov.monkeyc.sdk.SdkVersion
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Path

/**
 * Everything that has to be in place before a Connect IQ project can be built, in one list.
 *
 * There are six prerequisites and the user used to meet them one at a time, each at the moment it
 * blocked something, each as a sentence with no button. Gathered here they become a checklist that
 * can be read before anything is attempted — which is what Garmin's own VS Code extension offers as
 * "Verify Installation", and what this plugin previously had only as a headless Gradle task.
 *
 * Kept apart from the settings page so the rules can be tested as rules, without a dialog.
 */
object ConnectIqEnvironment {

    enum class Status { READY, MISSING }

    /** What to offer when something is missing. Null means there is nothing the plugin can do. */
    enum class Fix { SDK_MANAGER, GENERATE_KEY }

    /**
     * Which prerequisite an item is about.
     *
     * So a settings field can ask for its own line without matching on the display name, which is
     * text and therefore free to change. The settings page shows each of these beside the control
     * that fixes it rather than as a checklist of its own.
     */
    enum class Concern { SDK_MANAGER, SDK, LANGUAGE_SERVER, LIVE_ANALYSIS, DEVICES, DEVELOPER_KEY, JAVA }

    data class Item(
        val concern: Concern,
        val name: String,
        val status: Status,
        val detail: String,
        val fix: Fix? = null,
        /**
         * Whether this actually stops the user, as opposed to being worth knowing.
         *
         * The distinction earns its keep in the editor banner, which shows the first blocking item
         * and must not interrupt someone whose SDK works to tell them about an application they
         * have no present need for.
         */
        val blocking: Boolean = true,
    )

    /** True when nothing stands in the way of a build. */
    fun isReady(items: List<Item>): Boolean = items.none { it.status == Status.MISSING && it.blocking }

    /** The line about one prerequisite, for the control that fixes it. */
    fun of(items: List<Item>, concern: Concern): Item? = items.firstOrNull { it.concern == concern }

    /** The first thing actually in the way, or null when nothing is. */
    fun firstProblem(items: List<Item>): Item? =
        items.firstOrNull { it.status == Status.MISSING && it.blocking }

    fun check(project: Project?): List<Item> {
        val service = ConnectIqSdkService.getInstance()
        val sdk = service.sdkFor(project)

        return buildList {
            add(sdkManager(SdkManagerApp.location(), hasSdk = sdk != null))
            add(sdk(sdk, service.pinnedButMissing(project)))
            if (sdk != null) add(languageServer(sdk))
            if (project != null && sdk?.hasLanguageServer == true) {
                add(liveAnalysis(MonkeyCSettings.getInstance(project).liveAnalysis))
            }
            add(devices(service.devices().size, sdk != null, service.unreadableDevices()))
            add(developerKey(project))
            add(java(service.java(), service.javaVersion()))
        }
    }

    /**
     * Only in the way when there is no SDK either.
     *
     * An SDK can be on the machine without the manager — unpacked by hand, or inherited from a
     * colleague — and in that state everything works. Saying so is useful; blocking on it would be
     * an interruption about an application the user does not presently need.
     */
    internal fun sdkManager(where: Path?, hasSdk: Boolean): Item =
        when {
            where != null -> Item(Concern.SDK_MANAGER, "SDK Manager", Status.READY, shorten(where))
            hasSdk -> Item(
                Concern.SDK_MANAGER,
                "SDK Manager",
                Status.MISSING,
                "Not installed. The SDK is here without it, but new SDKs and devices come through it.",
                Fix.SDK_MANAGER,
                blocking = false,
            )
            else -> Item(
                Concern.SDK_MANAGER,
                "SDK Manager",
                Status.MISSING,
                "Not installed. Garmin ships the SDK and the devices through it and nowhere else.",
                Fix.SDK_MANAGER,
            )
        }

    /**
     * The SDK in use, and — when the project asked for one it did not get — which one that was.
     *
     * A pin that is not on this machine falls back to the current SDK rather than failing, because
     * these settings are committed and the path may be a colleague's. That fallback has to be
     * visible: a project silently built with an SDK other than the one it names is the exact
     * problem pinning exists to prevent.
     */
    internal fun sdk(sdk: ConnectIqSdk?, pinnedButMissing: String? = null): Item = when {
        sdk == null ->
            Item(Concern.SDK, "Connect IQ SDK", Status.MISSING, "Not found. Install one with the SDK Manager.", Fix.SDK_MANAGER)

        pinnedButMissing != null -> Item(
            Concern.SDK,
            "Connect IQ SDK",
            Status.MISSING,
            "${sdk.version ?: "unknown version"} at ${shorten(sdk.root)}, but this project asks for " +
                "$pinnedButMissing, which is not on this machine. Install it, or change the SDK " +
                "for this project.",
            Fix.SDK_MANAGER,
            blocking = false,
        )

        else -> Item(Concern.SDK, "Connect IQ SDK", Status.READY, "${sdk.version ?: "unknown version"} at ${shorten(sdk.root)}")
    }

    /**
     * Code intelligence is the one thing an old SDK cannot give: `LanguageServer.jar` first shipped
     * in 8.1.0, and everything else — building, running, debugging — works without it.
     */
    private fun languageServer(sdk: ConnectIqSdk): Item = if (sdk.hasLanguageServer) {
        Item(Concern.LANGUAGE_SERVER, "Language server", Status.READY, "Included in this SDK")
    } else {
        Item(
            Concern.LANGUAGE_SERVER,
            "Language server",
            Status.MISSING,
            "This SDK has none, so there is no code intelligence. " +
                "It arrived in SDK ${SdkVersion.LANGUAGE_SERVER_MINIMUM}" +
                (sdk.version?.let { "; this one is $it." } ?: "."),
            Fix.SDK_MANAGER,
        )
    }

    /**
     * Says so when the server has been switched off deliberately.
     *
     * Not blocking — it is a choice, and the build does not care. Reported all the same, because
     * an editor with no completion and no reason on screen is precisely the failure this checklist
     * exists to prevent, and "I turned it off three weeks ago" is a cause like any other.
     */
    internal fun liveAnalysis(enabled: Boolean): Item = if (enabled) {
        Item(Concern.LIVE_ANALYSIS, "Live analysis", Status.READY, "The language server runs while you edit")
    } else {
        Item(
            Concern.LIVE_ANALYSIS,
            "Live analysis",
            Status.MISSING,
            "Turned off for this project, so there is no completion, no diagnostics and no " +
                "navigation. Building, running and debugging are unaffected.",
            blocking = false,
        )
    }

    internal fun devices(count: Int, hasSdk: Boolean, unreadable: List<String> = emptyList()): Item = when {
        // Reported rather than hidden: a device that is downloaded but unreadable is indis-
        // tinguishable from one that was never downloaded, and only one of the two is fixable
        // by downloading it again.
        count > 0 && unreadable.isNotEmpty() -> Item(
            Concern.DEVICES,
            "Devices",
            Status.MISSING,
            "$count downloaded, and ${unreadable.size} that could not be read " +
                "(${unreadable.take(3).joinToString()}). Downloading them again usually settles it.",
            Fix.SDK_MANAGER,
            blocking = false,
        )

        count > 0 -> Item(Concern.DEVICES, "Devices", Status.READY, "$count downloaded")
        !hasSdk -> Item(Concern.DEVICES, "Devices", Status.MISSING, "None, and no SDK to hold them.", Fix.SDK_MANAGER)
        else -> Item(
            Concern.DEVICES,
            "Devices",
            Status.MISSING,
            "None downloaded. An app is built for one device, so there is nothing to build for yet.",
            Fix.SDK_MANAGER,
        )
    }

    /**
     * A key is needed to sign anything, and the SDK Manager makes one on first run — so the usual
     * answer is "already there", and the interesting case is a project that names a key which is
     * not on this machine.
     */
    private fun developerKey(project: Project?): Item {
        if (project == null) return Item(Concern.DEVELOPER_KEY, "Developer key", Status.READY, "Chosen per project")

        val model = MonkeyCProject.getInstance(project)
        val problem = model.developerKeyProblem()
        if (problem != null) return Item(Concern.DEVELOPER_KEY, "Developer key", Status.MISSING, problem, Fix.GENERATE_KEY)

        val key = model.developerKey()!!
        val location = DeveloperKeyLocation.check(key, model.roots(), isIgnored(project, key))
        if (location is DeveloperKeyLocation.Verdict.Committable) {
            // Not blocking: the build works perfectly well, and this is about what happens later.
            return Item(Concern.DEVELOPER_KEY, "Developer key", Status.MISSING, DeveloperKeyLocation.describe(location), blocking = false)
        }

        return Item(Concern.DEVELOPER_KEY, "Developer key", Status.READY, shorten(key))
    }

    /**
     * Whether version control is ignoring this file, or null when nothing can say.
     *
     * Null is the common answer for a key outside the project, and for a project that is not under
     * version control at all. The caller treats it as "do not warn": a false alarm about a leaked
     * key is the kind of thing that teaches people to stop reading the checklist.
     */
    private fun isIgnored(project: Project, key: Path): Boolean? {
        val file = LocalFileSystem.getInstance().findFileByNioFile(key) ?: return null
        // `getService` rather than `ProjectLevelVcsManager.getInstance`, which is what it does:
        // the class gained a Kotlin companion object, so a Kotlin caller binds to
        // `Companion.getInstance` and gets a NoSuchFieldError on any IDE that predates it.
        val vcs = project.getService(ProjectLevelVcsManager::class.java) ?: return null
        if (!vcs.hasActiveVcss()) return null
        return runCatching { ChangeListManager.getInstance(project).isIgnoredFile(file) }.getOrNull()
    }

    /**
     * The JVM, and which one it is.
     *
     * The version is here because it is the largest unmarked performance variable on this
     * platform — a full export on the forums went from four hours to two minutes on nothing but a
     * change of JRE — and because nobody would think to look at it unless something said it
     * mattered. No judgement is offered on the answer; the build times in the Build window are
     * what make it mean something.
     */
    internal fun java(java: Path, version: String?) = Item(
        Concern.JAVA,
        "Java",
        Status.READY,
        shorten(java) + (version?.let { " — $it" } ?: ""),
    )

    private fun shorten(path: Path): String = FileUtil.getLocationRelativeToUserHome(path.toString())
}
