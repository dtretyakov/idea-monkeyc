package com.github.dtretyakov.monkeyc.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ManifestFileTest {

    @Test
    fun `reads an app manifest`(@TempDir root: Path) {
        val manifest = write(
            root,
            """
            <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
                <iq:application entry="SmokeApp" id="0123" type="watch-app" minSdkVersion="3.2.0">
                    <iq:products>
                        <iq:product id="fenix6"/>
                        <iq:product id="venu2"/>
                    </iq:products>
                </iq:application>
            </iq:manifest>
            """,
        )

        assertEquals("watch-app", manifest.appType)
        assertEquals("SmokeApp", manifest.entry)
        assertEquals(listOf("fenix6", "venu2"), manifest.devices)
        assertEquals("3.2.0", manifest.minSdkVersion.toString())
        assertFalse(manifest.isBarrel)
    }

    @Test
    fun `reads a barrel manifest, which declares no products`(@TempDir root: Path) {
        val manifest = write(
            root,
            """
            <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
                <iq:barrel id="4567" module="Shared" version="1.2.0"/>
            </iq:manifest>
            """,
        )

        assertTrue(manifest.isBarrel)
        assertEquals("1.2.0", manifest.barrelVersion)
        assertTrue(manifest.devices.isEmpty())
    }

    @Test
    fun `a broken manifest is absent rather than fatal`(@TempDir root: Path) {
        root.resolve(ManifestFile.FILE_NAME).writeText("<iq:manifest")

        assertNull(ManifestFile.parse(root.resolve(ManifestFile.FILE_NAME)))
        assertNull(ManifestFile.parse(root.resolve("nothing-here.xml")))
    }

    @Test
    fun `rewrites the product list and leaves the rest of the file alone`(@TempDir root: Path) {
        val original = """
            <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
                <iq:application entry="App" id="0123" type="watch-app">
                    <!-- keep me -->
                    <iq:products>
                        <iq:product id="venu2"/>
                    </iq:products>
                    <iq:permissions/>
                </iq:application>
            </iq:manifest>
        """.trimIndent()

        val updated = ManifestText.withDevices(original, listOf("fenix7", "fenix6"))

        assertTrue(updated.contains("<!-- keep me -->"), "comments in the template must survive")
        assertTrue(updated.contains("""<iq:product id="fenix6"/>"""))
        assertTrue(updated.contains("""<iq:product id="fenix7"/>"""))
        assertFalse(updated.contains("venu2"))
        assertTrue(updated.contains("<iq:permissions/>"))

        val parsed = write(root, updated)
        assertEquals(listOf("fenix6", "fenix7"), parsed.devices, "sorted, and still parseable")
    }

    @Test
    fun `an empty product element is filled in`(@TempDir root: Path) {
        val updated = ManifestText.withDevices(
            """
            <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
                <iq:application entry="App" id="0123" type="watch-app">
                    <iq:products></iq:products>
                </iq:application>
            </iq:manifest>
            """.trimIndent(),
            listOf("fenix7"),
        )

        assertEquals(listOf("fenix7"), write(root, updated).devices)
    }

    private fun write(root: Path, xml: String): ManifestFile {
        val path = root.resolve(ManifestFile.FILE_NAME)
        path.writeText(xml.trimIndent())
        return ManifestFile.parse(path)!!
    }

    @Test
    fun `a Connect IQ manifest is recognised by its namespace`() {
        val manifest = """
            <iq:manifest version="3" xmlns:iq="http://www.garmin.com/xml/connectiq">
                <iq:application id="a" type="watchface" name="@Strings.AppName" entry="App"/>
            </iq:manifest>
        """.trimIndent()

        assertTrue(ManifestFile.isConnectIqManifest(manifest))
    }

    @Test
    fun `somebody else's manifest is not one`() {
        // manifest.xml is not a Connect IQ invention, and the form editor must not offer itself
        // for a file it cannot edit.
        assertFalse(ManifestFile.isConnectIqManifest("<manifest package=\"com.example\"/>"))
        assertFalse(ManifestFile.isConnectIqManifest(""))
    }
}
