package com.github.dtretyakov.monkeyc.lang

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class MonkeyCTokenType(debugName: String) : IElementType(debugName, MonkeyCLanguage)
class JungleTokenType(debugName: String) : IElementType(debugName, JungleLanguage)
class MssTokenType(debugName: String) : IElementType(debugName, MssLanguage)

/**
 * The tokens the lexer produces.
 *
 * This is a colouring alphabet, not a grammar: the set stops exactly where syntax highlighting,
 * bracket matching and commenting stop needing to tell things apart.
 */
object MonkeyCTokens {
    val FILE = IFileElementType(MonkeyCLanguage)

    val LINE_COMMENT = MonkeyCTokenType("LINE_COMMENT")
    val BLOCK_COMMENT = MonkeyCTokenType("BLOCK_COMMENT")
    val DOC_COMMENT = MonkeyCTokenType("DOC_COMMENT")

    val STRING = MonkeyCTokenType("STRING")
    val CHARACTER = MonkeyCTokenType("CHARACTER")
    val NUMBER = MonkeyCTokenType("NUMBER")

    val KEYWORD = MonkeyCTokenType("KEYWORD")
    val BUILTIN_TYPE = MonkeyCTokenType("BUILTIN_TYPE")

    /** `:foo` — a Monkey C symbol, and what annotations such as `(:test)` are made of. */
    val SYMBOL = MonkeyCTokenType("SYMBOL")
    val IDENTIFIER = MonkeyCTokenType("IDENTIFIER")

    val OPERATOR = MonkeyCTokenType("OPERATOR")
    val SEMICOLON = MonkeyCTokenType("SEMICOLON")
    val COMMA = MonkeyCTokenType("COMMA")
    val DOT = MonkeyCTokenType("DOT")

    val LPAREN = MonkeyCTokenType("LPAREN")
    val RPAREN = MonkeyCTokenType("RPAREN")
    val LBRACE = MonkeyCTokenType("LBRACE")
    val RBRACE = MonkeyCTokenType("RBRACE")
    val LBRACKET = MonkeyCTokenType("LBRACKET")
    val RBRACKET = MonkeyCTokenType("RBRACKET")

    val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)
    val STRINGS = TokenSet.create(STRING, CHARACTER)

    /**
     * Every word the compiler reserves, from the Monkey C section of the Programmer's Guide.
     *
     * `me` and `self` are here rather than in the types: they are keywords the compiler knows, and
     * colouring them as identifiers would make a method's own receiver look like a local.
     */
    val KEYWORDS = setOf(
        "and", "as", "break", "case", "catch", "class", "const", "continue", "default", "do",
        "else", "enum", "extends", "finally", "for", "function", "has", "hidden", "if", "import",
        "instanceof", "me", "module", "native", "new", "not", "null", "or", "private", "protected",
        "public", "return", "self", "static", "switch", "throw", "try", "typedef", "using", "var",
        "while", "true", "false", "NaN",
    )

    /** The Toybox::Lang types that may appear in a type annotation without being imported. */
    val BUILTIN_TYPES = setOf(
        "Array", "Boolean", "ByteArray", "Char", "Dictionary", "Double", "Exception", "Float",
        "Lang", "Long", "Method", "Null", "Number", "Object", "String", "Symbol", "Void", "Weak",
    )
}

object JungleTokens {
    val FILE = IFileElementType(JungleLanguage)

    val COMMENT = JungleTokenType("COMMENT")
    val STRING = JungleTokenType("STRING")
    /** `$(base.sourcePath)` — a reference to another entry, resolved by the build. */
    val VARIABLE = JungleTokenType("VARIABLE")
    val IDENTIFIER = JungleTokenType("IDENTIFIER")
    val OPERATOR = JungleTokenType("OPERATOR")
    val SEPARATOR = JungleTokenType("SEPARATOR")
    val TEXT = JungleTokenType("TEXT")

    val COMMENTS = TokenSet.create(COMMENT)
}

object MssTokens {
    val FILE = IFileElementType(MssLanguage)

    val LINE_COMMENT = MssTokenType("LINE_COMMENT")
    val BLOCK_COMMENT = MssTokenType("BLOCK_COMMENT")
    val STRING = MssTokenType("STRING")
    val NUMBER = MssTokenType("NUMBER")
    val IDENTIFIER = MssTokenType("IDENTIFIER")
    val OPERATOR = MssTokenType("OPERATOR")
    val SEMICOLON = MssTokenType("SEMICOLON")
    val LBRACE = MssTokenType("LBRACE")
    val RBRACE = MssTokenType("RBRACE")

    val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT)
}
