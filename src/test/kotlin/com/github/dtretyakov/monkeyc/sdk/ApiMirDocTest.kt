package com.github.dtretyakov.monkeyc.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Turning Garmin's `//!` blocks into the HTML the editor renders.
 *
 * The blocks below are written for the test, in the shape `bin/api.mir` uses: the `//!` prefix, the
 * `@` tags, the `{Module::Type Text}` references and the indented `@example`. The shape is what the
 * renderer reads, and the shape is a format rather than anyone's writing — so nothing here is
 * copied out of an installed SDK, and the plugin's claim to redistribute nothing of Garmin's stays
 * true of its test sources as well as of what it ships.
 *
 * The paths are invented for the same reason. `Fixture::Sample::Gauge` exercises exactly what
 * `Toybox::Graphics::Dc` would: a `::` path that has to come out dotted.
 */
class ApiMirDocTest {

    @Test
    fun `prose becomes prose and the comment markers go away`() {
        val html = ApiMirDoc.toHtml(
            """
            //! The Gauge class draws a dial and the needle over it.
            //!
            //! There is no need to construct a Gauge directly. The layout does
            //! that for you when the view is loaded.
            //! @since 2.1.0
            """.trimIndent(),
        )

        assertFalse(html.contains("//!"), html)
        assertTrue(html.contains("The Gauge class draws a dial and the needle over it."), html)
        assertTrue(html.contains("that for you when the view is loaded."), html)
        assertTrue(html.contains("<i>since</i> 2.1.0"), html)
    }

    @Test
    fun `a parameter becomes a row with its name`() {
        val html = ApiMirDoc.toHtml(
            """
            //! Draw a {Fixture::Sample::Label Label} at the top of the dial.
            //! @param text [Fixture::Sample::Label] The label to draw
            //! @return [Fixture::Sample::Boolean] Whether it fitted
            """.trimIndent(),
        )

        assertTrue(html.contains("<code>text</code>"), html)
        assertTrue(html.contains("The label to draw"), html)
        assertTrue(html.contains("<i>return</i>"), html)
    }

    @Test
    fun `a reference shows the text written for it, and a bare type its own path`() {
        // `{Fixture::Sample::Gauge Gauge}` is a path plus the words to show in its place.
        assertTrue(
            ApiMirDoc.toHtml("//! Use a {Fixture::Sample::Gauge Gauge} here.")
                .contains("<code>Gauge</code>"),
        )
        // With no text after the path, the path itself is what there is to show.
        assertTrue(
            ApiMirDoc.toHtml("//! See {Fixture::Sample::Dial}.").contains("<code>Fixture.Sample.Dial</code>"),
        )
        assertTrue(
            ApiMirDoc.toHtml("//! @throws [Fixture::Sample::InvalidDialException] Bad dial")
                .contains("<code>Fixture.Sample.InvalidDialException</code>"),
        )
    }

    @Test
    fun `an example keeps its shape`() {
        val html = ApiMirDoc.toHtml(
            """
            //! Draw the dial.
            //! @example
            //!  using Fixture.Sample;
            //!  (:test)
            //!  function aDialTest(logger) {
            //!     logger.debug("This is a debug message.");
            //!     return true;
            //!  }
            //! @since 2.1.0
            """.trimIndent(),
        )

        assertTrue(html.contains("<pre><code>"), html)
        assertTrue(html.contains("function aDialTest(logger) {"), html)
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
