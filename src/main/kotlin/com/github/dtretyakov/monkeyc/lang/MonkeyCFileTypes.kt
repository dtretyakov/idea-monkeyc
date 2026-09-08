package com.github.dtretyakov.monkeyc.lang

import com.github.dtretyakov.monkeyc.ui.MonkeyCIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object MonkeyCFileType : LanguageFileType(MonkeyCLanguage) {
    /** `.mcgen` is generated source and `.mb` a compiled barrel's source; both are Monkey C. */
    const val EXTENSIONS = "mc;mcgen;mb"

    override fun getName(): String = "Monkey C"
    override fun getDescription(): String = "Monkey C source file"
    override fun getDefaultExtension(): String = "mc"
    override fun getIcon(): Icon = MonkeyCIcons.MONKEY_C
}

object JungleFileType : LanguageFileType(JungleLanguage) {
    override fun getName(): String = "Jungle"
    override fun getDescription(): String = "Connect IQ jungle build file"
    override fun getDefaultExtension(): String = "jungle"
    override fun getIcon(): Icon = MonkeyCIcons.JUNGLE
}

object MssFileType : LanguageFileType(MssLanguage) {
    override fun getName(): String = "MSS"
    override fun getDescription(): String = "Monkey style sheet"
    override fun getDefaultExtension(): String = "mss"
    override fun getIcon(): Icon = MonkeyCIcons.MSS
}

/**
 * `api.mir`, and the per-file `.mir` the compiler writes into `bin/`.
 *
 * Read-only in practice rather than by declaration: the SDK's copy lives outside every content
 * root, so the platform's own non-project-file banner covers it, and the project's own are under
 * an excluded directory.
 */
object ApiMirFileType : LanguageFileType(ApiMirLanguage) {
    override fun getName(): String = "Connect IQ API"
    override fun getDescription(): String = "Connect IQ API surface"
    override fun getDefaultExtension(): String = "mir"
    override fun getIcon(): Icon = MonkeyCIcons.CONNECT_IQ
}
