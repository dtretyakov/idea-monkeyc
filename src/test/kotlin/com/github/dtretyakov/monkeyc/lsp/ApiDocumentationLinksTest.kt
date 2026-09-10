package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The rewriting behind Shift+F1 and behind every link in a hover.
 *
 * All of it is string work over a directory layout, and until now the only thing exercising it was
 * a live test that needs a real SDK — which CI has not got, so this was shipping unchecked. The
 * layout is small enough to build in a temp directory, so the SDK is not needed to check the part
 * that is ours.
 */
class ApiDocumentationLinksTest {

    @TempDir
    lateinit var root: Path

    /** An SDK-shaped directory with the pages this test names, and nothing else in it. */
    private fun sdkWith(vararg pages: String): ConnectIqSdk {
        pages.forEach { page ->
            val file = root.resolve("doc").resolve("$page.html")
            file.parent.createDirectories()
            file.writeText("<html></html>")
        }
        return ConnectIqSdk(root, root.resolve("Devices"), root)
    }

    @Test
    fun `a member's page is the class's file, anchored by the member`() {
        val sdk = sdkWith("Toybox/Graphics/Dc")

        val url = ApiDocumentationLinks.documentationUrl(sdk, "drawText-instance_function", "Toybox.Graphics.Dc")

        assertTrue(url!!.startsWith("file:"), url)
        assertTrue(url.endsWith("/doc/Toybox/Graphics/Dc.html#drawText-instance_function"), url)
    }

    @Test
    fun `a module's own page needs no anchor`() {
        val sdk = sdkWith("Toybox/Graphics")

        val url = ApiDocumentationLinks.documentationUrl(sdk, "", "Toybox.Graphics")

        assertTrue(url!!.endsWith("/doc/Toybox/Graphics.html"), url)
        assertFalse(url.contains('#'), url)
    }

    @Test
    fun `an SDK without the page says so rather than offering a link to nothing`() {
        val sdk = sdkWith("Toybox/Graphics/Dc")

        assertNull(ApiDocumentationLinks.documentationUrl(sdk, "", "Toybox.Invented.Thing"))
        // No SDK at all, and no module, are the same answer for the same reason.
        assertNull(ApiDocumentationLinks.documentationUrl(null, "", "Toybox.Graphics.Dc"))
        assertNull(ApiDocumentationLinks.documentationUrl(sdk, "", ""))
    }

    @Test
    fun `a VS Code command in a hover becomes a link to the file on disk`() {
        val sdk = sdkWith("Toybox/Graphics/Dc")
        val hover = """
            Returns the width. See
            <a href='command:monkeyc.viewApiDocumentation?["getWidth-instance_function","Toybox.Graphics.Dc"]'>getWidth</a>.
        """.trimIndent()

        val rewritten = ApiDocumentationLinks.rewrite(hover, sdk)

        assertFalse(rewritten.contains("command:"), rewritten)
        assertTrue(rewritten.contains("/doc/Toybox/Graphics/Dc.html#getWidth-instance_function"), rewritten)
        assertTrue(rewritten.contains(">getWidth</a>"), rewritten)
    }

    @Test
    fun `a command this IDE cannot serve is unwrapped, keeping its text`() {
        // No SDK, so nothing can be resolved — and a link that does nothing is worse than no link.
        assertEquals(
            "See getWidth.",
            ApiDocumentationLinks.rewrite(
                """See <a href='command:monkeyc.viewApiDocumentation?["getWidth","Toybox.Graphics.Dc"]'>getWidth</a>.""",
                null,
            ),
        )
        assertEquals(
            "See getWidth.",
            ApiDocumentationLinks.rewrite("See [getWidth](command:monkeyc.somethingElse).", null),
        )
    }

    @Test
    fun `markup with no links in it comes back unchanged`() {
        val plain = "**Dc** — the drawing context. No links here."

        assertEquals(plain, ApiDocumentationLinks.rewrite(plain, null))
    }
}
