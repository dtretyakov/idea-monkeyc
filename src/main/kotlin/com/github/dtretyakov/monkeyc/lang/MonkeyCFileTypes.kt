package com.github.dtretyakov.monkeyc.lang

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object MonkeyCFileType : LanguageFileType(MonkeyCLanguage) {
    /** `.mcgen` is generated source and `.mb` a compiled barrel's source; both are Monkey C. */
    const val EXTENSIONS = "mc;mcgen;mb"

    override fun getName(): String = "Monkey C"
    override fun getDescription(): String = "Monkey C source file"
    override fun getDefaultExtension(): String = "mc"
    override fun getIcon(): Icon = AllIcons.FileTypes.Any_type
}

object JungleFileType : LanguageFileType(JungleLanguage) {
    override fun getName(): String = "Jungle"
    override fun getDescription(): String = "Connect IQ jungle build file"
    override fun getDefaultExtension(): String = "jungle"
    override fun getIcon(): Icon = AllIcons.FileTypes.Config
}

object MssFileType : LanguageFileType(MssLanguage) {
    override fun getName(): String = "MSS"
    override fun getDescription(): String = "Monkey style sheet"
    override fun getDefaultExtension(): String = "mss"
    override fun getIcon(): Icon = AllIcons.FileTypes.Css
}
