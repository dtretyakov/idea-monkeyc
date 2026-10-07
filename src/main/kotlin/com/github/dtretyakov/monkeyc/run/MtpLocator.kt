package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.MonkeyCAppSettings
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Finds `mtp-rs`, or says it is not here.
 *
 * Deliberately not a hard dependency. A watch that mounts as storage needs none of this, and a
 * developer who never plugs one in should not be told to install a Rust toolchain — which, until
 * the project publishes binaries, is what installing it means.
 */
object MtpLocator {

    /** The tool, or null when nothing on this machine looks like it. */
    fun resolve(
        configured: String? = MonkeyCAppSettings.getInstance().mtpToolPath,
        home: Path = Path.of(System.getProperty("user.home")),
        path: String? = System.getenv("PATH"),
        windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
        system: Path = Path.of("/"),
    ): Path? {
        configured?.trim()?.takeIf { it.isNotEmpty() }?.let { setting ->
            val candidate = Path.of(setting)
            // Accept a directory holding it as well as the executable, which is what people paste.
            val executable =
                if (candidate.isDirectory()) candidate.resolve(MtpTool.executable(windows)) else candidate
            return executable.takeIf { MtpTool.isUsable(it, windows) }
        }

        return (onPath(path, windows) + MtpTool.candidates(home, windows, system))
            .firstOrNull { MtpTool.isUsable(it, windows) }
    }

    /**
     * The tool as each entry of `PATH` would spell it, dropping any entry that is not a path.
     *
     * `PATH` on Windows routinely holds quoted entries, and `Path.of("\"C:\\tools\"")` throws on
     * the illegal character — out of the lookup that lists attached watches.
     */
    private fun onPath(path: String?, windows: Boolean): List<Path> =
        path.orEmpty().split(java.io.File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { Path.of(it).resolve(MtpTool.executable(windows)) }.getOrNull() }

    /** Where to get it: the crate `cargo install mtp-rs-cli` installs from. */
    const val PROJECT_URL = "https://crates.io/crates/mtp-rs-cli"

    /**
     * What to tell someone who has no tool and whose watch could not be found, on this platform.
     *
     * The three differ in what the system itself can do, and the advice has to follow. Windows
     * shows an MTP watch in File Explorer, so the copy Garmin documents works there by hand, and
     * the tool is a convenience. A Linux desktop built on gvfs mounts the watch on its own and the
     * plugin installs through that mount, so not finding one means nothing did — KDE's kio has no
     * directory to copy into, and a machine without a desktop has no mount. macOS has no MTP at all:
     * Finder never shows the watch, and the tool is the only way onto it.
     */
    fun installHint(os: String = System.getProperty("os.name")): String = when {
        os.startsWith("Mac") ->
            "A current Garmin watch connects over MTP, which macOS does not speak: Finder will not " +
                "show it, and installing on one needs mtp-rs. $HOW_TO_GET_IT"
        os.startsWith("Windows") ->
            "A current Garmin watch connects over MTP and appears in File Explorer under This PC " +
                "rather than as a drive: copy the .prg to GARMIN\\APPS there, or install mtp-rs and " +
                "let the IDE do it. $HOW_TO_GET_IT"
        else ->
            "A current Garmin watch connects over MTP. GNOME and most other desktops mount it on " +
                "their own, and the IDE installs through that mount; KDE reaches it in a way the IDE " +
                "cannot use, and without a desktop nothing mounts it. There, install mtp-rs. " +
                HOW_TO_GET_IT
    }

    /**
     * What the tool is for on this platform, for the settings page beside its path.
     *
     * Absence is normal everywhere, and only on macOS does it leave no way onto a current watch.
     */
    fun purpose(os: String = System.getProperty("os.name")): String = when {
        os.startsWith("Mac") -> "Needed to install a build on a current watch, which macOS cannot open without it."
        os.startsWith("Windows") -> "Lets the IDE install a build on a current watch. Without it, copy the .prg in File Explorer."
        else -> "Needed only where the desktop does not mount the watch itself: under KDE, or with no desktop."
    }

    private const val HOW_TO_GET_IT =
        "Install it with `cargo install mtp-rs-cli` ($PROJECT_URL), or set its path in " +
            "Settings | Languages & Frameworks | Monkey C."
}
