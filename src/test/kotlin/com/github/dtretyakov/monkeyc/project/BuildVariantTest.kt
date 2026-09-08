package com.github.dtretyakov.monkeyc.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * A project that builds two variants from one source tree, which is a real shape and not a
 * hypothetical one.
 *
 * `~/projects/homeassistant/homeassistant-garmin` has exactly this: `monkey.jungle` naming
 * `manifest.xml` with a minimum API level of 5.0.0 and two products, and `monkey-api51.jungle`
 * naming `manifest-api51.xml` with 5.1.0 and ten, differing only in `excludeAnnotations`. The
 * plugin read `manifest.xml` whichever jungle was selected, so on the second variant the device
 * selector, the memory budget's target, the app-type filter and the missing-device diagnostics were
 * all about the wrong file — and every existing test wrote `project.manifest = manifest.xml`, which
 * is why none of them noticed.
 */
class BuildVariantTest {

    @Test
    fun `the default jungle gives the default manifest`(@TempDir temp: Path) {
        twoVariants(temp)

        val manifest = ProjectLayout.manifestPath(temp, jungles(temp, "monkey.jungle"))

        assertEquals("manifest.xml", manifest.fileName.toString())
        assertEquals(listOf("venu2"), ManifestFile.parse(manifest)!!.devices)
    }

    @Test
    fun `the other jungle reads the manifest it names`(@TempDir temp: Path) {
        twoVariants(temp)

        val manifest = ProjectLayout.manifestPath(temp, jungles(temp, "monkey-api51.jungle"))

        assertEquals("manifest-api51.xml", manifest.fileName.toString())
        val parsed = ManifestFile.parse(manifest)!!
        assertEquals(listOf("fenix7", "fr955"), parsed.devices)
        assertEquals("5.1.0", parsed.minSdkVersion.toString())
    }

    @Test
    fun `a jungle naming no manifest falls back to the default`(@TempDir temp: Path) {
        temp.resolve("manifest.xml").writeText(manifest("5.0.0", "venu2"))
        temp.resolve("monkey.jungle").writeText("base.sourcePath = source")

        assertEquals(
            temp.resolve("manifest.xml"),
            ProjectLayout.manifestPath(temp, jungles(temp, "monkey.jungle")),
        )
    }

    @Test
    fun `the first jungle that names one wins, as the compiler resolves them`(@TempDir temp: Path) {
        twoVariants(temp)
        temp.resolve("barrels.jungle").writeText("base.barrelPath = barrels")

        // Order matters and is the caller's: the compiler takes the jungles as it is given them.
        val manifest = ProjectLayout.manifestPath(
            temp,
            listOf(temp.resolve("barrels.jungle"), temp.resolve("monkey-api51.jungle")),
        )

        assertEquals("manifest-api51.xml", manifest.fileName.toString())
    }

    @Test
    fun `the two variants really do disagree, which is the point`(@TempDir temp: Path) {
        twoVariants(temp)

        val base = ManifestFile.parse(ProjectLayout.manifestPath(temp, jungles(temp, "monkey.jungle")))!!
        val api51 = ManifestFile.parse(ProjectLayout.manifestPath(temp, jungles(temp, "monkey-api51.jungle")))!!

        assertTrue(base.devices != api51.devices, "the variants declare different products")
        assertTrue(base.minSdkVersion != api51.minSdkVersion, "and different minimum API levels")
    }

    private fun jungles(temp: Path, vararg names: String): List<Path> = names.map { temp.resolve(it) }

    private fun twoVariants(temp: Path) {
        temp.resolve("monkey.jungle").writeText("project.manifest = manifest.xml")
        temp.resolve("monkey-api51.jungle").writeText("project.manifest = manifest-api51.xml")
        temp.resolve("manifest.xml").writeText(manifest("5.0.0", "venu2"))
        temp.resolve("manifest-api51.xml").writeText(manifest("5.1.0", "fenix7", "fr955"))
    }

    private fun manifest(minApiLevel: String, vararg products: String) = """
        <?xml version="1.0"?>
        <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
            <iq:application entry="App" id="0123" launcherIcon="@Drawables.LauncherIcon"
                            minApiLevel="$minApiLevel" name="@Strings.AppName" type="watch-app">
                <iq:products>${products.joinToString("") { "<iq:product id=\"$it\"/>" }}</iq:products>
                <iq:permissions/>
                <iq:languages><iq:language>eng</iq:language></iq:languages>
            </iq:application>
        </iq:manifest>
    """.trimIndent()
}
