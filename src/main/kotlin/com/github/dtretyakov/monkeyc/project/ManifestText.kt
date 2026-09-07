package com.github.dtretyakov.monkeyc.project

/**
 * Edits a `manifest.xml` as text.
 *
 * Not as a DOM: a manifest carries the comments Garmin's template puts in it, and the attribute
 * order and formatting of a file the user may be editing by hand in the next tab. Re-serialising a
 * parsed document would quietly rewrite all of that, and the diff would be unreadable.
 *
 * Each function returns the file unchanged when it cannot find what it was asked to edit, so a
 * manifest shaped in some way this does not expect is left alone rather than mangled.
 */
object ManifestText {

    private const val APPLICATION = "iq:application"
    private const val BARREL = "iq:barrel"

    fun withDevices(text: String, ids: List<String>): String =
        withElements(text, "iq:products", "iq:product", ids)

    fun withPermissions(text: String, ids: List<String>): String =
        withElements(text, "iq:permissions", "iq:uses-permission", ids)

    /** Languages carry their code as element text rather than as an attribute. */
    fun withLanguages(text: String, codes: List<String>): String =
        withElements(text, "iq:languages", "iq:language", codes, asAttribute = false)

    /**
     * Sets an attribute on `<iq:application>` — or on `<iq:barrel>`, for a library project.
     *
     * An attribute that is not there yet is added at the end of the opening tag, which is where the
     * SDK's own tools put a new one.
     */
    fun withAttribute(text: String, name: String, value: String): String {
        val open = openingTag(text) ?: return text
        val existing = Regex("""(\s$name=")([^"]*)(")""").find(open.tag)

        val updated = if (existing != null) {
            open.tag.replaceRange(existing.range, "${existing.groupValues[1]}${escape(value)}\"")
        } else {
            // Before the `>`, or before the `/` of a self-closing tag: putting it after the slash
            // both breaks the XML and turns `<iq:barrel …/>` into a tag that is never closed.
            val end = open.tag.length - if (open.tag.endsWith("/>")) 2 else 1
            open.tag.replaceRange(end, end, """ $name="${escape(value)}"""")
        }
        return text.replaceRange(open.range, updated)
    }

    private class OpeningTag(val tag: String, val range: IntRange)

    private fun openingTag(text: String): OpeningTag? {
        val match = Regex("""<(?:$APPLICATION|$BARREL)\b[^>]*>""", RegexOption.DOT_MATCHES_ALL).find(text)
            ?: return null
        return OpeningTag(match.value, match.range)
    }

    /**
     * Replaces the children of a container element, keeping the file's indentation.
     *
     * Handles the self-closing form too: an empty container is written `<iq:permissions/>` by the
     * SDK's own tools, and adding the first permission has to turn that back into a pair.
     */
    private fun withElements(
        text: String,
        container: String,
        child: String,
        values: List<String>,
        asAttribute: Boolean = true,
    ): String {
        val pattern = Regex("""<$container\s*/>|<$container>.*?</$container>""", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(text) ?: return text

        val indent = indentBefore(text, match.range.first)
        val sorted = values.distinct().sorted()
        val replacement = if (sorted.isEmpty()) {
            "<$container/>"
        } else {
            val children = sorted.joinToString("\n") { value ->
                if (asAttribute) {
                    """$indent    <$child id="${escape(value)}"/>"""
                } else {
                    "$indent    <$child>${escape(value)}</$child>"
                }
            }
            "<$container>\n$children\n$indent</$container>"
        }
        return text.replaceRange(match.range, replacement)
    }

    /** The whitespace the container's own line starts with, so new children line up under it. */
    private fun indentBefore(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
}
