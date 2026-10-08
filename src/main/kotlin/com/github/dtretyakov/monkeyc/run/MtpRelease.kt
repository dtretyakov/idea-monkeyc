package com.github.dtretyakov.monkeyc.run

/**
 * The `mtp-rs` the plugin installs, and the oldest one it will use.
 *
 * Pinned rather than "latest". The tool is at 0.x, where its JSON and its exit codes may change in
 * any release, and this plugin parses both; a fresh upstream release must not be able to break
 * installing on a watch for everyone at once, with no change here. Pinning also lets the download
 * be checked against a checksum that comes from this plugin, not from the server it is downloaded
 * from — which proves the bytes are the ones that were tested, and not merely that they arrived
 * intact.
 *
 * Moving to a new release is these lines: the version, and the checksums from its `sha256.sum`.
 */
object MtpRelease {

    const val VERSION = "0.9.1"

    /**
     * The oldest version the plugin trusts to answer the way it parses. The one it installs: that is
     * the one the JSON models and the exit codes in [MtpTool] were checked against.
     */
    const val MINIMUM = VERSION

    /** One release archive: its file name, and what it hashes to. */
    data class Asset(val file: String, val sha256: String) {
        val url: String get() = "https://github.com/vdavid/mtp-rs/releases/download/mtp-rs-cli-v$VERSION/$file"
        val isZip: Boolean get() = file.endsWith(".zip")
    }

    private val MAC_ARM = Asset(
        "mtp-rs-cli-aarch64-apple-darwin.tar.xz",
        "2429dca838c046e89a8d32b1a3891ade2359464a7addd8b7ae0680be63aac22d",
    )
    private val MAC_INTEL = Asset(
        "mtp-rs-cli-x86_64-apple-darwin.tar.xz",
        "be0784455d2fca862f1761a3dc787250c42dd7ad864543ade80897b7ef777da1",
    )
    private val LINUX_ARM = Asset(
        "mtp-rs-cli-aarch64-unknown-linux-gnu.tar.xz",
        "3c6c3c0d3e4ae2bd78abfa5aa26c9ed579b43a0f982db4af6a4945aebd7730ba",
    )
    private val LINUX_INTEL = Asset(
        "mtp-rs-cli-x86_64-unknown-linux-gnu.tar.xz",
        "cb22715f04b38ea503b8281363ae708158d3c9ae7771c411b2422ba65a29d405",
    )
    private val WINDOWS = Asset(
        "mtp-rs-cli-x86_64-pc-windows-msvc.zip",
        "f9303d9fe4c1813ba23b321a00ece74ad464c234655dc7271cadf32f793c1352",
    )

    /**
     * The archive for this operating system and processor, or null where there is none.
     *
     * Windows on ARM gets the x86-64 build, which runs under the system's emulation; there is no
     * native one, and the watch is reached through Windows' own MTP stack either way.
     */
    fun forPlatform(
        os: String = System.getProperty("os.name"),
        arch: String = System.getProperty("os.arch"),
    ): Asset? {
        val arm = arch == "aarch64" || arch == "arm64"
        val intel = arch == "amd64" || arch == "x86_64"
        return when {
            os.startsWith("Mac") && arm -> MAC_ARM
            os.startsWith("Mac") && intel -> MAC_INTEL
            os.startsWith("Linux") && arm -> LINUX_ARM
            os.startsWith("Linux") && intel -> LINUX_INTEL
            os.startsWith("Windows") && (arm || intel) -> WINDOWS
            else -> null
        }
    }

    /** The version `mtp-rs --version` reports — `mtp-rs 0.9.1` — or null when it is not one. */
    fun parseVersion(output: String): String? =
        VERSION_LINE.find(output)?.groupValues?.get(1)

    /** Whether [version] is at least [MINIMUM]. A version that cannot be read is not. */
    fun isSupported(version: String): Boolean {
        val have = numbers(version) ?: return false
        val need = numbers(MINIMUM) ?: return true
        for (i in 0 until maxOf(have.size, need.size)) {
            val a = have.getOrElse(i) { 0 }
            val b = need.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }

    private fun numbers(version: String): List<Int>? =
        version.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }

    private val VERSION_LINE = Regex("""mtp-rs\s+(\d+(?:\.\d+)+(?:-[\w.]+)?)""")
}
