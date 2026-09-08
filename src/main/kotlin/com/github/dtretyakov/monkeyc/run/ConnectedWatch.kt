package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import java.text.Normalizer

/**
 * The watch on the desk, and which device in the catalogue it is.
 *
 * Worth working out because nothing else connects the two. A developer picks a target from a list
 * of a hundred and seventy and hopes; a `.prg` built for the wrong one does not run, and looks like
 * a broken app rather than a wrong target.
 *
 * Deliberately a suggestion. Targeting a device you do not own is the normal case for an app that
 * ships to forty of them, so the watch attached is evidence and not instruction: it preselects, and
 * it warns when the build and the watch disagree, and it never silently changes what is being
 * built.
 */
object ConnectedWatch {

    /**
     * The catalogue device this MTP product string names, or null when nothing matches.
     *
     * Matched on the display name, which is what both sides call the model: `mtp-rs` reports
     * "Venu 2" from the USB descriptor, and the SDK's own device data calls it "Venu 2" too.
     * Garmin decorates its names with trademark marks in places, and a watch reports its model
     * without them, so both sides are reduced before comparing.
     */
    fun match(product: String?, devices: List<ConnectIqDevice>): ConnectIqDevice? {
        val wanted = normalise(product) ?: return null
        return devices.firstOrNull { normalise(it.displayName) == wanted }
    }

    /**
     * Why the build and the watch disagree, or null when they do not — including when there is no
     * way to tell, which is not a disagreement.
     */
    fun mismatch(built: String?, watch: ConnectIqDevice?, product: String?): String? {
        if (built.isNullOrEmpty() || product == null) return null
        if (watch == null) {
            // A watch we cannot place is not evidence of anything. Say what was seen and stop.
            return null
        }
        if (watch.id == built) return null
        return "This build is for $built, but the watch connected is ${watch.displayName} " +
            "(${watch.id}). A .prg built for another device will not run — it installs and then " +
            "does nothing, which reads as a broken app."
    }

    /**
     * Down to unaccented letters and digits, lower case.
     *
     * The two sides of this comparison are a USB descriptor and a marketing name, and they do not
     * agree on much. Of the 173 devices the SDK ships, 171 have a display name that is not plain
     * text: `fēnix® 7`, `vívoactive® 5`, `Enduro™ 3`, `Venu® Sq. Music Edition`. A watch reports
     * itself without any of that. Decomposing and dropping the combining marks handles the
     * diacritics without a table of special cases — spelling out `ē` and forgetting `í` is exactly
     * the kind of near-miss that makes a matcher look like it works.
     */
    private fun normalise(name: String?): String? = name
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFD) }
        ?.lowercase()
        ?.filter { it.isLetterOrDigit() && it.code < ASCII_LIMIT }
        ?.takeIf { it.isNotEmpty() }

    /** Combining marks survive decomposition as non-ASCII; dropping them is the point. */
    private const val ASCII_LIMIT = 128
}
