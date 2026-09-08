package com.github.dtretyakov.monkeyc.build

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Answers whether a `.prg` still matches the sources and the flags it was built from.
 *
 * Without this, every Run recompiles the whole project — which for a watch app is a few seconds
 * each time, on the inner loop, for nothing. The compiler has no incremental mode to lean on, so
 * the decision has to be made before it is started at all.
 *
 * Two things can invalidate a build, and both are checked. The arguments, because switching device
 * or type-check level produces a different binary from the same sources; they are recorded in a
 * stamp file next to the output, so the answer survives an IDE restart. And the sources, by
 * modification time: anything under the project newer than the output means rebuild.
 *
 * The bias is deliberate. Anything unreadable, unstamped or unexpected counts as out of date — a
 * needless rebuild costs seconds, a skipped one costs an afternoon of debugging the wrong binary.
 */
object BuildFingerprint {

    /** Directory names that hold build output rather than input, and would make every build dirty. */
    private val IGNORED = setOf("bin", "out")

    fun isUpToDate(spec: BuildSpec, arguments: List<String>): Boolean = runCatching {
        if (!spec.output.exists()) return false
        val stamp = stampFor(spec.output)
        if (!stamp.exists() || stamp.readText() != id(arguments)) return false

        newestInput(spec) <= spec.output.getLastModifiedTime().toMillis()
    }.getOrDefault(false)

    fun record(spec: BuildSpec, arguments: List<String>) {
        runCatching { stampFor(spec.output).writeText(id(arguments)) }
    }

    fun invalidate(output: Path) {
        runCatching { stampFor(output).deleteIfExists() }
    }

    private fun stampFor(output: Path): Path = output.resolveSibling("${output.fileName}.build-id")

    private fun id(arguments: List<String>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(arguments.joinToString(" ").toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * The most recent change to anything the build reads.
     *
     * The project tree, plus the jungles and the developer key, which a project can keep outside
     * it. Hidden directories are skipped along with the output ones: `.git` alone would otherwise
     * make every build dirty, since a build is usually preceded by a save and a commit.
     */
    private fun newestInput(spec: BuildSpec): Long {
        var newest = 0L

        val root = spec.root.toFile()
        root.walkTopDown()
            // The root itself is always entered: a project living in `~/.config/watch` or in a
            // directory called `bin` is still a project, and skipping it here would leave no
            // inputs at all — which reads as "up to date" for ever.
            .onEnter { directory -> directory == root || (directory.name !in IGNORED && !directory.name.startsWith(".")) }
            .filter { it.isFile }
            .forEach { newest = maxOf(newest, it.lastModified()) }

        (spec.jungleFiles + listOfNotNull(spec.developerKey))
            .filter { !it.startsWith(spec.root) && it.isRegularFile() }
            .forEach { newest = maxOf(newest, it.getLastModifiedTime().toMillis()) }

        return newest
    }
}
