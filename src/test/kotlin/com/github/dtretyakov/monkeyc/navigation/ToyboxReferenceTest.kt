package com.github.dtretyakov.monkeyc.navigation

import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Going to a Toybox declaration from ordinary code.
 *
 * The case that matters most is the one a qualified-name match cannot answer: a call on an
 * instance. `logger.debug(...)` names a variable, and the API knows only `Toybox.Test.Logger.debug`.
 */
class ToyboxReferenceTest {

    private val index = ApiMirIndex.parse(
        """
        module Toybox {
            module Test {
                class Logger {
                    public function debug(str as Object) as Void {}
                    public function error(str as Object) as Void {}
                }
            }
            module Graphics {
                class Dc {
                    public function drawText(x as Numeric) as Void {}
                    public function clear() as Void {}
                }
                enum ColorValue {
                    COLOR_WHITE = 16777215,
                }
            }
            module WatchUi {
                class View {
                    public function onUpdate(dc as Dc) as Void {}
                }
            }
        }
        """.trimIndent(),
    )

    @Test
    fun `a call on a typed parameter finds the method on that type`() {
        val text = """
            (:test)
            function fails(logger as Logger) as Boolean {
                logger.debug("a test that fails");
                return false;
            }
        """.trimIndent()

        assertEquals(
            listOf("Toybox.Test.Logger.debug"),
            resolveAt(text, "debug").map { it.qualifiedName },
        )
    }

    @Test
    fun `a path still resolves as a path`() {
        val text = "dc.setColor(Graphics.COLOR_WHITE, 0);"

        assertEquals(listOf("Toybox.Graphics.COLOR_WHITE"), resolveAt(text, "COLOR_WHITE").map { it.qualifiedName })
    }

    @Test
    fun `the nearest annotation above the caret wins`() {
        // `dc` is a parameter of every onUpdate in a project; the one in scope is the one just
        // above the call, not the first in the file.
        val text = """
            function first(dc as Logger) as Void {
            }
            function second(dc as Dc) as Void {
                dc.clear();
            }
        """.trimIndent()

        assertEquals(listOf("Toybox.Graphics.Dc.clear"), resolveAt(text, "dc.clear").map { it.qualifiedName })
    }

    @Test
    fun `an untyped receiver falls back to the member name across the API`() {
        val text = """
            function f() as Void {
                var thing = make();
                thing.drawText(0);
            }
        """.trimIndent()

        assertEquals(
            listOf("Toybox.Graphics.Dc.drawText"),
            resolveAt(text, "thing.drawText").map { it.qualifiedName },
        )
    }

    @Test
    fun `a member name that means nothing in the API resolves to nothing`() {
        val text = "helper.somethingOfMyOwn();"

        assertTrue(resolveAt(text, "somethingOfMyOwn").isEmpty())
    }

    @Test
    fun `a name declared nowhere has no type`() {
        assertNull(ToyboxReference.declaredType("var x = 1;", "x", 0))
        assertNull(ToyboxReference.declaredType("logger.debug();", "", 0))
    }

    @Test
    fun `the absolute form the API itself writes is read as a type`() {
        val text = "function f(logger as \$.Toybox.Test.Logger) as Void {\n    logger.error();\n}"

        assertEquals("Toybox.Test.Logger", ToyboxReference.declaredType(text, "logger", text.length))
        assertEquals(listOf("Toybox.Test.Logger.error"), resolveAt(text, "logger.error").map { it.qualifiedName })
    }

    /** Resolves with the caret just past the last character of [where]. */
    private fun resolveAt(text: String, where: String) =
        ToyboxReference.resolve(index, text, text.indexOf(where) + where.length)
}
