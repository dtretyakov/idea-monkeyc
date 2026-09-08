package com.github.dtretyakov.monkeyc.sdk

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Garmin's SDK Manager: the separate application that installs SDKs and downloads devices.
 *
 * Nothing the plugin needs can be obtained without it — not the compiler, not the simulator, not a
 * single device — and Garmin offers no other way to get them. So the plugin's advice has to end at
 * this application, and the difference between saying "get them with the SDK Manager" and opening
 * it is most of what a first run feels like.
 *
 * Where it lives is not guessed: the manager records its own location next to `current-sdk.cfg`,
 * in the same directory the plugin already reads the selected SDK and the device catalogue from.
 */
object SdkManagerApp {

    /** Garmin's download page, which offers the manager for Windows, macOS and Linux. */
    const val DOWNLOAD_URL: String = "https://developer.garmin.com/connect-iq/sdk/"

    private const val LOCATION_FILE = "sdkmanager-location.cfg"

    /** Where the manager is installed, or null when it has never run on this machine. */
    fun location(dataRoot: Path = ConnectIqSdk.dataRoot()): Path? =
        dataRoot.resolve(LOCATION_FILE)
            .takeIf { it.exists() }
            ?.runCatching { readText().trim() }
            ?.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(it) }
            ?.takeIf { it.exists() }

    fun isInstalled(dataRoot: Path = ConnectIqSdk.dataRoot()): Boolean = location(dataRoot) != null

    /**
     * How to start it, or null when it is not installed.
     *
     * On macOS the recorded path is an application bundle, which has to be opened rather than
     * executed — running the binary inside gives it no window server connection, the same trap the
     * simulator has.
     */
    fun startCommand(dataRoot: Path = ConnectIqSdk.dataRoot()): List<String>? {
        val app = location(dataRoot) ?: return null
        return when {
            System.getProperty("os.name").startsWith("Mac") -> listOf("open", "-a", app.toString())
            else -> listOf(app.toString())
        }
    }
}
