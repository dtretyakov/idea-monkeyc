package com.github.dtretyakov.monkeyc.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.name

class ProjectLayoutTest {

    @Test
    fun `defaults to monkey jungle when nothing is configured`(@TempDir root: Path) {
        assertEquals(listOf(root.resolve("monkey.jungle")), ProjectLayout.jungleFiles(root, null))
        assertEquals(listOf(root.resolve("monkey.jungle")), ProjectLayout.jungleFiles(root, "  "))
    }

    @Test
    fun `resolves configured jungles against the root and keeps absolute ones`(@TempDir root: Path) {
        val absolute = root.resolve("elsewhere/shared.jungle")
        val files = ProjectLayout.jungleFiles(root, "monkey.jungle; $absolute")

        assertEquals(listOf(root.resolve("monkey.jungle"), absolute), files)
        assertTrue(files.all { it.isAbsolute }, "the language server ignores relative jungle paths")
    }

    @Test
    fun `picks up barrels jungle when it exists`(@TempDir root: Path) {
        root.resolve("barrels.jungle").createFile()

        assertEquals(
            listOf(root.resolve("monkey.jungle"), root.resolve("barrels.jungle")),
            ProjectLayout.jungleFiles(root, null),
        )
    }

    @Test
    fun `does not add barrels jungle twice`(@TempDir root: Path) {
        root.resolve("barrels.jungle").createFile()

        assertEquals(
            listOf(root.resolve("monkey.jungle"), root.resolve("barrels.jungle")),
            ProjectLayout.jungleFiles(root, "monkey.jungle;barrels.jungle"),
        )
    }

    @Test
    fun `artifact name keeps only what a prg name may hold`() {
        assertEquals("MyWatchFace", ProjectLayout.artifactName("My Watch Face!"))
        assertEquals("connectiq2", ProjectLayout.artifactName("connect-iq 2"))
        assertEquals("my_app", ProjectLayout.artifactName("my_app"))
    }

    @Test
    fun `test builds are named per device so they do not overwrite the app`(@TempDir root: Path) {
        assertEquals("test_fenix6_App.prg", ProjectLayout.testPrg(root, "App", "fenix6").name)
        assertEquals("App.prg", ProjectLayout.appPrg(root, "App").name)
    }

    @Test
    fun `debug symbols sit next to the prg`(@TempDir root: Path) {
        val prg = ProjectLayout.appPrg(root, "App")
        assertEquals(prg.parent.resolve("App.prg.debug.xml"), ProjectLayout.debugXml(prg))
    }
}
