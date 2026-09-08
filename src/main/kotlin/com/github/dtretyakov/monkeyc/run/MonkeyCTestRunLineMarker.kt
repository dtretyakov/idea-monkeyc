package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.lang.MonkeyCFileType
import com.github.dtretyakov.monkeyc.lang.MonkeyCTestFunctions
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.github.dtretyakov.monkeyc.project.ProjectLayout
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * The green arrow next to a `(:test)` function.
 *
 * Running one test rather than the whole suite is the difference between a two-second loop and a
 * thirty-second one, and the gutter is where a developer looks for it.
 */
class MonkeyCTestRunLineMarkerContributor : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        val name = MonkeyCTestFunctions.nameOf(element) ?: return null
        return Info(
            AllIcons.RunConfigurations.TestState.Run,
            ExecutorAction.getActions(0),
        ) { "Run '$name'" }
    }
}

/**
 * Turns whatever is under the cursor into a run of exactly those tests.
 *
 * The four scopes are the ones the platform's own test integrations offer, because they are the
 * ones people reach for: a test, a file's worth, a selection of files, and a directory. The
 * runner can express all of them — `monkeydo -t a b c` takes any number of names, and `-t` alone
 * means every test in the project — so none of them is a euphemism for something coarser.
 *
 * There is no "run all tests" menu item, deliberately: the platform has none either. Right-clicking
 * the project root is that command, and it is the one that never goes stale, because it asks for
 * every test rather than for the names of the tests that existed when it was made.
 *
 * It has to be a producer of its own rather than a branch in [MonkeyCRunConfigurationProducer]:
 * the platform picks between producers by asking each one, and the two answer for different
 * things — this one for tests, that one for the app.
 */
class MonkeyCTestConfigurationProducer : LazyRunConfigurationProducer<MonkeyCRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(MonkeyCRunConfigurationType::class.java)
            .configurationFactories
            .first { it.name == MonkeyCRunKind.TESTS.display }

    /**
     * A barrel's tests are a different build, and the gutter used to ignore that.
     *
     * It always made a `TESTS` configuration, which a barrel project then refused — so the only
     * route to barrel tests was the Edit Configurations dialog, and the only thing pointing at it
     * was the text of the refusal.
     */
    private fun kindFor(context: ConfigurationContext): MonkeyCRunKind {
        val project = context.project ?: return MonkeyCRunKind.TESTS
        val model = MonkeyCProject.getInstance(project)
        val root = model.primaryRoot() ?: return MonkeyCRunKind.TESTS
        return if (model.manifest(root)?.isBarrel == true) MonkeyCRunKind.BARREL_TESTS else MonkeyCRunKind.TESTS
    }

    override fun setupConfigurationFromContext(
        configuration: MonkeyCRunConfiguration,
        context: ConfigurationContext,
        source: Ref<PsiElement>,
    ): Boolean {
        val selection = testsAt(context) ?: return false
        configuration.options.kind = kindFor(context)
        configuration.options.tests = selection.tests.joinToString(" ")
        configuration.name = selection.name
        return true
    }

    override fun isConfigurationFromContext(
        configuration: MonkeyCRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val selection = testsAt(context) ?: return false
        return configuration.options.kind.isTests && configuration.options.testNames == selection.tests
    }

    /** Tests beat "run the whole app" whenever the context actually holds some. */
    override fun isPreferredConfiguration(self: ConfigurationFromContext?, other: ConfigurationFromContext?): Boolean =
        runsTheWholeApp(other)

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean =
        runsTheWholeApp(other)

    /** Only the app configuration is displaced; another test configuration is left to stand. */
    private fun runsTheWholeApp(other: ConfigurationFromContext?): Boolean =
        (other?.configuration as? MonkeyCRunConfiguration)?.options?.kind == MonkeyCRunKind.APP

    /** What the context asks to run, and what to call it. */
    private class Selection(val name: String, val tests: List<String>)

    private fun testsAt(context: ConfigurationContext): Selection? {
        singleTest(context)?.let { return Selection(it, listOf(it)) }
        return directory(context) ?: wholeFiles(context)
    }

    /**
     * The test the caret is on, if it is on one.
     *
     * Deliberately narrow: the name itself, or the `function` keyword and annotation in front of
     * it. There is no grammar here, so "the test this line is inside" cannot be answered without
     * guessing, and a guess would offer to run the previous test from inside the next one.
     */
    private fun singleTest(context: ConfigurationContext): String? {
        val element = context.psiLocation ?: return null
        MonkeyCTestFunctions.nameOf(element)?.let { return it }
        return generateSequence(element) { PsiTreeUtil.nextLeaf(it) }
            .take(DECLARATION_HEAD_TOKENS)
            .firstNotNullOfOrNull { MonkeyCTestFunctions.nameOf(it) }
    }

    /** Every test in the Monkey C files the context covers, which may be one or several. */
    private fun wholeFiles(context: ConfigurationContext): Selection? {
        val project = context.project ?: return null
        val manager = PsiManager.getInstance(project)

        val files = context.dataContext.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            ?.filter { it.fileType == MonkeyCFileType }
            ?.mapNotNull { manager.findFile(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(context.psiLocation?.containingFile?.takeIf { it.fileType == MonkeyCFileType })

        val tests = files.flatMap { file -> MonkeyCTestFunctions.testsIn(file).map { it.text }.toList() }
        if (tests.isEmpty()) return null

        return Selection(nameFor(files), tests)
    }

    /**
     * Every test under a directory.
     *
     * A directory that is the project itself asks for no names at all: `-t` on its own is the
     * runner's own way of saying "every test", and unlike a list of names it still means that
     * after the next test is written.
     */
    private fun directory(context: ConfigurationContext): Selection? {
        val project = context.project ?: return null
        val directory = context.psiLocation as? PsiDirectory ?: return null
        val path = directory.virtualFile.toNioPathOrNull() ?: return null

        if (ProjectLayout.isProjectRoot(path)) {
            return Selection("All tests in ${directory.name}", emptyList())
        }
        if (MonkeyCProject.getInstance(project).rootFor(path) == null) return null

        val tests = monkeyCFilesUnder(directory)
            .flatMap { file -> MonkeyCTestFunctions.testsIn(file).map { it.text }.toList() }
        if (tests.isEmpty()) return null

        return Selection("Tests in '${directory.name}'", tests)
    }

    private fun monkeyCFilesUnder(directory: PsiDirectory): List<PsiFile> =
        directory.files.filter { it.fileType == MonkeyCFileType } +
            directory.subdirectories.flatMap { monkeyCFilesUnder(it) }

    private fun nameFor(files: List<PsiFile>): String =
        if (files.size == 1) files.single().name else "${files.size} files"

    private companion object {
        /** `(`, `:test`, `)`, newline, `function`, space, and the name: seven leaves at most. */
        const val DECLARATION_HEAD_TOKENS = 7
    }
}

private fun com.intellij.openapi.vfs.VirtualFile.toNioPathOrNull(): java.nio.file.Path? =
    runCatching { toNioPath() }.getOrNull()
