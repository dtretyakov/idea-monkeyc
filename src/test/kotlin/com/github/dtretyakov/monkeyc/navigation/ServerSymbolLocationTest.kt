package com.github.dtretyakov.monkeyc.navigation

import com.github.dtretyakov.monkeyc.lsp.MonkeyCLanguageServerFactory
import com.github.dtretyakov.monkeyc.project.MonkeyCSettings
import com.github.dtretyakov.monkeyc.testing.IdeTestCase
import com.redhat.devtools.lsp4ij.LanguageServerManager

/**
 * That Go To Declaration never starts the language server the user turned off.
 *
 * LSP4IJ's public way to a server names the server rather than the file, and asking for one by
 * name starts it — past [com.github.dtretyakov.monkeyc.lsp.MonkeyCClientFeatures.isEnabled], the
 * gate that otherwise keeps it down. That shows up as no wrong answer, since there is no answer
 * either way, so the server's status is what is asserted: asking must leave it as it was.
 */
class ServerSymbolLocationTest : IdeTestCase() {

    private val status
        get() = LanguageServerManager.getInstance(project).getServerStatus(MonkeyCLanguageServerFactory.SERVER_ID)

    private var liveAnalysis = true

    override fun setUp() {
        super.setUp()
        liveAnalysis = MonkeyCSettings.getInstance(project).liveAnalysis
    }

    override fun tearDown() {
        try {
            MonkeyCSettings.getInstance(project).liveAnalysis = liveAnalysis
        } finally {
            super.tearDown()
        }
    }

    fun `test a project with the server turned off does not have it started by navigation`() {
        MonkeyCSettings.getInstance(project).liveAnalysis = false
        val file = myFixture.configureByText("App.mc", "using Toybox.Graphics;\n")
        val before = status

        assertNull(ServerSymbolLocation.of(file, "using Toy".length))
        assertEquals(before, status)
    }
}
