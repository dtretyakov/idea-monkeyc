package com.github.dtretyakov.monkeyc.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which archive the plugin downloads, and which versions it trusts.
 *
 * Every platform is named rather than taken from the machine running the tests: the JVM spells
 * the processor `aarch64` on Apple Silicon, `x86_64` on an Intel Mac and `amd64` on Windows and
 * Linux, and a mapping only ever exercised on one of them is how the other two go wrong unseen.
 */
class MtpReleaseTest {

    @Test
    fun `each platform gets its own archive`() {
        assertEquals("mtp-rs-cli-aarch64-apple-darwin.tar.xz", MtpRelease.forPlatform("Mac OS X", "aarch64")?.file)
        assertEquals("mtp-rs-cli-x86_64-apple-darwin.tar.xz", MtpRelease.forPlatform("Mac OS X", "x86_64")?.file)
        assertEquals("mtp-rs-cli-x86_64-unknown-linux-gnu.tar.xz", MtpRelease.forPlatform("Linux", "amd64")?.file)
        assertEquals("mtp-rs-cli-aarch64-unknown-linux-gnu.tar.xz", MtpRelease.forPlatform("Linux", "aarch64")?.file)
        assertEquals("mtp-rs-cli-x86_64-pc-windows-msvc.zip", MtpRelease.forPlatform("Windows 11", "amd64")?.file)
    }

    @Test
    fun `Windows on ARM gets the x86-64 build, which it runs under emulation`() {
        assertEquals("mtp-rs-cli-x86_64-pc-windows-msvc.zip", MtpRelease.forPlatform("Windows 11", "aarch64")?.file)
    }

    @Test
    fun `a platform with no build gets nothing rather than a wrong one`() {
        assertNull(MtpRelease.forPlatform("FreeBSD", "amd64"))
        assertNull(MtpRelease.forPlatform("Linux", "riscv64"))
    }

    @Test
    fun `the download is the pinned release on GitHub`() {
        val asset = MtpRelease.forPlatform("Mac OS X", "aarch64")!!

        assertEquals(
            "https://github.com/vdavid/mtp-rs/releases/download/mtp-rs-cli-v${MtpRelease.VERSION}/${asset.file}",
            asset.url,
        )
        // A SHA-256, written out in full: a truncated or empty checksum would accept anything.
        assertTrue(asset.sha256.matches(Regex("[0-9a-f]{64}")), asset.sha256)
    }

    @Test
    fun `the version is read from what the tool says`() {
        assertEquals("0.9.1", MtpRelease.parseVersion("mtp-rs 0.9.1\n"))
        assertEquals("0.3.0", MtpRelease.parseVersion("mtp-rs 0.3.0"))
        assertNull(MtpRelease.parseVersion("error: unexpected argument '--version'"))
    }

    @Test
    fun `older versions are refused and newer ones accepted`() {
        assertFalse(MtpRelease.isSupported("0.3.0"))
        assertFalse(MtpRelease.isSupported("0.9.0"))
        assertTrue(MtpRelease.isSupported(MtpRelease.MINIMUM))
        assertTrue(MtpRelease.isSupported("0.9.2"))
        assertTrue(MtpRelease.isSupported("0.10.0"), "compared as numbers, not as text")
        assertTrue(MtpRelease.isSupported("1.0.0"))
        assertFalse(MtpRelease.isSupported("not a version"))
    }
}
