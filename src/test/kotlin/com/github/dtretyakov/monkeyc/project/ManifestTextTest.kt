package com.github.dtretyakov.monkeyc.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * The manifest is edited as text so the comments and formatting survive, which means these are the
 * tests that keep it from being mangled — every one of them checks both that the edit landed and
 * that the rest of the file is untouched.
 */
class ManifestTextTest {

    private val manifest = """
        <?xml version="1.0"?>
        <!-- This is a generated file. -->
        <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
            <iq:application entry="App" id="0123" launcherIcon="@Drawables.LauncherIcon"
                            minApiLevel="3.2.0" name="@Strings.AppName" type="watch-app">
                <!-- keep me -->
                <iq:products>
                    <iq:product id="venu2"/>
                </iq:products>
                <iq:permissions/>
                <iq:languages>
                    <iq:language>eng</iq:language>
                </iq:languages>
            </iq:application>
        </iq:manifest>
    """.trimIndent()

    @Test
    fun `replaces the products, sorted, at the file's own indentation`() {
        val updated = ManifestText.withDevices(manifest, listOf("fenix7", "fenix6"))

        assertTrue(updated.contains("\n            <iq:product id=\"fenix6\"/>\n"), updated)
        assertTrue(updated.contains("<iq:product id=\"fenix7\"/>"))
        assertTrue(!updated.contains("venu2"))
        assertKeptTheRest(updated)
    }

    @Test
    fun `fills a self-closing container and empties it again`() {
        val added = ManifestText.withPermissions(manifest, listOf("Positioning", "Communications"))

        assertTrue(added.contains("""<iq:uses-permission id="Communications"/>"""), added)
        assertTrue(added.contains("""<iq:uses-permission id="Positioning"/>"""))

        // Removing the last one has to give the empty element back, not an empty pair of tags with
        // nothing in them — that is the form the SDK's own tools write.
        assertTrue(ManifestText.withPermissions(added, emptyList()).contains("<iq:permissions/>"))
        assertKeptTheRest(added)
    }

    @Test
    fun `languages carry their code as text, not as an attribute`() {
        val updated = ManifestText.withLanguages(manifest, listOf("deu", "eng"))

        assertTrue(updated.contains("<iq:language>deu</iq:language>"), updated)
        assertTrue(updated.contains("<iq:language>eng</iq:language>"))
        assertKeptTheRest(updated)
    }

    @Test
    fun `changes an attribute in place`() {
        val updated = ManifestText.withAttribute(manifest, "type", "watchface")

        assertTrue(updated.contains("""type="watchface""""), updated)
        assertTrue(!updated.contains("""type="watch-app""""))
        assertKeptTheRest(updated)
    }

    @Test
    fun `adds an attribute that is not there yet`() {
        val without = manifest.replace(""" launcherIcon="@Drawables.LauncherIcon"""", "")

        val updated = ManifestText.withAttribute(without, "launcherIcon", "@Drawables.Icon")

        assertTrue(updated.contains("""launcherIcon="@Drawables.Icon""""), updated)
    }

    @Test
    fun `a barrel has its attributes on its own element`() {
        val barrel = """
            <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
                <iq:barrel id="4567" module="Shared" version="1.2.0"/>
            </iq:manifest>
        """.trimIndent()

        assertTrue(ManifestText.withAttribute(barrel, "version", "1.3.0").contains("""version="1.3.0""""))
    }

    @Test
    fun `a manifest it does not recognise is left alone rather than mangled`() {
        val strange = "<iq:manifest><something-else/></iq:manifest>"

        assertEquals(strange, ManifestText.withDevices(strange, listOf("fenix7")))
        assertEquals(strange, ManifestText.withAttribute(strange, "type", "widget"))
    }

    @Test
    fun `the result still parses, and says what was written`(@TempDir root: Path) {
        var updated = ManifestText.withDevices(manifest, listOf("fenix7"))
        updated = ManifestText.withPermissions(updated, listOf("Positioning"))
        updated = ManifestText.withLanguages(updated, listOf("deu"))
        updated = ManifestText.withAttribute(updated, "entry", "SunriseApp")

        val path = root.resolve(ManifestFile.FILE_NAME)
        path.writeText(updated)
        val parsed = ManifestFile.parse(path)!!

        assertEquals(listOf("fenix7"), parsed.devices)
        assertEquals(listOf("Positioning"), parsed.permissions)
        assertEquals(listOf("deu"), parsed.languages)
        assertEquals("SunriseApp", parsed.entry)
        assertEquals("3.2.0", parsed.minSdkVersion.toString(), "minApiLevel is the newer spelling")
    }

    private fun assertKeptTheRest(updated: String) {
        assertTrue(updated.contains("<!-- keep me -->"), "comments must survive an edit")
        assertTrue(updated.contains("<!-- This is a generated file. -->"))
        assertTrue(updated.contains("""id="0123""""), "unrelated attributes must survive")
    }
}
