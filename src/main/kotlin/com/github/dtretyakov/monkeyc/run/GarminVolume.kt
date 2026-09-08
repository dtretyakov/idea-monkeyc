package com.github.dtretyakov.monkeyc.run

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * A Garmin device mounted as a disk, and the two directories on it that matter.
 *
 * Putting a build on a real watch is the last part of this workflow that nobody automated. Eclipse
 * had a "build for device" wizard; nothing since has, so the documented procedure is still: build,
 * plug in the USB cable, find the volume, copy the `.prg` into `GARMIN/APPS`, unplug. It is four
 * steps of clerical work that the IDE has every fact needed to do.
 */
data class GarminVolume(val root: Path) {

    val name: String get() = root.name.ifEmpty { root.toString() }

    /** Where a sideloaded `.prg` goes. */
    val apps: Path get() = garmin.resolve("APPS")

    /**
     * Where a sideloaded app's settings go.
     *
     * Sideloaded apps have no settings: the app store is what serves them, and a build that never
     * went through the store is not in it. The documented way round that is to copy the settings
     * the simulator wrote into this directory, which is a trick nobody finds by themselves.
     */
    val settings: Path get() = apps.resolve("SETTINGS")

    private val garmin: Path get() = GarminVolume.garminDirectory(root) ?: root.resolve("GARMIN")

    /**
     * Copies a built `.prg` onto the device, with its settings if there are any.
     *
     * Returns where it went. The caller says so, and says the other thing too — that the file will
     * not be there when the device is next plugged in.
     */
    fun install(prg: Path, settingsJson: Path?): Path {
        apps.createDirectories()
        val destination = apps.resolve(prg.name)
        prg.copyTo(destination, overwrite = true)

        if (settingsJson != null && settingsJson.isRegularFile()) {
            settings.createDirectories()
            settingsJson.copyTo(settings.resolve(settingsJson.name), overwrite = true)
        }
        return destination
    }

    companion object {

        /** Every Garmin device mounted right now. Usually none, occasionally one, rarely two. */
        fun mounted(): List<GarminVolume> = candidateRoots()
            .filter { garminDirectory(it) != null }
            .map { GarminVolume(it) }

        /**
         * The `GARMIN` directory on a volume, whatever case it is spelled in.
         *
         * Upper case on every device seen, but the mount can be case-insensitive or not depending
         * on the host, and being wrong here means reporting no watch while one is plugged in.
         */
        internal fun garminDirectory(root: Path): Path? = runCatching {
            Files.list(root).use { entries ->
                entries.filter { it.isDirectory() && it.name.equals("GARMIN", ignoreCase = true) }
                    .findFirst()
                    .orElse(null)
            }
        }.getOrNull()

        /**
         * Where removable volumes appear, per platform.
         *
         * Windows has no mount directory, so the filesystem roots are the candidates: a watch is a
         * drive letter of its own there.
         */
        private fun candidateRoots(): List<Path> {
            val mountPoints = listOf("/Volumes", "/media", "/run/media")
                .map { Path.of(it) }
                .filter { it.isDirectory() }
                .flatMap { children(it) }
                // /media/<user>/<volume> on most Linux distributions, so one level deeper too.
                .flatMap { listOf(it) + children(it) }

            val roots = FileSystems.getDefault().rootDirectories.toList()
            return (mountPoints + roots).distinct()
        }

        private fun children(directory: Path): List<Path> = runCatching {
            Files.list(directory).use { it.filter { child -> child.isDirectory() }.toList() }
        }.getOrDefault(emptyList())
    }
}
