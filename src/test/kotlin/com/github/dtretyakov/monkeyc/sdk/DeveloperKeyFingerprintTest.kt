package com.github.dtretyakov.monkeyc.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Telling one signing key from another, before the store does it for you.
 *
 * Which key an app was signed with is the most consequential fact in this workflow and the only one
 * nothing records. Garmin rejects an update signed with a different key, does not keep a copy, and
 * offers no recovery: a changed key means republishing as a new listing and abandoning the ratings,
 * the downloads and every installed user. The fingerprint exists so that becomes a dialog rather
 * than a discovery.
 */
class DeveloperKeyFingerprintTest {

    @Test
    fun `the same key always gives the same fingerprint`(@TempDir temp: Path) {
        val key = DeveloperKey.generate(temp.resolve("developer_key.der"))

        assertEquals(DeveloperKey.fingerprint(key), DeveloperKey.fingerprint(key))
    }

    @Test
    fun `a copy of the key is the same key`(@TempDir temp: Path) {
        // Moving machines, restoring a backup, a colleague's checkout: the same bytes under another
        // name must not read as a different key, or the warning would fire on the safe case.
        val key = DeveloperKey.generate(temp.resolve("developer_key.der"))
        val copy = temp.resolve("backup/key.der").also {
            it.parent.toFile().mkdirs()
            it.writeBytes(key.readBytes())
        }

        assertEquals(DeveloperKey.fingerprint(key), DeveloperKey.fingerprint(copy))
    }

    @Test
    fun `two keys have two fingerprints`(@TempDir temp: Path) {
        val one = DeveloperKey.generate(temp.resolve("one.der"))
        val two = DeveloperKey.generate(temp.resolve("two.der"))

        assertNotEquals(DeveloperKey.fingerprint(one), DeveloperKey.fingerprint(two))
    }

    @Test
    fun `it is short enough to compare by eye, and shaped like a fingerprint`(@TempDir temp: Path) {
        val fingerprint = DeveloperKey.fingerprint(DeveloperKey.generate(temp.resolve("k.der")))!!

        assertEquals(8, fingerprint.split(":").size, "eight bytes: $fingerprint")
        assertTrue(fingerprint.matches(Regex("([0-9a-f]{2}:){7}[0-9a-f]{2}")), fingerprint)
    }

    @Test
    fun `it is not made of the secret`(@TempDir temp: Path) {
        // It is a digest of the public key. Nothing recognisable from the private file may appear
        // in something meant to be pasted into a chat window.
        val key = DeveloperKey.generate(temp.resolve("k.der"))
        val fingerprint = DeveloperKey.fingerprint(key)!!

        val secret = key.readBytes().joinToString("") { "%02x".format(it) }
        assertTrue(secret.indexOf(fingerprint.replace(":", "")) < 0, "the fingerprint is in the key file")
    }

    @Test
    fun `something that is not a key has no fingerprint`(@TempDir temp: Path) {
        assertNull(DeveloperKey.fingerprint(temp.resolve("absent.der")))
        assertNull(DeveloperKey.fingerprint(temp.resolve("junk.der").also { it.writeText("not a key") }))
    }
}
