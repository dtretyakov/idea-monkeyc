package com.github.dtretyakov.monkeyc.ui.manifest

import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.ui.components.JBTabbedPane
import java.awt.Component
import java.awt.Container
import com.github.dtretyakov.monkeyc.testing.IdeTestCase

/**
 * Builds the manifest form inside a real IDE.
 *
 * The text rewriting is covered by plain unit tests; what needs a Project is everything around it —
 * that the provider claims a Connect IQ manifest and nothing else, that the form assembles without
 * throwing, and that an edit made through it lands in the document the text tab is showing.
 */
class ManifestFormEditorTest : IdeTestCase() {

    private val manifestXml = """
        <?xml version="1.0"?>
        <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
            <iq:application entry="App" id="0123" launcherIcon="@Drawables.LauncherIcon"
                            minApiLevel="3.2.0" name="@Strings.AppName" type="watch-app">
                <iq:products>
                    <iq:product id="fenix7"/>
                </iq:products>
                <iq:permissions/>
                <iq:languages>
                    <iq:language>eng</iq:language>
                </iq:languages>
            </iq:application>
        </iq:manifest>
    """.trimIndent()

    fun testClaimsAConnectIqManifestOnly() {
        val provider = ManifestEditorProvider()

        myFixture.addFileToProject("monkey.jungle", "project.manifest = manifest.xml")
        val manifest = myFixture.addFileToProject(ManifestFile.FILE_NAME, manifestXml)
        assertTrue(provider.accept(project, manifest.virtualFile))

        // Same file name, no jungle beside it: somebody else's build, not ours.
        val other = myFixture.addFileToProject("elsewhere/${ManifestFile.FILE_NAME}", "<manifest/>")
        assertFalse(provider.accept(project, other.virtualFile))
    }

    fun testBuildsTheForm() {
        myFixture.addFileToProject("monkey.jungle", "project.manifest = manifest.xml")
        val manifest = myFixture.addFileToProject(ManifestFile.FILE_NAME, manifestXml)

        val editor = ManifestEditorProvider().createEditor(project, manifest.virtualFile)
        try {
            // Assembling the panel is the part that cannot be checked by compiling: the UI DSL,
            // the check box lists, the search over a hundred and sixty devices.
            assertNotNull(editor.component)
            assertEquals("Manifest", editor.name)

            val tabs = find<JBTabbedPane>(editor.component)
            assertNotNull("the three lists live in tabs", tabs)
            assertEquals(3, tabs!!.tabCount)
            // The count belongs in the title, so the tab says what is inside without being opened.
            assertTrue(tabs.getTitleAt(0), tabs.getTitleAt(0).startsWith("Products"))
            assertTrue(tabs.getTitleAt(0), tabs.getTitleAt(0).trim().endsWith("1"))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }

    fun testABarrelHasOnlyProducts() {
        myFixture.addFileToProject("monkey.jungle", "project.manifest = manifest.xml")
        val manifest = myFixture.addFileToProject(
            ManifestFile.FILE_NAME,
            """
            <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
                <iq:barrel id="4567" module="Shared" version="1.0.0"/>
            </iq:manifest>
            """.trimIndent(),
        )

        val editor = ManifestEditorProvider().createEditor(project, manifest.virtualFile)
        try {
            // A barrel declares no permissions and no languages, so it is offered neither.
            assertEquals(1, find<JBTabbedPane>(editor.component)?.tabCount)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }

    private inline fun <reified T : Component> find(root: Component): T? = find(root, T::class.java)

    private fun <T : Component> find(root: Component, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root !is Container) return null
        return root.components.firstNotNullOfOrNull { find(it, type) }
    }

    fun testAnEditThroughTheFormLandsInTheDocument() {
        myFixture.addFileToProject("monkey.jungle", "project.manifest = manifest.xml")
        val manifest = myFixture.addFileToProject(ManifestFile.FILE_NAME, manifestXml)
        val document = FileDocumentManager.getInstance().getDocument(manifest.virtualFile)!!

        val model = ManifestModel(project, document)
        model.setPermissions(listOf("Positioning"))
        model.setAttribute("entry", "SunriseApp", "Change Entry Class")

        val parsed = ManifestFile.parseText(document.text)!!
        assertEquals(listOf("Positioning"), parsed.permissions)
        assertEquals("SunriseApp", parsed.entry)
        assertEquals(listOf("fenix7"), parsed.devices)
    }

    fun testABarrelKeepsItsShape() {
        // A barrel has a module and a version where an application has an entry class and a
        // launcher icon, and it is often written self-closing — the shape that used to be
        // corrupted the first time the form added an attribute to it.
        myFixture.addFileToProject("monkey.jungle", "project.manifest = manifest.xml")
        val manifest = myFixture.addFileToProject(
            ManifestFile.FILE_NAME,
            """
            <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
                <iq:barrel id="4567" module="Shared"/>
            </iq:manifest>
            """.trimIndent(),
        )
        val document = FileDocumentManager.getInstance().getDocument(manifest.virtualFile)!!

        ManifestModel(project, document).setAttribute("version", "1.3.0", "Change Barrel Version")

        val parsed = ManifestFile.parseText(document.text)
        assertNotNull("the manifest must still parse", parsed)
        assertEquals("1.3.0", parsed!!.barrelVersion)
        assertEquals("Shared", parsed.module)

        val editor = ManifestEditorProvider().createEditor(project, manifest.virtualFile)
        try {
            assertNotNull(editor.component)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }

    fun testTheApiLevelMovesUnderEverySpellingTheFileUses() {
        myFixture.addFileToProject("monkey.jungle", "project.manifest = manifest.xml")
        val manifest = myFixture.addFileToProject(
            ManifestFile.FILE_NAME,
            """
            <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
                <iq:application entry="App" id="0123" minApiLevel="3.2.0" minSdkVersion="3.2.0" type="watch-app">
                    <iq:products/>
                </iq:application>
            </iq:manifest>
            """.trimIndent(),
        )
        val document = FileDocumentManager.getInstance().getDocument(manifest.virtualFile)!!

        ManifestModel(project, document).setMinApiLevel("5.0.0")

        // Leaving one behind would give the file two different minimums.
        assertFalse(document.text, document.text.contains("3.2.0"))
        assertTrue(document.text, document.text.contains("""minApiLevel="5.0.0""""))
        assertTrue(document.text, document.text.contains("""minSdkVersion="5.0.0""""))
    }

    fun testAHalfTypedManifestDoesNotBreakTheForm() {
        myFixture.addFileToProject("monkey.jungle", "project.manifest = manifest.xml")
        val manifest = myFixture.addFileToProject(ManifestFile.FILE_NAME, "<iq:manifest")

        val editor = ManifestEditorProvider().createEditor(project, manifest.virtualFile)
        try {
            // It says so rather than throwing; the user is mid-edit in the text tab.
            assertNotNull(editor.component)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }
}
