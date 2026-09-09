package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Opens the manifest on the products it declares.
 *
 * It used to be a dialog of its own with a table in it, and that dialog predates the manifest form
 * editor by two commits — it was the only way to edit products when it was written, and stopped
 * being so as soon as the form existed. Two tables editing one list is one table too many: they
 * drift, they get fixed separately, and a developer who learns one is surprised by the other. The
 * form is the one that stays, because it edits the file as text with the XML tab beside it, and
 * because everything else in the manifest is already there.
 *
 * The action stays, because a command called Edit Products in the Build menu is a reasonable way to
 * ask for this and the target popup wants somewhere to point. What it does now is navigate.
 */
class EditProductsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible =
            project != null && MonkeyCProject.getInstance(project).primaryRoot() != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val model = MonkeyCProject.getInstance(project)
        val root = model.primaryRoot() ?: return
        // The manifest the build reads, which a jungle can name: opening `manifest.xml` while the
        // build uses `manifest-api51.xml` shows a file nobody compiles.
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(model.manifestPath(root)) ?: return

        val editors = FileEditorManager.getInstance(project)
        editors.openFile(file, true)
        // The form rather than the XML, and Products is the tab it opens on because Products is
        // added first. Asking by type id rather than by index: the text editor is there too, and
        // which of them comes first is the provider's business, not ours.
        editors.setSelectedEditor(file, MANIFEST_EDITOR_TYPE)
    }

    private companion object {
        /** Must match `getEditorTypeId` in ManifestEditorProvider. */
        const val MANIFEST_EDITOR_TYPE = "monkeyc-manifest"
    }
}
