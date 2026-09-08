package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.build.BuildKind

/**
 * What one run configuration is for.
 *
 * These are one configuration class with a flag rather than several kinds of thing, because
 * everything up to the last few compiler arguments is identical — same SDK, same jungles, same
 * developer key, same device. What differs is what comes out and whether anything is started
 * afterwards, and both of those are this enum.
 */
enum class MonkeyCRunKind(
    val display: String,
    val buildKind: BuildKind,
    /** Whether something is started once the build succeeds, or the build was the whole point. */
    val launches: Boolean,
) {
    APP("Connect IQ App", BuildKind.APP, launches = true),
    TESTS("Connect IQ Tests", BuildKind.TESTS, launches = true),
    BUILD("Connect IQ Build", BuildKind.APP, launches = false),
    ;

    val isTests: Boolean get() = this == TESTS

    companion object {
        fun of(name: String?): MonkeyCRunKind = entries.firstOrNull { it.name == name } ?: APP
    }
}
