package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.lang.MonkeyCClasses
import com.github.dtretyakov.monkeyc.lang.MonkeyCLanguage
import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.github.dtretyakov.monkeyc.testing.IdeTestCase
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl

/**
 * Where the arrow that runs the app appears, and where it must not.
 *
 * The manifest decides, which is the whole reason this can be got wrong quietly: an arrow on the
 * wrong class still runs the app, so it looks like it works, and the only sign of the mistake is
 * an offer to run sitting next to something that is not the entry point.
 */
class AppEntryGutterTest : IdeTestCase() {

    /** Real files: the contributor finds the project root by the file's path. */
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    private val contributor = MonkeyCAppRunLineMarkerContributor()

    fun testTheEntryClassGetsTheArrow() {
        val file = project(entry = "App")
        assertNotNull("the entry class is where the app starts", infoFor(file, "App"))
    }

    fun testAnotherClassInTheSameFileDoesNot() {
        val file = project(entry = "App")
        assertNull("a view is not an entry point", infoFor(file, "View"))
    }

    fun testTheArrowFollowsTheManifestRatherThanTheSuperclass() {
        // Both classes extend something from the SDK; only one of them is named by the manifest.
        val file = project(entry = "View")
        assertNull(infoFor(file, "App"))
        assertNotNull(infoFor(file, "View"))
    }

    fun testABarrelGetsNoArrow() {
        val file = project(entry = null)
        assertNull("a barrel has no entry class and nothing to run", infoFor(file, "App"))
    }

    fun testTheTooltipNamesTheConfigurationThatWillRun() {
        val file = project(entry = "App")
        val root = file.virtualFile.parent.parent
        val info = infoFor(file, "App")!!

        // The project's name, not the class's: this arrow starts the same configuration the
        // toolbar does, and naming it after the class would name something that does not exist.
        assertEquals("Run '" + root.name + "'", info.tooltipProvider!!.apply(identifier(file, "App")))
    }

    /**
     * That the IDE is told about this contributor at all.
     *
     * Everything above asks this class directly, which says nothing about whether the platform
     * ever calls it: a contributor missing from `plugin.xml` passes every one of those tests and
     * shows no icon. Asked of the extension point rather than of a real gutter, because opening a
     * Connect IQ file in a fixture starts the language server, and the language server is not
     * something this test has any business needing.
     */
    fun testTheContributorIsRegistered() {
        val registered = RunLineMarkerContributor.EXTENSION.allForLanguage(MonkeyCLanguage)
        assertTrue(
            "the app run line marker is not registered for Monkey C, so no icon can appear",
            registered.any { it is MonkeyCAppRunLineMarkerContributor },
        )
    }

    /** A project whose manifest names [entry], or a barrel when it is null. */
    private fun project(entry: String?): PsiFile {
        myFixture.addFileToProject("monkey.jungle", "project.manifest = manifest.xml")
        myFixture.addFileToProject(ManifestFile.FILE_NAME, if (entry == null) BARREL else application(entry))
        return myFixture.addFileToProject("source/App.mc", SOURCE)
    }

    private fun infoFor(file: PsiFile, className: String) = contributor.getInfo(identifier(file, className))

    private fun identifier(file: PsiFile, className: String): PsiElement =
        generateSequence(PsiTreeUtil.getDeepestFirst(file as PsiElement)) { PsiTreeUtil.nextLeaf(it) }
            .first { MonkeyCClasses.nameOf(it) == className }

    private companion object {
        val SOURCE = """
            class App extends Application.AppBase {
            }

            class View extends WatchUi.View {
            }
        """.trimIndent()

        fun application(entry: String) = """
            <?xml version="1.0"?>
            <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
                <iq:application entry="$entry" id="0123" launcherIcon="@Drawables.LauncherIcon"
                                minApiLevel="3.2.0" name="@Strings.AppName" type="watch-app">
                    <iq:products><iq:product id="fenix7"/></iq:products>
                    <iq:permissions/>
                    <iq:languages><iq:language>eng</iq:language></iq:languages>
                </iq:application>
            </iq:manifest>
        """.trimIndent()

        val BARREL = """
            <?xml version="1.0"?>
            <iq:manifest xmlns:iq="http://www.garmin.com/xml/connectiq" version="3">
                <iq:barrel id="0123" module="Helpers" version="1.0.0">
                    <iq:products><iq:product id="fenix7"/></iq:products>
                </iq:barrel>
            </iq:manifest>
        """.trimIndent()
    }
}
