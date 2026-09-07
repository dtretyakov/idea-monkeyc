package com.github.dtretyakov.monkeyc.ui.manifest

import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.intellij.openapi.fileEditor.FileDocumentManager
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
            // the check box lists, the speed search over 166 devices.
            assertNotNull(editor.component)
            assertEquals("Manifest", editor.name)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
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
