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
            MtpLocator.resolve(configured = tool.toString(), home = temp, path = null, windows = false),
        )
    }

    @Test
    fun `a configured directory is looked inside`(@TempDir temp: Path) {
        // What people paste when they mean "it is in here".
        val tool = executable(temp.resolve("custom/mtp-rs"))

        assertEquals(
            tool,
            MtpLocator.resolve(configured = tool.parent.toString(), home = temp, path = null, windows = false),
        )
    }

    @Test
    fun `a configured directory on Windows is looked inside for the exe`(@TempDir temp: Path) {
        val tool = executable(temp.resolve("custom/mtp-rs.exe"))

        assertEquals(
            tool,
            MtpLocator.resolve(configured = tool.parent.toString(), home = temp, path = null, windows = true),
        )
    }

    @Test
    fun `a configured path that is not there is not silently replaced`(@TempDir temp: Path) {
        // Falling back would build with something the user did not choose, which is worse than
        // saying the setting is wrong.
        executable(temp.resolve(".cargo/bin/mtp-rs"))

        assertNull(
            MtpLocator.resolve(configured = temp.resolve("gone").toString(), home = temp, path = null, windows = false),
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
                windows = false,
            ),
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
            MtpLocator.resolve(configured = "", home = temp, path = NOT_A_PATH, windows = true),
        )
    }

    @Test
    fun `cargo's directory is found when PATH does not have it`(@TempDir temp: Path) {
        val cargo = executable(temp.resolve(".cargo/bin/mtp-rs"))

        // The system root is the temporary directory too, or a tool Homebrew put on this machine
        // would answer first.
        assertEquals(
            cargo,
            MtpLocator.resolve(configured = "", home = temp, path = "/nowhere", windows = false, system = temp),
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
            MtpLocator.resolve(configured = "", home = temp, path = null, windows = true),
        )
    }

    @Test
    fun `on Windows the bare name is not the tool`(@TempDir temp: Path) {
        executable(temp.resolve(".cargo/bin/mtp-rs"))

        assertNull(MtpLocator.resolve(configured = "", home = temp, path = null, windows = true))
    }

    @Test
    fun `nothing installed is null, not an error`(@TempDir temp: Path) {
        // A watch that mounts as a disk needs none of this, so absence is normal. The system root
        // is the temporary directory too, or a tool Homebrew put on this machine would answer.
        assertNull(MtpLocator.resolve(configured = "", home = temp, path = "/nowhere", windows = false, system = temp))
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
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = "/nowhere", windows = false, system = temp),
        )
    }

    @Test
    fun `a tool put in usr local bin by hand is found`(@TempDir temp: Path) {
        val placed = executable(temp.resolve("usr/local/bin/mtp-rs"))

        assertEquals(
            placed,
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = "/nowhere", windows = false, system = temp),
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
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = "/nowhere", windows = false, system = temp),
        )
    }

    @Test
    fun `the install script's copy wins over an older cargo install`(@TempDir temp: Path) {
        executable(temp.resolve("home/.cargo/bin/mtp-rs.exe"))
        val scripted = executable(temp.resolve("home/.local/bin/mtp-rs.exe"))

        assertEquals(
            scripted,
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = null, windows = true, system = temp),
        )
    }

    @Test
    fun `Windows does not look in Homebrew's directories`(@TempDir temp: Path) {
        executable(temp.resolve("usr/local/bin/mtp-rs.exe"))

        assertNull(MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = null, windows = true, system = temp))
    }

    /**
     * Only macOS has no way onto a current watch without the tool, and the hint has to say so
     * there — and must not tell a Windows user, whose File Explorer shows the watch, that it is
     * the only way.
     */
    @Test
    fun `the hint says what the platform itself can do`() {
        val mac = MtpLocator.installHint("Mac OS X")
        val windows = MtpLocator.installHint("Windows 11")
        val linux = MtpLocator.installHint("Linux")

        assertTrue(mac.contains("Finder will not show it"), mac)
        assertTrue(windows.contains("File Explorer"), windows)
        assertTrue(windows.contains("GARMIN\\APPS"), windows)
        assertTrue(linux.contains("GNOME"), linux)
        assertTrue(linux.contains("KDE"), linux)
        // Every one of them says how to get the tool and where to point at it.
        listOf(mac, windows, linux).forEach {
            assertTrue(it.contains(MtpLocator.INSTALL_URL), it)
            assertTrue(it.contains("Settings | Languages & Frameworks | Monkey C"), it)
        }
    }

    /**
     * One command where Homebrew exists, and nothing that needs a Rust toolchain. The formula is
     * named in full: that is what lets Homebrew load it from a tap nobody has told it to trust.
     */
    @Test
    fun `the hint offers Homebrew where there is Homebrew`() {
        listOf("Mac OS X", "Linux").map { MtpLocator.installHint(it) }.forEach {
            assertTrue(it.contains("`brew install vdavid/tap/mtp-rs`"), it)
            assertFalse(it.contains("cargo"), it)
        }
        val windows = MtpLocator.installHint("Windows 11")
        assertTrue(windows.contains("PowerShell installer"), windows)
        assertFalse(windows.contains("brew"), windows)
    }

    /** Where the project's install script puts it, on every platform. */
    @Test
    fun `the install script's directory is found when PATH does not have it`(@TempDir temp: Path) {
        val scripted = executable(temp.resolve("home/.local/bin/mtp-rs"))

        assertEquals(
            scripted,
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = "/nowhere", windows = false, system = temp),
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
            MtpLocator.resolve(configured = "", home = temp.resolve("home"), path = null, windows = true, system = temp),
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

        assertNull(MtpLocator.resolve(configured = "", home = temp, path = "/nowhere", windows = false, system = temp))
    }

    private companion object {
        /** A NUL, which no platform allows in a path. Spelled as a character to keep it visible. */
        val NOT_A_PATH = Char(0).toString()
    }
}
