package com.github.dtretyakov.monkeyc.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Putting a build on the watch, which is the last part of this workflow nobody automated.
 *
 * Eclipse had a wizard for it and nothing since has, so the documented procedure is still four
 * steps of clerical work with a USB cable. The volume is an ordinary directory, which is what
 * makes it testable without one.
 */
class GarminVolumeTest {

    @Test
    fun `a volume with a GARMIN directory is a device`(@TempDir temp: Path) {
        temp.resolve("GARMIN").createDirectories()

        assertNotNull(GarminVolume.garminDirectory(temp))
    }

    @Test
    fun `the directory is found whatever case it is spelled in`(@TempDir temp: Path) {
        // Upper case on every device seen, but a case-sensitive host plus a differently-spelled
        // mount would otherwise mean reporting no watch while one is plugged in.
        temp.resolve("Garmin").createDirectories()

        assertNotNull(GarminVolume.garminDirectory(temp))
    }

    @Test
    fun `an ordinary disk is not a device`(@TempDir temp: Path) {
        temp.resolve("Users").createDirectories()

        assertNull(GarminVolume.garminDirectory(temp))
    }

    @Test
    fun `installing puts the prg where the watch looks for it`(@TempDir temp: Path) {
        val volume = deviceAt(temp)
        val prg = temp.resolve("build/App.prg").also {
            it.parent.createDirectories()
            it.writeText("a program")
        }

        val destination = volume.install(prg)

        assertEquals(volume.apps.resolve("App.prg"), destination)
        assertEquals("a program", destination.readText())
    }

    @Test
    fun `nothing but the program is copied`(@TempDir temp: Path) {
        // This used to copy the simulator's settings file into GARMIN/APPS/SETTINGS, which was a
        // guess that looked like a feature. Settings reach an installed app from its store listing,
        // and a sideloaded build has none; the file the device is said to read is a `.SET` whose
        // name matches the program's, which is neither the file the simulator writes nor the name
        // we gave it. Until somebody can hold a watch and find out, copying nothing is the honest
        // behaviour, and this is what would notice it coming back.
        val volume = deviceAt(temp)
        val prg = temp.resolve("build/App.prg").also {
            it.parent.createDirectories()
            it.writeText("a program")
        }
        temp.resolve("build/App-settings.json").writeText("{}")

        volume.install(prg)

        val copied = volume.apps.toFile().walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals(listOf("App.prg"), copied, "only the program should reach the device")
    }

    @Test
    fun `installing over an earlier build replaces it`(@TempDir temp: Path) {
        val volume = deviceAt(temp)
        val prg = temp.resolve("build/App.prg").also {
            it.parent.createDirectories()
            it.writeText("yesterday")
        }
        volume.install(prg)
        prg.writeText("today")

        val destination = volume.install(prg)

        assertEquals("today", destination.readText())
    }

    @Test
    fun `a device with no APPS directory yet gets one`(@TempDir temp: Path) {
        // A watch that has never had a sideloaded app on it.
        val volume = deviceAt(temp)
        val prg = temp.resolve("App.prg").also { it.writeText("a program") }

        assertTrue(volume.install(prg).exists())
    }

    private fun deviceAt(temp: Path): GarminVolume {
        temp.resolve("GARMIN").createDirectories()
        return GarminVolume(temp)
    }
}
