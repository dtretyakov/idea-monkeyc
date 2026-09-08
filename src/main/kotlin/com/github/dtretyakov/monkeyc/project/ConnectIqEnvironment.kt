package com.github.dtretyakov.monkeyc.project

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.sdk.SdkManagerApp
import com.github.dtretyakov.monkeyc.sdk.SdkVersion
import com.intellij.openapi.project.Project
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

    data class Item(
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

    /** The first thing actually in the way, or null when nothing is. */
    fun firstProblem(items: List<Item>): Item? =
        items.firstOrNull { it.status == Status.MISSING && it.blocking }

    fun check(project: Project?): List<Item> {
        val service = ConnectIqSdkService.getInstance()
        val sdk = service.sdk

        return buildList {
            add(sdkManager(SdkManagerApp.location(), hasSdk = sdk != null))
            add(sdk(sdk))
            if (sdk != null) add(languageServer(sdk))
            add(devices(service.devices().size, sdk != null))
            add(developerKey(project))
            add(java(service.java()))
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
            where != null -> Item("SDK Manager", Status.READY, shorten(where))
            hasSdk -> Item(
                "SDK Manager",
                Status.MISSING,
                "Not installed. The SDK is here without it, but new SDKs and devices come through it.",
                Fix.SDK_MANAGER,
                blocking = false,
            )
            else -> Item(
                "SDK Manager",
                Status.MISSING,
                "Not installed. Garmin ships the SDK and the devices through it and nowhere else.",
                Fix.SDK_MANAGER,
            )
        }

    private fun sdk(sdk: ConnectIqSdk?): Item = if (sdk == null) {
        Item("Connect IQ SDK", Status.MISSING, "Not found. Install one with the SDK Manager.", Fix.SDK_MANAGER)
    } else {
        Item("Connect IQ SDK", Status.READY, "${sdk.version ?: "unknown version"} at ${shorten(sdk.root)}")
    }

    /**
     * Code intelligence is the one thing an old SDK cannot give: `LanguageServer.jar` first shipped
     * in 8.1.0, and everything else — building, running, debugging — works without it.
     */
    private fun languageServer(sdk: ConnectIqSdk): Item = if (sdk.hasLanguageServer) {
        Item("Language server", Status.READY, "Included in this SDK")
    } else {
        Item(
            "Language server",
            Status.MISSING,
            "This SDK has none, so there is no code intelligence. " +
                "It arrived in SDK ${SdkVersion.LANGUAGE_SERVER_MINIMUM}" +
                (sdk.version?.let { "; this one is $it." } ?: "."),
            Fix.SDK_MANAGER,
        )
    }

    internal fun devices(count: Int, hasSdk: Boolean): Item = when {
        count > 0 -> Item("Devices", Status.READY, "$count downloaded")
        !hasSdk -> Item("Devices", Status.MISSING, "None, and no SDK to hold them.", Fix.SDK_MANAGER)
        else -> Item(
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
        if (project == null) return Item("Developer key", Status.READY, "Chosen per project")

        val model = MonkeyCProject.getInstance(project)
        val problem = model.developerKeyProblem()
            ?: return Item("Developer key", Status.READY, shorten(model.developerKey()!!))

        return Item("Developer key", Status.MISSING, problem, Fix.GENERATE_KEY)
    }

    private fun java(java: Path) = Item("Java", Status.READY, shorten(java))

    private fun shorten(path: Path): String = FileUtil.getLocationRelativeToUserHome(path.toString())
}
