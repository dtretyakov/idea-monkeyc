package com.github.dtretyakov.monkeyc.build

import java.nio.file.Path

/** What a build produces, which is what decides how the compiler is invoked. */
enum class BuildKind {
    /** A `.prg` for one device. */
    APP,

    /** A `.prg` holding the project's unit tests, for one device. */
    TESTS,

    /** A signed `.iq` for the store, built for every device the manifest declares. */
    EXPORT,

    /** A `.barrel` — a library for other projects, with no device of its own. */
    BARREL,

    /** A `.prg` of a barrel's tests, which unlike the barrel itself does need a device. */
    BARREL_TESTS,
    ;

    val needsDevice: Boolean get() = this == APP || this == TESTS || this == BARREL_TESTS
    val needsDeveloperKey: Boolean get() = this != BARREL
}

/**
 * One invocation of the Connect IQ compiler.
 *
 * [simulator] is the subtlety: a build for the simulator asks for device id `fenix7_sim` rather
 * than `fenix7`, and a `.prg` built for the one will not run on the other.
 */
data class BuildSpec(
    val kind: BuildKind,
    val root: Path,
    val output: Path,
    val jungleFiles: List<Path>,
    val device: String? = null,
    val simulator: Boolean = true,
    val developerKey: Path? = null,
    /** Arguments from the run configuration, on top of the ones from the settings. */
    val extraArguments: List<String> = emptyList(),
)
