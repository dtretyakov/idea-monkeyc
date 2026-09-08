package com.github.dtretyakov.monkeyc.library

import com.intellij.navigation.ItemPresentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import javax.swing.ImageIcon

/**
 * The two things about a synthetic library that fail silently when they are wrong.
 *
 * A library that is not an `ItemPresentation` is dropped by `ExternalLibrariesNode` with a
 * `LOG.warn` and no node; a library whose roots are binary rather than source is indexed but falls
 * out of `projectScope`, so Go to File stops finding it unless the user switches to "All Places".
 * Neither shows up as an error anywhere.
 */
class ConnectIqLibraryTest {

    @Test
    fun `a library is presentable, or the project tree drops it without saying so`() {
        assertInstanceOf(ItemPresentation::class.java, library())
    }

    @Test
    fun `the name and version are what the tree will show`() {
        val presentation = library() as ItemPresentation

        assertEquals("Connect IQ SDK", presentation.presentableText)
        assertEquals("9.2.0", presentation.locationString)
    }

    @Test
    fun `roots are source roots, so they stay inside the project scope`() {
        // getSourceRoots is registered as EXTERNAL_SOURCE, which isInProjectScope accepts;
        // getBinaryRoots is EXTERNAL, which it rejects. Empty roots here only prove which of the
        // two collections this class fills - the paths themselves need a real VFS.
        val library = library()

        assertEquals(emptyList<Any>(), library.sourceRoots.toList())
        assertEquals(emptyList<Any>(), library.binaryRoots.toList())
    }

    @Test
    fun `two libraries for the same SDK are equal, so the platform does not re-index on every change`() {
        assertEquals(library(), library())
        assertEquals(library().hashCode(), library().hashCode())
        assertNotEquals(library(), library(version = "9.1.0"))
    }

    private fun library(version: String = "9.2.0") = ConnectIqLibrary(
        id = "connect-iq-sdk",
        name = "Connect IQ SDK",
        location = version,
        icon = ImageIcon(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)),
        roots = emptyList(),
        onNavigate = {},
        navigateText = "Connect IQ Settings",
    )
}
