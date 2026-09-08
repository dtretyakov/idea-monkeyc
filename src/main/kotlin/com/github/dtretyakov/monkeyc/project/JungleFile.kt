package com.github.dtretyakov.monkeyc.project

import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * The assignments in a jungle file, as text.
 *
 * Deliberately not a jungle interpreter. Resolving a jungle properly means resolving its variables
 * and its per-device qualifiers, which is the compiler's job — this reads the two or three
 * assignments the IDE has to agree with the compiler about, and skips anything it cannot make
 * sense of rather than guessing.
 */
object JungleFile {

    /** Every `key = value` in the file, in order, with comments and continuations resolved. */
    fun assignments(jungle: Path): List<Pair<String, String>> {
        if (!jungle.isRegularFile()) return emptyList()
        val text = jungle.runCatching { readText() }.getOrNull() ?: return emptyList()

        return joinContinuations(text.lineSequence())
            .mapNotNull { line ->
                val statement = line.substringBefore('#').trim()
                val separator = statement.indexOf('=')
                if (separator < 0) return@mapNotNull null
                statement.take(separator).trim() to statement.drop(separator + 1).trim()
            }
            .toList()
    }

    /** The last value assigned to [key], or null when the file never assigns it. */
    fun valueOf(jungle: Path, key: String): String? =
        assignments(jungle).lastOrNull { it.first == key }?.second?.takeIf { it.isNotEmpty() }

    /**
     * The manifest this jungle names, resolved against the jungle's own directory.
     *
     * `project.manifest` is how a project has more than one manifest, and it is the whole reason
     * this exists: a real project in the wild builds two variants from one source tree —
     * `monkey.jungle` naming `manifest.xml` and `monkey-api51.jungle` naming `manifest-api51.xml`,
     * with different products and different minimum API levels. Reading `manifest.xml` regardless,
     * as this plugin used to, means every device-derived answer is about the wrong file.
     *
     * Null when the jungle names none, which is the common case and means the default.
     */
    fun manifest(jungle: Path): Path? {
        val declared = valueOf(jungle, MANIFEST_KEY) ?: return null
        // A `$(...)` reference needs the whole jungle resolved to mean anything, which is exactly
        // what this does not do.
        if (declared.contains("$(")) return null

        val path = Path.of(declared)
        return (if (path.isAbsolute) path else jungle.parent?.resolve(path) ?: return null).normalize()
    }

    /**
     * A jungle assignment can be continued on the next line with a trailing backslash, and a value
     * split over three lines is common enough in a real barrels jungle to matter.
     */
    private fun joinContinuations(lines: Sequence<String>): Sequence<String> = sequence {
        val pending = StringBuilder()
        lines.forEach { line ->
            if (line.endsWith('\\')) {
                pending.append(line.dropLast(1))
            } else {
                yield(pending.append(line).toString())
                pending.setLength(0)
            }
        }
        if (pending.isNotEmpty()) yield(pending.toString())
    }

    private const val MANIFEST_KEY = "project.manifest"
}
