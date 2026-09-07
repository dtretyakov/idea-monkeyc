package com.github.dtretyakov.monkeyc.sdk

import java.nio.file.Path
import java.security.KeyPairGenerator
import kotlin.io.path.createParentDirectories
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

    fun generate(destination: Path): Path {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(BITS) }
        val key = generator.generateKeyPair().private

        check(key.format == "PKCS#8") { "expected a PKCS#8 key, got ${key.format}" }

        destination.createParentDirectories()
        destination.writeBytes(key.encoded)
        return destination
    }
}
