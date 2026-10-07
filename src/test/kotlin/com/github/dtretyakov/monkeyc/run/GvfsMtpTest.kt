package com.github.dtretyakov.monkeyc.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * A watch the Linux desktop mounted over MTP, found as a directory and written through `gio`.
 *
 * The mount is a directory tree, which is what makes this testable without a watch or a desktop:
 * gvfsd-fuse's layout is `<runtime dir>/gvfs/mtp:host=<ID_SERIAL>/<storage>/GARMIN`, and a
 * temporary directory can be given the same shape. Host names are udev's `ID_SERIAL` — vendor,
 * model and serial number joined by underscores — as gvfs's MTP volume monitor builds them.
 *
 * Not on Windows, where a colon cannot be in a file name and `mtp:host=` cannot be made — nor
 * exist, gvfs being a Linux thing.
 */
@DisabledOnOs(OS.WINDOWS)
class GvfsMtpTest {

    @Test
    fun `a watch mounted by the desktop is found`(@TempDir temp: Path) {
        val storage = watchAt(temp, "Garmin_Venu_2_3348567420")

        val found = GarminVolume.mountedOverMtp(listOf(temp.resolve("gvfs")))

        assertEquals(listOf(storage), found.map { it.root })
        assertEquals("Garmin_Venu_2_3348567420", found.single().mtpHost)
    }

    @Test
    fun `it is named by its model, not by its storage`(@TempDir temp: Path) {
        // The directory under the host is the storage's name, "Primary", which says nothing about
        // which watch this is.
        watchAt(temp, "Garmin_Venu_2_3348567420")

        assertEquals("Venu 2", GarminVolume.mountedOverMtp(listOf(temp.resolve("gvfs"))).single().name)
    }

    @Test
    fun `a phone mounted beside it is not a watch`(@TempDir temp: Path) {
        temp.resolve("gvfs/mtp:host=SAMSUNG_SAMSUNG_Android_R9ZZZZZZZZZ/Internal storage/DCIM").createDirectories()

        assertTrue(GarminVolume.mountedOverMtp(listOf(temp.resolve("gvfs"))).isEmpty())
    }

    @Test
    fun `network mounts beside it are not opened`(@TempDir temp: Path) {
        // Listing an SMB or SFTP mount reaches across the network, at the end of every device
        // build. A GARMIN directory inside one is the cheapest way to tell whether it was looked at.
        temp.resolve("gvfs/smb-share:server=nas,share=backup/watch/GARMIN").createDirectories()
        temp.resolve("gvfs/sftp:host=example.org/GARMIN").createDirectories()

        assertTrue(GarminVolume.mountedOverMtp(listOf(temp.resolve("gvfs"))).isEmpty())
    }

    @Test
    fun `no gvfs directory is no watch, not an error`(@TempDir temp: Path) {
        // macOS and Windows, and a Linux machine with no desktop.
        assertTrue(GarminVolume.mountedOverMtp(listOf(temp.resolve("gvfs"))).isEmpty())
    }

    @Test
    fun `the runtime directory names where gvfs mounts`() {
        val roots = GvfsMtp.roots(runtimeDir = "/run/user/1000", home = Path.of("/nowhere"))

        assertEquals(listOf(Path.of("/run/user/1000", "gvfs")), roots)
    }

    @Test
    fun `the model drops the vendor and the serial`() {
        assertEquals("Venu 2", GvfsMtp.model("Garmin_Venu_2_3348567420"))
        assertEquals("Forerunner 965", GvfsMtp.model("Garmin_Forerunner_965_0000c5f2a1b2"))
        assertEquals("Edge 1040", GvfsMtp.model("Garmin_Edge_1040_3398765432"))
    }

    @Test
    fun `a device with no serial keeps the whole of its model`() {
        // udev leaves the serial out when the device has none, and the last word is then the
        // model's: `Venu_2` must not lose its `2`, nor `Edge_1040` its number.
        assertEquals("Venu 2", GvfsMtp.model("Garmin_Venu_2"))
        assertEquals("Edge 1040", GvfsMtp.model("Garmin_Edge_1040"))
    }

    @Test
    fun `an escaped host is read as gvfs wrote it`() {
        // gvfs escapes ID_SERIAL into the mount's name, and a colon or a space would otherwise
        // reach the screen as %3A or %20.
        assertEquals("fēnix 7", GvfsMtp.model("Garmin_f%C4%93nix_7_3348567420"))
    }

    @Test
    fun `the host is recognised as the device mtp-rs lists`() {
        assertTrue(GvfsMtp.holds("Garmin_Venu_2_3348567420", "3348567420"))
        assertFalse(GvfsMtp.holds("Garmin_Venu_2_3348567420", "1111111111"))
        // Nothing to match on is not a match: the device stays offered rather than vanishing.
        assertFalse(GvfsMtp.holds("Garmin_Venu_2_3348567420", null))
        assertFalse(GvfsMtp.holds("Garmin_Venu_2_3348567420", ""))
    }

    @Test
    fun `a watch the desktop holds is not offered again over MTP`(@TempDir temp: Path) {
        watchAt(temp, "Garmin_Venu_2_3348567420")
        val volumes = GarminVolume.mountedOverMtp(listOf(temp.resolve("gvfs")))
        val held = mtp(serial = "3348567420")
        val other = mtp(serial = "1111111111")

        assertEquals(listOf(other), GarminTarget.withoutMounted(listOf(held, other), volumes))
    }

    @Test
    fun `a disk takes nothing away from the MTP list`(@TempDir temp: Path) {
        val disk = GarminVolume(temp)
        val device = mtp(serial = "3348567420")

        assertEquals(listOf(device), GarminTarget.withoutMounted(listOf(device), listOf(disk)))
    }

    /**
     * The copy is a push through `gio`, because gvfs refuses to open a file on a Garmin watch for
     * writing: that needs Android's partial-object extensions, which a watch does not have.
     */
    @Test
    fun `installing creates the directory and copies with gio`() {
        val gio = Path.of("/usr/bin/gio")
        val prg = Path.of("/project/bin/App.prg")
        val destination = Path.of("/run/user/1000/gvfs/mtp:host=Garmin_Venu_2_1/Primary/GARMIN/APPS/APP.PRG")

        assertEquals(
            listOf(
                listOf(gio.toString(), "mkdir", "-p", destination.parent.toString()),
                listOf(gio.toString(), "copy", prg.toString(), destination.toString()),
            ),
            GvfsMtp.commands(gio, prg, destination),
        )
    }

    @Test
    fun `the copy never asks before overwriting`() {
        // `--interactive` is what would make gio refuse to replace the previous build, and a second
        // install in one session has one to replace: the watch hides a .prg only once unplugged.
        val commands = GvfsMtp.commands(Path.of("gio"), Path.of("a.prg"), Path.of("/w/GARMIN/APPS/A.PRG"))

        assertTrue(commands.flatten().none { it == "-i" || it == "--interactive" }, "$commands")
    }

    @Test
    fun `gio is not invented where there is none`(@TempDir temp: Path) {
        // The fallback is /usr/bin, which on a machine running these tests may well hold one; what
        // matters is that a PATH entry without it is not taken for one.
        val found = GvfsMtp.gio(path = temp.toString())

        assertTrue(found == null || found.parent == Path.of("/usr/bin"), "$found")
    }

    private fun watchAt(temp: Path, host: String): Path {
        val storage = temp.resolve("gvfs/mtp:host=$host/Primary")
        storage.resolve("GARMIN/APPS").createDirectories()
        return storage
    }

    private fun mtp(serial: String): GarminTarget.Mtp =
        GarminTarget.Mtp(Path.of("mtp-rs"), MtpDeviceInfo(vendor_id = MtpDeviceInfo.GARMIN_VENDOR_ID, serial_number = serial))
}
