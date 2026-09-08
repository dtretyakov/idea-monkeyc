package com.github.dtretyakov.monkeyc.run

import org.junit.jupiter.api.Test

/**
 * Scanning this machine's mount points, to be sure the scan is quick and quiet.
 *
 * It runs wherever the tests run and asserts nothing about the result — there is usually no watch
 * plugged in. What it does establish is that walking `/Volumes`, `/media` and the filesystem roots
 * on a real machine neither throws nor hangs, which is the failure that would matter: this runs at
 * the end of every device build.
 */
class GarminVolumeScanTest {

    @Test
    fun `scanning this machine finishes quickly and without throwing`() {
        val started = System.currentTimeMillis()

        val found = GarminVolume.mounted()

        val elapsed = System.currentTimeMillis() - started
        println("Garmin volumes: ${found.map { it.name }} (${elapsed}ms)")
        assert(elapsed < 5_000) { "scanning mount points took ${elapsed}ms" }
    }
}
