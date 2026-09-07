package com.github.dtretyakov.monkeyc.lsp

import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.client.features.FileUriSupport
import java.net.URI
import java.nio.file.Path

/**
 * Repairs the file URIs the Monkey C language server sends back.
 *
 * `textDocument/definition` answers with `file:/Users/…` — one slash, no authority — where the rest
 * of the protocol, and everything that consumes it, expects `file:///Users/…`. Left alone, every
 * go-to-definition lands nowhere. Outgoing URIs are unaffected; the server reads those correctly.
 */
object MonkeyCFileUriSupport : FileUriSupport {

    private const val MALFORMED_PREFIX = "file:/"
    private const val WELL_FORMED_PREFIX = "file:///"

    /**
     * The URI the server is told about a file, with symlinks resolved — see [CanonicalPaths] for
     * why a document it cannot match to a compiled file gets no answers at all.
     */
    override fun getFileUri(file: VirtualFile): URI? {
        val uri = FileUriSupport.DEFAULT.getFileUri(file) ?: return null
        val path = runCatching { Path.of(uri) }.getOrNull() ?: return uri
        val real = CanonicalPaths.of(path)
        return if (real == path) uri else real.toUri()
    }

    override fun findFileByUri(uri: String): VirtualFile? =
        FileUriSupport.DEFAULT.findFileByUri(repair(uri))

    override fun toString(file: VirtualFile): String? = FileUriSupport.DEFAULT.toString(file)

    override fun toString(uri: URI, encoded: Boolean): String? =
        FileUriSupport.DEFAULT.toString(uri, encoded)

    /** `file:/abs/path` becomes `file:///abs/path`; anything already well formed is left alone. */
    fun repair(uri: String): String =
        if (uri.startsWith(MALFORMED_PREFIX) && !uri.startsWith("file://")) {
            WELL_FORMED_PREFIX + uri.removePrefix(MALFORMED_PREFIX).trimStart('/')
        } else {
            uri
        }
}
