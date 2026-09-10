package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.AppType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The two lines that keep a manifest identifier out of a sentence.
 *
 * Both messages that use them are the ones a user meets on a bad day — no device runs this kind of
 * app, or the device they picked does not — so they are the last place to show `watch-app` where a
 * name belongs, or to write "a Audio Content Provider App".
 */
class AppTypeTextTest {

    private val known = listOf(
        AppType("watch-app", "Watch App", ""),
        AppType("audio-content-provider-app", "Audio Content Provider App", ""),
    )

    @Test
    fun `an identifier becomes the name the SDK ships for it`() {
        assertEquals("Watch App", appTypeLabel("watch-app", known))
        assertEquals("Audio Content Provider App", appTypeLabel("audio-content-provider-app", known))
    }

    @Test
    fun `an SDK too old to name it loses the polish and keeps the sentence`() {
        assertEquals("watch-app", appTypeLabel("watch-app", emptyList()))
        assertEquals("datafield", appTypeLabel("datafield", known))
    }

    @Test
    fun `no app type at all is no label, which is what the caller branches on`() {
        assertNull(appTypeLabel(null, known))
    }

    @Test
    fun `the article follows the name, because one of Garmin's own begins with a vowel`() {
        assertEquals("a", article("Watch App"))
        assertEquals("a", article("Data Field"))
        assertEquals("an", article("Audio Content Provider App"))
        // Lower case reaches this too, by way of the identifier fallback above.
        assertEquals("an", article("audio-content-provider-app"))
        assertEquals("a", article("watch-app"))
    }

    @Test
    fun `an empty name does not throw`() {
        assertEquals("a", article(""))
    }
}
