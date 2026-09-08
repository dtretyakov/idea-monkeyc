package com.github.dtretyakov.monkeyc.project

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * The barrels a project depends on, read out of its jungle files.
 *
 * A barrel is declared in two places and this is the half that says where the code is:
 * `base.barrelPath` in a jungle, while `manifest.xml` only names the barrel and a version. The
 * value is a `;`-separated list, optionally grouped in `[...]`, of three kinds of thing — a
 * `.barrel` file, a directory holding some, or the jungle of a barrel project being used from
 * source.
 *
 * This is deliberately not a jungle interpreter. Resolving a jungle properly means resolving its
 * variables and its per-device qualifiers, which is the compiler's job; what is wanted here is
 * only "which directories hold library code", and an entry this cannot make sense of is skipped
 * rather than guessed at. The build is unaffected either way — it goes through the compiler, which
 * reads the jungle itself.
 */
object JungleBarrels {

    /**
     * Every barrel the given jungles point at, as paths that exist.
     *
     * A `.barrel` file comes back as the file, not a directory: it is a zip, and mounting it is
     * the caller's business.
     */
    fun declaredIn(jungles: List<Path>): List<Path> =
        jungles.filter { it.isRegularFile() }
            .flatMap { jungle -> entries(jungle).flatMap { resolve(jungle.parent, it) } }
            .distinct()

    /** The raw right-hand sides of every `barrelPath` assignment in one jungle. */
    fun entries(jungle: Path): List<String> {
        val text = jungle.runCatching { readText() }.getOrNull() ?: return emptyList()

        return joinContinuations(text.lineSequence())
            .mapNotNull { line ->
                val statement = line.substringBefore('#').trim()
                val separator = statement.indexOf('=')
                if (separator < 0) return@mapNotNull null

                val key = statement.take(separator).trim()
                if (key != "barrelPath" && !key.endsWith(".barrelPath")) return@mapNotNull null

                statement.drop(separator + 1)
            }
            .flatMap { value -> value.split(';').map { it.trim().trim('[', ']').trim() } }
            .filter { it.isNotEmpty() }
            // A `$(...)` reference to another entry needs the whole jungle resolved to mean
            // anything, which is exactly what this does not do.
            .filterNot { it.contains("$(") }
            .toList()
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

    private fun resolve(base: Path?, entry: String): List<Path> {
        val path = Path.of(entry).let { if (it.isAbsolute) it else base?.resolve(it) ?: return emptyList() }
            .normalize()
        if (!path.exists()) return emptyList()

        return when {
            // A directory of barrels; the reference says every `.barrel` in it is picked up.
            path.isDirectory() -> path.runCatching { listDirectoryEntries("*.barrel") }.getOrDefault(emptyList())

            // A barrel project used from source: its jungle names the project, and the code is
            // beside it.
            path.extension == "jungle" -> listOfNotNull(path.parent)

            else -> listOf(path)
        }
    }
}
