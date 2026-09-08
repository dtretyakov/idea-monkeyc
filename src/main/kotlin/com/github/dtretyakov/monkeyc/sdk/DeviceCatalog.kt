package com.github.dtretyakov.monkeyc.sdk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/** One device the SDK Manager has downloaded, as the compiler and the simulator describe it. */
data class ConnectIqDevice(
    /** The id the compiler wants after `-d`, which is also the directory name. */
    val id: String,
    val displayName: String,
    val group: String?,
    val family: String?,
    val isTouch: Boolean,
    /** Newest Connect IQ version any of this device's part numbers supports. */
    val sdkVersion: SdkVersion?,
    /**
     * How much memory this device gives each kind of app, keyed by the catalogue's own names.
     *
     * The interesting number on this platform. A watch app gets between 64 KB and 768 KB depending
     * on the watch, a data field between 16 KB and 256 KB, and an app that fits one device fails
     * to load on another with a message about a limit it does not name. Developers have been
     * scraping these values out of `compiler.json` into shared spreadsheets to compare them; the
     * file is already open here.
     */
    val memoryLimits: Map<String, Long>,
) {
    /** `watchApp`, `widget`, `datafield`, … — what a project may declare for this device. */
    val appTypes: Set<String> get() = memoryLimits.keys

    /**
     * The memory this device allows an app of the kind the manifest declares, or null when it
     * cannot run one at all.
     */
    fun memoryLimitFor(manifestAppType: String?): Long? =
        AppTypes.catalogueName(manifestAppType)?.let { memoryLimits[it] }

    /** Whether this device can run the kind of app the manifest declares. */
    fun supports(manifestAppType: String?): Boolean =
        AppTypes.catalogueName(manifestAppType)?.let { it in memoryLimits } ?: true
}

/**
 * The two spellings of an app's kind, and the map between them.
 *
 * A manifest says `watch-app`; the device catalogue says `watchApp`. Nothing in the SDK writes the
 * correspondence down, so it is here, checked against every device SDK 9.2.0 ships.
 */
object AppTypes {

    private val CATALOGUE_NAMES = mapOf(
        "watch-app" to "watchApp",
        "watchface" to "watchFace",
        "datafield" to "datafield",
        "widget" to "widget",
        "audio-content-provider-app" to "audioContentProvider",
    )

    /**
     * What the catalogue calls this manifest app type, or null when the catalogue has no opinion.
     *
     * Null for a barrel, which declares no type and runs on nothing of its own, and for anything
     * a later SDK invents — an unknown kind must not be read as an unsupported one.
     */
    fun catalogueName(manifestAppType: String?): String? =
        manifestAppType?.let { CATALOGUE_NAMES[it] }
}

@Serializable
private data class CompilerJson(
    val deviceId: String? = null,
    val displayName: String? = null,
    val deviceGroup: String? = null,
    val deviceFamily: String? = null,
    val appTypes: List<AppTypeJson> = emptyList(),
    val partNumbers: List<PartNumberJson> = emptyList(),
) {
    @Serializable
    data class AppTypeJson(val type: String? = null, val memoryLimit: Long? = null)

    @Serializable
    data class PartNumberJson(val connectIQVersion: String? = null)
}

@Serializable
private data class SimulatorJson(val display: Display = Display()) {
    @Serializable
    data class Display(val isTouch: Boolean = false)
}

/**
 * Reads the device catalogue the SDK Manager installs.
 *
 * Each device is a directory holding `compiler.json` and `simulator.json`. There are 166 of them on
 * a full install, and they change only when the user downloads more, so the result is cached until
 * something asks for a refresh.
 */
class DeviceCatalog(private val devicesRoot: Path) {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<ConnectIqDevice>? = null

    fun devices(): List<ConnectIqDevice> = cached ?: load().also { cached = it }

    fun refresh(): List<ConnectIqDevice> = load().also { cached = it }

    fun byId(id: String): ConnectIqDevice? = devices().firstOrNull { it.id == id }

    /**
     * Device directories that are there but could not be read.
     *
     * Worth keeping rather than discarding: to the user a device downloaded through the SDK
     * Manager and a device whose `compiler.json` is truncated look the same — both are simply
     * absent from every list — and the second is something they can act on.
     */
    @Volatile
    var unreadable: List<String> = emptyList()
        private set

    private fun load(): List<ConnectIqDevice> {
        if (!devicesRoot.isDirectory()) {
            unreadable = emptyList()
            return emptyList()
        }

        val failed = mutableListOf<String>()
        val devices = Files.list(devicesRoot).use { entries ->
            entries.filter { it.isDirectory() }
                .map { directory ->
                    runCatching { read(directory) }
                        .onFailure { failed += directory.fileName.toString() }
                        .getOrNull()
                }
                .filter { it != null }
                .map { it!! }
                .toList()
        }
        unreadable = failed.sorted()
        return devices.sortedWith(compareBy(DISPLAY_NAME_ORDER) { it.displayName })
    }

    private fun read(directory: Path): ConnectIqDevice {
        val compiler = json.decodeFromString<CompilerJson>(directory.resolve("compiler.json").readText())
        val simulator = directory.resolve("simulator.json")
            .runCatching { json.decodeFromString<SimulatorJson>(readText()) }
            .getOrElse { SimulatorJson() }

        return ConnectIqDevice(
            id = compiler.deviceId ?: directory.name,
            displayName = compiler.displayName?.takeIf { it.isNotBlank() } ?: directory.name,
            group = compiler.deviceGroup,
            family = compiler.deviceFamily,
            isTouch = simulator.display.isTouch,
            sdkVersion = compiler.partNumbers.mapNotNull { SdkVersion.parse(it.connectIQVersion) }.maxOrNull(),
            memoryLimits = compiler.appTypes
                .mapNotNull { type -> type.type?.let { it to (type.memoryLimit ?: return@mapNotNull null) } }
                .toMap(),
        )
    }

    private companion object {
        /**
         * Garmin names devices "fenix 6", "fenix 6X Pro", "Forerunner 245" — plain string order puts
         * "fenix 10" before "fenix 6", so the numeric words are compared as numbers.
         */
        val DISPLAY_NAME_ORDER = Comparator<String> { a, b ->
            val left = a.replace('™', ' ').replace('®', ' ').split(' ').filter { it.isNotEmpty() }
            val right = b.replace('™', ' ').replace('®', ' ').split(' ').filter { it.isNotEmpty() }
            var result = 0
            var i = 0
            while (result == 0 && i < minOf(left.size, right.size)) {
                val ln = left[i].toIntOrNull()
                val rn = right[i].toIntOrNull()
                result = if (ln != null && rn != null) ln - rn else left[i].compareTo(right[i], ignoreCase = true)
                i++
            }
            if (result != 0) result else left.size - right.size
        }
    }
}
