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

/**
 * The intermediate representation the SDK ships the Toybox API in.
 *
 * A language of its own rather than an extra extension on Monkey C, and that is the point: LSP4IJ
 * sends `didOpen` for every file mapped to a language the server serves, and handing the server
 * its own `api.mir` would light the whole file up red. This language has no `languageMapping`, so
 * nothing is ever sent for it. The syntax is close enough to Monkey C to reuse the lexer for
 * colour, and everything else about the file — its outline, its folding — comes from the index.
 */
object ApiMirLanguage : Language("ConnectIqApi") {
    private fun readResolve(): Any = ApiMirLanguage
    override fun getDisplayName(): String = "Connect IQ API"
    override fun isCaseSensitive(): Boolean = true
}
