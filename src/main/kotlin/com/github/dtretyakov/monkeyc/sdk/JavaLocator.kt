package com.github.dtretyakov.monkeyc.sdk

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

        // Drained on a thread of its own, and the timeout applied to the process rather than to
        // the read. Reading here first — which is the obvious way to write this — makes the
        // timeout decorative: `waitFor` is only reached once the child closes its stdout, so a
        // `java` that starts and then hangs is waited on forever. It is the same trap
        // `MtpTool.run` documents, and it matters more here, because this runs while the settings
        // page is being built.
        val output = AtomicReference("")
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().use { output.set(it.readText()) } }
        }.apply {
            isDaemon = true
            start()
        }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        // The process is gone, so its stream closes and the reader ends on its own; the join is
        // bounded anyway, because a reader that somehow does not is not worth hanging on.
        reader.join(DRAIN_MILLIS)

        // `java -version` writes three lines to standard error; the first carries the version.
        output.get().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    }.getOrNull()

    /** How long to wait for the drain thread once the process itself is already gone. */
    private const val DRAIN_MILLIS = 500L
}
