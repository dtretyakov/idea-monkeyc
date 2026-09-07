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
    /** `watch-app`, `widget`, `datafield`, … — what a project may declare for this device. */
    val appTypes: Set<String>,
)

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
    data class AppTypeJson(val type: String? = null)

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

    private fun load(): List<ConnectIqDevice> {
        if (!devicesRoot.isDirectory()) return emptyList()
        return Files.list(devicesRoot).use { entries ->
            entries.filter { it.isDirectory() }
                .map { runCatching { read(it) }.getOrNull() }
                .filter { it != null }
                .map { it!! }
                .toList()
        }.sortedWith(compareBy(DISPLAY_NAME_ORDER) { it.displayName })
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
            appTypes = compiler.appTypes.mapNotNull { it.type }.toSet(),
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
