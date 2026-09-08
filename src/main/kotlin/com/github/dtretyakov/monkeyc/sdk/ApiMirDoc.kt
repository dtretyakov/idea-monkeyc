package com.github.dtretyakov.monkeyc.sdk

/**
 * Garmin's `//!` documentation, as the HTML the IDE renders in place of it.
 *
 * `api.mir` is more comment than code — a hundred-line class carries four hundred lines of `//!`
 * above it — so read as plain text it is mostly punctuation. This is the same content the SDK's
 * HTML reference is generated from, and rendering it in the editor is what makes the API file read
 * the way JDK sources do.
 *
 * The tags are Garmin's own, near enough to javadoc to be obvious: `@param`, `@return`, `@throws`,
 * `@option`, `@since`, `@example`, `@see`, `@note`. Types are written `[Toybox::Lang::String]` and
 * cross-references `{Toybox::Graphics::Dc Dc}`, both of which read better as code than as prose.
 */
object ApiMirDoc {

    private val TAG = Regex("""^@(\w+)\s*(.*)$""")

    /** `{Toybox::Graphics::Dc Dc}` — a reference, with the text to show after the path. */
    private val REFERENCE = Regex("""\{([\w:]+)(?:\s+([^}]*))?}""")

    /** `[Toybox::Lang::String]` — a type, on its own. */
    private val TYPE = Regex("""\[([\w:]+)]""")

    /** Tags whose body is a name followed by a type and a description. */
    private val NAMED = setOf("param", "option")

    /** Tags that are one line of prose about the whole declaration. */
    private val SINGLE = setOf("since", "deprecated", "see", "note", "resource")

    fun toHtml(comment: String): String {
        val lines = comment.lineSequence()
            .map { it.trimStart().removePrefix("//!").let { rest -> rest.removePrefix(" ") } }
            .toList()

        val body = StringBuilder()
        val rows = StringBuilder()
        val notes = StringBuilder()
        var example: StringBuilder? = null

        fun closeExample() {
            example?.let { body.append("<pre><code>").append(it.toString().trimEnd()).append("</code></pre>") }
            example = null
        }

        lines.forEach { line ->
            val tag = TAG.find(line.trim())

            if (tag == null) {
                if (example != null) {
                    example?.append(escape(line))?.append('\n')
                } else if (line.isBlank()) {
                    // A paragraph break, but only between paragraphs: a comment that is one blank
                    // line, or that ends with one, must render as nothing rather than as a gap.
                    if (body.isNotEmpty()) body.append("<p>")
                } else {
                    body.append(inline(line)).append(' ')
                }
                return@forEach
            }

            closeExample()
            val name = tag.groupValues[1]
            val rest = tag.groupValues[2]

            when {
                name == "example" -> example = StringBuilder()

                name in NAMED -> {
                    val label = rest.substringBefore(' ')
                    val description = rest.substringAfter(' ', "")
                    rows.append("<tr><td valign='top'><code>").append(escape(label)).append("</code></td>")
                        .append("<td>").append(inline(description)).append("</td></tr>")
                }

                name == "return" || name == "throws" ->
                    rows.append("<tr><td valign='top'><i>").append(name).append("</i></td>")
                        .append("<td>").append(inline(rest)).append("</td></tr>")

                name in SINGLE ->
                    notes.append("<p><i>").append(name).append("</i> ").append(inline(rest))

                // An unknown tag is still information; showing it beats dropping it.
                else -> notes.append("<p><i>").append(escape(name)).append("</i> ").append(inline(rest))
            }
        }
        closeExample()

        return buildString {
            append(body.toString().trim().removeSuffix("<p>").trim())
            if (rows.isNotEmpty()) append("<table>").append(rows).append("</table>")
            append(notes)
        }
    }

    /**
     * The markup Garmin writes inside a line.
     *
     * References are rendered as the plain path rather than as links: the SDK's HTML pages are the
     * thing worth linking to, and Shift+F1 already opens them for the symbol under the caret.
     */
    private fun inline(text: String): String {
        val escaped = escape(text)
        return TYPE.replace(REFERENCE.replace(escaped) { match ->
            val path = match.groupValues[1].replace("::", ".")
            val shown = match.groupValues[2].ifBlank { path }
            "<code>${shown.trim()}</code>"
        }) { match -> "<code>${match.groupValues[1].replace("::", ".")}</code>" }
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
