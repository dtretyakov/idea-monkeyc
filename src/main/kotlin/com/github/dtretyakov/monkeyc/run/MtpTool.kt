package com.github.dtretyakov.monkeyc.run

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * One MTP device as `mtp-rs` describes it.
 *
 * Field names are the tool's own, taken from `DeviceRow` in its source rather than from a forum
 * post, so a rename upstream shows up as a parse that finds nothing rather than as a subtly wrong
 * answer.
 */
@Serializable
data class MtpDeviceInfo(
    val vendor_id: Int = 0,
    val product_id: Int = 0,
    val manufacturer: String? = null,
    val product: String? = null,
    val serial_number: String? = null,
    val location: String = "",
    val match_reason: String = "",
) {
    /** Garmin's USB vendor id, which is how a watch is told from a phone or a camera. */
    val isGarmin: Boolean get() = vendor_id == GARMIN_VENDOR_ID

    /** What to call it on screen: the model if the device says one, else something honest. */
    val displayName: String
        get() = product?.takeIf { it.isNotBlank() }
            ?: manufacturer?.takeIf { it.isNotBlank() }
            ?: "an MTP device at $location"

    companion object {
        /** `0x091e`, which is why `--known 091e:0003` is the example in the tool's own docs. */
        const val GARMIN_VENDOR_ID = 0x091e
    }
}

/** What an upload did, as `mtp-rs` reports it. */
@Serializable
data class MtpUpload(
    val remote_path: String = "",
    val filename: String = "",
    val bytes: Long = 0,
    val replaced: Boolean = false,
    val verified: Boolean = false,
)

/**
 * Speaking MTP by driving the tool that already does.
 *
 * A modern Garmin watch is an MTP device, not a disk: nothing appears under `/Volumes`, which is
 * why the forums tell Mac users to install Android File Transfer. Implementing MTP in the JVM would
 * mean either a native dependency the user has to install, or writing PTP framing over raw USB per
 * platform — the opposite of how this plugin does everything else, which is to find the program
 * that exists and speak its protocol.
 *
 * Everything here that can be pure is pure, because the rest needs hardware to exercise.
 */
object MtpTool {

    private val json = Json { ignoreUnknownKeys = true }

    /** The name the binary installs under. */
    const val EXECUTABLE = "mtp-rs"

    /**
     * Where to look for it when nothing is configured.
     *
     * `~/.cargo/bin` is on the list because `cargo install mtp-rs-cli` is currently the only way to
     * get it, and that directory is on `PATH` for a shell but not always for a GUI application —
     * an IDE launched from Finder inherits a `PATH` that a terminal would not recognise.
     */
    fun candidates(home: Path): List<Path> = listOf(home.resolve(".cargo/bin").resolve(EXECUTABLE))

    fun isUsable(path: Path): Boolean = path.isRegularFile() && path.isExecutable()

    /**
     * The devices in `mtp-rs --json devices` output.
     *
     * An empty list is the normal answer and not a failure: the command exits 0 with `[]` when
     * nothing is plugged in. Only the commands that need a device exit 2.
     */
    fun parseDevices(output: String): List<MtpDeviceInfo> =
        runCatching { json.decodeFromString<List<MtpDeviceInfo>>(output.trim().ifEmpty { "[]" }) }
            .getOrDefault(emptyList())

    fun parseUpload(output: String): MtpUpload? =
        runCatching { json.decodeFromString<MtpUpload>(output.trim()) }.getOrNull()

    /**
     * The arguments that put a file on the device.
     *
     * Shaped after an invocation that is known to work on a real watch rather than after the
     * documentation's example: the device is named globally, and `--verify` reads the bytes back.
     * `--replace` is deliberately not passed by default — it "replaces a visible existing remote
     * file", and current Garmin devices hide a `.prg` once they have taken it, so there is often no
     * visible file to replace.
     */
    fun uploadArguments(serial: String?, local: Path, remote: String, replace: Boolean = false): List<String> =
        buildList {
            add("--json")
            serial?.takeIf { it.isNotBlank() }?.let {
                add("--device")
                add(it)
            }
            add("put")
            add("--verify")
            if (replace) add("--replace")
            add(local.toString())
            add(remote)
        }

    fun deviceArguments(): List<String> = listOf("--json", "devices")

    /** What a finished `mtp-rs` run said and how it ended. */
    data class Outcome(val exitCode: Int, val output: String, val errors: String)

    /**
     * Runs the tool and collects both streams without deadlocking on either.
     *
     * The obvious way to write this is wrong, and quietly: read stdout to the end, then read
     * stderr. `mtp-rs` writes its JSON result to stdout only when the transfer finishes, and
     * streams progress to stderr throughout — "Transfer progress still goes to stderr" even in
     * `--json` mode. So the reader blocks on stdout while the child fills the stderr pipe buffer,
     * the child blocks writing, and neither moves again. The timeout below cannot help, because
     * `waitFor` is never reached.
     *
     * The streams cannot simply be merged either: keeping stdout parseable is the entire reason
     * the tool separates them.
     */
    fun run(command: List<String>, timeout: Long, unit: TimeUnit): Outcome {
        val process = ProcessBuilder(command).start()

        // Both streams are drained on threads of their own, and the timeout is applied to the
        // process rather than to a read. Reading either stream on this thread makes the timeout
        // decorative: a run that hangs without printing anything never reaches `waitFor` at all,
        // and one that floods stderr while stdout stays silent deadlocks outright — which is the
        // shape of a real transfer, since progress goes to stderr and the JSON result arrives on
        // stdout only at the end.
        val output = StringBuilder()
        val errors = StringBuilder()
        val readers = listOf(
            drain(process.inputStream, output),
            drain(process.errorStream, errors),
        )

        val finished = process.waitFor(timeout, unit)
        if (!finished) process.destroyForcibly()
        // Once the process is gone its streams close, so the readers end on their own; the wait is
        // bounded anyway, because a reader that somehow does not is not worth hanging the IDE for.
        readers.forEach { it.join(DRAIN_MILLIS) }

        return Outcome(if (finished) process.exitValue() else TIMED_OUT, output.toString(), errors.toString())
    }

    private fun drain(stream: java.io.InputStream, into: StringBuilder): Thread = Thread {
        runCatching { stream.bufferedReader().use { it.forEachLine { line -> synchronized(into) { into.appendLine(line) } } } }
    }.apply {
        isDaemon = true
        start()
    }

    /** Not one of the tool's codes: ours, for a run that never finished. */
    const val TIMED_OUT = -1

    /** Long enough for a drained reader to notice the stream closed, short enough not to wait on it. */
    private const val DRAIN_MILLIS = 2_000L

    /**
     * What went wrong, in words, from the tool's exit code and whatever it said.
     *
     * The codes are its own and worth telling apart: a device nobody can open because another
     * application has it is a different problem from a cable that dropped mid-transfer, and on
     * macOS the first is the common one — `ptpcamerad` claims MTP devices on connection, and
     * Android File Transfer and Garmin Express both take them too.
     */
    fun describeFailure(exitCode: Int, stderr: String): String {
        val said = stderr.trim().takeIf { it.isNotEmpty() }?.lines()?.first()
        return when (exitCode) {
            NO_DEVICE -> "No Garmin device is connected, or it is not in file-transfer mode."
            AMBIGUOUS -> "More than one device is connected; choose which one to install on."
            ACCESS_DENIED ->
                "The device is connected but could not be opened. Another application usually has " +
                    "it: quit Garmin Express, Android File Transfer, or anything else reading the " +
                    "watch, and try again." + (said?.let { " ($it)" } ?: "")
            REMOTE_PATH -> "The device rejected the path. ${said ?: ""}".trim()
            TRANSFER -> "The transfer failed. ${said ?: "Try a different cable or port."}".trim()
            VERIFICATION -> "The file was copied but read back differently, so it was not installed correctly."
            else -> said ?: "mtp-rs exited with $exitCode."
        }
    }

    const val NO_DEVICE = 2
    const val AMBIGUOUS = 3
    const val ACCESS_DENIED = 4
    const val REMOTE_PATH = 5
    const val TRANSFER = 6
    const val VERIFICATION = 7
}
