package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
    data class Mtp(val tool: Path, val info: MtpDeviceInfo) : GarminTarget {
        override val name: String get() = info.displayName

        override val device: ConnectIqDevice?
            get() = ConnectedWatch.match(info.product, ConnectIqSdkService.getInstance().devices())

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
         * The ids of the catalogue devices currently attached, from a short-lived cache.
         *
         * The toolbar's device chip asks this on every repaint, and finding out means walking the
         * mount points and starting a subprocess. Neither belongs on the IDE's pulse, and a watch
         * does not appear and vanish within a couple of seconds — so the answer is remembered
         * briefly and recomputed off the UI thread by whoever asks next.
         */
        @Volatile
        private var attachedCache: Pair<Long, Set<String>> = 0L to emptySet()

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
        fun attachedDeviceIds(): Set<String> = attachedCache.second

        /**
         * Looks, if the last look is old enough. Must not be called on the UI thread.
         *
         * Called when the device popup opens, on a pooled thread, so the popup shows what was
         * found last time and the next one is right. The worst case is a watch that has just been
         * plugged in going unmarked until the menu is opened again, which is a great deal better
         * than an IDE that stops responding — or than a subprocess every few seconds for as long
         * as a project is open, answering a question nobody is asking.
         */
        fun refreshAttached() {
            val now = System.currentTimeMillis()
            if (now - attachedCache.first < CACHE_MILLIS) return

            val fresh = runCatching { attached().mapNotNull { it.device?.id }.toSet() }.getOrDefault(emptySet())
            attachedCache = now to fresh
        }

        /** Short: a look only happens when the menu is opened, so this only collapses a double-open. */
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

            // Garmin's vendor id, so a phone or a camera on the same bus is not offered as a watch.
            return MtpTool.parseDevices(outcome.output).filter { it.isGarmin }.map { Mtp(tool, it) }
        }

        private const val LIST_TIMEOUT_SECONDS = 20L
    }
}
