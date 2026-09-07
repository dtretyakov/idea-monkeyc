package com.github.dtretyakov.monkeyc.lsp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Adds the `context` that the Monkey C language server requires on `textDocument/signatureHelp`.
 *
 * The server dereferences the context without checking it:
 *
 * ```
 * NullPointerException: Cannot invoke "SignatureHelpContext.getTriggerCharacter()" because "ctx" is null
 *   at com.garmin.monkeybrains.languageserver.requests.SignatureHelpContext.buildSignatureHelp
 * ```
 *
 * The context is optional in the protocol, so a client that leaves it out is right and the server
 * is wrong — but the result is that parameter hints never appear, on a language whose whole appeal
 * is discovering the Toybox API. The fix has to sit on the wire because the request is built inside
 * LSP4IJ, which exposes no hook for altering it.
 *
 * Everything else passes through byte for byte. Only a signature help request with no context of
 * its own is rewritten, and then only to add the field the server needs.
 */
class SignatureHelpContextFilter(private val delegate: OutputStream) : OutputStream() {

    private val pending = ByteArrayOutputStream()

    override fun write(b: Int) {
        pending.write(b)
        forwardCompleteMessages()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        pending.write(b, off, len)
        forwardCompleteMessages()
    }

    override fun flush() = delegate.flush()

    override fun close() {
        forwardCompleteMessages()
        delegate.flush()
        delegate.close()
    }

    /**
     * A write can carry part of a message, a whole one, or several; only whole ones can be
     * inspected, so the tail waits for the bytes that complete it.
     */
    private fun forwardCompleteMessages() {
        val bytes = pending.toByteArray()
        var offset = 0
        while (true) {
            val message = nextMessage(bytes, offset) ?: break
            delegate.write(patch(bytes, message))
            offset = message.end
        }
        if (offset > 0) {
            pending.reset()
            pending.write(bytes, offset, bytes.size - offset)
        }
    }

    private class Message(val headerStart: Int, val bodyStart: Int, val end: Int)

    private fun nextMessage(bytes: ByteArray, from: Int): Message? {
        val separator = indexOfSeparator(bytes, from) ?: return null
        val headers = String(bytes, from, separator - from, StandardCharsets.US_ASCII)
        val length = headers.lineSequence()
            .firstOrNull { it.startsWith(CONTENT_LENGTH, ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()
            ?: return null

        val bodyStart = separator + SEPARATOR.size
        if (bytes.size - bodyStart < length) return null
        return Message(from, bodyStart, bodyStart + length)
    }

    private fun patch(bytes: ByteArray, message: Message): ByteArray {
        val body = String(bytes, message.bodyStart, message.end - message.bodyStart, StandardCharsets.UTF_8)
        val patched = withContext(body) ?: return bytes.copyOfRange(message.headerStart, message.end)

        val content = patched.toByteArray(StandardCharsets.UTF_8)
        return "$CONTENT_LENGTH: ${content.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII) + content
    }

    /** Returns the rewritten body, or null when this message is not ours to touch. */
    private fun withContext(body: String): String? {
        val message = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        if (message.get("method")?.asString != SIGNATURE_HELP) return null

        val params = message.getAsJsonObject("params") ?: return null
        if (params.has("context")) return null

        params.add(
            "context",
            JsonObject().apply {
                // "Invoked", the kind for a request the user asked for rather than one a trigger
                // character started. The server only reads the trigger character, which is absent.
                addProperty("triggerKind", 1)
                addProperty("isRetrigger", false)
            },
        )
        return message.toString()
    }

    private fun indexOfSeparator(bytes: ByteArray, from: Int): Int? {
        var i = from
        while (i + SEPARATOR.size <= bytes.size) {
            if (SEPARATOR.indices.all { bytes[i + it] == SEPARATOR[it] }) return i
            i++
        }
        return null
    }

    private companion object {
        const val CONTENT_LENGTH = "Content-Length"
        const val SIGNATURE_HELP = "textDocument/signatureHelp"
        val SEPARATOR = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    }
}
