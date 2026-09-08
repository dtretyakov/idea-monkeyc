package com.github.dtretyakov.monkeyc.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Finding Garmin's SDK Manager, which is the only way anyone gets an SDK or a device.
 *
 * The file it records itself in is written by another application, so every reading of it is a
 * guess about someone else's format: it can be absent, empty, whitespace, or a path to something
 * that has since been deleted. Each of those has to mean "not installed" rather than a broken
 * button, because the button is the plugin's whole answer to a first run.
 */
class SdkManagerAppTest {

    @Test
    fun `finds the manager where it recorded itself`(@TempDir temp: Path) {
        val app = temp.resolve("SdkManager.app").also { it.createDirectories() }
        location(temp, app.toString())

        assertEquals(app, SdkManagerApp.location(temp))
        assertTrue(SdkManagerApp.isInstalled(temp))
    }

    @Test
    fun `no file means not installed`(@TempDir temp: Path) {
        assertNull(SdkManagerApp.location(temp))
        assertFalse(SdkManagerApp.isInstalled(temp))
        assertNull(SdkManagerApp.startCommand(temp))
    }

    @Test
    fun `a path that no longer exists means not installed`(@TempDir temp: Path) {
        location(temp, temp.resolve("uninstalled.app").toString())

        assertNull(SdkManagerApp.location(temp), "a recorded path is a claim, not a fact")
    }

    @Test
    fun `an empty or blank file means not installed`(@TempDir temp: Path) {
        location(temp, "")
        assertNull(SdkManagerApp.location(temp))

        location(temp, "   \n")
        assertNull(SdkManagerApp.location(temp))
    }

    @Test
    fun `surrounding whitespace is not part of the path`(@TempDir temp: Path) {
        val app = temp.resolve("SdkManager.app").also { it.createDirectories() }
        location(temp, "  ${app}\n")

        assertEquals(app, SdkManagerApp.location(temp))
    }

    @Test
    fun `the start command opens a bundle rather than executing it`(@TempDir temp: Path) {
        val app = temp.resolve("SdkManager.app").also { it.createDirectories() }
        location(temp, app.toString())

        val command = SdkManagerApp.startCommand(temp)

        if (System.getProperty("os.name").startsWith("Mac")) {
            // Running the binary inside a bundle gives it no window server connection — the same
            // trap the simulator has, and the reason this is not just an exec.
            assertEquals(listOf("open", "-a", app.toString()), command)
        } else {
            assertEquals(listOf(app.toString()), command)
        }
    }

    private fun location(dataRoot: Path, text: String) {
        dataRoot.resolve("sdkmanager-location.cfg").writeText(text)
    }
}
