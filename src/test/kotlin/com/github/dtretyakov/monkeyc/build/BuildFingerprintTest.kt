package com.github.dtretyakov.monkeyc.build

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText

/**
 * The rules for skipping the compiler.
 *
 * Every case here is one where getting the answer wrong means running yesterday's binary and
 * believing it is today's, which is the most expensive kind of wrong this plugin can be.
 */
class BuildFingerprintTest {

    private val arguments = listOf("java", "-o", "bin/App.prg", "-d", "fenix7_sim")

    @Test
    fun `a build nobody recorded is not up to date`(@TempDir temp: Path) {
        val spec = project(temp)
        write(spec.output, "prg")

        assertFalse(BuildFingerprint.isUpToDate(spec, arguments), "there is no stamp to trust")
    }

    @Test
    fun `a recorded build with untouched sources is up to date`(@TempDir temp: Path) {
        val spec = project(temp)
        write(spec.root.resolve("source/App.mc"), "class App {}")
        write(spec.output, "prg")
        BuildFingerprint.record(spec, arguments)

        assertTrue(BuildFingerprint.isUpToDate(spec, arguments))
    }

    @Test
    fun `a source touched after the build is not up to date`(@TempDir temp: Path) {
        val spec = project(temp)
        val source = spec.root.resolve("source/App.mc")
        write(source, "class App {}")
        write(spec.output, "prg")
        BuildFingerprint.record(spec, arguments)

        source.setLastModifiedTime(FileTime.fromMillis(System.currentTimeMillis() + 10_000))

        assertFalse(BuildFingerprint.isUpToDate(spec, arguments), "an edited source must rebuild")
    }

    @Test
    fun `different arguments are a different build`(@TempDir temp: Path) {
        val spec = project(temp)
        write(spec.output, "prg")
        BuildFingerprint.record(spec, arguments)

        assertFalse(
            BuildFingerprint.isUpToDate(spec, arguments + listOf("-l", "3")),
            "the same sources compiled with other flags are a different binary",
        )
    }

    @Test
    fun `output written by the build itself does not make it dirty`(@TempDir temp: Path) {
        val spec = project(temp)
        write(spec.root.resolve("source/App.mc"), "class App {}")
        write(spec.output, "prg")
        BuildFingerprint.record(spec, arguments)

        // The compiler leaves generated resources in bin/ after writing the .prg, so bin/ is
        // always newer than the output. Counting it as input would mean never skipping a build.
        write(spec.root.resolve("bin/gen/resources.xml"), "<x/>")
        spec.root.resolve("bin/gen/resources.xml")
            .setLastModifiedTime(FileTime.fromMillis(System.currentTimeMillis() + 10_000))

        assertTrue(BuildFingerprint.isUpToDate(spec, arguments))
    }

    @Test
    fun `a missing output is not up to date, whatever the stamp says`(@TempDir temp: Path) {
        val spec = project(temp)
        write(spec.output, "prg")
        BuildFingerprint.record(spec, arguments)
        spec.output.toFile().delete()

        assertFalse(BuildFingerprint.isUpToDate(spec, arguments))
    }

    @Test
    fun `a developer key kept outside the project still counts as input`(@TempDir temp: Path) {
        val key = temp.resolve("keys/developer_key.der")
        write(key, "key")
        val spec = project(temp.resolve("app"), key)
        write(spec.output, "prg")
        BuildFingerprint.record(spec, arguments)

        key.setLastModifiedTime(FileTime.fromMillis(System.currentTimeMillis() + 10_000))

        assertFalse(BuildFingerprint.isUpToDate(spec, arguments), "a new signing key is a new binary")
    }

    @Test
    fun `a project in a hidden directory still notices its own sources`(@TempDir temp: Path) {
        // The walk skips hidden directories, and the root is one: entering it anyway is the whole
        // difference between this project rebuilding and never rebuilding again.
        val spec = project(temp.resolve(".watch"))
        val source = spec.root.resolve("source/App.mc")
        write(source, "class App {}")
        write(spec.output, "prg")
        BuildFingerprint.record(spec, arguments)
        source.setLastModifiedTime(FileTime.fromMillis(System.currentTimeMillis() + 10_000))

        assertFalse(BuildFingerprint.isUpToDate(spec, arguments))
    }

    private fun project(root: Path, key: Path? = null) = BuildSpec(
        kind = BuildKind.APP,
        root = root,
        output = root.resolve("bin/App.prg"),
        jungleFiles = listOf(root.resolve("monkey.jungle")),
        device = "fenix7",
        developerKey = key,
    )

    private fun write(path: Path, text: String) {
        path.parent.createDirectories()
        path.writeText(text)
    }
}
