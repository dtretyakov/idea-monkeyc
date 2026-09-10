package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.lang.MonkeyCClasses
import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import kotlin.io.path.name

/**
 * The green arrow next to the class the app starts from.
 *
 * Running the app was already possible from the toolbar and from the project tree's context menu,
 * and neither is where a developer is looking while writing the app — they are looking at the
 * class they just changed. Every other language in the IDE puts a way to run beside the entry
 * point for that reason: `main` in Java, a `fun main` in Kotlin, a test in any of them.
 *
 * Beside the entry class and nowhere else. The manifest says which class that is, so this asks it
 * rather than guessing from `extends Application.AppBase` — a project may have its own base class
 * in between, and the manifest is the thing the compiler itself reads. A barrel has no entry, so
 * it gets no arrow, which is right: there is nothing there to run.
 */
class MonkeyCAppRunLineMarkerContributor : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        // The token check first, and the manifest only for the few identifiers that survive it:
        // this runs for every leaf in the file on every highlighting pass, and reading the
        // manifest is parsing XML.
        val name = MonkeyCClasses.nameOf(element) ?: return null

        val file = element.containingFile?.virtualFile ?: return null
        val model = MonkeyCProject.getInstance(element.project)
        val root = model.rootFor(file) ?: return null
        if (model.manifest(root)?.entry != name) return null

        // Named after the project, not the class, because that is the configuration this starts —
        // the same one the toolbar runs and the same words the project tree's menu uses. An arrow
        // offering to "Run 'FixtureApp'" would be naming something that does not exist.
        return Info(
            AllIcons.RunConfigurations.TestState.Run,
            ExecutorAction.getActions(0),
        ) { "Run '${root.name}'" }
    }
}
