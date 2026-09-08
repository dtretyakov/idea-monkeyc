package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.testing.IdeTestCase
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext

/**
 * That the plugin is invisible in a project that has nothing to do with Connect IQ.
 *
 * This is the test that answers how every IntelliJ plugin for this language before this one died.
 * Not one of them died of a missing feature. `liias/monkey` put a `NoClassDefFoundError` in the
 * New Project dialog and made it impossible to create a project of *any* kind — "when the plugin
 * is disabled or uninstalled, works IntelliJ fine" — and its run configuration producer claimed
 * every `ModuleRunProfile` in the IDE, which took the Go plugin down with it.
 *
 * A developer who installs this plugin for one repository and works in Java in the next window has
 * to be unable to tell it is there. Inspecting the `update` methods says so today; this says so
 * after the next edit.
 */
class StaysOutOfTheWayTest : IdeTestCase() {

    /** Actions this plugin adds to menus every user of the IDE sees. */
    private val inGlobalMenus = listOf(
        "MonkeyC.BuildApp",
        "MonkeyC.EditProducts",
        "MonkeyC.SelectDevice",
    )

    fun `test the plugin's actions hide themselves in a project with no manifest`() {
        // A project with source in it and no Connect IQ anywhere, which is every other project.
        myFixture.addFileToProject("src/Main.java", "class Main {}")

        val actions = inGlobalMenus.associateWith { ActionManager.getInstance().getAction(it) }
        // Named ids go stale when an action is renamed, and a list of ids that resolve to nothing
        // would make this test pass by finding nothing rather than by finding nothing wrong.
        val missing = actions.filterValues { it == null }.keys
        assertEquals("these action ids no longer exist: $missing", 0, missing.size)

        val visible = actions.values.filterNotNull().filter { presentationOf(it).isVisible }

        assertEquals(
            "these are visible outside a Connect IQ project: ${visible.map { it.javaClass.simpleName }}",
            0,
            visible.size,
        )
    }

    fun `test every action this plugin registers belongs to it`() {
        // A plugin that overrides an id the platform or another plugin owns replaces their action.
        // Ours are all prefixed, and this is what keeps them that way.
        val stolen = ActionManager.getInstance().getActionIdList("").filter { id ->
            val action = ActionManager.getInstance().getAction(id) ?: return@filter false
            action.javaClass.name.startsWith("com.github.dtretyakov.monkeyc") && !id.startsWith("MonkeyC.")
        }

        assertEquals("actions of ours under someone else's id: $stolen", 0, stolen.size)
    }

    private fun presentationOf(action: AnAction): Presentation {
        val event = AnActionEvent.createFromDataContext("test", null, dataContext())
        action.update(event)
        return event.presentation
    }

    private fun dataContext(): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .build()
}
