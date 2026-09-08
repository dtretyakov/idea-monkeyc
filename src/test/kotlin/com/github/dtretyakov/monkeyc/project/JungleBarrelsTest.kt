package com.github.dtretyakov.monkeyc.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Reading `barrelPath` out of a jungle, in the shapes the Jungle Reference actually documents.
 *
 * The value is not a path: it is a `;`-separated list that may be bracketed, may name a directory
 * of barrels rather than one barrel, and may point at a barrel project's own jungle. Getting any
 * of those wrong shows the user an empty library node with no reason given, which is the failure
 * mode this project keeps having to design against.
 */
class JungleBarrelsTest {

    @Test
    fun `a plain list, a bracketed group, and a qualified key are all read`() {
        val jungle = write(
            """
            base.sourcePath = source
            base.barrelPath = barrels/Icon.barrel;barrels/Math.barrel
            fenix7.barrelPath = [b/round.jungle;b/rect.jungle]
            barrelPath = plain.barrel
            """,
        )

        assertEquals(
            listOf("barrels/Icon.barrel", "barrels/Math.barrel", "b/round.jungle", "b/rect.jungle", "plain.barrel"),
            JungleBarrels.entries(jungle),
        )
    }

    @Test
    fun `a comment is not an assignment, and a trailing comment is not part of the value`() {
        val jungle = write(
            """
            # base.barrelPath = commented.barrel
            base.barrelPath = real.barrel # and a note
            """,
        )

        assertEquals(listOf("real.barrel"), JungleBarrels.entries(jungle))
    }

    @Test
    fun `a value continued over several lines is one value`() {
        val jungle = write(
            """
            base.barrelPath = one.barrel;\
            two.barrel;\
            three.barrel
            """,
        )

        assertEquals(listOf("one.barrel", "two.barrel", "three.barrel"), JungleBarrels.entries(jungle))
    }

    @Test
    fun `an entry that needs the jungle resolved is left alone`() {
        // `$(...)` interpolates another entry; working out what it means is the compiler's job,
        // and a wrong guess would put a directory in the tree that is not a dependency at all.
        val jungle = write("base.barrelPath = \$(base.barrelPath);extra.barrel")

        assertEquals(listOf("extra.barrel"), JungleBarrels.entries(jungle))
    }

    @Test
    fun `a directory contributes the barrels inside it, and a jungle its own project`(@TempDir temp: Path) {
        temp.resolve("barrels").createDirectories()
        temp.resolve("barrels/Icon.barrel").writeText("")
        temp.resolve("barrels/Math.barrel").writeText("")
        temp.resolve("barrels/notes.txt").writeText("")
        temp.resolve("IconSource").createDirectories()
        temp.resolve("IconSource/barrel.jungle").writeText("")

        val jungle = temp.resolve("monkey.jungle")
        jungle.writeText("base.barrelPath = barrels;IconSource/barrel.jungle\n")

        val found = JungleBarrels.declaredIn(listOf(jungle))

        assertEquals(
            listOf("Icon.barrel", "Math.barrel", "IconSource"),
            found.map { it.fileName.toString() }.sortedBy { listOf("Icon.barrel", "Math.barrel", "IconSource").indexOf(it) },
        )
    }

    @Test
    fun `a barrel that is not on disk is not reported`(@TempDir temp: Path) {
        val jungle = temp.resolve("monkey.jungle")
        jungle.writeText("base.barrelPath = missing.barrel\n")

        assertTrue(JungleBarrels.declaredIn(listOf(jungle)).isEmpty())
    }

    private fun write(text: String): Path {
        val file = kotlin.io.path.createTempFile(suffix = ".jungle")
        file.writeText(text.trimIndent())
        file.toFile().deleteOnExit()
        return file
    }
}
