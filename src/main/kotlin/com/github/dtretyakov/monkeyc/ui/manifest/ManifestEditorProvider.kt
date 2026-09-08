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
        // Any XML, not only `manifest.xml`: a jungle names its own manifest with
        // `project.manifest`, and a project that builds two variants has a second one under a name
        // of its own — which used to get the text editor and nothing else, while every other part
        // of the plugin treated it as the manifest.
        if (!file.name.endsWith(XML_SUFFIX, ignoreCase = true)) return false
        // Reading every XML file the user opens would be the cost of that, so this reads a prefix.
        // The namespace is on the root element and a manifest is a small file; a match on the first
        // few kilobytes is the same answer for a fraction of the work.
        if (file.length > MAXIMUM_LENGTH) return false

        // `manifest.xml` is not a Connect IQ invention, so the namespace decides. A `.jungle` file
        // beside it used to, which was a proxy rather than an answer — a project whose jungle is
        // named something else got no form, while the rest of the plugin treated it as its own.
        return runCatching { ManifestFile.isConnectIqManifest(String(file.contentsToByteArray())) }
            .getOrDefault(false)
    }

    private companion object {
        const val XML_SUFFIX = ".xml"

        /**
         * Bigger than any manifest and small enough to read on the way past.
         *
         * The largest manifest in the SDK's own samples is a couple of kilobytes; one declaring
         * every device and every language is still far under this.
         */
        const val MAXIMUM_LENGTH = 256L * 1024
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        ManifestFormEditor(project, file)

    override fun getEditorTypeId(): String = "monkeyc-manifest"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}
