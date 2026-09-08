package com.github.dtretyakov.monkeyc.build

/** How far an export has got: it builds for every device the manifest declares, one at a time. */
data class BuildProgress(val built: Int, val total: Int)

/** One diagnostic the Connect IQ compiler printed. */
data class CompilerMessage(
    val severity: Severity,
    /** The device being built when this was reported; a build for several reports each one. */
    val device: String?,
    val file: String?,
    /** 1-based, as the compiler prints it. */
    val line: Int?,
    /** 0-based, as the compiler prints it. */
    val column: Int?,
    val text: String,
) {
    enum class Severity { WARNING, ERROR }
}

/**
 * Reads the compiler's diagnostics off its standard error.
 *
 * The compiler writes one diagnostic per line, in one of these shapes:
 *
 * ```
 * ERROR: fenix7: /path/App.mc:30,8: mismatched input 'dc' expecting ';'
 * WARNING: fenix7: /path/App.mc:32: Local variable 'unused' is not used.
 * ERROR: Unable to find the developer key.
 * ```
 *
 * The device and the location are both optional, and a message that has neither still matters —
 * that is the shape a failure to even start the build takes.
 */
object CompilerOutputParser {

    private val DIAGNOSTIC = Regex(
        """^(WARNING|ERROR):\s*""" +          // severity
            """(?:(\w+):\s*)?""" +            // device, when building for one
            """(?:(.+?):(\d+)(?:,(\d+))?:\s*)?""" + // file:line[,column]
            """(.*)$""",
    )

    fun parseLine(line: String): CompilerMessage? {
        val match = DIAGNOSTIC.matchEntire(line.trim()) ?: return null
        val (severity, device, file, lineNumber, column, text) = match.destructured
        if (text.isBlank()) return null

        return CompilerMessage(
            severity = if (severity == "WARNING") CompilerMessage.Severity.WARNING else CompilerMessage.Severity.ERROR,
            device = device.takeIf { it.isNotEmpty() },
            file = file.takeIf { it.isNotEmpty() },
            line = lineNumber.toIntOrNull(),
            column = column.toIntOrNull(),
            text = text,
        )
    }

    fun parse(output: String): List<CompilerMessage> =
        output.lineSequence().mapNotNull { parseLine(it) }.toList()

    /**
     * The one line an export prints that says how far it has got.
     *
     * An export builds for every device the manifest declares and takes minutes; without this the
     * Build window can only show an indeterminate bar, which for a six-device project says nothing
     * about whether to wait or go and do something else.
     */
    fun progressOf(line: String): BuildProgress? {
        val match = PROGRESS.matchEntire(line.trim()) ?: return null
        val built = match.groupValues[1].toIntOrNull() ?: return null
        val total = match.groupValues[2].toIntOrNull() ?: return null
        return BuildProgress(built, total)
    }

    private val PROGRESS = Regex("""^(\d+) OUT OF (\d+) DEVICES BUILT$""")
}
