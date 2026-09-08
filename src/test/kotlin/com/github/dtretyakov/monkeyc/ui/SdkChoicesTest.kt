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
}
