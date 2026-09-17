package com.github.dtretyakov.monkeyc.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
 * `~/.cargo/bin` is searched explicitly because `cargo install mtp-rs-cli` is currently the only
 * way to get the tool, and that directory is on the `PATH` of a shell but not necessarily of an
 * IDE launched from the desktop — which is the case that matters here.
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

        assertEquals(
            cargo,
            MtpLocator.resolve(configured = "", home = temp, path = "/nowhere", windows = false),
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
        // A watch that mounts as a disk needs none of this, so absence is normal.
        assertNull(MtpLocator.resolve(configured = "", home = temp, path = "/nowhere", windows = false))
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

        assertNull(MtpLocator.resolve(configured = "", home = temp, path = "/nowhere", windows = false))
    }

    private companion object {
        /** A NUL, which no platform allows in a path. Spelled as a character to keep it visible. */
        val NOT_A_PATH = Char(0).toString()
    }
}
