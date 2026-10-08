package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.MonkeyCAppSettings
import com.intellij.openapi.application.ApplicationManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
     * 3. a copy of the user's that has not been asked its version yet, and then one known not to
     *    fit — a tool that may answer differently still beats no tool, and the watch list says
     *    which it is.
     *
     * [standing] says what is known of each candidate: null for one not asked yet. The default
     * reads what [refreshVersions] learnt and never starts a process — but this still looks at the
     * filesystem, once per `PATH` entry, so it is for background threads: the UI reads [snapshot].
     */
    fun resolve(
        configured: String? = MonkeyCAppSettings.getInstance().mtpToolPath,
        home: Path = Path.of(System.getProperty("user.home")),
        path: String? = System.getenv("PATH"),
        windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
        system: Path = Path.of("/"),
        managed: Path? = runCatching { MtpInstaller.managedTool(windows) }.getOrNull(),
        standing: (Path) -> MtpRelease.Compatibility? = ::knownStanding,
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
     * Always on macOS and Windows. On Linux, unless a desktop that mounts MTP devices by itself is
     * running and gvfs is there to do it. The gvfs directory alone is not that: gvfsd starts on
     * demand in KDE and Xfce sessions as well, and nothing there mounts a watch when it is plugged
     * in — Nautilus and its relatives do. Erring this way costs an install button someone did not
     * need; the other way it cost a watch nobody could reach and no word about why.
     */
    fun needed(
        os: String = System.getProperty("os.name"),
        desktop: String? = System.getenv("XDG_CURRENT_DESKTOP"),
        gvfsPresent: () -> Boolean = { GvfsMtp.roots().any { it.isDirectory() } },
    ): Boolean {
        if (os.startsWith("Mac") || os.startsWith("Windows")) return true
        val mounting = desktop.orEmpty().split(':').any { it.trim().uppercase() in MOUNTING_DESKTOPS }
        return !(mounting && gvfsPresent())
    }

    /**
     * Desktops whose file manager mounts an MTP device through gvfs when it is plugged in, as
     * `XDG_CURRENT_DESKTOP` names them. Ubuntu's reads `ubuntu:GNOME`, Mint's `X-Cinnamon`.
     */
    private val MOUNTING_DESKTOPS = setOf("GNOME", "UNITY", "CINNAMON", "X-CINNAMON", "BUDGIE", "PANTHEON", "MATE")

    /**
     * What is known of the tool at [tool]: null if it has not been asked, [MtpRelease.Compatibility.UNREADABLE]
     * if it was and gave no version. Never starts a process.
     *
     * The answer is remembered against the file's modification time, so a tool replaced in place —
     * by an update — is asked again rather than reported by its old version. A tool that cannot say
     * its version is remembered as such: forgetting it meant asking again, with a ten-second
     * timeout, on every look.
     */
    fun knownStanding(tool: Path): MtpRelease.Compatibility? {
        val asked = versions[tool]?.takeIf { it.first == stamp(tool) } ?: return null
        return asked.second?.let { MtpRelease.compatibility(it) } ?: MtpRelease.Compatibility.UNREADABLE
    }

    /** The version the tool at [tool] gave when asked, or null if it gave none or was not asked. */
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
     * Asks every copy on the machine that has not been asked yet. Not on the UI thread.
     *
     * Every copy, not only the one in use: which one is used depends on all of them.
     */
    fun refreshVersions(
        home: Path = Path.of(System.getProperty("user.home")),
        path: String? = System.getenv("PATH"),
        windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
    ) {
        val managed = runCatching { MtpInstaller.managedTool(windows) }.getOrNull()
        (userCandidates(home, path, windows, Path.of("/")) + listOfNotNull(managed))
            .filter { MtpTool.isUsable(it, windows) && knownStanding(it) == null }
            .forEach { readVersion(it) }
    }

    /** What the last look found: the tool that would be used, its version, and whether it is needed. */
    data class Snapshot(val tool: Path?, val version: String?, val needed: Boolean)

    /**
     * What the last look found, or null before the first. Never touches the disk.
     *
     * For the UI thread, and for the editor banner's read action: resolving looks at every `PATH`
     * entry, and on Windows one of those can be a network share that is not there, where the look
     * waits rather than failing.
     */
    fun snapshot(): Snapshot? = snapshot

    /** Looks: asks what has not been asked, decides which tool is used, and remembers it. Not on the UI thread. */
    fun refresh(): Snapshot {
        refreshVersions()
        val tool = resolve()
        return Snapshot(tool, tool?.let { knownVersion(it) }, needed()).also { snapshot = it }
    }

    /**
     * Starts a look in the background unless one is running. For the UI thread, when it found no
     * [snapshot] to read: the next reader finds one.
     */
    fun refreshInBackground() {
        if (!looking.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                runCatching { refresh() }
            } finally {
                looking.set(false)
            }
        }
    }

    @Volatile
    private var snapshot: Snapshot? = null

    private val looking = AtomicBoolean(false)

    /** What stands between this machine and a current watch. */
    enum class Shortfall {
        MISSING,

        /** Older than the plugin is built for. */
        OUTDATED,

        /** Past the next breaking release, or a pre-release: it may answer in a way the plugin does not read. */
        UNTESTED,
    }

    /** Looks, then says what stands between this machine and a current watch. Not on the UI thread. */
    fun shortfall(): Shortfall? = shortfallOf(refresh())

    /**
     * The same from the last look, for the UI thread. Null before the first look, which this
     * starts: saying nothing for a moment is better than guessing.
     */
    fun knownShortfall(): Shortfall? {
        val last = snapshot ?: return null.also { refreshInBackground() }
        return shortfallOf(last)
    }

    /** What a look's result lacks, or null when nothing is in the way. */
    fun shortfallOf(snapshot: Snapshot): Shortfall? {
        if (!snapshot.needed) return null
        if (snapshot.tool == null) return Shortfall.MISSING
        return shortfallOf(snapshot.version)
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
                "A current Garmin watch connects over MTP. GNOME and its relatives mount it on their " +
                    "own and the IDE installs through that mount; under KDE or Xfce, or without a " +
                    "desktop, it needs mtp-rs."
        }
        return "$lead Install mtp-rs ${MtpRelease.VERSION} from the notification, from the watch list " +
            "beside Run, or in Settings | Languages & Frameworks | Monkey C — or yourself, from " +
            "$INSTALL_URL."
    }
}
