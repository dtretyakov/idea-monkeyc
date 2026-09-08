package com.github.dtretyakov.monkeyc.run

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * The virtual device the simulator keeps on disk, and the state on it that outlives a run.
 *
 * The simulator writes a whole fake watch filesystem into the system temporary directory —
 * `GARMIN/APPS` for the programs, `APPS/SETTINGS` for their settings, `APPS/DATA` for everything
 * `Application.Storage` and `Application.Properties` persist. That directory survives restarting
 * the simulator, restarting the IDE and rebuilding the app.
 *
 * Which is the answer to a confusion the forums have never quite resolved: a value in
 * `properties.xml` is a *default*, applied when the property does not yet exist. Once the app has
 * run, it exists. Editing the default and running again shows the old value, the developer
 * concludes the edit did not take, and nothing anywhere says otherwise. Clearing this is what
 * makes the new default apply — and knowing that is most of the fix.
 */
object SimulatorStorage {

    /** Where the simulator keeps the virtual device, or null when it has never run here. */
    fun deviceRoot(): Path? = temporaryDirectory()
        ?.resolve(SIMULATOR_DIRECTORY)
        ?.resolve("GARMIN")
        ?.takeIf { it.isDirectory() }

    /** Persisted `Application.Storage` and `Application.Properties`, per app. */
    fun persistedData(): Path? = deviceRoot()?.resolve("APPS/DATA")?.takeIf { it.isDirectory() }

    /** Settings written for a sideloaded app. */
    fun settings(): Path? = deviceRoot()?.resolve("APPS/SETTINGS")?.takeIf { it.isDirectory() }

    /**
     * Whether there is anything stored that could be masking an edited default.
     *
     * Asked before offering to clear it, so the offer is not made to someone with nothing to
     * clear.
     */
    fun hasPersistedData(): Boolean = persistedData()?.let { anyFileUnder(it) } ?: false

    /**
     * Deletes the persisted data and settings, leaving the installed programs alone.
     *
     * Deliberately narrow. Deleting the whole directory is the folklore remedy on the forums and
     * it also throws away the apps, which then have to be pushed again; this removes only the
     * state that makes a rebuilt app behave like the old one. Returns how many files went.
     */
    fun clearPersistedData(): Int {
        var removed = 0
        listOfNotNull(persistedData(), settings()).forEach { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    if (path == root) return@forEach
                    // Directories go too, but only once they are empty, which the reverse order
                    // guarantees. Counting files alone keeps the number meaningful.
                    val wasFile = !path.isDirectory()
                    if (runCatching { path.deleteIfExists() }.getOrDefault(false) && wasFile) removed++
                }
            }
        }
        return removed
    }

    private fun anyFileUnder(root: Path): Boolean = runCatching {
        Files.walk(root).use { paths -> paths.anyMatch { !it.isDirectory() } }
    }.getOrDefault(false)

    /**
     * The system temporary directory the simulator writes into.
     *
     * `java.io.tmpdir` is the same directory the simulator uses on all three platforms — on macOS
     * the per-user one under `/var/folders`, on Windows `%LOCALAPPDATA%\Temp`, on Linux `/tmp`.
     */
    private fun temporaryDirectory(): Path? =
        System.getProperty("java.io.tmpdir")?.let { runCatching { Path.of(it) }.getOrNull() }

    private const val SIMULATOR_DIRECTORY = "com.garmin.connectiq"

    /** For a message that names the place rather than making the user find it. */
    fun describe(): String = deviceRoot()?.parent?.name ?: SIMULATOR_DIRECTORY
}
