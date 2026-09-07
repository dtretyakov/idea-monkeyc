package com.github.dtretyakov.monkeyc.project

/**
 * The three graded compiler settings.
 *
 * Each carries both spellings on purpose: the CLI takes a digit (`-l 2`), while the language server
 * takes the name and resolves it with `TypeCheckLevel.fromName`, which upper-cases and looks up the
 * enum. `Default` means "say nothing" — the compiler and the server each have their own default and
 * it is not ours to guess.
 */
enum class TypeCheckLevel(val display: String, val flag: String?) {
    DEFAULT("Default", null),
    OFF("Off", "0"),
    GRADUAL("Gradual", "1"),
    INFORMATIVE("Informative", "2"),
    STRICT("Strict", "3"),
    ;

    companion object {
        fun of(display: String?): TypeCheckLevel =
            entries.firstOrNull { it.display.equals(display, ignoreCase = true) } ?: DEFAULT
    }
}

enum class OptimizationLevel(val display: String, val flag: String?) {
    DEFAULT("Default", null),
    NONE("None", "0"),
    BASIC("Basic", "1"),
    FAST("Fast", "2"),
    SLOW("Slow", "3"),
    ;

    companion object {
        fun of(display: String?): OptimizationLevel =
            entries.firstOrNull { it.display.equals(display, ignoreCase = true) } ?: DEFAULT
    }
}

enum class DebugLogLevel(val display: String, val flag: String?) {
    DEFAULT("Default", null),
    ERRORS_ONLY("Errors Only", "0"),
    BASIC("Basic", "1"),
    INTERMEDIATE("Intermediate", "2"),
    VERBOSE("Verbose", "3"),
    ;

    companion object {
        fun of(display: String?): DebugLogLevel =
            entries.firstOrNull { it.display.equals(display, ignoreCase = true) } ?: DEFAULT
    }
}
