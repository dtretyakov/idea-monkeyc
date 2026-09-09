package com.github.dtretyakov.monkeyc.project

import com.github.dtretyakov.monkeyc.testing.IdeTestCase
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl

/**
 * An edit that has not reached the disk yet is still the manifest.
 *
 * The form editor writes into the document, because that is what an editor does, and nothing
 * flushes it until the frame loses focus or a run saves everything. Reading the file meant every
 * answer derived from the manifest was one edit behind: the device chip beside Run offered products
 * that had just been deleted, the export preflight checked languages that had just been added, and
 * the setup checklist reported problems that had just been fixed. All of it through one accessor,
 * so all of it is fixed by one test holding that accessor honest.
 */
class UnsavedManifestTest : IdeTestCase() {

    /**
     * Real files, because the accessor under test looks the manifest up by its path.
     *
     * The default light fixture keeps the project in an in-memory filesystem whose files have no
     * `nio` path at all, so `LocalFileSystem` can never match one — the lookup would miss for a
     * reason that has nothing to do with what is being tested, and the test would pass by
     * accident once it stopped throwing.
     */
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    private fun manifestWith(vararg devices: String) = """
        <?xml version="1.0"?>
        <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
            <iq:application entry="App" id="0123" launcherIcon="@Drawables.LauncherIcon"
                            minApiLevel="3.2.0" name="@Strings.AppName" type="watch-app">
                <iq:products>
        ${devices.joinToString("\n") { "            <iq:product id=\"$it\"/>" }}
                </iq:products>
                <iq:permissions/>
                <iq:languages><iq:language>eng</iq:language></iq:languages>
            </iq:application>
        </iq:manifest>
    """.trimIndent()

    fun testTheProductsAreTheOnesInTheEditorNotTheOnesOnDisk() {
        myFixture.addFileToProject("monkey.jungle", "project.manifest = manifest.xml")
        val manifest = myFixture.addFileToProject(ManifestFile.FILE_NAME, manifestWith("fenix7", "venu2"))
        val root = manifest.virtualFile.parent.toNioPath()
        val model = MonkeyCProject.getInstance(project)

        assertEquals(listOf("fenix7", "venu2"), model.manifest(root)?.devices)

        // Edited the way the form edits it: into the document, left unsaved. This is the state a
        // user is in for as long as the manifest tab has focus.
        val document = FileDocumentManager.getInstance().getDocument(manifest.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(manifestWith("fenix7"))
        }
        assertTrue("the test must leave the document unsaved", FileDocumentManager.getInstance().isDocumentUnsaved(document))

        assertEquals(listOf("fenix7"), model.manifest(root)?.devices)
    }

    fun testAManifestNobodyHasOpenIsStillReadFromDisk() {
        // The other half of the same accessor, and the one every headless caller takes: no editor,
        // no document, so the file is the only answer there is.
        myFixture.addFileToProject("monkey.jungle", "project.manifest = manifest.xml")
        val manifest = myFixture.addFileToProject(ManifestFile.FILE_NAME, manifestWith("fenix7"))
        val root = manifest.virtualFile.parent.toNioPath()

        runWriteAction {
            manifest.virtualFile.setBinaryContent(manifestWith("venu2", "fr965").toByteArray())
        }

        assertEquals(listOf("venu2", "fr965"), MonkeyCProject.getInstance(project).manifest(root)?.devices)
    }
}
