package com.github.dtretyakov.monkeyc.lsp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Spells out, on the wire, the fields the Monkey C language server reads without checking.
 *
 * Several fields in LSP are optional and have a defined meaning when absent. This server does not
 * treat them as optional: it unboxes them, and a client that leaves them out — which is the client
 * being right — gets a `NullPointerException` back. One of them costs parameter hints; the rest
 * cost everything, because they are read while answering `initialize`, so the server never starts.
 *
 * Decompiling the whole `languageserver` package gives the complete list, so this is not a fix for
 * the one that happened to bite: `dynamicRegistration` on seventeen capability types, and
 * `foldingRange.lineFoldingOnly`. Every one of them is a direct child of `textDocument` or
 * `workspace`, which is what makes the rules below structural rather than a list of names.
 *
 * The fix has to sit here because the messages are built inside LSP4IJ, which offers no hook for
 * altering the ones that matter. Nothing else is touched: every other message is forwarded byte for
 * byte, and the two that are not are only added to, never rewritten.
 */
class RequiredFieldsFilter(private val delegate: OutputStream) : OutputStream() {

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
        val patched = fill(body) ?: return bytes.copyOfRange(message.headerStart, message.end)

        val content = patched.toByteArray(StandardCharsets.UTF_8)
        return "$CONTENT_LENGTH: ${content.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII) + content
    }

    /** Returns the rewritten body, or null when this message is not ours to touch. */
    private fun fill(body: String): String? {
        val message = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        val params = message.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null

        val changed = when (message.get("method")?.takeIf { it.isJsonPrimitive }?.asString) {
            INITIALIZE -> fillDynamicRegistration(params)
            SIGNATURE_HELP -> fillSignatureHelpContext(params)
            else -> false
        }
        return if (changed) message.toString() else null
    }

    /**
     * Declares dynamic registration everywhere the client did not mention it.
     *
     * `LSClientUtils` asks seventeen times whether a capability supports dynamic registration, and
     * each time it unboxes `dynamicRegistration` straight to a `boolean`. LSP4IJ sets it on most
     * capabilities but not on all of them — `textDocument.synchronization` is one it leaves out —
     * and the first one it leaves out throws inside `initialize`, so the server never starts and
     * the plugin does nothing at all. `foldingRange.lineFoldingOnly` goes the same way one method
     * later.
     *
     * `false`, not `true`: absent means unsupported in the protocol, so this writes down what the
     * client already meant rather than promising something on its behalf.
     */
    private fun fillDynamicRegistration(params: JsonObject): Boolean {
        val capabilities = params.get("capabilities")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false

        var changed = false
        listOf("textDocument", "workspace").forEach { section ->
            val group = capabilities.get(section)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            group.entrySet().forEach { (name, capability) ->
                if (declare(capability, DYNAMIC_REGISTRATION)) changed = true
                // The one field outside the pattern: LSContext.setClientCapabilities unboxes it
                // the same way, and folding is the capability it hangs off.
                if (name == "foldingRange" && declare(capability, LINE_FOLDING_ONLY)) changed = true
            }
        }
        return changed
    }

    private fun declare(capability: JsonElement, field: String): Boolean {
        if (!capability.isJsonObject) return false
        val target = capability.asJsonObject
        if (target.has(field)) return false
        target.addProperty(field, false)
        return true
    }

    /**
     * Adds the context on a signature help request that has none.
     *
     * ```
     * NullPointerException: Cannot invoke "SignatureHelpContext.getTriggerCharacter()" because "ctx" is null
     *   at com.garmin.monkeybrains.languageserver.requests.SignatureHelpContext.buildSignatureHelp
     * ```
     *
     * The cost of leaving it is that parameter hints never appear, on a language whose whole appeal
     * is discovering the Toybox API.
     */
    private fun fillSignatureHelpContext(params: JsonObject): Boolean {
        if (params.has("context")) return false

        params.add(
            "context",
            JsonObject().apply {
                // "Invoked", the kind for a request the user asked for rather than one a trigger
                // character started. The server only reads the trigger character, which is absent.
                addProperty("triggerKind", 1)
                addProperty("isRetrigger", false)
            },
        )
        return true
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
        const val INITIALIZE = "initialize"
        const val SIGNATURE_HELP = "textDocument/signatureHelp"
        const val DYNAMIC_REGISTRATION = "dynamicRegistration"
        const val LINE_FOLDING_ONLY = "lineFoldingOnly"
        val SEPARATOR = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    }
}
