package com.github.dtretyakov.monkeyc.sdk

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The Connect IQ SDK installed on this machine, and the tools inside it.
 *
 * Everything this plugin knows how to do — analyse, compile, run, debug — is a Java program that
 * Garmin ships in `bin/`. Nothing is bundled with the plugin, so this class is the single place
 * that has to be right about where those programs live.
 *
 * The SDK Manager writes the chosen SDK into `current-sdk.cfg`, and that file is the authority: a
 * machine can hold several SDKs, and the device data of one does not match the tools of another.
 */
class ConnectIqSdk(
    /** Root of the selected SDK, the directory holding `bin/`. */
    val root: Path,
    /** Where the SDK Manager unpacks per-device data, one directory per device id. */
    val devicesRoot: Path,
    /** Data root, which also holds the key the SDK Manager generates. */
    val dataRoot: Path,
) {
    val version: SdkVersion? = SdkVersion.parse(
        root.resolve("bin/version.txt").takeIf { it.exists() }?.runCatching { readText() }?.getOrNull(),
    )

    /** The compiler, the runner and everything else with a `com.garmin.monkeybrains.*` entry point. */
    val monkeybrainsJar: Path = root.resolve("bin/monkeybrains.jar")

    /**
     * The language server — and, less obviously, the debug adapter.
     *
     * `com.garmin.monkeybrains.monkeydodo.DebugAdapterProtocol` is present in `monkeybrains.jar`
     * too, but that jar carries no gson, so launching it from there dies with
     * `NoClassDefFoundError: com/google/gson/TypeAdapterFactory`. This jar has the class, gson and
     * lsp4j, which is why both servers are started from it.
     */
    val languageServerJar: Path = root.resolve("bin/LanguageServer.jar")

    /** The simulator's own debug shell; `monkeydo` hands its path to the runner with `-s`. */
    val shell: Path = root.resolve(if (isWindows) "bin/shell.exe" else "bin/shell")

    val samples: Path = root.resolve("samples")
    val templates: Path = root.resolve("bin/templates")

    /** The key the SDK Manager generates, used when the project has not been given one. */
    val defaultDeveloperKey: Path = dataRoot.resolve("developer_key.der")

    val hasLanguageServer: Boolean
        get() = languageServerJar.exists()

    val supportsTypeChecking: Boolean
        get() = version?.let { it >= SdkVersion.TYPE_CHECK_MINIMUM } ?: false

    val supportsOptimization: Boolean
        get() = version?.let { it >= SdkVersion.OPTIMIZATION_MINIMUM } ?: false

    /** How to open the simulator. On macOS it is an app bundle, elsewhere a plain executable. */
    val simulator: Path
        get() = if (isMac) root.resolve("bin/ConnectIQ.app") else root.resolve("bin/simulator")

    /**
     * The program inside the bundle, which is what gets executed.
     *
     * Running this rather than opening the bundle is what makes the simulator a child process with
     * pipes on it: `open` hands the app to LaunchServices and returns, leaving nothing to read, to
     * wait on, or to kill. On every platform but macOS the two are the same file.
     */
    val simulatorExecutable: Path
        get() = when {
            isMac -> root.resolve("bin/ConnectIQ.app/Contents/MacOS/simulator")
            isWindows -> root.resolve("bin/simulator.exe")
            else -> root.resolve("bin/simulator")
        }

    override fun toString(): String = "$root${version?.let { " ($it)" } ?: ""}"

    companion object {
        private val isMac get() = System.getProperty("os.name").startsWith("Mac")
        private val isWindows get() = System.getProperty("os.name").startsWith("Windows")

        /**
         * Where the SDK Manager keeps `current-sdk.cfg`, the downloaded devices and the developer key.
         */
        fun dataRoot(env: Map<String, String> = System.getenv()): Path {
            val home = Path.of(System.getProperty("user.home"))
            return when {
                isMac -> home.resolve("Library/Application Support/Garmin/ConnectIQ")
                isWindows -> env["APPDATA"]?.let { Path.of(it, "Garmin", "ConnectIQ") }
                    ?: home.resolve("AppData/Roaming/Garmin/ConnectIQ")
                else -> home.resolve(".Garmin/ConnectIQ")
            }
        }

        /** The file the SDK Manager rewrites when the user switches SDKs; worth watching. */
        fun currentSdkMarker(dataRoot: Path = dataRoot()): Path = dataRoot.resolve("current-sdk.cfg")

        /**
         * Finds the SDK to use, or null when none is installed.
         *
         * `current-sdk.cfg` can point at an SDK the user has since deleted; the newest one still
         * installed is a better answer than an error the user cannot act on.
         */
        fun detect(dataRoot: Path = dataRoot()): ConnectIqSdk? {
            val root = selectedRoot(dataRoot) ?: return null
            return ConnectIqSdk(root, devicesRoot(dataRoot), dataRoot)
        }

        /** Builds an SDK handle for a root the user picked by hand, without consulting the marker. */
        fun at(root: Path, dataRoot: Path = dataRoot()): ConnectIqSdk =
            ConnectIqSdk(root, devicesRoot(dataRoot), dataRoot)

        /** Every SDK the manager has unpacked, newest first. */
        fun installed(dataRoot: Path = dataRoot()): List<Path> =
            dataRoot.resolve("Sdks").takeIf { it.isDirectory() }
                ?.runCatching { toFile().listFiles()?.map { it.toPath() }.orEmpty() }
                ?.getOrNull()
                .orEmpty()
                .filter { it.resolve("bin").isDirectory() }
                .sortedByDescending { it.name }

        /**
         * The devices directory. The SDK Manager can be told to download elsewhere, and records
         * that choice in `sdkmanager-download-location.cfg`.
         */
        fun devicesRoot(dataRoot: Path = dataRoot()): Path {
            val relocated = dataRoot.resolve("sdkmanager-download-location.cfg")
                .takeIf { it.exists() }
                ?.runCatching { readText().trim() }
                ?.getOrNull()
                ?.takeIf { it.isNotEmpty() }
            return (relocated?.let { Path.of(it) } ?: dataRoot).resolve("Devices")
        }

        private fun selectedRoot(dataRoot: Path): Path? {
            val marked = currentSdkMarker(dataRoot).takeIf { it.exists() }
                ?.runCatching { readText().trim() }
                ?.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { Path.of(it) }
            if (marked != null && marked.resolve("bin").isDirectory()) return marked
            return installed(dataRoot).firstOrNull()
        }
    }
}
