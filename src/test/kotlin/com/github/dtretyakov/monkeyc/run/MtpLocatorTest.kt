package com.github.dtretyakov.monkeyc.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Finding `mtp-rs` without making it a requirement.
 *
 * Each way of installing the tool has a directory of its own — `~/.cargo/bin`, `~/.local/bin`,
 * Homebrew's — and each is searched explicitly, because it is on the `PATH` of a shell but not
 * necessarily of an IDE launched from the desktop, which is the case that matters here.
 *
 * Every test names the platform it is about rather than inheriting the one it runs on: the tool is
 * `mtp-rs.exe` on Windows and `mtp-rs` elsewhere, and a suite that only asks about the platform
 * under it is how the missing `.exe` went unnoticed.
 */
class MtpLocatorTest {

    private fun executable(at: Path): Path = at.also {
        it.parent.createDirectories()
        it.writeText("#!/bin/sh\n")
        it.toFile().setExecutable(true)
    }

    @Test
    fun `a configured executable is used as given`(@TempDir temp: Path) {
        val tool = executable(temp.resolve("custom/mtp-rs"))

        assertEquals(
            tool,
            MtpLocator.resolve(configured = tool.toString(), home = temp, path = null, windows = false, managed = null),
        )
    }

    @Test
    fun `a configured directory is looked inside`(@TempDir temp: Path) {
        // What people paste when they mean "it is in here".
        val tool = executable(temp.resolve("custom/mtp-rs"))

        assertEquals(
            tool,
            MtpLocator.resolve(configured = tool.parent.toString(), home = temp, path = null, windows = false, managed = null),
        )
    }

    @Test
    fun `a configured directory on Windows is looked inside for the exe`(@TempDir temp: Path) {
        val tool = executable(temp.resolve("custom/mtp-rs.exe"))

        assertEquals(
            tool,
            MtpLocator.resolve(configured = tool.parent.toString(), home = temp, path = null, windows = true, managed = null),
        )
    }

    @Test
    fun `a configured path that is not there is not silently replaced`(@TempDir temp: Path) {
        // Falling back would build with something the user did not choose, which is worse than
        // saying the setting is wrong.
        executable(temp.resolve(".cargo/bin/mtp-rs"))

        assertNull(
            MtpLocator.resolve(configured = temp.resolve("gone").toString(), home = temp, path = null, windows = false, managed = null),
        )
    }

    @Test
    fun `PATH is searched before cargo's directory`(@TempDir temp: Path) {
        val onPath = executable(temp.resolve("usr/local/bin/mtp-rs"))
        executable(temp.resolve(".cargo/bin/mtp-rs"))

        assertEquals(
            onPath,
            MtpLocator.resolve(
                configured = "",
                home = temp,
                path = onPath.parent.toString(),
                windows = false, managed = null),
        )
    }

    /**
     * A `PATH` entry `Path.of` refuses is skipped rather than thrown out of the device lookup.
     *
     * A NUL, because it is illegal in a path on every platform and so exercises the guard wherever
     * this runs. The entry that provoked it was Windows-shaped — `"C:\tools"`, quotes and all,
     * which an installer will write into `PATH` without complaint — but [MtpLocator] splits on the
     * *host's* separator, so on a Linux runner that one is two harmless POSIX paths and the guard
     * is never reached.
     */
    @Test
    fun `a PATH entry that is not a valid path is skipped`(@TempDir temp: Path) {
        val cargo = executable(temp.resolve(".cargo/bin/mtp-rs.exe"))

        // Asserted rather than assumed. Were some platform to accept it, this test would go on
        // passing while covering nothing at all — which is the trap it exists to get out of.
        assertThrows(InvalidPathException::class.java) { Path.of(NOT_A_PATH) }

        assertEquals(
            cargo,
            MtpLocator.resolve(configured = "", home = temp, path = NOT_A_PATH, windows = true, managed = null),
        )
    }

    @Test
    fun `cargo's directory is found when PATH does not have it`(@TempDir temp: Path) {
        val cargo = executable(temp.resolve(".cargo/bin/mtp-rs"))

        // The system root is the temporary directory too, or a tool Homebrew put on this machine
        // would answer first.
        assertEquals(
            cargo,
            MtpLocator.resolve(configured = "", home = temp, path = "/nowhere", windows = false, system = temp, managed = null),
        )
    }

    /**
     * The whole bug: `cargo install mtp-rs-cli` writes `mtp-rs.exe`, the locator asked for
     * `mtp-rs`, and installing on an MTP watch was impossible on Windows with the tool in place.
     */
    @Test
    fun `on Windows the tool is the exe cargo installs`(@TempDir temp: Path) {
        val cargo = executable(temp.resolve(".cargo/bin/mtp-rs.exe"))

        assertEquals(
            cargo,
            MtpLocator.resolve(configured = "", home = temp, path = null, windows = true, managed = null),
        )
    }

    @Test
    fun `on Windows the bare name is not the tool`(@TempDir temp: Path) {
        executable(temp.resolve(".cargo/bin/mtp-rs"))

        assertNull(MtpLocator.resolve(configured = "", home = temp, path = null, windows = true, managed = null))
    }

    @Test
    fun `nothing installed is null, not an error`(@TempDir temp: Path) {
        // A watch that mounts as a disk needs none of this, so absence is normal. The system root
        // is the temporary directory too, or a tool Homebrew put on this machine would answer.
        assertNull(MtpLocator.resolve(configured = "", home = temp, path = "/nowhere", windows = false, system = temp, managed = null))
    }

    /**
     * An IDE started from the Dock has neither of Homebrew's directories on its `PATH`, so a tool
     * installed with `brew` was in plain sight and reported missing.
     */
    @Test
    fun `Homebrew's directory is found when PATH does not have it`(@TempDir temp: Path) {
        val brewed = executable(temp.resolve("opt/homebrew/bin/mtp-rs"))

        assertEquals(
            brewed,
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = "/nowhere", windows = false, system = temp, managed = null),
        )
    }

    @Test
    fun `a tool put in usr local bin by hand is found`(@TempDir temp: Path) {
        val placed = executable(temp.resolve("usr/local/bin/mtp-rs"))

        assertEquals(
            placed,
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = "/nowhere", windows = false, system = temp, managed = null),
        )
    }

    /**
     * Found on a real machine: `mtp-rs` 0.3.0 left in `~/.cargo/bin` from the months when cargo
     * was the only way, and 0.9.1 just installed with Homebrew as the hint now says. The plugin
     * took the old one.
     */
    @Test
    fun `Homebrew's copy wins over an older cargo install`(@TempDir temp: Path) {
        executable(temp.resolve("home/.cargo/bin/mtp-rs"))
        val brewed = executable(temp.resolve("opt/homebrew/bin/mtp-rs"))

        assertEquals(
            brewed,
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = "/nowhere", windows = false, system = temp, managed = null),
        )
    }

    @Test
    fun `the install script's copy wins over an older cargo install`(@TempDir temp: Path) {
        executable(temp.resolve("home/.cargo/bin/mtp-rs.exe"))
        val scripted = executable(temp.resolve("home/.local/bin/mtp-rs.exe"))

        assertEquals(
            scripted,
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = null, windows = true, system = temp, managed = null),
        )
    }

    @Test
    fun `Windows does not look in Homebrew's directories`(@TempDir temp: Path) {
        executable(temp.resolve("usr/local/bin/mtp-rs.exe"))

        assertNull(MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = null, windows = true, system = temp, managed = null))
    }

    /**
     * The lead says what the platform itself can do, and every hint offers the same remedy: the
     * plugin installs the tool when asked. Nothing sends anyone to copy a file by hand, and nothing
     * asks for a Rust toolchain.
     */
    @Test
    fun `the hint says what the platform can do, and offers the install`() {
        val mac = MtpLocator.installHint("Mac OS X")
        val windows = MtpLocator.installHint("Windows 11")
        val linux = MtpLocator.installHint("Linux")

        assertTrue(mac.contains("Finder will not show it"), mac)
        assertTrue(linux.contains("GNOME"), linux)
        assertTrue(linux.contains("KDE"), linux)
        listOf(mac, windows, linux).forEach {
            assertTrue(it.contains("Install mtp-rs ${MtpRelease.VERSION}"), it)
            assertTrue(it.contains(MtpLocator.INSTALL_URL), it)
            assertFalse(it.contains("cargo"), it)
            assertFalse(it.contains("by hand"), it)
            assertFalse(it.contains("File Explorer"), it)
        }
    }

    @Test
    fun `the plugin's own copy is used before anything on PATH`(@TempDir temp: Path) {
        val managed = executable(temp.resolve("managed/mtp-rs"))
        val onPath = executable(temp.resolve("bin/mtp-rs"))

        assertEquals(
            managed,
            MtpLocator.resolve(configured = "", home = temp, path = onPath.parent.toString(), windows = false, system = temp, managed = managed),
        )
    }

    @Test
    fun `a path set in the settings still wins over the plugin's own copy`(@TempDir temp: Path) {
        val managed = executable(temp.resolve("managed/mtp-rs"))
        val chosen = executable(temp.resolve("chosen/mtp-rs"))

        assertEquals(
            chosen,
            MtpLocator.resolve(configured = chosen.toString(), home = temp, path = null, windows = false, system = temp, managed = managed),
        )
    }

    @Test
    fun `the plugin's own copy that is not there yet is skipped`(@TempDir temp: Path) {
        val brewed = executable(temp.resolve("opt/homebrew/bin/mtp-rs"))

        assertEquals(
            brewed,
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = "/nowhere", windows = false, system = temp, managed = temp.resolve("managed/mtp-rs")),
        )
    }

    /** Where a current watch is unreachable without the tool, and where the desktop does it instead. */
    @Test
    fun `the tool is needed on macOS and Windows, and on Linux only without gvfs`() {
        assertTrue(MtpLocator.needed("Mac OS X") { true })
        assertTrue(MtpLocator.needed("Windows 11") { true })
        assertFalse(MtpLocator.needed("Linux") { true })
        assertTrue(MtpLocator.needed("Linux") { false })
    }

    /** Where the project's install script puts it, on every platform. */
    @Test
    fun `the install script's directory is found when PATH does not have it`(@TempDir temp: Path) {
        val scripted = executable(temp.resolve("home/.local/bin/mtp-rs"))

        assertEquals(
            scripted,
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = "/nowhere", windows = false, system = temp, managed = null),
        )
    }

    /**
     * The PowerShell installer writes to `~/.local/bin` and adds it to the user's `PATH` — which an
     * IDE already running never sees, so the next build after installing would not find it.
     */
    @Test
    fun `on Windows the install script's directory is found too`(@TempDir temp: Path) {
        val scripted = executable(temp.resolve("home/.local/bin/mtp-rs.exe"))

        assertEquals(
            scripted,
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = null, windows = true, system = temp, managed = null),
        )
    }

    /**
     * POSIX only: Windows has no executable bit, so there is nothing here to assert. The
     * extension is what decides there, which `on Windows the bare name is not the tool` covers.
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a file that is not executable does not count`(@TempDir temp: Path) {
        val notExecutable = temp.resolve(".cargo/bin/mtp-rs")
        notExecutable.parent.createDirectories()
        notExecutable.writeText("text")

        assertNull(MtpLocator.resolve(configured = "", home = temp, path = "/nowhere", windows = false, system = temp, managed = null))
    }

    private companion object {
        /** A NUL, which no platform allows in a path. Spelled as a character to keep it visible. */
        val NOT_A_PATH = Char(0).toString()
    }
}
