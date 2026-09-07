package com.github.dtretyakov.monkeyc.project

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.sdk.DeviceCatalog
import com.github.dtretyakov.monkeyc.sdk.JavaLocator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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

    val sdk: ConnectIqSdk?
        get() = current().sdk

    /** Devices the SDK Manager has downloaded. Empty when there is no SDK, or none downloaded yet. */
    fun devices(): List<ConnectIqDevice> = current().catalog?.devices().orEmpty()

    fun device(id: String): ConnectIqDevice? = current().catalog?.byId(id)

    /** The `java` that runs the SDK's jars. */
    fun java(): Path = JavaLocator.resolve(MonkeyCAppSettings.getInstance().javaPath)

    /** Forces a re-read; for the settings dialog and the "reload" action. */
    fun refresh() {
        snapshot = null
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
