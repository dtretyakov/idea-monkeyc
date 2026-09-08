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
    ): Path? {
        configured?.trim()?.takeIf { it.isNotEmpty() }?.let { setting ->
            val candidate = Path.of(setting)
            // Accept a directory holding it as well as the executable, which is what people paste.
            val executable = if (candidate.isDirectory()) candidate.resolve(MtpTool.EXECUTABLE) else candidate
            return executable.takeIf { MtpTool.isUsable(it) }
        }

        return (onPath(path) + MtpTool.candidates(home)).firstOrNull { MtpTool.isUsable(it) }
    }

    private fun onPath(path: String?): List<Path> =
        path.orEmpty().split(java.io.File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { Path.of(it).resolve(MtpTool.EXECUTABLE) }

    /** What to tell someone who has no tool and a watch that needs one. */
    const val INSTALL_HINT =
        "Installing a Connect IQ app on a current Garmin device needs mtp-rs, because the watch " +
            "speaks MTP rather than mounting as a disk. Install it with `cargo install mtp-rs-cli`, " +
            "or set its path in Settings | Languages & Frameworks | Monkey C."
}
