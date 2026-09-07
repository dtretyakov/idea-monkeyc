package com.github.dtretyakov.monkeyc.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

class DeveloperKeyTest {

    @Test
    fun `writes a 4096-bit RSA key in the format the compiler reads`(@TempDir temp: Path) {
        val key = DeveloperKey.generate(temp.resolve("nested/developer_key.der"))

        // The compiler reads the file as PKCS#8 DER; anything else fails at signing time, which is
        // the end of a build rather than the start of one.
        val parsed = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(key.readBytes())) as RSAPrivateKey

        assertEquals(4096, parsed.modulus.bitLength())
    }

    @Test
    fun `says what is wrong with a key before a build has to`(@TempDir temp: Path) {
        assertEquals("No such file.", DeveloperKey.problemWith(temp.resolve("missing.der")))

        val notAKey = temp.resolve("notes.der")
        notAKey.writeText("this is not a key")
        assertTrue(
            DeveloperKey.problemWith(notAKey)!!.startsWith("Not a PKCS#8"),
            DeveloperKey.problemWith(notAKey).orEmpty(),
        )

        assertNull(DeveloperKey.problemWith(DeveloperKey.generate(temp.resolve("good.der"))))
    }

    @Test
    fun `every key is a new one`(@TempDir temp: Path) {
        val first = DeveloperKey.generate(temp.resolve("a.der")).readBytes()
        val second = DeveloperKey.generate(temp.resolve("b.der")).readBytes()

        assertTrue(!first.contentEquals(second), "a shared key would be a shared developer identity")
    }
}
