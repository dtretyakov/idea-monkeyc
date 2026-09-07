package com.github.dtretyakov.monkeyc.lsp

import java.nio.file.Path

/**
 * Resolves symlinks out of the paths the language server is given.
 *
 * The server matches an open document against the files the compiler resolved, and the compiler
 * resolves through symlinks. Give it a workspace root or a document URI that reaches the same file
 * by another name and it answers "Could not find file context" — to the user, that is completion
 * and go-to-definition quietly doing nothing, with no error anywhere.
 *
 * Both halves have to agree, which was established by trying all four combinations against SDK
 * 9.1.0: only real path for the workspace *and* real path for the document works.
 *
 * On a path with no symlinks in it — which is most of them — this changes nothing.
 */
object CanonicalPaths {

    fun of(path: Path): Path = runCatching { path.toRealPath() }.getOrDefault(path)

    fun of(path: String): String = runCatching { Path.of(path).toRealPath().toString() }.getOrDefault(path)
}
