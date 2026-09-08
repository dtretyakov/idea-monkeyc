package com.github.dtretyakov.monkeyc.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Finding `mtp-rs` without making it a requirement.
 *
 * `~/.cargo/bin` is searched explicitly because `cargo install mtp-rs-cli` is currently the only
 * way to get the tool, and that directory is on the `PATH` of a shell but not necessarily of an
 * IDE launched from the desktop — which is the case that matters here.
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

        assertEquals(tool, MtpLocator.resolve(configured = tool.toString(), home = temp, path = null))
    }

    @Test
    fun `a configured directory is looked inside`(@TempDir temp: Path) {
        // What people paste when they mean "it is in here".
        val tool = executable(temp.resolve("custom/mtp-rs"))

        assertEquals(tool, MtpLocator.resolve(configured = tool.parent.toString(), home = temp, path = null))
    }

    @Test
    fun `a configured path that is not there is not silently replaced`(@TempDir temp: Path) {
        // Falling back would build with something the user did not choose, which is worse than
        // saying the setting is wrong.
        executable(temp.resolve(".cargo/bin/mtp-rs"))

        assertNull(MtpLocator.resolve(configured = temp.resolve("gone").toString(), home = temp, path = null))
    }

    @Test
    fun `PATH is searched before cargo's directory`(@TempDir temp: Path) {
        val onPath = executable(temp.resolve("usr/local/bin/mtp-rs"))
        executable(temp.resolve(".cargo/bin/mtp-rs"))

        assertEquals(onPath, MtpLocator.resolve(configured = "", home = temp, path = onPath.parent.toString()))
    }

    @Test
    fun `cargo's directory is found when PATH does not have it`(@TempDir temp: Path) {
        val cargo = executable(temp.resolve(".cargo/bin/mtp-rs"))

        assertEquals(cargo, MtpLocator.resolve(configured = "", home = temp, path = "/nowhere"))
    }

    @Test
    fun `nothing installed is null, not an error`(@TempDir temp: Path) {
        // A watch that mounts as a disk needs none of this, so absence is normal.
        assertNull(MtpLocator.resolve(configured = "", home = temp, path = "/nowhere"))
    }

    @Test
    fun `a file that is not executable does not count`(@TempDir temp: Path) {
        val notExecutable = temp.resolve(".cargo/bin/mtp-rs")
        notExecutable.parent.createDirectories()
        notExecutable.writeText("text")

        assertNull(MtpLocator.resolve(configured = "", home = temp, path = "/nowhere"))
    }
}
