package com.github.dtretyakov.monkeyc.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * A signing key somewhere it can be committed.
 *
 * The restraint is the point. A key inside the project is normal — a real project keeps one in its
 * root behind a one-line `.gitignore` and is entirely fine — so this fires only on the combination
 * that is actually dangerous. A checklist that cries wolf is a checklist people stop reading, and
 * this one has to still be believed on the day it says something expensive.
 */
class DeveloperKeyLocationTest {

    private val root = Path.of("/home/dev/watchface")

    @Test
    fun `a key in the project that is ignored is fine`() {
        // The real project this was written from does exactly this.
        assertEquals(
            DeveloperKeyLocation.Verdict.Fine,
            DeveloperKeyLocation.check(root.resolve("developer_key"), listOf(root), ignored = true),
        )
    }

    @Test
    fun `a key in the project that is not ignored is one commit from being published`() {
        val verdict = DeveloperKeyLocation.check(root.resolve("developer_key"), listOf(root), ignored = false)

        assertTrue(verdict is DeveloperKeyLocation.Verdict.Committable)
    }

    @Test
    fun `a key outside the project is not the project's problem`() {
        val elsewhere = Path.of("/home/dev/.connectiq/developer_key.der")

        assertEquals(
            DeveloperKeyLocation.Verdict.Fine,
            DeveloperKeyLocation.check(elsewhere, listOf(root), ignored = false),
        )
    }

    @Test
    fun `not knowing whether it is ignored means saying nothing`() {
        // No version control, or nothing could answer. A false alarm about a leaked key is worse
        // than silence, because it is the kind that teaches people to skip the checklist.
        assertEquals(
            DeveloperKeyLocation.Verdict.Fine,
            DeveloperKeyLocation.check(root.resolve("developer_key"), listOf(root), ignored = null),
        )
    }

    @Test
    fun `no key at all is somebody else's check`() {
        assertEquals(
            DeveloperKeyLocation.Verdict.Fine,
            DeveloperKeyLocation.check(null, listOf(root), ignored = false),
        )
    }

    @Test
    fun `the message says both what is at risk and what to do`() {
        val verdict = DeveloperKeyLocation.check(root.resolve("developer_key"), listOf(root), false)
        val message = DeveloperKeyLocation.describe(verdict as DeveloperKeyLocation.Verdict.Committable)

        assertTrue(message.contains(".gitignore"), message)
        assertTrue(message.contains("backup"), message)
    }
}
