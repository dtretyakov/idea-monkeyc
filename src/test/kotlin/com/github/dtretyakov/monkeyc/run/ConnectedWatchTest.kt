package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Recognising the watch on the desk.
 *
 * The two sides of this never agree on spelling. A watch reports "Venu 2" over USB; the SDK calls
 * the same device `Venu® 2`, and of the 173 devices it ships, 171 have a display name carrying a
 * trademark mark or a diacritic — `fēnix® 7`, `vívoactive® 5`, `Enduro™ 3`.
 */
class ConnectedWatchTest {

    private fun device(id: String, displayName: String) = ConnectIqDevice(
        id = id,
        displayName = displayName,
        group = null,
        family = null,
        isTouch = false,
        sdkVersion = null,
        memoryLimits = mapOf("watchApp" to 65_536L),
    )

    private val catalogue = listOf(
        device("venu2", "Venu® 2"),
        device("fenix7", "fēnix® 7"),
        device("vivoactive5", "vívoactive® 5"),
        device("enduro3", "Enduro™ 3"),
        device("fr955", "Forerunner® 955"),
    )

    @Test
    fun `a plain product string finds the decorated catalogue name`() {
        assertEquals("venu2", ConnectedWatch.match("Venu 2", catalogue)?.id)
        assertEquals("fr955", ConnectedWatch.match("Forerunner 955", catalogue)?.id)
    }

    @Test
    fun `diacritics are handled without a table of special cases`() {
        // Spelling out `ē` and forgetting `í` is exactly the near-miss that makes a matcher look
        // like it works until somebody plugs in a vivoactive.
        assertEquals("fenix7", ConnectedWatch.match("fenix 7", catalogue)?.id)
        assertEquals("vivoactive5", ConnectedWatch.match("vivoactive 5", catalogue)?.id)
        assertEquals("enduro3", ConnectedWatch.match("Enduro 3", catalogue)?.id)
    }

    @Test
    fun `case and spacing do not matter`() {
        assertEquals("venu2", ConnectedWatch.match("VENU2", catalogue)?.id)
        assertEquals("venu2", ConnectedWatch.match("  venu 2  ", catalogue)?.id)
    }

    @Test
    fun `a device the catalogue does not have is not forced onto the nearest one`() {
        assertNull(ConnectedWatch.match("Venu 4", catalogue))
        assertNull(ConnectedWatch.match(null, catalogue))
        assertNull(ConnectedWatch.match("", catalogue))
    }

    @Test
    fun `building for the watch that is attached says nothing`() {
        val watch = catalogue.first { it.id == "venu2" }

        assertNull(ConnectedWatch.mismatch(built = "venu2", watch = watch, product = "Venu 2"))
    }

    @Test
    fun `building for another device is worth saying, and says why it matters`() {
        val watch = catalogue.first { it.id == "venu2" }

        val message = ConnectedWatch.mismatch(built = "fenix7", watch = watch, product = "Venu 2")!!

        assertTrue(message.contains("fenix7"), message)
        assertTrue(message.contains("Venu"), message)
        // The consequence is the point: a wrong-device .prg installs and then does nothing.
        assertTrue(message.contains("will not run"), message)
    }

    @Test
    fun `a watch that cannot be placed is not evidence of a mismatch`() {
        // A device the catalogue spells differently, or one not downloaded. Guessing here would
        // warn people away from builds that are perfectly correct.
        assertNull(ConnectedWatch.mismatch(built = "fenix7", watch = null, product = "Some New Watch"))
        assertNull(ConnectedWatch.mismatch(built = "fenix7", watch = null, product = null))
    }
}
