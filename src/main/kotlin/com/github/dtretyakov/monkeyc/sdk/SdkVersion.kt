package com.github.dtretyakov.monkeyc.sdk

/**
 * A Connect IQ SDK version, as written in `bin/version.txt` — for example `9.1.0` or `8.1.0-beta`.
 *
 * Only the numeric part is compared: Garmin ships betas of a version before the version itself, and
 * treating `8.1.0-beta` as older than `8.1.0` would refuse a beta that has the feature we are
 * gating on.
 */
data class SdkVersion(val parts: List<Int>, val raw: String) : Comparable<SdkVersion> {

    override fun compareTo(other: SdkVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val diff = parts.getOrElse(i) { 0 } - other.parts.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    override fun toString(): String = raw

    companion object {
        /** The first SDK that shipped `bin/LanguageServer.jar`; below it there is no language support. */
        val LANGUAGE_SERVER_MINIMUM = parse("8.1.0")!!

        /** Type checking (`-l`) arrived in 4.0.0, optimization levels (`-O`) in 4.1.6. */
        val TYPE_CHECK_MINIMUM = parse("4.0.0")!!
        val OPTIMIZATION_MINIMUM = parse("4.1.6")!!

        fun parse(text: String?): SdkVersion? {
            val raw = text?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val numeric = raw.takeWhile { it.isDigit() || it == '.' }.trim('.')
            if (numeric.isEmpty()) return null
            val parts = numeric.split('.').mapNotNull { it.toIntOrNull() }
            return if (parts.isEmpty()) null else SdkVersion(parts, raw)
        }
    }
}
