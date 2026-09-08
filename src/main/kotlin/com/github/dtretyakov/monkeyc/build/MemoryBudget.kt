package com.github.dtretyakov.monkeyc.build

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

/**
 * How much of a device's memory the built program takes.
 *
 * Memory is the constraint that shapes Connect IQ development, and it is the one number no editor
 * shows. The limits differ by an order of magnitude between devices — a data field gets 16 KB on
 * an old watch and 256 KB on a new one — so an app that fits the watch on the developer's wrist
 * fails to load on a device they do not own, with a message that names neither the limit nor the
 * size. Developers have been collecting these figures out of `compiler.json` into shared
 * spreadsheets; the plugin already reads that file for every device the SDK Manager downloaded.
 *
 * This is the load-time budget, which is what the `.prg` is measured against and what the
 * compiler's own "exceeds the memory limit" refers to. It says nothing about what the app
 * allocates once it is running — that is peak memory, it is the simulator's to report, and it is a
 * different question with a different answer.
 */
object MemoryBudget {

    /** Above this share of the limit, the number is worth pointing at rather than merely stating. */
    private const val TIGHT = 0.85

    data class Report(
        val device: String,
        val appType: String,
        val used: Long,
        val limit: Long,
    ) {
        val share: Double get() = used.toDouble() / limit
        val tight: Boolean get() = share >= TIGHT
        val overflowing: Boolean get() = used > limit
    }

    /**
     * The report for a finished build, or null when there is nothing to say.
     *
     * Null rather than a guess whenever any part of the question is open: no output on disk, no
     * device (an export builds for many, a barrel for none), or a device whose catalogue entry has
     * no figure for this kind of app. A wrong number here would be worse than no number, because
     * the whole point of it is to be trusted.
     */
    fun of(output: Path, device: ConnectIqDevice?, manifestAppType: String?): Report? {
        if (device == null) return null
        val limit = device.memoryLimitFor(manifestAppType)?.takeIf { it > 0 } ?: return null
        val used = output.takeIf { it.isRegularFile() }
            ?.runCatching { fileSize() }?.getOrNull()
            ?.takeIf { it > 0 } ?: return null
        return Report(device.displayName, manifestAppType.orEmpty(), used, limit)
    }

    /** One line for the Build window: the size, the limit, and what share of it has gone. */
    fun describe(report: Report): String {
        val headroom = report.limit - report.used
        val where = "${kilobytes(report.used)} of ${kilobytes(report.limit)} " +
            "(${percent(report.share)}) on ${report.device}"
        return when {
            report.overflowing ->
                "$where — over the limit by ${kilobytes(-headroom)}, so it will not load."
            report.tight ->
                "$where — ${kilobytes(headroom)} left."
            else -> "$where."
        }
    }

    /**
     * Kilobytes, to one decimal place, because the numbers that matter here are tens of kilobytes
     * and rounding to whole ones hides the difference between fitting and not.
     *
     * Formatted in the root locale, not the machine's. The sentence around it is English and the
     * figure is a technical one, so a decimal comma here would be neither one thing nor the other
     * — and it would make the same build report differently on two developers' machines.
     */
    private fun kilobytes(bytes: Long): String = "%.1f KB".format(Locale.ROOT, bytes / 1024.0)

    private fun percent(share: Double): String = "%.0f%%".format(Locale.ROOT, share * 100)
}
