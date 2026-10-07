package com.github.dtretyakov.monkeyc.run

import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * A watch the Linux desktop has already mounted over MTP, which it does on its own.
 *
 * gvfs mounts an MTP device as soon as it is plugged in — its volume monitor answers yes to
 * `should_automount` — and gvfsd-fuse exposes the mount as an ordinary directory under
 * `$XDG_RUNTIME_DIR/gvfs`, named `mtp:host=` and the device's udev `ID_SERIAL`: vendor, model and
 * serial number joined by underscores. That is a watch the plugin can find without `mtp-rs`, and
 * one that `mtp-rs` could not open anyway, because gvfs already holds the device.
 *
 * Finding it is a directory listing. Writing to it is not. gvfs only lets a file on an MTP device
 * be opened for writing when the device supports Android's `SendPartialObject` and `EditObjects`
 * extensions, and a Garmin watch is not an Android phone, so a plain copy through the FUSE path
 * fails with "Operation not supported". What does work is a *push*, which is how the file manager
 * copies, and `gio copy` does one: GIO maps a path under the FUSE directory back to the `mtp://`
 * mount behind it. So the volume is found as a path and written through `gio`.
 */
internal object GvfsMtp {

    /** The prefix gvfsd-fuse gives an MTP mount's directory. */
    const val HOST_PREFIX = "mtp:host="

    /**
     * Where gvfsd-fuse puts its mounts for this user.
     *
     * `XDG_RUNTIME_DIR` when the session sets it, which a desktop session does; otherwise the
     * directory it would have named, worked out from the home directory's owner because the JVM
     * has no public way to ask for the current uid.
     */
    fun roots(
        runtimeDir: String? = System.getenv("XDG_RUNTIME_DIR"),
        home: Path = Path.of(System.getProperty("user.home")),
    ): List<Path> {
        val fromSession = runtimeDir?.takeIf { it.isNotBlank() }?.let { runCatching { Path.of(it, "gvfs") }.getOrNull() }
        val fromUid = runCatching { Files.getAttribute(home, "unix:uid") as Int }.getOrNull()
            ?.let { Path.of("/run/user/$it/gvfs") }
        return listOfNotNull(fromSession, fromUid).distinct()
    }

    /**
     * Each storage of each MTP device mounted under [root], with the host it belongs to.
     *
     * Only the `mtp:host=` entries are opened. The same directory holds the user's SMB and SFTP
     * mounts, and listing one of those reaches across the network — at the end of every device
     * build, and on the IDE regaining focus.
     */
    fun storages(root: Path): List<Pair<Path, String>> =
        children(root)
            .filter { it.name.startsWith(HOST_PREFIX) }
            .flatMap { host -> children(host).map { storage -> storage to host.name.removePrefix(HOST_PREFIX) } }

    /**
     * The model a host names, or null when it does not name one.
     *
     * `Garmin_Venu_2_3348567420` is "Venu 2": the vendor is dropped, being the same on every watch,
     * and so is the serial number. A device with no serial has none to drop, and its last word is
     * part of the model — `Venu_2` must not lose its `2` — so only a word that looks like a serial
     * number is taken off: six characters or more, letters and digits, with at least one digit.
     */
    fun model(host: String): String? {
        val words = decode(host).split('_').filter { it.isNotEmpty() }.toMutableList()
        if (words.firstOrNull().equals(VENDOR, ignoreCase = true)) words.removeAt(0)
        if (words.size > 1 && looksLikeSerial(words.last())) words.removeAt(words.size - 1)
        return words.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /**
     * Whether this host is the device `mtp-rs` reports under [serial].
     *
     * Both read the same USB string descriptor, and the host ends with it, so a watch gvfs holds
     * is not offered a second time as an MTP device that cannot be opened.
     */
    fun holds(host: String, serial: String?): Boolean =
        !serial.isNullOrBlank() && decode(host).endsWith("_$serial", ignoreCase = true)

    /** `gio`, from `PATH` or where every distribution installs it, or null. */
    fun gio(path: String? = System.getenv("PATH")): Path? =
        (path.orEmpty().split(java.io.File.pathSeparatorChar).filter { it.isNotBlank() } + "/usr/bin")
            .mapNotNull { runCatching { Path.of(it).resolve("gio") }.getOrNull() }
            .firstOrNull { it.isRegularFile() && it.isExecutable() }

    /** The commands that put [prg] at [destination], creating the directory it goes in. */
    fun commands(gio: Path, prg: Path, destination: Path): List<List<String>> = listOf(
        listOf(gio.toString(), "mkdir", "-p", destination.parent.toString()),
        // Without `--interactive`, `gio copy` overwrites, which a second install in one session
        // needs: the watch only hides a `.prg` once it has been unplugged.
        listOf(gio.toString(), "copy", prg.toString(), destination.toString()),
    )

    /** Pushes [prg] to [destination] on a gvfs MTP mount, or says why it could not. */
    fun push(prg: Path, destination: Path) {
        val gio = gio() ?: throw IllegalStateException(
            "The watch is mounted by the desktop, and copying onto it needs `gio`, which is not " +
                "installed. Install it (it comes with GLib), or copy the .prg to GARMIN/APPS in the " +
                "file manager.",
        )
        for (command in commands(gio, prg, destination)) {
            // The runner `mtp-rs` uses, for the same reason: both streams drained, timeout applied
            // to the process rather than to a read.
            val outcome = MtpTool.run(command, TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (outcome.exitCode == MtpTool.TIMED_OUT) {
                throw IllegalStateException("Copying onto the watch did not finish.")
            }
            if (outcome.exitCode != 0) {
                val said = outcome.errors.trim().lines().firstOrNull { it.isNotBlank() }
                throw IllegalStateException(said ?: "gio exited with ${outcome.exitCode}.")
            }
        }
    }

    /** `%XX` escapes as gvfs writes them, without treating `+` as a space the way forms do. */
    private fun decode(host: String): String =
        runCatching { URLDecoder.decode(host.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(host)

    private fun looksLikeSerial(word: String): Boolean =
        word.length >= SERIAL_LENGTH && word.all { it.isLetterOrDigit() } && word.any { it.isDigit() }

    private fun children(directory: Path): List<Path> = runCatching {
        Files.list(directory).use { it.filter { child -> child.isDirectory() }.toList() }
    }.getOrDefault(emptyList())

    private const val VENDOR = "Garmin"

    /** Garmin unit ids are ten digits; model numbers run to four (`Edge 1040`). */
    private const val SERIAL_LENGTH = 6

    private const val TIMEOUT_MINUTES = 5L
}
