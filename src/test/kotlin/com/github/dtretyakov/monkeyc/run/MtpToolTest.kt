package com.github.dtretyakov.monkeyc.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Reading what `mtp-rs` says, without a watch to say it.
 *
 * The shapes here are the tool's own `DeviceRow` and `PutRow` structs, read out of its source, and
 * the working invocation is the one recorded in a real project's notes — not the documentation's
 * example, which differs. Everything a unit test can settle is settled here, because the rest needs
 * hardware and a USB cable that fits.
 */
class MtpToolTest {

    /** What `mtp-rs --json devices` answers with a Venu 2 attached, in its own field names. */
    private val venu2 = """
        [{"vendor_id":2334,"product_id":3,"manufacturer":"Garmin","product":"Venu 2",
          "serial_number":"0123456789","location_id":338690048,"location":"14300000",
          "speed":"High","match_reason":"interface_string"}]
    """.trimIndent()

    @Test
    fun `a connected watch is read, and known to be a Garmin`() {
        val devices = MtpTool.parseDevices(venu2)

        assertEquals(1, devices.size)
        val watch = devices.first()
        assertEquals("Venu 2", watch.product)
        assertEquals("0123456789", watch.serial_number)
        assertTrue(watch.isGarmin, "0x091e is Garmin, which is how a watch is told from a phone")
        assertEquals("Venu 2", watch.displayName)
        // The Venu 2 has no standard MTP class and is found by its interface string; a detector
        // that only accepted the standard class would report no watch at all.
        assertEquals("interface_string", watch.match_reason)
    }

    @Test
    fun `nothing plugged in is an empty list, not a failure`() {
        // Confirmed against the installed binary: `devices --json` prints `[]` and exits 0. Only
        // the commands that need a device exit 2, so detection reads the JSON rather than the code.
        assertEquals(emptyList<MtpDeviceInfo>(), MtpTool.parseDevices("[]"))
        assertEquals(emptyList<MtpDeviceInfo>(), MtpTool.parseDevices(""))
    }

    @Test
    fun `a phone is not a watch`() {
        val phone = """[{"vendor_id":6353,"product_id":20194,"manufacturer":"Google","product":"Pixel 9",
                        "serial_number":"abc","location_id":1,"location":"1","match_reason":"standard_class"}]"""

        assertFalse(MtpTool.parseDevices(phone).first().isGarmin)
    }

    @Test
    fun `output that is not what we expect yields nothing rather than nonsense`() {
        assertEquals(emptyList<MtpDeviceInfo>(), MtpTool.parseDevices("not json"))
        assertNull(MtpTool.parseUpload("not json"))
    }

    @Test
    fun `an upload reports where it went and whether it was verified`() {
        val put = """{"operation":"put","local_path":"bin/ha-integration.prg",
                      "remote_path":"/GARMIN/APPS/HA-INTEG.PRG","filename":"HA-INTEG.PRG",
                      "handle":42,"bytes":231484,"replaced":false,"verified":true}"""

        val upload = MtpTool.parseUpload(put)!!
        assertEquals("/GARMIN/APPS/HA-INTEG.PRG", upload.remote_path)
        assertEquals(231484, upload.bytes)
        assertTrue(upload.verified)
    }

    @Test
    fun `the upload command is the one known to work on a real watch`() {
        val arguments = MtpTool.uploadArguments("0123456789", Path.of("bin/app.prg"), "/GARMIN/APPS/APP.PRG")

        // `--device` before the subcommand, `--verify` after it, and no `--replace`: current
        // devices hide a .prg once taken, so there is usually no visible file to replace.
        assertEquals(
            listOf("--json", "--device", "0123456789", "put", "--verify", "bin/app.prg", "/GARMIN/APPS/APP.PRG"),
            arguments,
        )
        assertFalse(arguments.contains("--replace"))
    }

    @Test
    fun `with one device attached the serial can be left out`() {
        val arguments = MtpTool.uploadArguments(null, Path.of("a.prg"), "/GARMIN/APPS/A.PRG")

        assertFalse(arguments.contains("--device"))
    }

    @Test
    fun `a device that cannot be opened names the applications that take it`() {
        // The common macOS failure, and the one a generic message wastes: ptpcamerad claims MTP
        // devices on connection, and Garmin Express and Android File Transfer both grab them.
        val message = MtpTool.describeFailure(MtpTool.ACCESS_DENIED, "error: exclusive access denied")

        assertTrue(message.contains("Garmin Express"), message)
    }

    @Test
    fun `each exit code says its own thing`() {
        assertTrue(MtpTool.describeFailure(MtpTool.NO_DEVICE, "").contains("No Garmin device"))
        assertTrue(MtpTool.describeFailure(MtpTool.VERIFICATION, "").contains("read back differently"))
        assertTrue(MtpTool.describeFailure(MtpTool.TRANSFER, "").contains("transfer failed"))
        // Anything unrecognised repeats what the tool said rather than inventing a diagnosis.
        assertTrue(MtpTool.describeFailure(99, "error: something new").contains("something new"))
    }

    @Test
    fun `cargo's bin directory is searched, because that is the only way to install it`() {
        val candidates = MtpTool.candidates(Path.of("/home/dev"))

        assertTrue(candidates.any { it.toString().contains(".cargo/bin") }, "$candidates")
    }
}
