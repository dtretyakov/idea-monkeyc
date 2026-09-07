package com.github.dtretyakov.monkeyc.lang

import com.intellij.lang.Language

/**
 * Monkey C, Garmin's language for Connect IQ.
 *
 * The plugin registers the language and lexes it for colouring, bracket matching and commenting,
 * but it deliberately builds no syntax tree: every question about meaning — what a name refers to,
 * what type it has, whether the code compiles — goes to the language server that ships with the
 * SDK, which is the same compiler front end that will build the code.
 */
object MonkeyCLanguage : Language("MonkeyC") {
    private fun readResolve(): Any = MonkeyCLanguage
    override fun getDisplayName(): String = "Monkey C"
    override fun isCaseSensitive(): Boolean = true
}

/** The jungle build files that tell the compiler what a project is made of. */
object JungleLanguage : Language("Jungle") {
    private fun readResolve(): Any = JungleLanguage
    override fun getDisplayName(): String = "Jungle"
    override fun isCaseSensitive(): Boolean = true
}

/** Monkey Style Sheets: the per-device styling that personalities are written in. */
object MssLanguage : Language("MSS") {
    private fun readResolve(): Any = MssLanguage
    override fun getDisplayName(): String = "Monkey Style Sheet"
    override fun isCaseSensitive(): Boolean = true
}
