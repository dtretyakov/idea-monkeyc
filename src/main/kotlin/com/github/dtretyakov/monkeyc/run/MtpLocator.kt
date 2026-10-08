package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.MonkeyCAppSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory

/**
 * Finds `mtp-rs`, says which version it is, and says whether this machine needs it at all.
 *
 * Not a requirement everywhere. A watch that mounts as storage needs none of this, and neither
 * does one a Linux desktop has mounted over gvfs. On macOS and Windows a current watch is reachable
 * through nothing else, and there its absence is said up front — in the watch list, the settings
 * and the run console — rather than discovered as a watch that never appears.
 */
object MtpLocator {

    /**
     * The tool, or null when nothing on this machine looks like it.
     *
     * A path set in the settings is used as given and nothing else is tried: falling back would run
     * something the user did not choose. Otherwise the plugin's own copy comes first — it is the
     * version the plugin was checked against — and then wherever the user may have put one.
     */
    fun resolve(
        configured: String? = MonkeyCAppSettings.getInstance().mtpToolPath,
        home: Path = Path.of(System.getProperty("user.home")),
        path: String? = System.getenv("PATH"),
        windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
        system: Path = Path.of("/"),
        managed: Path? = runCatching { MtpInstaller.managedTool(windows) }.getOrNull(),
    ): Path? {
        configured?.trim()?.takeIf { it.isNotEmpty() }?.let { setting ->
            val candidate = Path.of(setting)
            // Accept a directory holding it as well as the executable, which is what people paste.
            val executable =
                if (candidate.isDirectory()) candidate.resolve(MtpTool.executable(windows)) else candidate
            return executable.takeIf { MtpTool.isUsable(it, windows) }
        }

        return (listOfNotNull(managed) + onPath(path, windows) + MtpTool.candidates(home, windows, system))
            .firstOrNull { MtpTool.isUsable(it, windows) }
    }

    /**
     * Whether a current watch needs the tool on this machine.
     *
     * Always on macOS and Windows. On Linux only where nothing mounts the watch for it: gvfs keeps
     * its mounts under the session's runtime directory, and where that directory exists the
     * desktop does the work — under GNOME and most others. KDE and a machine without a desktop
     * have none.
     */
    fun needed(
        os: String = System.getProperty("os.name"),
        gvfsPresent: () -> Boolean = { GvfsMtp.roots().any { it.isDirectory() } },
    ): Boolean = os.startsWith("Mac") || os.startsWith("Windows") || !gvfsPresent()

    /**
     * The version of the tool at [tool], if it has been asked already. Never starts a process.
     *
     * For the UI thread, which builds the watch list and the settings page and must not wait on a
     * subprocess. The answer is remembered against the file's modification time, so a tool
     * replaced in place — by an update — is asked again rather than reported by its old version.
     */
    fun knownVersion(tool: Path): String? =
        versions[tool]?.takeIf { it.first == stamp(tool) }?.second

    /** Asks the tool its version and remembers the answer. Starts a process: not on the UI thread. */
    fun readVersion(tool: Path): String? {
        val outcome = runCatching {
            MtpTool.run(listOf(tool.toString(), "--version"), VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrNull()
        val version = outcome?.takeIf { it.exitCode == 0 }?.let { MtpRelease.parseVersion(it.output) }
        stamp(tool)?.let { versions[tool] = it to version }
        return version
    }

    /** Asks the tool that would be used, if its version is not known yet. Not on the UI thread. */
    fun refreshVersion() {
        val tool = resolve() ?: return
        if (knownVersion(tool) == null) readVersion(tool)
    }

    /** What stands between this machine and a current watch. */
    enum class Shortfall { MISSING, OUTDATED }

    /**
     * What stands between this machine and a current watch, or null when nothing does.
     *
     * Asks the tool its version if that is not known yet, so it starts a process: not on the UI
     * thread. [knownShortfall] is the one that never does.
     */
    fun shortfall(): Shortfall? = shortfallOf { tool -> knownVersion(tool) ?: readVersion(tool) }

    /** The same, from what is already known. Never starts a process; for the UI thread. */
    fun knownShortfall(): Shortfall? = shortfallOf { tool -> knownVersion(tool) }

    private fun shortfallOf(version: (Path) -> String?): Shortfall? {
        if (!needed()) return null
        val tool = resolve() ?: return Shortfall.MISSING
        // A version that cannot be read is not called old: see [ConnectIqEnvironment.mtpTool].
        val known = version(tool) ?: return null
        return if (MtpRelease.isSupported(known)) null else Shortfall.OUTDATED
    }

    private val versions = ConcurrentHashMap<Path, Pair<FileTime?, String?>>()

    private fun stamp(tool: Path): FileTime? = runCatching { Files.getLastModifiedTime(tool) }.getOrNull()

    private const val VERSION_TIMEOUT_SECONDS = 10L

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

    /**
     * Where to get it: the CLI's own install instructions, which cover Homebrew, an install script
     * for each platform, the prebuilt binaries and cargo. Linked rather than repeated, because the
     * script's one-liner is long and belongs to the project that publishes it.
     */
    const val INSTALL_URL = "https://github.com/vdavid/mtp-rs/tree/main/crates/mtp-rs-cli#install"

    /**
     * What to tell someone whose watch could not be found and who has no tool to look with.
     *
     * The lead differs by what the system itself can do; the remedy is the same everywhere — the
     * plugin installs the tool when asked, from the notification beside this, from the watch list
     * or from the settings. The project's own page is there for anyone who would rather do it
     * themselves.
     */
    fun installHint(os: String = System.getProperty("os.name")): String {
        val lead = when {
            os.startsWith("Mac") ->
                "A current Garmin watch connects over MTP, which macOS does not speak: Finder will not " +
                    "show it, and the IDE reaches it through mtp-rs."
            os.startsWith("Windows") ->
                "A current Garmin watch connects over MTP rather than as a drive, and the IDE reaches " +
                    "it through mtp-rs."
            else ->
                "A current Garmin watch connects over MTP. GNOME and most other desktops mount it on " +
                    "their own and the IDE installs through that mount; under KDE, or without a " +
                    "desktop, it needs mtp-rs."
        }
        return "$lead Install mtp-rs ${MtpRelease.VERSION} from the notification, from the watch list " +
            "beside Run, or in Settings | Languages & Frameworks | Monkey C — or yourself, from " +
            "$INSTALL_URL."
    }
}
