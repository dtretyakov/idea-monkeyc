package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.project.MonkeyCProject
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.DocumentMatcher

/**
 * Keeps the language server to the XML that belongs to a Connect IQ project.
 *
 * The server does understand `manifest.xml` and the resource files, and offers completion for
 * both — but a project can hold XML of every other kind, and sending it all to a Monkey C compiler
 * would be both pointless and slow.
 */
class ConnectIqXmlMatcher : DocumentMatcher {

    override fun match(file: VirtualFile, project: Project): Boolean =
        MonkeyCProject.getInstance(project).rootFor(file) != null
}
