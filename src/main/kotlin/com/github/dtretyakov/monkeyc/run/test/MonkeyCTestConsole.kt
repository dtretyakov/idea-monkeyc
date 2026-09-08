package com.github.dtretyakov.monkeyc.run.test

import com.github.dtretyakov.monkeyc.lang.MonkeyCTestFunctions
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope

/** The framework's name, as it appears in the test runner's own settings. */
const val MONKEY_C_TEST_FRAMEWORK: String = "Connect IQ"

class MonkeyCTestConsoleProperties(
    configuration: RunConfiguration,
    executor: Executor,
) : SMTRunnerConsoleProperties(configuration, MONKEY_C_TEST_FRAMEWORK, executor) {

    override fun getTestLocator(): SMTestLocator = MonkeyCTestLocator
}

/**
 * Takes a name out of the test tree back to the `(:test)` function it came from.
 *
 * Without it a test in the tree is a dead row: no double-click to the source, and no way for the
 * platform to offer "run this one again".
 */
object MonkeyCTestLocator : SMTestLocator {

    private const val PROTOCOL = "monkeyc"

    override fun getLocation(
        protocol: String,
        path: String,
        project: Project,
        scope: GlobalSearchScope,
    ): List<Location<*>> {
        if (protocol != PROTOCOL) return emptyList()
        // The runner reports Module.Class.name; only the last part is the function's own name.
        val name = path.substringAfterLast('.')
        return MonkeyCTestFunctions.find(project, scope, name).map { PsiLocation.fromPsiElement(it) }
    }
}
