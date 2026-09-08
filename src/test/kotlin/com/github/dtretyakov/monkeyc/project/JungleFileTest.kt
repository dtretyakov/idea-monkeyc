package com.github.dtretyakov.monkeyc.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Which manifest a jungle names.
 *
 * This exists because the plugin assumed the answer was always `manifest.xml`, and a real project
 * proved otherwise: one source tree, `monkey.jungle` naming `manifest.xml` and
 * `monkey-api51.jungle` naming `manifest-api51.xml`, with different products and different minimum
 * API levels. Reading the wrong one makes every device-derived answer wrong while looking right.
 */
class JungleFileTest {

    @Test
    fun `a jungle that names a manifest resolves it beside itself`(@TempDir temp: Path) {
        val jungle = jungle(temp, "monkey-api51.jungle", "project.manifest = manifest-api51.xml")

        assertEquals(temp.resolve("manifest-api51.xml"), JungleFile.manifest(jungle))
    }

    @Test
    fun `a jungle that names none says so`(@TempDir temp: Path) {
        // The common case, and it means the default rather than an error.
        val jungle = jungle(temp, "monkey.jungle", "base.sourcePath = source")

        assertNull(JungleFile.manifest(jungle))
    }

    @Test
    fun `an absolute path is left alone`(@TempDir temp: Path) {
        val elsewhere = temp.resolve("shared/manifest.xml").toAbsolutePath()
        val jungle = jungle(temp, "monkey.jungle", "project.manifest = $elsewhere")

        assertEquals(elsewhere.normalize(), JungleFile.manifest(jungle))
    }

    @Test
    fun `a relative path climbs out of the jungle's directory`(@TempDir temp: Path) {
        val jungle = jungle(temp, "build/monkey.jungle", "project.manifest = ../manifest.xml")

        assertEquals(temp.resolve("manifest.xml").normalize(), JungleFile.manifest(jungle))
    }

    @Test
    fun `a variable reference is declined rather than guessed at`(@TempDir temp: Path) {
        // Resolving `$(...)` means resolving the whole jungle, which is the compiler's job.
        val jungle = jungle(temp, "monkey.jungle", "project.manifest = \$(base.dir)/manifest.xml")

        assertNull(JungleFile.manifest(jungle))
    }

    @Test
    fun `comments and continuations are handled as the compiler handles them`(@TempDir temp: Path) {
        val jungle = jungle(
            temp,
            "monkey.jungle",
            """
            # project.manifest = commented-out.xml
            project.manifest = \
                manifest-api51.xml
            """.trimIndent(),
        )

        assertEquals(temp.resolve("manifest-api51.xml"), JungleFile.manifest(jungle))
    }

    @Test
    fun `the last assignment wins, as in any jungle`(@TempDir temp: Path) {
        val jungle = jungle(
            temp,
            "monkey.jungle",
            "project.manifest = first.xml\nproject.manifest = second.xml",
        )

        assertEquals(temp.resolve("second.xml"), JungleFile.manifest(jungle))
    }

    @Test
    fun `a jungle that is not there is not an error`(@TempDir temp: Path) {
        assertNull(JungleFile.manifest(temp.resolve("absent.jungle")))
        assertEquals(emptyList<Pair<String, String>>(), JungleFile.assignments(temp.resolve("absent.jungle")))
    }

    private fun jungle(temp: Path, name: String, text: String): Path =
        temp.resolve(name).also {
            it.parent.toFile().mkdirs()
            it.writeText(text)
        }
}
