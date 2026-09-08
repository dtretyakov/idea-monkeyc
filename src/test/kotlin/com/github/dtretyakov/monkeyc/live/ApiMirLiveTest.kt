package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.sdk.ApiDocumentation
import com.github.dtretyakov.monkeyc.sdk.ApiMirDoc
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * The index, read from the `api.mir` of the SDK actually installed on this machine.
 *
 * The synthetic tests prove the parser understands the shapes; this one proves the shapes are
 * still what Garmin ships. `api.mir` is a generated file with no compatibility promise, and a
 * change to its layout would not fail anything else in this project — go-to-definition would
 * simply stop finding anything, which is the same as it never having worked.
 */
class ApiMirLiveTest {

    @Test
    fun `the whole Toybox API is there, and the symbols code reaches for are findable`() {
        val sdk = LiveSdk.require()
        val index = requireNotNull(ApiMirIndex.of(sdk)) { "the SDK at ${sdk.root} has no bin/api.mir" }

        // A floor rather than an exact count: the API grows with every SDK, and only a collapse
        // means the parser has stopped understanding the file. 9.2.0 yields a few thousand.
        assertTrue(index.size > 2_000, "only ${index.size} declarations, so the format has moved")

        assertEquals(Kind.MODULE, index.exact("Toybox.Graphics")?.kind)
        assertEquals(Kind.MODULE, index.exact("Toybox.WatchUi")?.kind)
        assertEquals(Kind.CLASS, index.exact("Toybox.Graphics.Dc")?.kind)
        assertEquals(Kind.FUNCTION, index.exact("Toybox.Graphics.Dc.drawText")?.kind)
        assertEquals(Kind.CONSTANT, index.exact("Toybox.Graphics.COLOR_WHITE")?.kind)
        assertEquals(Kind.CLASS, index.exact("Toybox.WatchUi.Menu2")?.kind)
    }

    @Test
    fun `every offset lands on the name it claims`() {
        val sdk = LiveSdk.require()
        val file = ApiMirIndex.fileIn(sdk)
        val text = file.toFile().readText()
        val index = requireNotNull(ApiMirIndex.at(file))

        // An offset that is off by even one character sends the caret into the middle of a word,
        // and every one of them would be wrong the same way, so a sample is enough to catch it.
        index.all().take(500).forEach { declaration ->
            assertEquals(
                declaration.simpleName,
                text.substring(declaration.offset, declaration.offset + declaration.simpleName.length),
                "${declaration.qualifiedName} points at the wrong place",
            )
        }
    }

    @Test
    fun `the line number matches the offset`() {
        val sdk = LiveSdk.require()
        val file = ApiMirIndex.fileIn(sdk)
        val lines = file.readLines()
        val index = requireNotNull(ApiMirIndex.at(file))

        val dc = requireNotNull(index.exact("Toybox.Graphics.Dc"))
        assertTrue(
            lines[dc.line - 1].contains("class Dc"),
            "line ${dc.line} is ${lines[dc.line - 1]}",
        )
    }

    @Test
    fun `a bare class name resolves the way it is written in code`() {
        val sdk = LiveSdk.require()
        val index = requireNotNull(ApiMirIndex.of(sdk))

        assertEquals(
            listOf("Toybox.WatchUi.Menu2"),
            index.resolve("WatchUi.Menu2").map { it.qualifiedName },
        )
        assertTrue(
            index.resolve("Dc").any { it.qualifiedName == "Toybox.Graphics.Dc" },
            "a bare Dc must offer Toybox.Graphics.Dc",
        )
    }

    @Test
    fun `the documentation page for a symbol exists and has the anchor claimed`() {
        val sdk = LiveSdk.require()
        val index = requireNotNull(ApiMirIndex.of(sdk))

        // The page path and the `<name>-<kind>` anchor are both worked out mechanically, so if the
        // doc generator ever changes either, every link the plugin offers breaks at once and
        // nothing else would notice.
        listOf(
            "Toybox.Graphics.COLOR_WHITE" to "COLOR_WHITE-const",
            "Toybox.Graphics.Dc.drawText" to "drawText-instance_function",
            "Toybox.WatchUi.Menu2" to null,
        ).forEach { (name, anchor) ->
            val declaration = requireNotNull(index.exact(name)) { "$name is not in api.mir" }
            val url = requireNotNull(ApiDocumentation.urlFor(sdk, declaration)) { "no page for $name" }

            val page = Path.of(URI.create(url.substringBefore('#')))
            assertTrue(page.isRegularFile(), "$url does not exist")
            if (anchor != null) {
                assertEquals("#$anchor", url.substring(url.indexOf('#')))
                assertTrue(page.readText().contains("id=\"$anchor\""), "$page has no $anchor")
            }
        }
    }

    @Test
    fun `every documented declaration has a comment where it says, and it renders`() {
        val sdk = LiveSdk.require()
        val file = ApiMirIndex.fileIn(sdk)
        val text = file.toFile().readText()
        val index = requireNotNull(ApiMirIndex.at(file))

        val documented = index.all().filter { it.doc != null }
        assertTrue(documented.size > 1_000, "only ${documented.size} declarations carry documentation")

        documented.forEach { declaration ->
            val doc = declaration.doc!!
            val comment = text.substring(doc.first, doc.last)

            assertTrue(
                comment.trimStart().startsWith("//!"),
                "${declaration.qualifiedName} points at ${comment.take(40)}, which is not a comment",
            )
            // Rendering all of it is the cheapest way to find the one block that breaks the
            // renderer: it is machine-generated from Garmin's sources and nobody has read it all.
            val html = ApiMirDoc.toHtml(comment)
            assertFalse(html.startsWith("//!"), "${declaration.qualifiedName} kept its markers")

            // Markers survive only inside an @example, where Garmin is showing sample code that
            // has documentation of its own - AntPlus.BikePowerListener is one. Everywhere else
            // one left behind means a line the renderer did not recognise.
            if (!comment.contains("@example")) {
                assertFalse(html.contains("//!"), "${declaration.qualifiedName} kept its markers")
            }
        }
    }

    @Test
    fun `the bookkeeping line is found where one exists`() {
        val sdk = LiveSdk.require()
        val file = ApiMirIndex.fileIn(sdk)
        val text = file.toFile().readText()
        val index = requireNotNull(ApiMirIndex.at(file))

        val annotated = index.all().filter { it.annotation != null }
        assertTrue(annotated.size > 1_000, "only ${annotated.size} declarations carry an annotation")

        annotated.take(500).forEach { declaration ->
            val at = declaration.annotation!!
            assertTrue(
                text.substring(at.first, at.last).trimStart().startsWith("[@"),
                "${declaration.qualifiedName} points at something that is not an annotation",
            )
        }
    }
}
