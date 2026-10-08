package com.github.dtretyakov.monkeyc.run

import com.intellij.openapi.application.PathManager
import com.intellij.util.io.Decompressor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Puts the pinned `mtp-rs` where the plugin will find it, from its GitHub release.
 *
 * Only ever on the user's say-so: this is the one place the plugin downloads anything, and it does
 * it because they clicked a button that says so. What it downloads is fixed by [MtpRelease], and
 * anything that does not hash to the checksum written there is thrown away unopened.
 *
 * The directory is JetBrains' common data directory rather than this IDE's own: that one changes
 * with every IDE version, and a tool installed once should not have to be installed again after
 * an update, nor once per IDE on the same machine.
 */
object MtpInstaller {

    /** Where the plugin keeps its own copy. */
    fun directory(): Path = PathManager.getCommonDataPath().resolve("monkeyc").resolve("mtp-rs")

    /** The plugin's own copy of the tool, whether or not it is there. */
    fun managedTool(windows: Boolean = System.getProperty("os.name").startsWith("Windows")): Path =
        directory().resolve(MtpTool.executable(windows))

    /**
     * Downloads [asset], checks it, and puts the tool in [into]. Returns the tool's path.
     *
     * [download] fetches a URL to a file; it is a parameter so the rest can be tested without a
     * network. Everything happens in a temporary directory, and the tool is moved into place only
     * once it has been checked and found, so a failure at any step leaves whatever was installed
     * before exactly as it was.
     */
    fun install(
        asset: MtpRelease.Asset,
        into: Path,
        windows: Boolean,
        download: (url: String, to: Path) -> Unit,
    ): Path {
        val work = Files.createTempDirectory("mtp-rs-")
        try {
            val archive = work.resolve(asset.file)
            download(asset.url, archive)

            val actual = sha256(archive)
            check(actual.equals(asset.sha256, ignoreCase = true)) {
                "The download of ${asset.file} is not the file this plugin expects (SHA-256 $actual), " +
                    "so it was not installed."
            }

            val unpacked = work.resolve("unpacked")
            if (asset.isZip) Decompressor.Zip(archive).extract(unpacked) else Decompressor.Tar(archive).extract(unpacked)

            // The archives differ in shape — the tarballs hold a directory, the zip does not — so
            // the tool is looked for by name rather than by a path that is true of only some.
            val name = MtpTool.executable(windows)
            val unpackedTool = Files.walk(unpacked).use { paths ->
                paths.filter { it.name == name && it.isRegularFile() }.findFirst().orElse(null)
            } ?: error("${asset.file} has no $name in it.")

            into.createDirectories()
            clearAside(into, name)
            // A name of its own, so a second install running alongside cannot take this one's file.
            val staged = Files.createTempFile(into, name, ".part")
            Files.copy(unpackedTool, staged, StandardCopyOption.REPLACE_EXISTING)
            if (!windows) staged.toFile().setExecutable(true, false)

            // The previous copy is moved aside rather than overwritten. Windows will not replace an
            // executable that is running — and the watch list may be running this one at any
            // moment — but it will rename it; the old file goes when it is no longer in use.
            val tool = into.resolve(name)
            if (Files.exists(tool)) {
                Files.move(tool, Files.createTempFile(into, name, ASIDE), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(staged, tool, StandardCopyOption.ATOMIC_MOVE)
            clearAside(into, name)
            return tool
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    /**
     * Deletes what earlier installs left: copies moved aside, where nothing runs them any more, and
     * staging files from one that was cut short. Installs run one at a time, so none is in use.
     */
    private fun clearAside(directory: Path, name: String) {
        Files.list(directory).use { entries ->
            entries.filter { it.name.startsWith(name) && (it.name.endsWith(ASIDE) || it.name.endsWith(".part")) }
                .forEach { runCatching { Files.delete(it) } }
        }
    }

    private const val ASIDE = ".old"

    internal fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val BUFFER = 64 * 1024
}
