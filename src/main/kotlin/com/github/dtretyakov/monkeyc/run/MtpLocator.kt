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
     * something the user did not choose. Otherwise, in this order:
     *
     * 1. a copy the user installed — on `PATH`, Homebrew's, the install scripts', cargo's — that is
     *    known to be compatible ([MtpRelease.compatibility]): theirs is the one they meant;
     * 2. the plugin's own copy, which is compatible by construction;
     * 3. a copy of the user's whose version is not known yet, and then one known not to fit — a
     *    tool that may answer differently still beats no tool, and the watch list says which it is.
     *
     * [version] says what is known of each candidate. The default never starts a process, so this
     * can be asked on the UI thread; [refreshVersions] fills it in from the background.
     */
    fun resolve(
        configured: String? = MonkeyCAppSettings.getInstance().mtpToolPath,
        home: Path = Path.of(System.getProperty("user.home")),
        path: String? = System.getenv("PATH"),
        windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
        system: Path = Path.of("/"),
        managed: Path? = runCatching { MtpInstaller.managedTool(windows) }.getOrNull(),
        version: (Path) -> String? = ::knownVersion,
    ): Path? {
        configured?.trim()?.takeIf { it.isNotEmpty() }?.let { setting ->
            val candidate = Path.of(setting)
            // Accept a directory holding it as well as the executable, which is what people paste.
            val executable =
                if (candidate.isDirectory()) candidate.resolve(MtpTool.executable(windows)) else candidate
            return executable.takeIf { MtpTool.isUsable(it, windows) }
        }

        val theirs = userCandidates(home, path, windows, system).filter { MtpTool.isUsable(it, windows) }
        val ours = managed?.takeIf { MtpTool.isUsable(it, windows) }
        fun standing(tool: Path) = version(tool)?.let { MtpRelease.compatibility(it) }

        return theirs.firstOrNull { standing(it) == MtpRelease.Compatibility.COMPATIBLE }
            ?: ours
            ?: theirs.firstOrNull { standing(it) == null }
            ?: theirs.firstOrNull()
    }

    /** Everywhere the user may have put one, in the order they are preferred. */
    private fun userCandidates(home: Path, path: String?, windows: Boolean, system: Path): List<Path> =
        (onPath(path, windows) + MtpTool.candidates(home, windows, system)).distinct()

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

    /**
     * Asks every copy on the machine whose version is not known yet. Not on the UI thread.
     *
     * Every copy, not only the one in use: which one is used depends on their versions, so the
     * choice made on the UI thread is only right once all of them are known.
     */
    fun refreshVersions(
        home: Path = Path.of(System.getProperty("user.home")),
        path: String? = System.getenv("PATH"),
        windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
    ) {
        val managed = runCatching { MtpInstaller.managedTool(windows) }.getOrNull()
        (userCandidates(home, path, windows, Path.of("/")) + listOfNotNull(managed))
            .filter { MtpTool.isUsable(it, windows) && knownVersion(it) == null }
            .forEach { readVersion(it) }
    }

    /** What stands between this machine and a current watch. */
    enum class Shortfall {
        MISSING,

        /** Older than the plugin is built for. */
        OUTDATED,

        /** Past the next breaking release: it may answer in a way the plugin does not read. */
        UNTESTED,
    }

    /**
     * What stands between this machine and a current watch, or null when nothing does.
     *
     * Asks the tool its version if that is not known yet, so it starts a process: not on the UI
     * thread. [knownShortfall] is the one that never does.
     */
    fun shortfall(): Shortfall? {
        refreshVersions()
        return knownShortfall()
    }

    /** The same, from what is already known. Never starts a process; for the UI thread. */
    fun knownShortfall(): Shortfall? {
        if (!needed()) return null
        val tool = resolve() ?: return Shortfall.MISSING
        return shortfallOf(knownVersion(tool))
    }

    /**
     * What a tool of [version] lacks. A version that is not known, or cannot be read, is not
     * called a problem: see [ConnectIqEnvironment.mtpTool].
     */
    fun shortfallOf(version: String?): Shortfall? = when (version?.let { MtpRelease.compatibility(it) }) {
        MtpRelease.Compatibility.OLDER -> Shortfall.OUTDATED
        MtpRelease.Compatibility.NEWER -> Shortfall.UNTESTED
        else -> null
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
