package com.github.dtretyakov.monkeyc.ui.manifest

import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.github.dtretyakov.monkeyc.project.ManifestText
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project

/**
 * The form's view of the manifest: read it, and put an edit back.
 *
 * Every edit goes through the document rather than the file, so the text tab beside the form shows
 * it at once and one undo takes it back. Each is its own command, named after what the user did.
 */
class ManifestModel(private val project: Project, private val document: Document) {

    /** The manifest as it is now, or null when it cannot be parsed — a half-typed file, usually. */
    fun read(): ManifestFile? = ManifestFile.parseText(document.text)

    fun setAttribute(name: String, value: String, commandName: String) =
        edit(commandName) { ManifestText.withAttribute(it, name, value) }

    /**
     * Sets the minimum API level under whichever name the file already uses.
     *
     * The templates write `minApiLevel` and the samples `minSdkVersion`; the compiler takes either,
     * but writing the other one would leave the file with two and no way to tell which wins.
     */
    fun setMinApiLevel(value: String) {
        val name = if (document.text.contains("minSdkVersion=")) "minSdkVersion" else "minApiLevel"
        setAttribute(name, value, "Change Minimum API Level")
    }

    fun setDevices(ids: List<String>) = edit("Change Products") { ManifestText.withDevices(it, ids) }

    fun setPermissions(ids: List<String>) = edit("Change Permissions") { ManifestText.withPermissions(it, ids) }

    fun setLanguages(codes: List<String>) = edit("Change Languages") { ManifestText.withLanguages(it, codes) }

    private fun edit(commandName: String, change: (String) -> String) {
        val updated = change(document.text)
        if (updated == document.text) return

        WriteCommandAction.runWriteCommandAction(project, commandName, GROUP, {
            document.setText(updated)
        })
    }

    private companion object {
        /** One undo group, so a run of toggles in the form does not need a run of undos. */
        const val GROUP = "monkeyc.manifest"
    }
}
