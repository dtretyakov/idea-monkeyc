package com.github.dtretyakov.monkeyc.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether a release is going out as the same thing the last one did.
 *
 * Two facts decide it and both are unrecoverable in their own way. A different key: Garmin rejects
 * the update, keeps no copy of yours, and the app is republished as a new listing without its
 * ratings, downloads or users. A different application id: the package is perfectly valid and
 * updates a different listing, or creates one nobody can find. Every branch here is a chance to
 * say so before it happens, or to say nothing when there is nothing to say.
 */
class ExportIdentityTest {

    private val original = "a4:1f:2b:3c:4d:5e:6f:70"
    private val other = "9c:20:11:22:33:44:55:66"

    @Test
    fun `the first export records the key rather than interrupting`() {
        val verdict = ExportIdentity.check(recorded = "", current = original)

        assertTrue(verdict is ExportIdentity.Verdict.FirstExport)
        assertEquals(original, (verdict as ExportIdentity.Verdict.FirstExport).value)
    }

    @Test
    fun `the same key again is silence`() {
        assertEquals(ExportIdentity.Verdict.Fine, ExportIdentity.check(original, original))
    }

    @Test
    fun `a different key is the one case that has to stop`() {
        val verdict = ExportIdentity.check(original, other)

        assertTrue(verdict is ExportIdentity.Verdict.Changed)
        val changed = verdict as ExportIdentity.Verdict.Changed
        assertEquals(original, changed.expected)
        assertEquals(other, changed.actual)
    }

    @Test
    fun `an unreadable key is left to the check that is about unreadable keys`() {
        // Reporting the same problem twice in different words helps nobody, and this rule has
        // nothing useful to say about a file that is not a key.
        assertEquals(ExportIdentity.Verdict.Fine, ExportIdentity.check(original, null))
        assertEquals(ExportIdentity.Verdict.Fine, ExportIdentity.check(original, ""))
    }

    @Test
    fun `the message names both keys and what is actually at stake`() {
        val message = ExportIdentity.describeKey(ExportIdentity.Verdict.Changed(original, other))

        assertTrue(message.contains(original), message)
        assertTrue(message.contains(other), message)
        // Not "the upload will fail" — the cost is the listing, and the message has to say so or
        // it reads as something to click past.
        assertTrue(message.contains("ratings"), message)
    }
    @Test
    fun `a changed application id is told apart from a changed key`() {
        // Same comparison, different stakes, so different words. The key costs the listing; the
        // application id quietly updates somebody else's.
        val changed = ExportIdentity.Verdict.Changed("0123abcd", "beta-9999")

        val message = ExportIdentity.describeApplicationId(changed)
        assertTrue(message.contains("0123abcd"), message)
        assertTrue(message.contains("beta-9999"), message)
        assertTrue(message.contains("beta"), "the commonest cause is Garmin's beta id: $message")
        assertFalse(message.contains("ratings"), "that is the key's problem, not this one: $message")
    }

    @Test
    fun `a project that has never exported records whichever id it used`() {
        val verdict = ExportIdentity.check(recorded = "", current = "0123abcd")

        assertEquals("0123abcd", (verdict as ExportIdentity.Verdict.FirstExport).value)
    }

    @Test
    fun `a manifest with no application id is left to the checks that are about manifests`() {
        assertEquals(ExportIdentity.Verdict.Fine, ExportIdentity.check("0123abcd", null))
    }

}
