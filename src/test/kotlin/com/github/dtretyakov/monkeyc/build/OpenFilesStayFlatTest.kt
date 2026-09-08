package com.github.dtretyakov.monkeyc.build

import com.github.dtretyakov.monkeyc.run.GarminVolume
import com.github.dtretyakov.monkeyc.sdk.DeviceCatalog
import com.sun.management.UnixOperatingSystemMXBean
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.management.ManagementFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * That repeating the things a build repeats does not accumulate open files.
 *
 * Version 1.1.x of Garmin's own VS Code extension went from holding 312 open files to holding
 * 21,431, and became unusable on macOS: "too many open files", builds that never finished, a
 * spinner that turned for ever. The community's remedy was to downgrade. It is the most expensive
 * regression in this ecosystem's recent history and it is invisible until it is severe.
 *
 * Everything here walks a directory tree on every build or every keystroke — the up-to-date check
 * walks the whole project, the device catalogue walks 170 directories, the volume scan walks the
 * mount points. A directory stream that is not closed leaks a handle per walk.
 */
class OpenFilesStayFlatTest {

    /** Enough repetitions that a leak of one handle per pass is unmistakable. */
    private val passes = 50

    @Test
    fun `walking the project, the catalogue and the volumes leaks no handles`(@TempDir temp: Path) {
        val bean = ManagementFactory.getOperatingSystemMXBean() as? UnixOperatingSystemMXBean
        assumeTrue(bean != null, "open file descriptors cannot be counted on this platform")

        val spec = project(temp)
        val devices = catalogue(temp)

        // Warm up first: the first pass loads classes and opens files that stay open on purpose,
        // and counting from zero would report that as a leak.
        repeat(5) { pass(spec, devices) }
        val before = bean!!.openFileDescriptorCount

        repeat(passes) { pass(spec, devices) }
        val after = bean.openFileDescriptorCount

        // Not equality: the JVM opens and closes files of its own throughout, and a handful of
        // handles either way is noise. A leak of one per pass would be fifty.
        assertTrue(
            after - before < passes / 5,
            "open file descriptors went from $before to $after over $passes passes",
        )
    }

    private fun pass(spec: BuildSpec, devices: Path) {
        BuildFingerprint.isUpToDate(spec, listOf("-d", "fenix7_sim"))
        DeviceCatalog(devices).refresh()
        GarminVolume.mounted()
    }

    private fun project(temp: Path): BuildSpec {
        val root = temp.resolve("project")
        repeat(20) { index ->
            root.resolve("source/pkg$index").createDirectories()
            root.resolve("source/pkg$index/File$index.mc").writeText("class File$index {}")
        }
        val output = root.resolve("bin/App.prg")
        output.parent.createDirectories()
        output.writeText("prg")
        return BuildSpec(kind = BuildKind.APP, root = root, output = output, jungleFiles = emptyList())
    }

    private fun catalogue(temp: Path): Path {
        val devices = temp.resolve("Devices")
        repeat(20) { index ->
            val device = devices.resolve("device$index")
            device.createDirectories()
            device.resolve("compiler.json").writeText(
                """{"deviceId":"device$index","displayName":"Device $index","appTypes":[{"type":"watchApp","memoryLimit":65536}]}""",
            )
        }
        return devices
    }
}
