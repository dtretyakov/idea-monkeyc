package com.github.dtretyakov.monkeyc.sdk

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Asking the JVM which one it is.
 *
 * Against the JVM running this test, because a version string parsed out of a fixture proves only
 * that the fixture was written correctly. The one thing that must never happen here is a hang:
 * this is called to fill in a line on a checklist.
 */
class JavaVersionTest {

    @Test
    fun `the running JVM reports a version`() {
        val java = JavaLocator.resolve(null)

        val version = JavaLocator.version(java)

        assertNotNull(version, "the JVM running this test could not say what it is")
        assertTrue(
            version!!.contains("version", ignoreCase = true) || version.contains(Regex("""\d+\.\d+""")),
            "not a version string: $version",
        )
    }

    @Test
    fun `something that is not a JVM answers nothing rather than throwing`() {
        assertNull(JavaLocator.version(Path.of("/definitely/not/here/java")))
    }
}
