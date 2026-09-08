package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The two-variant resolution, against the project that revealed it needed to exist.
 *
 * A fixture proves the rule; this proves the rule was written about the right thing. It is skipped
 * unless that project happens to be on this machine, because it is somebody's working tree and not
 * a checked-in fixture.
 */
class RealProjectVariantLiveTest {

    private val project: Path =
        Path.of(System.getProperty("user.home"), "projects/homeassistant/homeassistant-garmin")

    @Test
    fun `each jungle resolves to the manifest it names`() {
        assumeTrue(project.resolve("monkey-api51.jungle").exists(), "the reference project is not here")

        val base = ProjectLayout.manifestPath(project, listOf(project.resolve("monkey.jungle")))
        val api51 = ProjectLayout.manifestPath(project, listOf(project.resolve("monkey-api51.jungle")))

        assertEquals("manifest.xml", base.fileName.toString())
        assertEquals("manifest-api51.xml", api51.fileName.toString())

        // The whole point: these disagree about what the build targets, and the plugin used to read
        // the first one whichever jungle was selected.
        val baseManifest = ManifestFile.parse(base)!!
        val api51Manifest = ManifestFile.parse(api51)!!
        assertNotEquals(baseManifest.devices, api51Manifest.devices)
        assertNotEquals(baseManifest.minSdkVersion, api51Manifest.minSdkVersion)
    }
}
