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

    private fun write(root: Path, xml: String): ManifestFile {
        val path = root.resolve(ManifestFile.FILE_NAME)
        path.writeText(xml.trimIndent())
        return ManifestFile.parse(path)!!
    }
}
