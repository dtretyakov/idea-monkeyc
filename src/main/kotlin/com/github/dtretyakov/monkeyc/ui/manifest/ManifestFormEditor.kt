package com.github.dtretyakov.monkeyc.ui.manifest

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.sdk.ProjectInfo
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The form half of `manifest.xml`.
 *
 * It is built from the document rather than kept in sync with it. A manifest is a few hundred bytes
 * and rebuilding the form costs nothing, whereas a two-way binding between a form and a text editor
 * of the same file is a well-known way to lose an edit — so the form is rebuilt whenever it is
 * shown and the text has moved on, and every control writes straight into the document.
 */
class ManifestFormEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val document: Document? = FileDocumentManager.getInstance().getDocument(file)
    private val model = document?.let { ManifestModel(project, it) }

    private val container = Wrapper()
    private val root = JPanel(BorderLayout()).apply { add(container, BorderLayout.CENTER) }

    /** What the document looked like when the form was last built. */
    private var builtFrom: Long = -1

    private var form: ManifestForm? = null

    /** The scroll pane of the form on screen, so a rebuild can restore where it was. */
    private var scrolled: JBScrollPane? = null

    init {
        rebuild()
    }

    override fun selectNotify() {
        // Anything may have changed since the form was built — an edit in the text tab, or one the
        // form itself made, which can change what the rest of the form should offer: the
        // permissions a watch face may hold are not the ones a data field may.
        if (document != null && document.modificationStamp != builtFrom) rebuild()
    }

    private fun rebuild() {
        val document = this.document
        val model = this.model
        if (document == null || model == null) {
            container.setContent(JBLabel("This file cannot be edited as a form."))
            return
        }

        // Anything typed into the old form is written first: rebuilding takes the focus away
        // without the fields noticing, and their last edit would go with it.
        form?.flush()

        val manifest = model.read()
        if (manifest == null) {
            container.setContent(
                JBLabel("The manifest is not valid XML at the moment. Fix it in the text tab to edit it here."),
            )
            builtFrom = document.modificationStamp
            return
        }

        form?.let { Disposer.dispose(it) }
        val form = ManifestForm(project, manifest, sdkInfo(), ConnectIqSdkService.getInstance().devices()) { change ->
            change(model)
        }
        Disposer.register(this, form)
        this.form = form

        val offset = scrolled?.verticalScrollBar?.value ?: 0
        val pane = JBScrollPane(form.component).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = SCROLL_STEP
        }
        scrolled = pane
        container.setContent(pane)
        // Put the user back where they were reading, once the new content has a size.
        if (offset > 0) SwingUtilities.invokeLater { pane.verticalScrollBar.value = offset }

        builtFrom = document.modificationStamp
    }

    private fun sdkInfo(): ProjectInfo? =
        ConnectIqSdkService.getInstance().sdkFor(project)?.let { ProjectInfo.read(it) }

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent? = form?.preferredFocusedComponent

    override fun getName(): String = "Manifest"

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun getFile(): VirtualFile = file

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() = Unit

    private companion object {
        const val SCROLL_STEP = 16
    }
}

/** What a control does when the user changes it. */
typealias ManifestEdit = ((ManifestModel) -> Unit) -> Unit
