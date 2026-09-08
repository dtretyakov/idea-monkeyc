package com.github.dtretyakov.monkeyc.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * The state the simulator keeps between runs, and getting rid of it.
 *
 * This exists because of a confusion nobody wrote down: a value in `properties.xml` is a default,
 * applied when the property does not yet exist, and after one run it exists. Editing the default
 * and running again shows the old value, which reads as the edit not taking.
 *
 * The tests point `java.io.tmpdir` at a temporary directory, which is where the real code looks —
 * so what is exercised is the real path logic and not a parallel copy of it.
 */
class SimulatorStorageTest {

    @Test
    fun `a machine where the simulator never ran has no device`(@TempDir temp: Path) {
        withTemporaryDirectory(temp) {
            assertNull(SimulatorStorage.deviceRoot())
            assertFalse(SimulatorStorage.hasPersistedData())
        }
    }

    @Test
    fun `an empty device has nothing to clear`(@TempDir temp: Path) {
        device(temp)

        withTemporaryDirectory(temp) {
            assertNotNull(SimulatorStorage.deviceRoot())
            assertFalse(SimulatorStorage.hasPersistedData(), "the directories exist but hold nothing")
        }
    }

    @Test
    fun `stored properties and settings are found, and go`(@TempDir temp: Path) {
        val garmin = device(temp)
        val stored = garmin.resolve("APPS/DATA/MEDIA/OBJSTORE/TEST_FENIX7_APP/TEST.SEN")
        stored.parent.createDirectories()
        stored.writeText("a persisted property")
        garmin.resolve("APPS/SETTINGS/App-settings.json").writeText("{}")

        withTemporaryDirectory(temp) {
            assertTrue(SimulatorStorage.hasPersistedData())

            assertEquals(2, SimulatorStorage.clearPersistedData())

            assertFalse(stored.exists())
            assertFalse(SimulatorStorage.hasPersistedData())
        }
    }

    @Test
    fun `the installed programs are left alone`(@TempDir temp: Path) {
        // Deleting the whole directory is the folklore remedy on the forums, and it also throws
        // away the apps, which then have to be pushed again.
        val garmin = device(temp)
        val program = garmin.resolve("APPS/App.prg").also { it.writeText("a program") }
        garmin.resolve("APPS/DATA/state.bin").writeText("state")

        withTemporaryDirectory(temp) { SimulatorStorage.clearPersistedData() }

        assertTrue(program.exists(), "clearing the stored data must not uninstall the app")
    }

    /** The directory tree the simulator writes, as far as this code cares about it. */
    private fun device(temp: Path): Path {
        val garmin = temp.resolve("com.garmin.connectiq/GARMIN")
        garmin.resolve("APPS/DATA").createDirectories()
        garmin.resolve("APPS/SETTINGS").createDirectories()
        return garmin
    }

    private fun <T> withTemporaryDirectory(temp: Path, block: () -> T): T {
        val previous = System.getProperty("java.io.tmpdir")
        System.setProperty("java.io.tmpdir", temp.toString())
        try {
            return block()
        } finally {
            previous?.let { System.setProperty("java.io.tmpdir", it) }
        }
    }
}
