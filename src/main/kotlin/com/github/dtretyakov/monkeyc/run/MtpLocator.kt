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
    ): Path? {
        configured?.trim()?.takeIf { it.isNotEmpty() }?.let { setting ->
            val candidate = Path.of(setting)
            // Accept a directory holding it as well as the executable, which is what people paste.
            val executable =
                if (candidate.isDirectory()) candidate.resolve(MtpTool.executable(windows)) else candidate
            return executable.takeIf { MtpTool.isUsable(it, windows) }
        }

        return (onPath(path, windows) + MtpTool.candidates(home, windows))
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

    /** What to tell someone who has no tool and a watch that needs one. */
    const val INSTALL_HINT =
        "Installing a Connect IQ app on a current Garmin device needs mtp-rs, because the watch " +
            "speaks MTP rather than mounting as a disk. Install it with `cargo install mtp-rs-cli` " +
            "($PROJECT_URL), or set its path in Settings | Languages & Frameworks | Monkey C."
}
