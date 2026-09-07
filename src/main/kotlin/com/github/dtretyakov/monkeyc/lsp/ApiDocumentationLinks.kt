package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import kotlin.io.path.exists

/**
 * Turns the documentation links in the server's hovers into links that work here.
 *
 * The server writes them for VS Code, as commands:
 *
 * ```
 * <a href='command:monkeyc.viewApiDocumentation?["getWidth-instance_function","Toybox.Graphics.Dc"]'>
 * ```
 *
 * The SDK ships the same documentation as HTML, laid out by module — `doc/Toybox/Graphics/Dc.html`,
 * anchored by member — so the command can be rewritten into a link to the file on disk rather than
 * dropped. Where the file is missing, the link is unwrapped and the text kept, which is better than
 * offering a link that does nothing.
 */
object ApiDocumentationLinks {

    private val COMMAND = Regex(
        """(?:command:)?monkeyc\.viewApiDocumentation\?\[\s*"([^"]*)"\s*,\s*"([^"]*)"\s*]""",
    )

    /** An anchor whose href we could not turn into anything useful, kept as its own text. */
    private val DEAD_LINK = Regex("""<a\s+href=['"]command:[^'"]*['"]\s*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    /** The markdown equivalent: `[text](command:…)`. */
    private val DEAD_MARKDOWN_LINK = Regex("""\[([^]]*)]\(command:[^)]*\)""")

    fun rewrite(markup: String, sdk: ConnectIqSdk?): String {
        val linked = COMMAND.replace(markup) { match ->
            val member = match.groupValues[1]
            val module = match.groupValues[2]
            documentationUrl(sdk, member, module) ?: match.value
        }
        // Whatever is still a command link points at a command this IDE does not have.
        return DEAD_MARKDOWN_LINK.replace(DEAD_LINK.replace(linked) { it.groupValues[1] }) { it.groupValues[1] }
    }

    private fun documentationUrl(sdk: ConnectIqSdk?, member: String, module: String): String? {
        if (sdk == null || module.isEmpty()) return null
        val page = module.split('.').fold(sdk.root.resolve("doc")) { path, part -> path.resolve(part) }
        val file = page.resolveSibling("${page.fileName}.html")
        if (!file.exists()) return null
        return file.toUri().toString() + if (member.isNotEmpty()) "#$member" else ""
    }
}
