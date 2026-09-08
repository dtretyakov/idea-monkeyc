package com.github.dtretyakov.monkeyc.lang

import com.intellij.application.options.CodeStyle
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.intellij.psi.tree.IElementType

/**
 * Where the caret lands after Enter.
 *
 * The platform works this out from the formatter, and there is no formatter here: formatting needs
 * a syntax tree with blocks in it, and this plugin keeps a flat one on purpose because the SDK's
 * language server is the authority on structure. The server offers no formatting either. So
 * pressing Enter inside a function body left the caret in column one, which is the sort of thing
 * that makes an editor feel broken however much else works.
 *
 * `lineIndentProvider` is the platform's answer for exactly this case — a language that is lexed
 * but not parsed. The platform's own `FormatterBasedLineIndentProvider` is registered `order=last`,
 * so returning null here still falls through to it.
 *
 * The lexer already emits each bracket as its own token, so the balance of a line can be counted
 * from token types alone: a brace inside a string or a comment is part of that token and never
 * reaches the count.
 */
class MonkeyCLineIndentProvider : LineIndentProvider {

    override fun isSuitableFor(language: Language?): Boolean = language in SUPPORTED

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        if (!isSuitableFor(language)) return null
        if (offset !in 0..editor.document.textLength) return null

        val document = editor.document
        val text = document.charsSequence

        val currentLine = document.getLineNumber(offset)
        val previous = (currentLine - 1 downTo 0).firstOrNull { line ->
            (document.getLineStartOffset(line) until document.getLineEndOffset(line))
                .any { !text[it].isWhitespace() }
        } ?: return null

        val start = document.getLineStartOffset(previous)
        val end = document.getLineEndOffset(previous)

        val levels = Indentation.levels(
            previousIndent = indentWidth(text, start) / indentSize(project, editor),
            opened = Indentation.opened(tokensBetween(editor, start, end)),
            closerAhead = Indentation.startsWithCloser(
                tokensBetween(editor, offset, document.getLineEndOffset(currentLine)),
            ),
        )

        return render(project, editor, levels)
    }

    /**
     * The tokens between two offsets, from the editor's own highlighter.
     *
     * The highlighter is lexing the file already, and it is the only thing that can say whether a
     * brace three lines up is code or the middle of a block comment.
     */
    private fun tokensBetween(editor: Editor, from: Int, to: Int): List<IElementType> {
        if (from >= to) return emptyList()
        val highlighter = (editor as? EditorEx)?.highlighter ?: return emptyList()

        val tokens = mutableListOf<IElementType>()
        val iterator = highlighter.createIterator(from)
        while (!iterator.atEnd() && iterator.start < to) {
            iterator.tokenType?.let { tokens.add(it) }
            iterator.advance()
        }
        return tokens
    }

    private fun indentWidth(text: CharSequence, lineStart: Int): Int {
        var width = 0
        var at = lineStart
        while (at < text.length && (text[at] == ' ' || text[at] == '\t')) {
            width++
            at++
        }
        return width
    }

    private fun indentSize(project: Project, editor: Editor): Int =
        options(project, editor).INDENT_SIZE.takeIf { it > 0 } ?: DEFAULT_INDENT

    private fun render(project: Project, editor: Editor, levels: Int): String {
        val options = options(project, editor)
        val size = options.INDENT_SIZE.takeIf { it > 0 } ?: DEFAULT_INDENT
        return if (options.USE_TAB_CHARACTER) "\t".repeat(levels) else " ".repeat(levels * size)
    }

    private fun options(project: Project, editor: Editor) =
        CodeStyle.getIndentOptions(project, editor.document)

    private companion object {
        const val DEFAULT_INDENT = 4

        val SUPPORTED = setOf<Language>(MonkeyCLanguage, MssLanguage, ApiMirLanguage)
    }
}

/**
 * The arithmetic, apart from the editor.
 *
 * Everything above is reading the document; this is the part that can be wrong in a way a person
 * would notice, so it is kept where a test can reach it without an IDE around it.
 */
object Indentation {

    fun levels(previousIndent: Int, opened: Int, closerAhead: Boolean): Int =
        maxOf(previousIndent + opened - if (closerAhead) 1 else 0, 0)

    /**
     * How many brackets a line leaves open.
     *
     * Net rather than trailing: `function f(a as Number) {` opens a paren, closes it and opens a
     * brace, and only the last of the three should move the next line. A line that closes more
     * than it opens moves nothing — its own indentation already says where the block went.
     */
    fun opened(tokens: List<IElementType>): Int =
        maxOf(tokens.count { it in OPENERS } - tokens.count { it in CLOSERS }, 0)

    /**
     * Whether what follows the caret starts with a closing bracket.
     *
     * Pressing Enter between `{` and `}` should leave the closing brace where the opening line
     * was, not indented with the body about to be written between them.
     */
    fun startsWithCloser(tokens: List<IElementType>): Boolean =
        tokens.firstOrNull { it != TokenType.WHITE_SPACE } in CLOSERS

    private val OPENERS = setOf(MonkeyCTokens.LBRACE, MonkeyCTokens.LPAREN, MonkeyCTokens.LBRACKET, MssTokens.LBRACE)
    private val CLOSERS = setOf(MonkeyCTokens.RBRACE, MonkeyCTokens.RPAREN, MonkeyCTokens.RBRACKET, MssTokens.RBRACE)
}
