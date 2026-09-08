package com.github.dtretyakov.monkeyc.sdk

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isExecutable

/**
 * Finds a `java` to run the SDK's jars with.
 *
 * The IDE always has one — it runs on it — so unlike the VS Code extension, which falls back to
 * whatever `java` is on `PATH` and fails with `spawn java ENOENT` when there is none, this can
 * always answer. An explicit setting still wins, because the SDK is picky about very new JDKs and
 * the user may need to point at an older one.
 */
object JavaLocator {

    fun resolve(configured: String?): Path {
        configured?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
            val candidate = Path.of(path)
            // Accept either the executable itself or a JDK home, which is what the VS Code
            // setting of the same name holds.
            executableIn(candidate)?.let { return it }
            if (candidate.exists()) return candidate
        }

        System.getProperty("java.home")?.let { home ->
            executableIn(Path.of(home))?.let { return it }
        }

        System.getenv("JAVA_HOME")?.let { home ->
            executableIn(Path.of(home))?.let { return it }
        }

        return Path.of(executableName)
    }

    private fun executableIn(home: Path): Path? =
        home.resolve("bin").resolve(executableName).takeIf { it.exists() && it.isExecutable() }

    private val executableName: String
        get() = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"

    /**
     * The version string this `java` reports, or null when it will not say.
     *
     * Worth knowing, and worth showing, because the JVM is the largest unmarked performance
     * variable on this platform: a developer on the forums had a full export take four hours and
     * got it down to two minutes by changing nothing but the JRE. Nobody would look there, because
     * nothing tells them to. This does not judge the answer — the fast and slow versions are not a
     * range anyone has mapped, and a rule invented here would be wrong on someone's machine — it
     * only makes the variable visible next to the build times beside it.
     */
    fun version(java: Path, timeoutSeconds: Long = 5): String? = runCatching {
        val process = ProcessBuilder(java.toString(), "-version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        // `java -version` writes three lines to standard error; the first carries the version.
        output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    }.getOrNull()
}
