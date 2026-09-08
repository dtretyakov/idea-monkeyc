package com.github.dtretyakov.monkeyc.build

import com.github.dtretyakov.monkeyc.sdk.AppTypes
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes

/**
 * Measuring a build against the memory the device gives it.
 *
 * The number matters because the failure it prevents happens on somebody else's watch: an app at
 * 40% of a fenix 7 can be over the limit on a device the developer does not own, and until now
 * nothing said so until the store did.
 *
 * Every figure here is one the SDK actually ships — a watch app on a modern watch gets 768 KB, a
 * data field on an old one 16 KB.
 */
class MemoryBudgetTest {

    @Test
    fun `a manifest app type is translated to the catalogue's spelling`() {
        assertEquals("watchApp", AppTypes.catalogueName("watch-app"))
        assertEquals("watchFace", AppTypes.catalogueName("watchface"))
        assertEquals("audioContentProvider", AppTypes.catalogueName("audio-content-provider-app"))
    }

    @Test
    fun `a barrel and an unknown kind have no catalogue name`() {
        // A barrel declares no type and runs on nothing of its own; an unknown kind is one a later
        // SDK invented, and reading it as unsupported would be worse than admitting ignorance.
        assertNull(AppTypes.catalogueName(null))
        assertNull(AppTypes.catalogueName("something-garmin-adds-in-2027"))
    }

    @Test
    fun `an unknown app type is not reported as unsupported`() {
        assertTrue(device().supports("something-garmin-adds-in-2027"))
        assertTrue(device().supports(null), "a barrel builds for anything")
    }

    @Test
    fun `a device that cannot run this kind of app says so`() {
        assertFalse(device(limits = mapOf("watchFace" to 131_072L)).supports("datafield"))
    }

    @Test
    fun `a comfortable build states the number and stops there`(@TempDir temp: Path) {
        val report = MemoryBudget.of(prg(temp, bytes = 90_000), device(), "watch-app")!!

        assertEquals(786_432L, report.limit)
        assertFalse(report.tight)
        val described = MemoryBudget.describe(report)
        assertTrue(described.contains("768.0 KB"), described)
        assertTrue(described.endsWith("."), "nothing to warn about, so nothing after the number")
    }

    @Test
    fun `a build close to the limit says how much is left`(@TempDir temp: Path) {
        val report = MemoryBudget.of(prg(temp, bytes = 15_000), device(mapOf("datafield" to 16_384L)), "datafield")!!

        assertTrue(report.tight)
        assertFalse(report.overflowing)
        assertTrue(MemoryBudget.describe(report).contains("left"), MemoryBudget.describe(report))
    }

    @Test
    fun `a build over the limit says it will not load`(@TempDir temp: Path) {
        val report = MemoryBudget.of(prg(temp, bytes = 20_000), device(mapOf("datafield" to 16_384L)), "datafield")!!

        assertTrue(report.overflowing)
        assertTrue(MemoryBudget.describe(report).contains("will not load"))
    }

    @Test
    fun `nothing is claimed when anything is unknown`(@TempDir temp: Path) {
        val output = prg(temp, bytes = 1_000)

        assertNull(MemoryBudget.of(output, device = null, manifestAppType = "watch-app"), "an export has no one device")
        assertNull(MemoryBudget.of(output, device(), manifestAppType = null), "a barrel has no app type")
        assertNull(
            MemoryBudget.of(output, device(mapOf("watchFace" to 131_072L)), "datafield"),
            "this device has no budget for a data field, so there is no share to report",
        )
        assertNull(MemoryBudget.of(temp.resolve("never-built.prg"), device(), "watch-app"), "nothing was built")
    }

    private fun device(limits: Map<String, Long> = mapOf("watchApp" to 786_432L)) = ConnectIqDevice(
        id = "fenix7",
        displayName = "fenix 7",
        group = null,
        family = null,
        isTouch = false,
        sdkVersion = null,
        memoryLimits = limits,
    )

    private fun prg(temp: Path, bytes: Int): Path =
        temp.resolve("App.prg").also { it.writeBytes(ByteArray(bytes)) }
}
