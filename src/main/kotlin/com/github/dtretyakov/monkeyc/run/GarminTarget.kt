package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.name

/**
 * A Garmin device a build can be installed on, however it happens to be attached.
 *
 * There are two kinds and the difference is not cosmetic. Older watches mount as USB mass storage
 * and take a file copy; current ones speak MTP, appear under no volume at all, and need a tool that
 * talks the protocol. A plugin that knows only the first kind reports no device while the watch is
 * on the desk, which is what this one used to do.
 */
sealed interface GarminTarget {

    /** What to call it when asking the user which one to install on. */
    val name: String

    /** The catalogue device this is, when it can be worked out. Only MTP says its model. */
    val device: ConnectIqDevice? get() = null

    /** Puts the file on the device, returning where it landed. */
    fun install(prg: Path): Path

    /** A watch that mounts as a disk: the file copy that has always worked. */
    data class Volume(val volume: GarminVolume) : GarminTarget {
        override val name: String get() = volume.name
        override fun install(prg: Path): Path = volume.install(prg)
    }

    /**
     * A watch that speaks MTP, driven through `mtp-rs`.
     *
     * The remote name is upper-cased. Every working example of this in the wild uses an upper-case
     * name, and the forum thread that solved settings for sideloaded apps says the case of the
     * settings file has to match the program's — which only means anything if the program's case is
     * fixed. Whether the stem must also be eight characters is not settled, so it is left alone.
     */
    data class Mtp(val tool: Path, val info: MtpDeviceInfo, val model: String? = null) : GarminTarget {
        override val name: String get() = model ?: info.displayName

        override val device: ConnectIqDevice?
            get() = ConnectedWatch.match(model, ConnectIqSdkService.getInstance().devices())

        override fun install(prg: Path): Path {
            val remote = "$APPS_DIRECTORY/${prg.name.uppercase()}"
            val outcome = MtpTool.run(
                listOf(tool.toString()) + MtpTool.uploadArguments(info.serial_number, prg, remote),
                TIMEOUT_MINUTES,
                TimeUnit.MINUTES,
            )

            if (outcome.exitCode == MtpTool.TIMED_OUT) {
                throw IllegalStateException("Installing on $name did not finish.")
            }
            if (outcome.exitCode != 0) {
                throw IllegalStateException(MtpTool.describeFailure(outcome.exitCode, outcome.errors))
            }
            return Path.of(MtpTool.parseUpload(outcome.output)?.remote_path ?: remote)
        }
    }

    companion object {
        private const val APPS_DIRECTORY = "/GARMIN/APPS"
        private const val TIMEOUT_MINUTES = 5L

        /**
         * Every Garmin device attached right now, of either kind.
         *
         * The MTP half is skipped in silence when the tool is not installed: a watch that mounts as
         * a disk needs none of it, and telling somebody to install a Rust toolchain when nothing is
         * plugged in would be noise.
         */
        fun attached(): List<GarminTarget> {
            val volumes = GarminVolume.mounted().map { Volume(it) }
            return volumes + mtpDevices()
        }

        /**
         * A watch that was attached the last time anyone looked, as the device chip has to name it.
         *
         * The model is carried alongside the id because the two answer different questions, and
         * the chip asks both: which catalogue device this is — null when the watch could not be
         * placed among the downloaded ones — and what to call it on screen either way. Keeping
         * only the ids is what made a watch the project does not declare invisible, since there
         * was nothing left of it to show.
         */
        data class Attached(val name: String, val deviceId: String?)

        /**
         * What was attached, from a short-lived cache.
         *
         * The toolbar's device chip asks this on every repaint, and finding out means walking the
         * mount points and starting a subprocess. Neither belongs on the IDE's pulse, and a watch
         * does not appear and vanish within a couple of seconds — so the answer is remembered
         * briefly and recomputed off the UI thread by whoever asks next.
         */
        @Volatile
        private var attachedCache: Pair<Long, List<Attached>> = 0L to emptyList()

        /**
         * What was attached the last time anyone looked. Never looks itself.
         *
         * This is read while a popup is being built, which happens on the UI thread, and looking
         * means walking the mount points and starting a subprocess that is allowed twenty seconds
         * to answer. An earlier version computed here when the cache was stale, and the first click
         * on the chip froze the IDE for several seconds — the comment above it even said this must
         * not sit on the IDE's pulse. Refreshing is [refreshAttached]'s job, and its callers are on
         * background threads.
         */
        fun attachedWatches(): List<Attached> = attachedCache.second

        /**
         * Looks, if the last look is old enough. Must not be called on the UI thread.
         *
         * Called when the device popup opens and when the IDE regains focus, on a pooled thread.
         * The focus one is what makes plugging a watch in work the way people expect: the popup
         * renders from this cache, so without it the first look after attaching a watch showed
         * what was there before it, and only the second look was right. There is no portable way
         * to be told that a USB device arrived, and coming back to the IDE window is the one
         * moment that reliably follows plugging something in.
         */
        fun refreshAttached() {
            val now = System.currentTimeMillis()
            if (now - attachedCache.first < CACHE_MILLIS) return

            // One look at a time. The timestamp above is only written when a look finishes, so
            // while one is running the guard still sees the old one — and a look is not always
            // quick: walking the filesystem roots on Windows reaches a disconnected network
            // drive, where `Files.list` waits rather than failing. Without this, alt-tabbing
            // during one of those starts a thread per activation, each waiting on the same drive.
            if (!looking.compareAndSet(false, true)) return
            try {
                val fresh = runCatching { attached().map { Attached(it.name, it.device?.id) } }
                    .getOrDefault(emptyList())
                attachedCache = System.currentTimeMillis() to fresh
            } finally {
                looking.set(false)
            }
        }

        private val looking = AtomicBoolean(false)

        /**
         * Long enough that alt-tabbing does not start a subprocess each time, short enough that a
         * watch plugged in while the IDE was in the background is found on the way back to it.
         */
        private const val CACHE_MILLIS = 2_000L

        /** Whether the tool is missing, which is only worth saying when a watch might need it. */
        fun mtpToolMissing(): Boolean = MtpLocator.resolve() == null

        private fun mtpDevices(): List<Mtp> {
            val tool = MtpLocator.resolve() ?: return emptyList()
            val outcome = runCatching {
                MtpTool.run(
                    listOf(tool.toString()) + MtpTool.deviceArguments(),
                    LIST_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }.getOrNull() ?: return emptyList()

            // Garmin's vendor id, so a phone or a camera on the same bus is not offered as a
            // watch. On Windows it also keeps out a Synaptics fingerprint sensor, which the tool's
            // descriptor scan reaches and which is not an MTP device at all.
            return MtpTool.parseDevices(outcome.output)
                .filter { it.isGarmin }
                .map { Mtp(tool, it, model = it.product ?: modelOf(tool, it)) }
        }

        /**
         * The model, asked of the device when the bus scan did not say.
         *
         * Everything that names the watch rests on this: what it is called when the user is asked
         * where to install, which catalogue device it is, and the warning that a `.prg` was built
         * for another watch — which matters because such a build installs and then does nothing.
         * On Windows the scan reports no strings, so without this the watch is offered as "an MTP
         * device at a26a3a80c08a9b61" and the warning has no opinion.
         *
         * One extra process per attached Garmin device, and only when the list was silent. This
         * already runs a process and never runs on the UI thread — see [refreshAttached].
         */
        private fun modelOf(tool: Path, device: MtpDeviceInfo): String? {
            val outcome = runCatching {
                MtpTool.run(
                    listOf(tool.toString()) + MtpTool.infoArguments(device.serial_number),
                    INFO_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }.getOrNull() ?: return null
            if (outcome.exitCode != 0) return null
            return MtpTool.parseInfo(outcome.output)?.model?.takeIf { it.isNotBlank() }
        }

        private const val LIST_TIMEOUT_SECONDS = 20L

        /** Opening a device is quick; it is already attached and answering by the time this runs. */
        private const val INFO_TIMEOUT_SECONDS = 20L
    }
}
