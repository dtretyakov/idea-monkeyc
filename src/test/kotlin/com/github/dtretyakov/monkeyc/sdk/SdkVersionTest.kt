package com.github.dtretyakov.monkeyc.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SdkVersionTest {

    @Test
    fun `orders by numeric parts, not by text`() {
        assertTrue(SdkVersion.parse("9.1.0")!! > SdkVersion.parse("8.10.0")!!)
        assertTrue(SdkVersion.parse("4.1.6")!! > SdkVersion.parse("4.1.5")!!)
        assertTrue(SdkVersion.parse("4.2")!! > SdkVersion.parse("4.1.9")!!)
    }

    @Test
    fun `missing trailing parts count as zero`() {
        assertEquals(0, SdkVersion.parse("7.0")!!.compareTo(SdkVersion.parse("7.0.0")!!))
    }

    @Test
    fun `a beta is the version it is a beta of`() {
        // Garmin ships 8.1.0-beta before 8.1.0, and it has the feature we gate on.
        assertTrue(SdkVersion.parse("8.1.0-beta")!! >= SdkVersion.LANGUAGE_SERVER_MINIMUM)
    }

    @Test
    fun `keeps the text it was given`() {
        assertEquals("9.1.0", SdkVersion.parse(" 9.1.0\n")!!.toString())
    }

    @Test
    fun `refuses what is not a version`() {
        assertNull(SdkVersion.parse(null))
        assertNull(SdkVersion.parse(""))
        assertNull(SdkVersion.parse("unknown"))
    }
}
