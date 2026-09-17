package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.testing.IdeTestCase
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JEditorPane

/**
 * Nothing on the settings page is wider than the explanations on it.
 *
 * A page wider than the dialog grows a horizontal scrollbar and takes the right of every row off
 * the edge with it — which is how the Generate button beside the developer key field came to be
 * half a button. No field was too wide: the DSL asks for seventy characters for a comment whatever
 * the text says, and a comment attached to a field starts at the field's column, so the row spends
 * the label's width twice.
 *
 * Measured against the page's own widest comment rather than a pixel count, because these are
 * proportional fonts and a larger one moves both numbers together.
 */
class SettingsPageFitsTest : IdeTestCase() {

    fun testNoRowCostsMoreWidthThanTheWidestComment() {
        val configurable = MonkeyCConfigurable(project)
        try {
            val panel = configurable.createComponent() as JComponent
            val comments = collect(panel).filterIsInstance<JEditorPane>()
            assertFalse("Expected the page to explain itself", comments.isEmpty())

            val widest = comments.maxOf { it.preferredSize.width }
            val page = panel.preferredSize.width

            assertTrue(
                "The page wants ${page}px where its widest comment is ${widest}px. Something on " +
                    "it is wider than a sentence — most likely a comment attached to a field " +
                    "cell, which begins at the field's column and so spends the label's width a " +
                    "second time. Move it to the row with rowComment, or off the field and into a " +
                    "row of its own when it has to be rewritten at run time.\n" + describe(panel),
                page <= widest + INDENTS,
            )
        } finally {
            configurable.disposeUIResources()
        }
    }

    /** The widest components, so a failure names what to look at rather than only that it is wide. */
    private fun describe(panel: JComponent): String =
        collect(panel)
            .map { it.preferredSize.width to it }
            .sortedByDescending { it.first }
            .take(8)
            .joinToString("\n") { (width, component) ->
                val text = when (component) {
                    is javax.swing.JLabel -> component.text
                    is javax.swing.AbstractButton -> component.text
                    is javax.swing.text.JTextComponent -> component.text
                    else -> null
                }?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
                "  ${width}px  ${component.javaClass.simpleName}${text?.let { ": ${it.take(55)}" } ?: ""}"
            }

    private fun collect(root: Container): List<JComponent> {
        val found = mutableListOf<JComponent>()
        root.components.forEach { child ->
            if (child is JComponent) found += child
            if (child is Container) found += collect(child)
        }
        return found
    }

    private companion object {
        /**
         * What the group indent and the panel's own insets are allowed to add.
         *
         * Generous on purpose. The point of the check is a row that costs a whole label column
         * more than it needs, which is several times this; anything smaller is the layout doing
         * its job.
         */
        const val INDENTS = 80
    }
}
