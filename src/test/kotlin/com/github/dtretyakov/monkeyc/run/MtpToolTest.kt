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
        val local = Path.of("bin/app.prg")
        val arguments = MtpTool.uploadArguments("0123456789", local, "/GARMIN/APPS/APP.PRG")

        // `--device` before the subcommand, `--verify` and `--replace` after it.
        //
        // `--replace` is the one with a story. Without it the second install of a session fails —
        // `remote file already exists; pass --replace to delete it first` — because the watch only
        // hides a taken `.prg` across a replug, and an edit-and-run loop does not replug.
        //
        // The local path is spelled by the platform rather than written out here — it is handed to
        // a process and `bin\app.prg` is the right spelling on Windows. The remote one is the
        // device's own and stays as it is.
        assertEquals(
            listOf(
                "--json", "--device", "0123456789", "put",
                "--verify", "--replace", local.toString(), "/GARMIN/APPS/APP.PRG",
            ),
            arguments,
        )
    }

    @Test
    fun `an upload can be asked not to replace`() {
        val arguments = MtpTool.uploadArguments(null, Path.of("a.prg"), "/GARMIN/APPS/A.PRG", replace = false)

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
        val home = Path.of("/home/dev")
        val candidates = MtpTool.candidates(home, windows = false)

        // Compared as paths, not as text: the separator is the platform's and the directory is the
        // fact being asserted.
        assertTrue(candidates.any { it.parent == home.resolve(".cargo/bin") }, "$candidates")
    }

    /**
     * A real watch as the bus scan reports it on Windows, which is not how it reports one on macOS.
     *
     * Captured from a Venu 2 over USB. The vendor id and the serial are there, which is all that
     * is needed to install. Every string is missing: `opened_descriptor_scan` on Windows reads no
     * `product` and no `manufacturer`, for the watch and for everything else on the bus — which is
     * why the model is asked of the device separately.
     */
    @Test
    fun `the bus scan on Windows carries a serial and no model`() {
        val devices = MtpTool.parseDevices(WINDOWS_DEVICES)

        // Unknown fields and all: the tool says more than this plugin reads, and a new one must
        // not stop the answer parsing.
        assertEquals(2, devices.size)

        // The fingerprint sensor on the same bus, which is why the vendor filter is not decoration.
        val watch = devices.single { it.isGarmin }
        assertEquals("0000cbf416e5", watch.serial_number)
        assertNull(watch.product)

        // With no product string there is nothing to match against the catalogue, and the mismatch
        // guard has no opinion about a build made for another watch. Both are what asking the
        // device puts right.
        assertNull(ConnectedWatch.match(watch.product, emptyList()))
        assertNull(ConnectedWatch.mismatch(built = "fenix7", watch = null, product = watch.product))
    }

    /** Opening the device answers the plain name the catalogue uses. */
    @Test
    fun `asking the device gives the model the catalogue knows`() {
        val details = MtpTool.parseInfo(
            """
            {
              "manufacturer": "Garmin",
              "model": "Venu 2",
              "serial_number": "0000cbf416e5",
              "device_version": "1905",
              "supports_rename": true,
              "storages": [
                {"index": 0, "id": "cd5f512da6e96523", "description": "Internal Storage",
                 "max_capacity": 7613612032, "free_space_bytes": 6596067328}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("Venu 2", details?.model)
        assertEquals("Garmin", details?.manufacturer)
        assertEquals("0000cbf416e5", details?.serial_number)
    }

    @Test
    fun `the model asked for is what the watch is offered as`() {
        val watch = MtpTool.parseDevices(WINDOWS_DEVICES).single { it.isGarmin }
        val tool = Path.of("mtp-rs")

        // Unasked, the best that can be said is where it is on the bus.
        assertEquals("an MTP device at a26a3a80c08a9b61", GarminTarget.Mtp(tool, watch).name)
        assertEquals("Venu 2", GarminTarget.Mtp(tool, watch, model = "Venu 2").name)
    }

    @Test
    fun `the info command names the device globally, before the subcommand`() {
        assertEquals(listOf("--json", "--device", "0123456789", "info"), MtpTool.infoArguments("0123456789"))
        // Nothing to name with one device attached, and `--device` with no value would be an error.
        assertEquals(listOf("--json", "info"), MtpTool.infoArguments(null))
    }

    /** The name on each platform, which is the difference between finding the tool and not. */
    @Test
    fun `the tool is named as each platform installs it`() {
        assertEquals("mtp-rs", MtpTool.executable(windows = false))
        assertEquals("mtp-rs.exe", MtpTool.executable(windows = true))
        assertEquals(
            Path.of("/home/dev/.cargo/bin/mtp-rs.exe"),
            MtpTool.candidates(Path.of("/home/dev"), windows = true).single(),
        )
    }

    private companion object {
        /** A Venu 2 and a Synaptics fingerprint sensor, as `mtp-rs devices` answered on Windows. */
        val WINDOWS_DEVICES = """
            [
              {"vendor_id": 1739, "product_id": 189, "manufacturer": null, "product": null,
               "serial_number": "4e0767c8f382", "location_id": 17262426598600056251,
               "location": "ef907587facf9dbb", "speed": "Full",
               "match_reason": "opened_descriptor_scan"},
              {"vendor_id": 2334, "product_id": 20087, "manufacturer": null, "product": null,
               "serial_number": "0000cbf416e5", "location_id": 11703230906336189281,
               "location": "a26a3a80c08a9b61", "speed": "High",
               "match_reason": "opened_descriptor_scan"}
            ]
        """.trimIndent()
    }
}
