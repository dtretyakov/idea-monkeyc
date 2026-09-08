package com.github.dtretyakov.monkeyc.project

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.sdk.DeviceCatalog
import com.github.dtretyakov.monkeyc.sdk.JavaLocator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime

/**
 * The one place that answers "which SDK are we using, and what does it contain".
 *
 * The SDK Manager is a separate application: the user can switch SDKs or download a device while
 * the IDE is open, and the only sign of it is a rewritten `current-sdk.cfg`. Rather than watch a
 * file outside every project's content roots, this re-reads when the marker's timestamp moves —
 * a stat on each lookup, which is far cheaper than the work that follows it.
 */
@Service(Service.Level.APP)
class ConnectIqSdkService {

    private class Snapshot(val sdk: ConnectIqSdk?, val catalog: DeviceCatalog?, val stamp: Long, val override: String)

    @Volatile
    private var snapshot: Snapshot? = null

    /**
     * The SDK, for callers with no project in hand — the wizard, the self-check, stopping every
     * simulator on the machine.
     */
    val sdk: ConnectIqSdk?
        get() = current().sdk

    /**
     * The SDK this project builds with: its own pin if it has one, otherwise the machine's.
     *
     * Every caller that compiles, analyses, runs or debugs *something* has a project, and should
     * ask this. The device catalogue does not need it — devices live in the shared data root, not
     * inside an SDK, so they are the same whichever one is current.
     */
    fun sdkFor(project: Project?): ConnectIqSdk? {
        val pinned = project?.let { MonkeyCSettings.getInstance(it).sdkPath.trim() }.orEmpty()
        if (pinned.isEmpty()) return sdk
        // A pin that is not there falls back rather than failing. These settings are committed, so
        // the path is as likely to have come from a colleague's machine as from this one, and a
        // project that refuses to build until the path is fixed would be a worse answer than one
        // that builds with the current SDK and says so on the checklist.
        return Path.of(pinned).takeIf { it.resolve("bin").exists() }?.let { ConnectIqSdk.at(it) } ?: sdk
    }

    /** Whether this project asked for an SDK that is not on this machine. */
    fun pinnedButMissing(project: Project?): String? {
        val pinned = project?.let { MonkeyCSettings.getInstance(it).sdkPath.trim() }.orEmpty()
        if (pinned.isEmpty()) return null
        return pinned.takeIf { !Path.of(it).resolve("bin").exists() }
    }

    /** Devices the SDK Manager has downloaded. Empty when there is no SDK, or none downloaded yet. */
    fun devices(): List<ConnectIqDevice> = current().catalog?.devices().orEmpty()

    fun device(id: String): ConnectIqDevice? = current().catalog?.byId(id)

    /** Device directories that are present but unreadable; empty is the normal answer. */
    fun unreadableDevices(): List<String> = current().catalog?.let {
        it.devices()
        it.unreadable
    }.orEmpty()

    /** The `java` that runs the SDK's jars. */
    fun java(): Path = JavaLocator.resolve(MonkeyCAppSettings.getInstance().javaPath)

    /**
     * What that `java` reports as its version, or null when it will not say.
     *
     * Cached against the path it was asked about, because answering means starting a process and
     * the checklist that shows it is rebuilt whenever an editor tab changes.
     */
    fun javaVersion(): String? {
        val java = java()
        javaVersion?.let { (path, version) -> if (path == java) return version }
        val version = JavaLocator.version(java)
        javaVersion = java to version
        return version
    }

    @Volatile
    private var javaVersion: Pair<Path, String?>? = null

    /** Forces a re-read; for the settings dialog and the "reload" action. */
    fun refresh() {
        snapshot = null
        javaVersion = null
        current()
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).sdkChanged(sdk)
    }

    private fun current(): Snapshot {
        val override = MonkeyCAppSettings.getInstance().sdkPath.trim()
        val stamp = markerStamp()
        snapshot?.let { if (it.stamp == stamp && it.override == override) return it }

        val resolved = if (override.isNotEmpty()) {
            Path.of(override).takeIf { it.resolve("bin").exists() }?.let { ConnectIqSdk.at(it) }
        } else {
            ConnectIqSdk.detect()
        }

        return Snapshot(
            sdk = resolved,
            catalog = resolved?.let { DeviceCatalog(it.devicesRoot) },
            stamp = stamp,
            override = override,
        ).also { snapshot = it }
    }

    private fun markerStamp(): Long =
        ConnectIqSdk.currentSdkMarker().takeIf { it.exists() }
            ?.runCatching { getLastModifiedTime().toMillis() }
            ?.getOrNull()
            ?: 0L

    companion object {
        fun getInstance(): ConnectIqSdkService = ApplicationManager.getApplication().service()

        @Topic.AppLevel
        val TOPIC: Topic<ConnectIqSdkListener> =
            Topic.create("Connect IQ SDK", ConnectIqSdkListener::class.java)
    }
}

fun interface ConnectIqSdkListener {
    fun sdkChanged(sdk: ConnectIqSdk?)
}
