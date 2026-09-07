package com.github.dtretyakov.monkeyc.lsp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonkeyCFileUriSupportTest {

    @Test
    fun `repairs the one-slash URIs the server sends from go-to-definition`() {
        assertEquals(
            "file:///Users/me/app/source/App.mc",
            MonkeyCFileUriSupport.repair("file:/Users/me/app/source/App.mc"),
        )
    }

    @Test
    fun `leaves a well-formed URI alone`() {
        val uri = "file:///Users/me/app/source/App.mc"
        assertEquals(uri, MonkeyCFileUriSupport.repair(uri))
    }

    @Test
    fun `leaves URIs it does not own alone`() {
        assertEquals("jar:///a.jar!/b.mc", MonkeyCFileUriSupport.repair("jar:///a.jar!/b.mc"))
        assertEquals("https://example.com", MonkeyCFileUriSupport.repair("https://example.com"))
    }
}
