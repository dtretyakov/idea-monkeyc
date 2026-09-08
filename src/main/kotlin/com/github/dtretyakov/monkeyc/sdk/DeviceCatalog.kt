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
    /**
     * The languages each of this device's part numbers supports, one set per part number.
     *
     * Per part number, not per device, because that is how Garmin ships it: a product id can map to
     * several hardware SKUs — a world-wide one and an APAC one, say — and they do not carry the
     * same fonts. Garmin's own documentation says "the languages your app support can impact what
     * regions of the world your app is available in", and one forum thread traced a failing export
     * precisely to an `<iq:languages>` block. Nothing anywhere shows the trade-off, and the data
     * has been sitting in a file this plugin already opens.
     */
    val languagesByPartNumber: List<Set<String>> = emptyList(),
    /** Pixels across and down, which with [shape] is what a layout has to fit. */
    val resolution: Pair<Int, Int>? = null,
    /** `round`, `rectangle`, `semi-octagon`, `semi-round`. */
    val shape: String? = null,
    /** 16, 8, 4 or 1. A design drawn for sixteen falls apart on four. */
    val bitsPerPixel: Int? = null,
    /** `mip`, `amoled` or `lcd`; an AMOLED watch face has burn-in rules a MIP one does not. */
    val displayType: String? = null,
    /** How many physical buttons, or null when the profile does not say. */
    val buttons: Int? = null,
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

    /** Every language any of this device's part numbers offers. */
    val languages: Set<String> get() = languagesByPartNumber.flatten().toSet()

    /**
     * How many of this device's part numbers offer [language], out of how many there are.
     *
     * Both halves matter. None means the device cannot serve that language at all; some but not all
     * means a hardware variant of the same watch cannot, which is the case nobody expects and the
     * reason this is not a boolean.
     */
    fun partNumbersWith(language: String): Pair<Int, Int> =
        languagesByPartNumber.count { language in it } to languagesByPartNumber.size

    /** "round 240×240", or as much of it as the profile says. */
    val screen: String
        get() = listOfNotNull(shape, resolution?.let { "${it.first}×${it.second}" }).joinToString(" ")

    /** How the user gets around: the thing that decides whether an app is usable at all. */
    val input: String
        get() = when {
            isTouch && (buttons ?: 0) > 0 -> "touch, ${buttons} button${if (buttons == 1) "" else "s"}"
            isTouch -> "touch"
            buttons != null -> "$buttons button${if (buttons == 1) "" else "s"}"
            else -> ""
        }
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
    val bitsPerPixel: Int? = null,
    val displayType: String? = null,
    val resolution: ResolutionJson? = null,
    val appTypes: List<AppTypeJson> = emptyList(),
    val partNumbers: List<PartNumberJson> = emptyList(),
) {
    @Serializable
    data class AppTypeJson(val type: String? = null, val memoryLimit: Long? = null)

    @Serializable
    data class PartNumberJson(
        val connectIQVersion: String? = null,
        val languages: List<LanguageJson> = emptyList(),
    )

    @Serializable
    data class LanguageJson(val code: String? = null)

    @Serializable
    data class ResolutionJson(val width: Int? = null, val height: Int? = null)
}

@Serializable
private data class SimulatorJson(
    val display: Display = Display(),
    val keys: List<KeyJson> = emptyList(),
) {
    @Serializable
    data class Display(val isTouch: Boolean = false, val shape: String? = null)

    @Serializable
    data class KeyJson(val id: String? = null)
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
            shape = simulator.display.shape,
            buttons = simulator.keys.size.takeIf { simulator.keys.isNotEmpty() },
            bitsPerPixel = compiler.bitsPerPixel,
            displayType = compiler.displayType,
            resolution = compiler.resolution?.let { size ->
                val width = size.width
                val height = size.height
                if (width != null && height != null) width to height else null
            },
            sdkVersion = compiler.partNumbers.mapNotNull { SdkVersion.parse(it.connectIQVersion) }.maxOrNull(),
            languagesByPartNumber = compiler.partNumbers.map { part ->
                part.languages.mapNotNull { it.code }.toSet()
            },
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
