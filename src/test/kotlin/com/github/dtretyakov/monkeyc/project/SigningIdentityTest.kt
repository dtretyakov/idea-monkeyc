package com.github.dtretyakov.monkeyc.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether the key about to sign a release is the one that signed the last.
 *
 * The consequence is what makes this worth a rule of its own: Garmin rejects an update signed with
 * a different key, keeps no copy of yours, and offers no recovery — the app is republished as a new
 * listing, losing its ratings, its downloads and its users. Every branch here is a chance to say
 * that before it happens, or to say nothing when there is nothing to say.
 */
class SigningIdentityTest {

    private val original = "a4:1f:2b:3c:4d:5e:6f:70"
    private val other = "9c:20:11:22:33:44:55:66"

    @Test
    fun `the first export records the key rather than interrupting`() {
        val verdict = SigningIdentity.check(recorded = "", current = original)

        assertTrue(verdict is SigningIdentity.Verdict.FirstExport)
        assertEquals(original, (verdict as SigningIdentity.Verdict.FirstExport).fingerprint)
    }

    @Test
    fun `the same key again is silence`() {
        assertEquals(SigningIdentity.Verdict.Fine, SigningIdentity.check(original, original))
    }

    @Test
    fun `a different key is the one case that has to stop`() {
        val verdict = SigningIdentity.check(original, other)

        assertTrue(verdict is SigningIdentity.Verdict.Changed)
        val changed = verdict as SigningIdentity.Verdict.Changed
        assertEquals(original, changed.expected)
        assertEquals(other, changed.actual)
    }

    @Test
    fun `an unreadable key is left to the check that is about unreadable keys`() {
        // Reporting the same problem twice in different words helps nobody, and this rule has
        // nothing useful to say about a file that is not a key.
        assertEquals(SigningIdentity.Verdict.Fine, SigningIdentity.check(original, null))
        assertEquals(SigningIdentity.Verdict.Fine, SigningIdentity.check(original, ""))
    }

    @Test
    fun `the message names both keys and what is actually at stake`() {
        val message = SigningIdentity.describe(SigningIdentity.Verdict.Changed(original, other))

        assertTrue(message.contains(original), message)
        assertTrue(message.contains(other), message)
        // Not "the upload will fail" — the cost is the listing, and the message has to say so or
        // it reads as something to click past.
        assertTrue(message.contains("ratings"), message)
    }
}
