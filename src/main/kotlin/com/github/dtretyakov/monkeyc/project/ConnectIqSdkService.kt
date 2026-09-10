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
import java.util.concurrent.atomic.AtomicBoolean
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

    private class Snapshot(val sdk: ConnectIqSdk?, val catalog: DeviceCatalog?, val stamp: Long)

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

        // Cached, because this is asked on paths that run constantly — folding rebuilds on every
        // document change, and it goes through here. Building a ConnectIqSdk is not free: the
        // constructor reads bin/version.txt, so an uncached answer would be a file read per
        // keystroke for a pinned project and nothing at all for an unpinned one.
        pinnedSdk?.let { (path, resolved) -> if (path == pinned) return resolved }

        // A pin that is not there falls back rather than failing. These settings are committed, so
        // the path is as likely to have come from a colleague's machine as from this one, and a
        // project that refuses to build until the path is fixed would be a worse answer than one
        // that builds with the current SDK and says so on the checklist.
        val resolved = Path.of(pinned).takeIf { it.resolve("bin").exists() }?.let { ConnectIqSdk.at(it) }
            ?: return sdk // Not cached: the SDK may be installed at that path later.
        pinnedSdk = pinned to resolved
        return resolved
    }

    /**
     * The last pin resolved, by the path it was asked about.
     *
     * One entry: an IDE window holds one project, and two windows on two pinned SDKs would resolve
     * again on each switch — which costs one file read and is rarer than the folding pass this
     * exists to keep off the disk. Only successful resolutions are kept, so a pin whose SDK is
     * installed later starts working without a refresh.
     */
    @Volatile
    private var pinnedSdk: Pair<String, ConnectIqSdk>? = null

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
     * What that `java` reports as its version, or null when it has not been asked yet.
     *
     * Never starts a process on the caller's thread. The checklist this feeds is built in two
     * places that must not spawn a child and wait on it: the settings page, which the platform
     * builds on the EDT, and the editor banner, which it computes inside a non-blocking read
     * action. So the first call schedules the probe and answers null; the answer is cached against
     * the path it was asked about, and the next checklist carries it.
     *
     * Cached rather than re-read because the checklist is rebuilt whenever an editor tab changes.
     */
    fun javaVersion(): String? {
        val java = java()
        javaVersion?.let { (path, version) -> if (path == java) return version }

        // One probe in flight at a time: the checklist is rebuilt often, and every rebuild before
        // the first answer lands would otherwise start another `java -version`.
        if (probing.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    javaVersion = java to JavaLocator.version(java)
                } finally {
                    probing.set(false)
                }
            }
        }
        return null
    }

    @Volatile
    private var javaVersion: Pair<Path, String?>? = null

    private val probing = AtomicBoolean(false)

    /** Forces a re-read; for the settings dialog and the "reload" action. */
    fun refresh() {
        snapshot = null
        javaVersion = null
        probing.set(false)
        pinnedSdk = null
        current()
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).sdkChanged(sdk)
    }

    private fun current(): Snapshot {
        val stamp = markerStamp()
        snapshot?.let { if (it.stamp == stamp) return it }

        // Whatever the SDK Manager has made current. A project that wants another one pins it, and
        // one the manager has never heard of is added to that project's list from disk — which is
        // what the machine-wide override here used to be for, and it was a second answer to a
        // question that only wants one.
        val resolved = ConnectIqSdk.detect()

        return Snapshot(
            sdk = resolved,
            catalog = resolved?.let { DeviceCatalog(it.devicesRoot) },
            stamp = stamp,
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
