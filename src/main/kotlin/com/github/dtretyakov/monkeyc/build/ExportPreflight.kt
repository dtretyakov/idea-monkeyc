package com.github.dtretyakov.monkeyc.build

import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.github.dtretyakov.monkeyc.sdk.SdkVersion

/**
 * What an export is about to ship, and what it is quietly about to leave out.
 *
 * An export takes minutes and produces one file for every device the manifest declares — and it
 * narrows that list twice without saying so. A device whose part numbers all predate the manifest's
 * minimum API level is dropped; when that empties the list the compiler answers "No exportable
 * devices are supported by the project", and when it does not, the app simply ships to fewer
 * watches than the developer thinks. Languages narrow it again, per hardware variant, which nobody
 * expects at all.
 *
 * All of it is decidable before the compiler starts, from data this plugin already reads. Kept as
 * pure functions like the rest of the rules here, because the value is in the wording and the
 * wording is what a test can hold onto.
 */
object ExportPreflight {

    enum class Severity { WARNING, ERROR }

    data class Finding(val severity: Severity, val text: String)

    /**
     * Everything worth saying before an export, in the order it is worth saying it.
     *
     * @param declared what the manifest names, in its own order
     * @param installed the devices the SDK Manager has downloaded
     */
    fun check(manifest: ManifestFile, installed: List<ConnectIqDevice>): List<Finding> = buildList {
        val byId = installed.associateBy { it.id }
        val known = manifest.devices.mapNotNull { byId[it] }

        missingDevices(manifest.devices, byId.keys)?.let { add(it) }
        belowMinimum(known, manifest.minSdkVersion)?.let { add(it) }
        addAll(languageGaps(manifest.languages, known))
        trial(manifest)?.let { add(it) }
    }

    /**
     * Devices the manifest names that are not on this machine.
     *
     * The export cannot build them, and the store rejects a package whose part numbers it does not
     * recognise — the two most common reasons being a preview device that was never released and a
     * brand-new one the store's own database has not caught up with.
     */
    private fun missingDevices(declared: List<String>, installed: Set<String>): Finding? {
        val missing = declared.filterNot { it in installed }
        if (missing.isEmpty()) return null
        return Finding(
            Severity.ERROR,
            "${missing.size} declared device${plural(missing.size)} not downloaded, so " +
                "${if (missing.size == 1) "it" else "they"} cannot be built: ${name(missing)}. " +
                "Get them with the SDK Manager, or remove them from the manifest.",
        )
    }

    /**
     * Devices dropped because nothing they ship supports the minimum API level asked for.
     *
     * The silent one. Raising `minApiLevel` for one feature quietly stops shipping to every watch
     * that cannot reach it, and the export says nothing at all.
     */
    private fun belowMinimum(known: List<ConnectIqDevice>, minimum: SdkVersion?): Finding? {
        if (minimum == null) return null
        val dropped = known.filter { it.sdkVersion != null && it.sdkVersion!! < minimum }
        if (dropped.isEmpty()) return null

        return Finding(
            Severity.WARNING,
            "${dropped.size} declared device${plural(dropped.size)} cannot reach API level " +
                "$minimum and will be left out of the package: ${name(dropped.map { it.id })}. " +
                "Lower the minimum, or remove them.",
        )
    }

    /**
     * Languages the declared devices cannot all serve.
     *
     * Reported per language rather than per device, because that is the decision in front of the
     * developer: keeping a language costs particular watches, and dropping it costs particular
     * users. Nothing else in the ecosystem shows either number.
     */
    private fun languageGaps(languages: List<String>, known: List<ConnectIqDevice>): List<Finding> {
        if (known.isEmpty()) return emptyList()

        return languages.mapNotNull { language ->
            val without = known.filter { language !in it.languages }
            val partial = known.filter { device ->
                val (with, total) = device.partNumbersWith(language)
                with in 1 until total
            }

            when {
                without.isNotEmpty() -> Finding(
                    Severity.WARNING,
                    "$language is declared, but ${without.size} of ${known.size} declared " +
                        "device${plural(known.size)} do not support it: ${name(without.map { it.id })}. " +
                        "A language a device cannot render narrows where the app is offered.",
                )

                // Same watch, different hardware variant — the case nobody expects, so it is worth
                // its own sentence rather than being folded into the one above.
                partial.isNotEmpty() -> Finding(
                    Severity.WARNING,
                    "$language is supported by only some hardware variants of " +
                        "${name(partial.map { it.id })}, so those variants will not receive it.",
                )

                else -> null
            }
        }
    }

    /**
     * The two rules the store applies to a trial that a manifest can be checked against.
     *
     * Both are documented and both are rejections rather than warnings: "The app store will only
     * accept secured HTTPS-URLs when uploading your iq-file", and "The app trials feature is not
     * supported for watch faces".
     */
    private fun trial(manifest: ManifestFile): Finding? {
        if (!manifest.trialMode) return null

        if (manifest.appType == "watchface") {
            return Finding(Severity.ERROR, "Trial mode is on, and the store does not support trials for watch faces.")
        }
        val url = manifest.unlockUrl
        if (url != null && !url.startsWith("https://", ignoreCase = true)) {
            return Finding(Severity.ERROR, "The unlock URL is not HTTPS, and the store accepts only HTTPS: $url")
        }
        return null
    }

    /** Enough names to act on, then a count: a list of forty devices is not a sentence. */
    private fun name(ids: List<String>): String =
        ids.take(NAMED).joinToString() + if (ids.size > NAMED) ", and ${ids.size - NAMED} more" else ""

    private fun plural(count: Int) = if (count == 1) "" else "s"

    private const val NAMED = 5
}
