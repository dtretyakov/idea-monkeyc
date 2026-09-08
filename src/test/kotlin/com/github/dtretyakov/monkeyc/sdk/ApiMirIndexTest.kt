package com.github.dtretyakov.monkeyc.sdk

import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading Garmin's IR, on a miniature of the real thing.
 *
 * Every shape here is copied from `bin/api.mir` rather than invented: the annotation blocks, the
 * `<init>` block that opens and closes a brace nothing should count, the one-line function bodies,
 * the nested class, and the enum whose members belong to the module around it.
 */
class ApiMirIndexTest {

    private val index = ApiMirIndex.parse(
        """
        module Toybox {
            [@file = "api/Graphics.mb"; @line = 16; minSdk = "1.0.0"; ]
            module Graphics {
                <init> {
                }
                type ColorType as ${'$'}.Toybox.Lang.Number or ${'$'}.Toybox.Graphics.ColorValue;
                //! Constant representing a colour
                //! @since 1.0.0
                enum ColorValue {
                    //! White
                    [@file = "api/Graphics.mb"; @line = 127; @position = 8; ]
                    COLOR_WHITE = 16777215,
                    COLOR_BLACK = 0,
                }
                [@file = "api/Graphics.mb"; @line = 300; ]
                class Dc {
                    //! Draw text at the given location.
                    public function drawText(x as ${'$'}.Toybox.Lang.Numeric) as Void {}
                    protected var mWidth as ${'$'}.Toybox.Lang.Number;
                    class Nested {
                        public function inner() as Void {}
                    }
                }
                const MAX_LAYERS = 4;
            }
            module WatchUi {
                class Menu2 {
                    public function addItem(item as ${'$'}.Toybox.WatchUi.MenuItem) as Void {}
                }
            }
        }
        """.trimIndent(),
    )

    @Test
    fun `modules, classes and their members keep their full path`() {
        assertEquals(Kind.MODULE, index.exact("Toybox.Graphics")?.kind)
        assertEquals(Kind.CLASS, index.exact("Toybox.Graphics.Dc")?.kind)
        assertEquals(Kind.FUNCTION, index.exact("Toybox.Graphics.Dc.drawText")?.kind)
        assertEquals(Kind.VARIABLE, index.exact("Toybox.Graphics.Dc.mWidth")?.kind)
        assertEquals(Kind.CLASS, index.exact("Toybox.Graphics.Dc.Nested")?.kind)
        assertEquals(Kind.FUNCTION, index.exact("Toybox.Graphics.Dc.Nested.inner")?.kind)
        assertEquals(Kind.CONSTANT, index.exact("Toybox.Graphics.MAX_LAYERS")?.kind)
        assertEquals(Kind.TYPE, index.exact("Toybox.Graphics.ColorType")?.kind)
    }

    @Test
    fun `an enum member belongs to the module, not to the enum`() {
        // `Graphics.COLOR_WHITE` is what code writes; `Graphics.ColorValue.COLOR_WHITE` does not
        // exist in the language, and neither does the enum's name as a container.
        assertEquals(Kind.CONSTANT, index.exact("Toybox.Graphics.COLOR_WHITE")?.kind)
        assertNotNull(index.exact("Toybox.Graphics.COLOR_BLACK"))
        assertNull(index.exact("Toybox.Graphics.ColorValue.COLOR_WHITE"))
        assertNull(index.exact("Toybox.Graphics.ColorValue"))
    }

    @Test
    fun `a declaration points at its own name, not at the start of the line`() {
        val text = "module Toybox {\n    class Dc {\n    }\n}\n"
        val dc = ApiMirIndex.parse(text).exact("Toybox.Dc")

        assertEquals(2, dc?.line)
        assertEquals("Dc", text.substring(dc!!.offset, dc.offset + 2))
    }

    @Test
    fun `a chain is resolved the way code writes it, not the way the file spells it`() {
        assertEquals(listOf("Toybox.WatchUi.Menu2"), index.resolve("WatchUi.Menu2").map { it.qualifiedName })
        assertEquals(listOf("Toybox.WatchUi.Menu2"), index.resolve("Menu2").map { it.qualifiedName })
        assertEquals(
            listOf("Toybox.WatchUi.Menu2"),
            index.resolve("Toybox.WatchUi.Menu2").map { it.qualifiedName },
        )
        // A leading `$.` is how the file itself writes an absolute reference.
        assertEquals(listOf("Toybox.Graphics.Dc"), index.resolve("$.Toybox.Graphics.Dc").map { it.qualifiedName })
    }

    @Test
    fun `an ambiguous name offers every candidate rather than picking one`() {
        val ambiguous = ApiMirIndex.parse(
            """
            module Toybox {
                module A {
                    class Thing {
                    }
                }
                module B {
                    class Thing {
                    }
                }
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf("Toybox.A.Thing", "Toybox.B.Thing"),
            ambiguous.resolve("Thing").map { it.qualifiedName }.sorted(),
        )
        assertEquals(listOf("Toybox.B.Thing"), ambiguous.resolve("B.Thing").map { it.qualifiedName })
    }

    @Test
    fun `a partial segment is not a match`() {
        // `enu2` must not resolve to `Menu2`: the suffix has to fall on a dot.
        assertTrue(index.resolve("enu2").isEmpty())
        assertTrue(index.resolve("chUi.Menu2").isEmpty())
    }

    @Test
    fun `members of a scope are what a structure view would show`() {
        assertEquals(
            listOf("ColorType", "COLOR_WHITE", "COLOR_BLACK", "Dc", "MAX_LAYERS"),
            index.membersOf("Toybox.Graphics").map { it.simpleName },
        )
    }

    @Test
    fun `nothing at all is a valid file`() {
        assertEquals(0, ApiMirIndex.parse("").size)
    }
}
