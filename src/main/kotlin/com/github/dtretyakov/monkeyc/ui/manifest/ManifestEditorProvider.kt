package com.github.dtretyakov.monkeyc.ui.manifest

import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Adds a form tab to `manifest.xml`.
 *
 * After the text editor rather than before it: the manifest is a small XML file that many people
 * would rather edit directly, and the form is there for the parts that are lists of identifiers
 * nobody remembers — devices, permissions, language codes.
 */
class ManifestEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.name != ManifestFile.FILE_NAME) return false
        // `manifest.xml` is not a Connect IQ invention. A jungle file beside it is what makes this
        // one a Connect IQ project rather than somebody else's build.
        return file.parent?.children.orEmpty().any { it.extension == "jungle" }
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        ManifestFormEditor(project, file)

    override fun getEditorTypeId(): String = "monkeyc-manifest"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}
