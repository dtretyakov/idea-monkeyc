package com.github.dtretyakov.monkeyc.run

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
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

    private val garmin: Path get() = GarminVolume.garminDirectory(root) ?: root.resolve("GARMIN")

    /**
     * Copies a built `.prg` onto the device, and returns where it went.
     *
     * The `.prg` and nothing else. This used to copy the simulator's `<App>-settings.json` into
     * `GARMIN/APPS/SETTINGS` as well, which was a guess that looked like a feature: settings reach
     * an installed app through Garmin Connect and Garmin Express, and those read them from the
     * app's *store listing* — which a sideloaded build does not have. The forum thread that finally
     * got settings working on a sideloaded app describes a `.SET` file whose name matches the
     * program's including case, which is not the file the simulator writes or the name we gave it.
     *
     * So it is gone until somebody can hold a watch and find out what the device actually reads.
     * Code that appears to configure the app and does not is worse than no code, because it stops
     * people looking for the real answer.
     */
    fun install(prg: Path): Path {
        apps.createDirectories()
        val destination = apps.resolve(prg.name)
        prg.copyTo(destination, overwrite = true)
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
