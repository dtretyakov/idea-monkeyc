package com.github.dtretyakov.monkeyc.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Choosing which SDK a project builds with.
 *
 * The case worth the test is a pin the machine cannot honour. These settings are committed, so the
 * path is as likely to be a colleague's as this machine's — and quietly showing "Follow the SDK
 * Manager" would make it look as though nobody had pinned anything, then throw their choice away
 * on the next save.
 */
class SdkChoicesTest {

    private val installed = listOf(
        Path.of("/Sdks/connectiq-sdk-mac-9.2.0"),
        Path.of("/Sdks/connectiq-sdk-mac-9.1.0"),
    )

    @Test
    fun `following the SDK Manager is the empty pin, and the first choice`() {
        val choices = MonkeyCConfigurable.SdkChoices(installed, pinned = "")

        assertEquals(MonkeyCConfigurable.SdkChoices.FOLLOW, choices.labels.first())
        assertEquals(MonkeyCConfigurable.SdkChoices.FOLLOW, choices.labelFor(""))
        assertEquals("", choices.pathFor(MonkeyCConfigurable.SdkChoices.FOLLOW))
    }

    @Test
    fun `an installed SDK reads as its directory name and stores as its path`() {
        val choices = MonkeyCConfigurable.SdkChoices(installed, pinned = "/Sdks/connectiq-sdk-mac-9.1.0")

        assertEquals("connectiq-sdk-mac-9.1.0", choices.labelFor("/Sdks/connectiq-sdk-mac-9.1.0"))
        assertEquals("/Sdks/connectiq-sdk-mac-9.1.0", choices.pathFor("connectiq-sdk-mac-9.1.0"))
    }

    @Test
    fun `a pin this machine does not have is kept, and marked`() {
        val choices = MonkeyCConfigurable.SdkChoices(installed, pinned = "/Sdks/connectiq-sdk-mac-7.4.3")

        val label = choices.labelFor("/Sdks/connectiq-sdk-mac-7.4.3")
        assertTrue(label.contains("not on this machine"), label)
        assertTrue(choices.labels.contains(label), "it has to be selectable, or saving would discard it")
        assertEquals("/Sdks/connectiq-sdk-mac-7.4.3", choices.pathFor(label))
    }

    @Test
    fun `every installed SDK is offered`() {
        val choices = MonkeyCConfigurable.SdkChoices(installed, pinned = "")

        assertEquals(3, choices.labels.size, "two SDKs plus following the manager")
    }

    @Test
    fun `the current SDK is named, because that is the question the user has`() {
        // "Current SDK" alone is a promise about the future. Which one it is today is what someone
        // opening this combo is actually asking, and the SDK Manager knows it — so it is shown.
        val choices = MonkeyCConfigurable.SdkChoices(
            installed,
            pinned = "",
            current = Path.of("/Sdks/connectiq-sdk-mac-9.2.0"),
        )

        assertEquals("Current SDK  (connectiq-sdk-mac-9.2.0)", choices.labels.first())
        // Still the entry an empty pin maps to, name or no name.
        assertEquals(choices.labels.first(), choices.labelFor(""))
        assertEquals("", choices.pathFor(choices.labels.first()))
    }

    @Test
    fun `with no current SDK it is the bare name, not a dangling bracket`() {
        val choices = MonkeyCConfigurable.SdkChoices(installed, pinned = "", current = null)

        assertEquals("Current SDK", choices.labels.first())
    }
}
