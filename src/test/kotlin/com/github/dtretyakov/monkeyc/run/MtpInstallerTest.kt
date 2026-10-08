package com.github.dtretyakov.monkeyc.run

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Putting the downloaded tool in place, without a network.
 *
 * The archives are made here in the release's two shapes — a tarball holding a directory, a zip
 * holding the files flat, which is what `mtp-rs-cli-v0.9.1` ships — and handed to the installer
 * by a download that copies a local file.
 */
class MtpInstallerTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a tarball's tool is found inside its directory and made executable`(@TempDir temp: Path) {
        val archive = tarXz(temp.resolve("mtp-rs-cli-aarch64-apple-darwin.tar.xz"), "mtp-rs-cli-aarch64-apple-darwin/mtp-rs", "the tool")
        val into = temp.resolve("installed")

        val tool = MtpInstaller.install(asset(archive), into, windows = false, download = copy(archive))

        assertEquals(into.resolve("mtp-rs"), tool)
        assertEquals("the tool", tool.readText())
        assertTrue(tool.isExecutable(), "a tool that cannot be run is not installed")
    }

    @Test
    fun `a zip's tool is found at its top level`(@TempDir temp: Path) {
        val archive = zip(temp.resolve("mtp-rs-cli-x86_64-pc-windows-msvc.zip"), "mtp-rs.exe", "the tool")
        val into = temp.resolve("installed")

        val tool = MtpInstaller.install(asset(archive), into, windows = true, download = copy(archive))

        assertEquals(into.resolve("mtp-rs.exe"), tool)
        assertEquals("the tool", tool.readText())
    }

    @Test
    fun `a download that is not the pinned file is refused, and what was there is kept`(@TempDir temp: Path) {
        val archive = zip(temp.resolve("mtp-rs-cli-x86_64-pc-windows-msvc.zip"), "mtp-rs.exe", "something else")
        val into = temp.resolve("installed").also { it.createDirectories() }
        into.resolve("mtp-rs.exe").writeText("the previous one")
        val pinned = MtpRelease.Asset(archive.fileName.toString(), "0".repeat(64))

        val failure = assertThrows(IllegalStateException::class.java) {
            MtpInstaller.install(pinned, into, windows = true, download = copy(archive))
        }

        assertTrue(failure.message!!.contains("not the file this plugin expects"), failure.message)
        assertEquals("the previous one", into.resolve("mtp-rs.exe").readText())
    }

    @Test
    fun `an archive without the tool in it is refused`(@TempDir temp: Path) {
        val archive = zip(temp.resolve("mtp-rs-cli-x86_64-pc-windows-msvc.zip"), "README.md", "no tool here")
        val into = temp.resolve("installed")

        assertThrows(IllegalStateException::class.java) {
            MtpInstaller.install(asset(archive), into, windows = true, download = copy(archive))
        }
        assertTrue(!into.resolve("mtp-rs.exe").exists())
    }

    /** The asset as the release describes it, with this archive's real checksum. */
    private fun asset(archive: Path) = MtpRelease.Asset(archive.fileName.toString(), MtpInstaller.sha256(archive))

    private fun copy(archive: Path): (String, Path) -> Unit = { _, to -> archive.toFile().copyTo(to.toFile(), overwrite = true) }

    private fun tarXz(archive: Path, name: String, content: String): Path {
        TarArchiveOutputStream(XZCompressorOutputStream(archive.outputStream())).use { tar ->
            val bytes = content.toByteArray()
            val entry = TarArchiveEntry(name).apply {
                size = bytes.size.toLong()
                mode = "755".toInt(8)
            }
            tar.putArchiveEntry(entry)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
        return archive
    }

    private fun zip(archive: Path, name: String, content: String): Path {
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
        return archive
    }
}
