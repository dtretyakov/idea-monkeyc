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

    /**
     * The colours the annotator applies, which the lexer cannot: an identifier is one token whether
     * it names a class, a call or a constant, so the demo text has to say which is which.
     */
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = mapOf(
        "class" to MonkeyCColors.CLASS_REFERENCE,
        "declaration" to MonkeyCColors.FUNCTION_DECLARATION,
        "call" to MonkeyCColors.FUNCTION_CALL,
        "constant" to MonkeyCColors.CONSTANT,
    )

    override fun getDemoText(): String = """
        import <class>Toybox</class>.<class>Graphics</class>;
        import <class>Toybox</class>.<class>WatchUi</class>;

        /**
         * The face itself.
         */
        class <class>SampleFace</class> extends <class>WatchUi</class>.<class>WatchFace</class> {

            hidden var mCounter as Number = 0;

            function <declaration>initialize</declaration>() {
                <class>WatchFace</class>.<call>initialize</call>();
            }

            (:test)
            function <declaration>onUpdate</declaration>(dc as Dc) as Void {
                var seconds = <class>System</class>.<call>getClockTime</call>().sec;   // 0 to 59
                dc.<call>setColor</call>(<class>Graphics</class>.<constant>COLOR_WHITE</constant>, <class>Graphics</class>.<constant>COLOR_TRANSPARENT</constant>);
                dc.<call>drawText</call>(dc.<call>getWidth</call>() / 2, 0.5f, <class>Graphics</class>.<constant>FONT_LARGE</constant>, "" + seconds, 0x01);
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
            AttributesDescriptor("Class or module reference", MonkeyCColors.CLASS_REFERENCE),
            AttributesDescriptor("Function declaration", MonkeyCColors.FUNCTION_DECLARATION),
            AttributesDescriptor("Function call", MonkeyCColors.FUNCTION_CALL),
            AttributesDescriptor("Constant", MonkeyCColors.CONSTANT),
            AttributesDescriptor("Symbol", MonkeyCColors.SYMBOL),
            AttributesDescriptor("String", MonkeyCColors.STRING),
            AttributesDescriptor("Escape sequence//Valid", MonkeyCColors.VALID_ESCAPE),
            AttributesDescriptor("Escape sequence//Invalid", MonkeyCColors.INVALID_ESCAPE),
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
