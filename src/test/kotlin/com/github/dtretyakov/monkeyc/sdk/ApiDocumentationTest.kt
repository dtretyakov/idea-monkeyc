package com.github.dtretyakov.monkeyc.sdk

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Which page Shift+F1 opens, for each kind of thing the caret can be on.
 *
 * The mapping from a declaration's kind to the doc generator's anchor suffix is five lines of
 * `when`, and it is the whole of what stands between the caret and the right place on the page. It
 * had no test that runs without an SDK installed, which is to say none that CI runs.
 */
class ApiDocumentationTest {

    @TempDir
    lateinit var root: Path

    private fun sdkWith(vararg pages: String): ConnectIqSdk {
        pages.forEach { page ->
            val file = root.resolve("doc").resolve("$page.html")
            file.parent.createDirectories()
            file.writeText("<html></html>")
        }
        return ConnectIqSdk(root, root.resolve("Devices"), root)
    }

    private fun declaration(name: String, kind: ApiMirIndex.Kind) =
        ApiMirIndex.Declaration(qualifiedName = name, kind = kind, offset = 0, line = 1)

    @Test
    fun `a class has a page of its own, not an anchor on somebody else's`() {
        val sdk = sdkWith("Toybox/Graphics/Dc")

        val url = ApiDocumentation.urlFor(sdk, declaration("Toybox.Graphics.Dc", ApiMirIndex.Kind.CLASS))

        assertTrue(url!!.endsWith("/doc/Toybox/Graphics/Dc.html"), url)
    }

    @Test
    fun `a module likewise`() {
        val sdk = sdkWith("Toybox/Graphics")

        val url = ApiDocumentation.urlFor(sdk, declaration("Toybox.Graphics", ApiMirIndex.Kind.MODULE))

        assertTrue(url!!.endsWith("/doc/Toybox/Graphics.html"), url)
    }

    @Test
    fun `every other kind is an anchor on the page of whatever contains it`() {
        val sdk = sdkWith("Toybox/Graphics/Dc", "Toybox/Graphics")

        // The suffixes are the doc generator's own, read off the shipped pages. A wrong one lands
        // on the right page at the top of it, which looks like the feature working.
        mapOf(
            ApiMirIndex.Kind.FUNCTION to "Toybox/Graphics/Dc.html#drawText-instance_function",
            ApiMirIndex.Kind.VARIABLE to "Toybox/Graphics/Dc.html#drawText-var",
        ).forEach { (kind, expected) ->
            val url = ApiDocumentation.urlFor(sdk, declaration("Toybox.Graphics.Dc.drawText", kind))
            assertTrue(url!!.endsWith(expected), "$kind gave $url")
        }

        mapOf(
            ApiMirIndex.Kind.CONSTANT to "Toybox/Graphics.html#COLOR_WHITE-const",
            ApiMirIndex.Kind.TYPE to "Toybox/Graphics.html#COLOR_WHITE-named_type",
        ).forEach { (kind, expected) ->
            val url = ApiDocumentation.urlFor(sdk, declaration("Toybox.Graphics.COLOR_WHITE", kind))
            assertTrue(url!!.endsWith(expected), "$kind gave $url")
        }
    }

    @Test
    fun `an SDK that ships no page for it offers nothing rather than a broken link`() {
        val sdk = sdkWith("Toybox/Graphics")

        assertNull(ApiDocumentation.urlFor(sdk, declaration("Toybox.Invented.Thing", ApiMirIndex.Kind.CLASS)))
        assertNull(ApiDocumentation.urlFor(null, declaration("Toybox.Graphics", ApiMirIndex.Kind.MODULE)))
    }
}
