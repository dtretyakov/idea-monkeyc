package com.github.dtretyakov.monkeyc.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Turning Garmin's `//!` blocks into the HTML the editor renders.
 *
 * The blocks below are copied out of `bin/api.mir` rather than written for the test, tags and
 * spacing and all.
 */
class ApiMirDocTest {

    @Test
    fun `prose becomes prose and the comment markers go away`() {
        val html = ApiMirDoc.toHtml(
            """
            //! The Logger class provides output capabilities to tests.
            //!
            //! It is not necessary to instantiate the Logger class. This is done
            //! automatically behind the scenes.
            //! @since 2.1.0
            """.trimIndent(),
        )

        assertFalse(html.contains("//!"), html)
        assertTrue(html.contains("The Logger class provides output capabilities to tests."), html)
        assertTrue(html.contains("automatically behind the scenes."), html)
        assertTrue(html.contains("<i>since</i> 2.1.0"), html)
    }

    @Test
    fun `a parameter becomes a row with its name`() {
        val html = ApiMirDoc.toHtml(
            """
            //! Write a debug {Toybox::Lang::String String} to the output stream.
            //! @param str [Toybox::Lang::String] The String output to the console
            //! @return [Toybox::Lang::Boolean] Whether it went out
            """.trimIndent(),
        )

        assertTrue(html.contains("<code>str</code>"), html)
        assertTrue(html.contains("The String output to the console"), html)
        assertTrue(html.contains("<i>return</i>"), html)
    }

    @Test
    fun `a reference shows the text Garmin wrote for it, and a bare type its own path`() {
        // `{Toybox::Graphics::BufferedBitmap BufferedBitmap}` is a path plus the words to show.
        assertTrue(
            ApiMirDoc.toHtml("//! Use a {Toybox::Graphics::BufferedBitmap BufferedBitmap} here.")
                .contains("<code>BufferedBitmap</code>"),
        )
        // With no text after the path, the path itself is what there is to show.
        assertTrue(
            ApiMirDoc.toHtml("//! See {Toybox::Graphics::Dc}.").contains("<code>Toybox.Graphics.Dc</code>"),
        )
        assertTrue(
            ApiMirDoc.toHtml("//! @throws [Toybox::Graphics::InvalidPaletteException] Bad palette")
                .contains("<code>Toybox.Graphics.InvalidPaletteException</code>"),
        )
    }

    @Test
    fun `an example keeps its shape`() {
        val html = ApiMirDoc.toHtml(
            """
            //! Write a debug String.
            //! @example
            //!  using Toybox.Test;
            //!  (:test)
            //!  function aDebugTest(logger) {
            //!     logger.debug("This is a debug message.");
            //!     return true;
            //!  }
            //! @since 2.1.0
            """.trimIndent(),
        )

        assertTrue(html.contains("<pre><code>"), html)
        assertTrue(html.contains("function aDebugTest(logger) {"), html)
        // The tag after the example ends it rather than joining it.
        assertTrue(html.indexOf("</code></pre>") < html.indexOf("<i>since</i>"), html)
    }

    @Test
    fun `angle brackets in the text do not become markup`() {
        val html = ApiMirDoc.toHtml("//! An Array<Number> of length 2 & nothing else")

        assertTrue(html.contains("Array&lt;Number&gt;"), html)
        assertTrue(html.contains("&amp;"), html)
    }

    @Test
    fun `an empty comment renders to nothing`() {
        assertEquals("", ApiMirDoc.toHtml(""))
        assertEquals("", ApiMirDoc.toHtml("//!"))
    }
}
