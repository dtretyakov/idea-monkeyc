package com.github.dtretyakov.monkeyc.sdk

import com.github.dtretyakov.monkeyc.lsp.ApiDocumentationLinks
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex.Declaration
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex.Kind

/**
 * Which page of the SDK's own documentation a symbol is written up on.
 *
 * The SDK ships the whole reference as HTML under `doc/`, laid out by module, and the doc
 * generator anchors each member as `<name>-<kind>`. Both halves are mechanical, so no index of
 * pages is needed — only the right suffix, and those were read off the shipped pages rather than
 * guessed: `const`, `var`, `instance_function`, `module`, `named_type` are the only five there are.
 *
 * A class or a module has a page of its own instead of an anchor on someone else's.
 */
object ApiDocumentation {

    fun urlFor(sdk: ConnectIqSdk?, declaration: Declaration): String? = when (declaration.kind) {
        Kind.MODULE, Kind.CLASS ->
            ApiDocumentationLinks.documentationUrl(sdk, member = "", module = declaration.qualifiedName)

        else -> ApiDocumentationLinks.documentationUrl(
            sdk,
            member = "${declaration.simpleName}-${anchorKind(declaration.kind)}",
            module = declaration.container,
        )
    }

    private fun anchorKind(kind: Kind): String = when (kind) {
        Kind.FUNCTION -> "instance_function"
        Kind.VARIABLE -> "var"
        Kind.CONSTANT -> "const"
        Kind.TYPE -> "named_type"
        Kind.MODULE, Kind.CLASS -> "module"
    }
}
