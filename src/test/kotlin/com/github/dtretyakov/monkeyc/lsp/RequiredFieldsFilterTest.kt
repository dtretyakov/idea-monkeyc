package com.github.dtretyakov.monkeyc.lsp

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class RequiredFieldsFilterTest {

    @Test
    fun `adds the context the server dereferences without checking`() {
        val out = filter(message("""{"jsonrpc":"2.0","id":7,"method":"textDocument/signatureHelp","params":{"textDocument":{"uri":"file:///a.mc"},"position":{"line":1,"character":2}}}"""))

        val context = body(out).getAsJsonObject("params").getAsJsonObject("context")
        assertEquals(1, context.get("triggerKind").asInt, "'Invoked': the user asked for it")
        assertFalse(context.get("isRetrigger").asBoolean)
    }

    @Test
    fun `the rewritten message declares its new length`() {
        val out = filter(message("""{"jsonrpc":"2.0","id":7,"method":"textDocument/signatureHelp","params":{"position":{}}}"""))

        val text = out.toString(StandardCharsets.UTF_8)
        val declared = text.substringAfter("Content-Length: ").substringBefore("\r\n").trim().toInt()
        val content = text.substringAfter("\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
        assertEquals(content.size, declared)
    }

    @Test
    fun `a request that already has a context is left alone`() {
        val original = message(
            """{"jsonrpc":"2.0","id":7,"method":"textDocument/signatureHelp","params":{"context":{"triggerKind":2,"triggerCharacter":"("}}}""",
        )

        assertArrayEquals(original, filter(original))
    }

    @Test
    fun `every other message passes through byte for byte`() {
        val original = message("""{"jsonrpc":"2.0","id":1,"method":"textDocument/completion","params":{}}""") +
            message("""{"jsonrpc":"2.0","method":"textDocument/didChange","params":{"contentChanges":[]}}""")

        assertArrayEquals(original, filter(original))
    }

    @Test
    fun `a message split across writes is still seen whole`() {
        // lsp4j writes the header and the body separately, and a large body in several chunks.
        val original = message("""{"jsonrpc":"2.0","id":7,"method":"textDocument/signatureHelp","params":{}}""")
        val sink = ByteArrayOutputStream()
        RequiredFieldsFilter(sink).use { filter ->
            original.forEach { filter.write(it.toInt()) }
        }

        assertTrue(body(sink).getAsJsonObject("params").has("context"))
    }

    @Test
    fun `nothing is forwarded until a message is complete`() {
        val whole = message("""{"jsonrpc":"2.0","id":7,"method":"textDocument/signatureHelp","params":{}}""")
        val sink = ByteArrayOutputStream()
        val filter = RequiredFieldsFilter(sink)

        filter.write(whole, 0, whole.size - 5)
        assertEquals(0, sink.size(), "a half-read message cannot be inspected, so it waits")

        filter.write(whole, whole.size - 5, 5)
        assertTrue(sink.size() > 0)
    }

    @Test
    fun `declares dynamic registration wherever the client left it out`() {
        // The server unboxes this straight to a boolean seventeen times during initialize, so one
        // capability without it means the server never starts at all.
        val out = filter(
            message(
                """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"capabilities":{""" +
                    """"textDocument":{"synchronization":{"willSave":true},"hover":{"dynamicRegistration":true},""" +
                    """"foldingRange":{"dynamicRegistration":true}},""" +
                    """"workspace":{"symbol":{},"workspaceFolders":true}}}}""",
            ),
        )

        val capabilities = body(out).getAsJsonObject("params").getAsJsonObject("capabilities")
        val textDocument = capabilities.getAsJsonObject("textDocument")
        assertFalse(
            textDocument.getAsJsonObject("synchronization").get("dynamicRegistration").asBoolean,
            "absent means unsupported, so that is what gets written down",
        )
        assertTrue(
            textDocument.getAsJsonObject("hover").get("dynamicRegistration").asBoolean,
            "what the client did say must survive",
        )
        assertFalse(capabilities.getAsJsonObject("workspace").getAsJsonObject("symbol").get("dynamicRegistration").asBoolean)
        assertFalse(
            textDocument.getAsJsonObject("foldingRange").get("lineFoldingOnly").asBoolean,
            "the one field outside the dynamicRegistration pattern, unboxed the same way",
        )
        // A capability that is a plain value, not an object, is left as it is.
        assertTrue(capabilities.getAsJsonObject("workspace").get("workspaceFolders").asBoolean)
    }

    @Test
    fun `an initialize that already declares everything is left alone`() {
        val original = message(
            """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"capabilities":""" +
                """{"textDocument":{"hover":{"dynamicRegistration":true}}}}}""",
        )

        assertArrayEquals(original, filter(original))
    }

    @Test
    fun `unicode is measured in bytes, as the protocol requires`() {
        // A body whose character count and byte count differ would produce a length the server
        // cannot parse if the two were confused.
        val out = filter(message("""{"jsonrpc":"2.0","id":7,"method":"textDocument/signatureHelp","params":{"text":"фикстура"}}"""))

        val text = out.toString(StandardCharsets.UTF_8)
        val declared = text.substringAfter("Content-Length: ").substringBefore("\r\n").trim().toInt()
        assertEquals(text.substringAfter("\r\n\r\n").toByteArray(StandardCharsets.UTF_8).size, declared)
    }

    private fun message(body: String): ByteArray {
        val content = body.toByteArray(StandardCharsets.UTF_8)
        return "Content-Length: ${content.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII) + content
    }

    private fun filter(input: ByteArray): ByteArrayOutputStream {
        val sink = ByteArrayOutputStream()
        RequiredFieldsFilter(sink).use { it.write(input, 0, input.size) }
        return sink
    }

    private fun body(sink: ByteArrayOutputStream) =
        JsonParser.parseString(sink.toString(StandardCharsets.UTF_8).substringAfter("\r\n\r\n")).asJsonObject

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArrayOutputStream) =
        assertEquals(
            expected.toString(StandardCharsets.UTF_8),
            actual.toString(StandardCharsets.UTF_8),
        )
}
