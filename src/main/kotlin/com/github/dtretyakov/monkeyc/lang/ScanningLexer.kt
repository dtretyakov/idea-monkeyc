package com.github.dtretyakov.monkeyc.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType

/**
 * Plumbing shared by the three lexers, so each of them is only its scanning rules.
 *
 * Every token is produced whole, including ones that span lines, so a restart always happens at a
 * token boundary in the one state there is. That keeps incremental lexing correct without carrying
 * a state machine across calls.
 */
abstract class ScanningLexer : LexerBase() {

    protected lateinit var buffer: CharSequence
        private set
    protected var limit: Int = 0
        private set

    /** Where the token being scanned ends; a scan sets this before returning its type. */
    protected var position: Int = 0

    private var tokenStart: Int = 0
    private var tokenEnd: Int = 0
    private var token: IElementType? = null

    final override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.limit = endOffset
        this.tokenStart = startOffset
        this.tokenEnd = startOffset
        advance()
    }

    final override fun advance() {
        tokenStart = tokenEnd
        if (tokenStart >= limit) {
            token = null
            return
        }
        position = tokenStart
        token = scan()
        // A scan that forgets to move would spin forever; one character is the safe minimum.
        if (position <= tokenStart) position = tokenStart + 1
        tokenEnd = minOf(position, limit)
    }

    /** Scans one token starting at [position], leaving [position] on the character after it. */
    protected abstract fun scan(): IElementType

    final override fun getState(): Int = 0
    final override fun getTokenType(): IElementType? = token
    final override fun getTokenStart(): Int = tokenStart
    final override fun getTokenEnd(): Int = tokenEnd
    final override fun getBufferSequence(): CharSequence = buffer
    final override fun getBufferEnd(): Int = limit

    protected fun peek(offset: Int = 0): Char {
        val index = position + offset
        return if (index < limit) buffer[index] else ' '
    }

    protected fun skipWhile(predicate: (Char) -> Boolean) {
        while (position < limit && predicate(buffer[position])) position++
    }

    /** Consumes to the end of the line, leaving the line break for the whitespace token. */
    protected fun skipToLineEnd() = skipWhile { it != '\n' && it != '\r' }

    protected fun skipBlockComment() {
        position += 2
        while (position < limit) {
            if (buffer[position] == '*' && position + 1 < limit && buffer[position + 1] == '/') {
                position += 2
                return
            }
            position++
        }
    }

    /**
     * Consumes a quoted run. An unterminated one stops at the line break rather than swallowing the
     * rest of the file, so a missing quote colours one line instead of everything below it.
     */
    protected fun skipQuoted(quote: Char) {
        position++
        while (position < limit) {
            when (buffer[position]) {
                '\\' -> position++
                quote -> {
                    position++
                    return
                }
                '\n', '\r' -> return
            }
            position++
        }
    }

    protected companion object {
        fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'
        fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
        fun isSpace(c: Char): Boolean = c.isWhitespace()
    }
}
