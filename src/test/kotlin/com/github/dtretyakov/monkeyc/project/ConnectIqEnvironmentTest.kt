package com.github.dtretyakov.monkeyc.project

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.project.ConnectIqEnvironment.Fix
import com.github.dtretyakov.monkeyc.project.ConnectIqEnvironment.Item
import com.github.dtretyakov.monkeyc.project.ConnectIqEnvironment.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Which of the setup problems are in the user's way, and which are merely worth knowing.
 *
 * The distinction is the whole reason the checklist can also drive the editor banner: a banner that
 * fires whenever anything at all is imperfect is a banner people learn to close without reading.
 */
class ConnectIqEnvironmentTest {

    @Test
    fun `no SDK manager and no SDK is a dead stop`() {
        val item = ConnectIqEnvironment.sdkManager(where = null, hasSdk = false)

        assertEquals(Status.MISSING, item.status)
        assertTrue(item.blocking, "with no SDK and no way to get one, there is nothing the user can do next")
        assertEquals(Fix.SDK_MANAGER, item.fix)
    }

    @Test
    fun `no SDK manager but an SDK anyway is worth knowing, not worth blocking`() {
        // An SDK unpacked by hand, or inherited from a colleague, works perfectly well.
        val item = ConnectIqEnvironment.sdkManager(where = null, hasSdk = true)

        assertEquals(Status.MISSING, item.status)
        assertFalse(item.blocking)
        assertNull(ConnectIqEnvironment.firstProblem(listOf(item)), "it must not raise the banner on its own")
    }

    @Test
    fun `an installed manager is simply reported`() {
        val item = ConnectIqEnvironment.sdkManager(where = Path.of("/Applications/SdkManager.app"), hasSdk = false)

        assertEquals(Status.READY, item.status)
        assertNull(item.fix)
    }

    @Test
    fun `no devices stops a build, and says why`() {
        val item = ConnectIqEnvironment.devices(count = 0, hasSdk = true)

        assertEquals(Status.MISSING, item.status)
        assertTrue(item.blocking)
        assertTrue(item.detail.contains("built for one device"), item.detail)
    }

    @Test
    fun `devices are counted when there are some`() {
        val item = ConnectIqEnvironment.devices(count = 166, hasSdk = true)

        assertEquals(Status.READY, item.status)
        assertTrue(item.detail.contains("166"), item.detail)
    }

    @Test
    fun `the first blocking problem is the one to report, in list order`() {
        val items = listOf(
            Item("SDK Manager", Status.MISSING, "informational", Fix.SDK_MANAGER, blocking = false),
            Item("Connect IQ SDK", Status.MISSING, "the real problem", Fix.SDK_MANAGER),
            Item("Devices", Status.MISSING, "a consequence of the one above", Fix.SDK_MANAGER),
        )

        assertEquals("Connect IQ SDK", ConnectIqEnvironment.firstProblem(items)?.name)
        assertFalse(ConnectIqEnvironment.isReady(items))
    }

    @Test
    fun `an environment whose only complaint is informational counts as ready`() {
        val items = listOf(
            Item("SDK Manager", Status.MISSING, "informational", Fix.SDK_MANAGER, blocking = false),
            Item("Connect IQ SDK", Status.READY, "9.1.0"),
        )

        assertTrue(ConnectIqEnvironment.isReady(items))
    }

    @Test
    fun `a device that cannot be read is reported, not hidden`() {
        // Downloaded-but-broken and never-downloaded look identical to the user, and only one of
        // them is fixed by downloading it again.
        val item = ConnectIqEnvironment.devices(count = 164, hasSdk = true, unreadable = listOf("fenix7", "venu2"))

        assertEquals(Status.MISSING, item.status)
        assertFalse(item.blocking, "164 usable devices are enough to build with")
        assertTrue(item.detail.contains("fenix7"), item.detail)
        assertEquals(Fix.SDK_MANAGER, item.fix)
    }

    @Test
    fun `all devices readable is the quiet case`() {
        val item = ConnectIqEnvironment.devices(count = 166, hasSdk = true, unreadable = emptyList())

        assertEquals(Status.READY, item.status)
    }
    @Test
    fun `live analysis switched off is reported, and does not block`() {
        val item = ConnectIqEnvironment.liveAnalysis(enabled = false)

        assertEquals(Status.MISSING, item.status)
        assertFalse(item.blocking, "it is a choice, and the build does not care")
        assertNull(item.fix, "the fix is the checkbox the user already knows about")
        assertNull(
            ConnectIqEnvironment.firstProblem(listOf(item)),
            "turning it off must not raise the editor banner",
        )
        assertTrue(
            item.detail.contains("completion"),
            "the point of reporting it is to explain the missing completion",
        )
    }

    @Test
    fun `live analysis switched on is the quiet case`() {
        val item = ConnectIqEnvironment.liveAnalysis(enabled = true)

        assertEquals(Status.READY, item.status)
        assertTrue(ConnectIqEnvironment.isReady(listOf(item)))
    }

    @Test
    fun `an SDK the project pinned but the machine lacks is reported, not hidden`() {
        // The pin falls back to the current SDK rather than failing, because these settings are
        // committed and the path may be a colleague's. A silent fallback would be the exact
        // problem pinning exists to prevent.
        val sdk = ConnectIqSdk.at(Path.of("/Sdks/connectiq-sdk-mac-9.2.0"))

        val item = ConnectIqEnvironment.sdk(sdk, pinnedButMissing = "/Sdks/connectiq-sdk-mac-7.4.3")

        assertEquals(Status.MISSING, item.status)
        assertFalse(item.blocking, "it built with something; it is wrong, not stuck")
        assertTrue(item.detail.contains("7.4.3"), item.detail)
    }

    @Test
    fun `an SDK with no pin to disappoint is simply reported`() {
        val item = ConnectIqEnvironment.sdk(ConnectIqSdk.at(Path.of("/Sdks/connectiq-sdk-mac-9.2.0")))

        assertEquals(Status.READY, item.status)
    }

}
