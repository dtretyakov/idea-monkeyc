package com.github.dtretyakov.monkeyc.sdk

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Makes the key the compiler signs an app with.
 *
 * Garmin's instructions are a pair of `openssl` commands, and the VS Code extension shells out to
 * them. It does not need to: the file is a 4096-bit RSA private key in PKCS#8 DER, which is exactly
 * what `PrivateKey.getEncoded()` returns — so the JVM the IDE is already running on can write it,
 * on a machine that has no openssl at all.
 *
 * The key identifies the developer to the Connect IQ Store. It is not a secret the plugin should
 * be casual with: it is written where the user asked and nowhere else, and never copied.
 */
object DeveloperKey {

    private const val BITS = 4096

    /** Enough to distinguish keys by eye without becoming a wall of hex. */
    private const val FINGERPRINT_BYTES = 8

    /**
     * What is wrong with a key, said in the settings dialog rather than at the end of a build.
     *
     * The compiler reads the file as PKCS#8 DER and says little when it cannot; the same check
     * here costs milliseconds and happens while the user is still looking at the field.
     */
    fun problemWith(path: Path): String? {
        if (!path.exists()) return "No such file."
        if (!path.isRegularFile()) return "Not a file."

        val bytes = runCatching { path.readBytes() }.getOrElse { return "Cannot be read." }
        return runCatching {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
            null
        }.getOrElse {
            "Not a PKCS#8 RSA private key. Generate one, or export the one the SDK Manager made."
        }
    }

    /**
     * A short, stable identifier for the key — safe to write down, show and commit.
     *
     * Which key an app was signed with is the most consequential fact in this whole workflow and
     * the only one nothing records. Garmin: "You will need to use the same key to sign updates to
     * an existing app on the store", "If you lose your original signing key you will not be able to
     * update your app", and "Your upload will be rejected If the developer key has changed since
     * your last upload." There is no recovery — a different key means republishing as a new listing
     * and abandoning the ratings, the download count and every installed user.
     *
     * So the point of this is to notice *before* the upload does. It is a SHA-256 of the **public**
     * key, derived from the private one's modulus and exponent, so it identifies the key pair
     * without being any part of the secret: two people can compare fingerprints in a chat window
     * safely.
     *
     * Null when the file is not a key this can read, which [problemWith] is the place to say.
     */
    fun fingerprint(path: Path): String? = runCatching {
        val bytes = path.readBytes()
        val private = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        val crt = private as? RSAPrivateCrtKey ?: return null
        val public = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent))

        MessageDigest.getInstance("SHA-256")
            .digest(public.encoded)
            // Grouped in pairs, and only the first few: this is read aloud and compared by eye, and
            // sixty-four hex characters is not. Eight bytes is far more than enough to tell two of
            // somebody's keys apart.
            .take(FINGERPRINT_BYTES)
            .joinToString(":") { "%02x".format(it) }
    }.getOrNull()

    fun generate(destination: Path): Path {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(BITS) }
        val key = generator.generateKeyPair().private

        check(key.format == "PKCS#8") { "expected a PKCS#8 key, got ${key.format}" }

        destination.createParentDirectories()
        destination.writeBytes(key.encoded)
        restrictToOwner(destination)
        return destination
    }

    /**
     * Takes the new key away from everyone but its owner.
     *
     * A default umask is 022, so a private key written with no thought about it lands readable by
     * every account on the machine — and `openssl`, which is what Garmin's own instructions use,
     * does not leave it that way. Best effort by design: a filesystem without POSIX permissions
     * (Windows, or a mounted share) gets the `File` fallback, and a filesystem that refuses both
     * still gets a working key, because failing to *narrow* permissions is not a reason to leave
     * the user without one.
     */
    private fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }.onFailure {
            val file = path.toFile()
            runCatching {
                file.setReadable(false, false)
                file.setWritable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
            }
        }
    }
}
