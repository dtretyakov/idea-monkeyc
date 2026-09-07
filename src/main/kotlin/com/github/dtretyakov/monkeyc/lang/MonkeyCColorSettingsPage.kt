package com.github.dtretyakov.monkeyc.lang

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import javax.swing.Icon

class MonkeyCColorSettingsPage : ColorSettingsPage {

    override fun getDisplayName(): String = "Monkey C"

    override fun getIcon(): Icon? = null

    override fun getHighlighter(): SyntaxHighlighter = MonkeyCSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getDemoText(): String = """
        import Toybox.Graphics;
        import Toybox.WatchUi;

        /**
         * The face itself.
         */
        class SampleFace extends WatchUi.WatchFace {

            hidden var mCounter as Number = 0;

            function initialize() {
                WatchFace.initialize();
            }

            (:test)
            function onUpdate(dc as Dc) as Void {
                var seconds = System.getClockTime().sec;   // 0 to 59
                dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
                dc.drawText(dc.getWidth() / 2, 0.5f, Graphics.FONT_LARGE, "" + seconds, 0x01);
                mCounter += 1l;
            }
        }
    """.trimIndent()

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Comments//Line comment", MonkeyCColors.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", MonkeyCColors.BLOCK_COMMENT),
            AttributesDescriptor("Comments//Documentation comment", MonkeyCColors.DOC_COMMENT),
            AttributesDescriptor("Keyword", MonkeyCColors.KEYWORD),
            AttributesDescriptor("Built-in type", MonkeyCColors.BUILTIN_TYPE),
            AttributesDescriptor("Identifier", MonkeyCColors.IDENTIFIER),
            AttributesDescriptor("Symbol", MonkeyCColors.SYMBOL),
            AttributesDescriptor("String", MonkeyCColors.STRING),
            AttributesDescriptor("Number", MonkeyCColors.NUMBER),
            AttributesDescriptor("Operator", MonkeyCColors.OPERATOR),
            AttributesDescriptor("Braces and operators//Braces", MonkeyCColors.BRACES),
            AttributesDescriptor("Braces and operators//Brackets", MonkeyCColors.BRACKETS),
            AttributesDescriptor("Braces and operators//Parentheses", MonkeyCColors.PARENTHESES),
            AttributesDescriptor("Braces and operators//Semicolon", MonkeyCColors.SEMICOLON),
            AttributesDescriptor("Braces and operators//Comma", MonkeyCColors.COMMA),
            AttributesDescriptor("Braces and operators//Dot", MonkeyCColors.DOT),
            AttributesDescriptor("Bad character", MonkeyCColors.BAD_CHARACTER),
        )
    }
}
