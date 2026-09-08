package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.build.BuildKind
import com.github.dtretyakov.monkeyc.lsp.SdkServerCommands
import com.github.dtretyakov.monkeyc.run.Simulator
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import com.github.dtretyakov.monkeyc.sdk.JavaLocator
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.debug.SourceBreakpoint
import org.eclipse.lsp4j.debug.StackTraceArguments
import org.eclipse.lsp4j.debug.StoppedEventArguments
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the SDK's debug adapter the way the plugin does: build, launch, stop on a breakpoint.
 *
 * Two things this exists to pin down, both learned the hard way.
 *
 * The first is the classpath. `DebugAdapterProtocol` is in `monkeybrains.jar` as well, and started
 * from there it dies on a missing gson before it can answer anything — so "the adapter is in the
 * SDK" is only true of one jar.
 *
 * The second is that a launch which does nothing looks exactly like a launch that failed: the
 * adapter answers `success`, emits a burst of `exited`/`terminated`, and says nothing else. The
 * only way to tell the two apart is to require something to actually happen — a breakpoint that
 * gets hit, or a test that reports a result.
 */
class DebugAdapterLiveTest {

    @Test
    fun `the adapter in the SDK answers initialize`() {
        val sdk = LiveSdk.require()
        Session(sdk).use { session ->
            val capabilities: Capabilities = session.initialize()
            assertTrue(
                capabilities.supportsConfigurationDoneRequest == true,
                "the adapter must take breakpoints before the app starts",
            )
            assertTrue(
                capabilities.supportsEvaluateForHovers == true,
                "hovering a variable while stopped is most of what a debugger is for",
            )
        }
    }

    @Test
    fun `an app launched into the simulator stops on a breakpoint`(@TempDir temp: Path) {
        val sdk = LiveSdk.require()
        val project = LiveSdk.fixture(temp)
        val device = LiveSdk.device(sdk, listOf("fenix7", "fenix6"))

        val built = LiveBuild.run(sdk, project, device)
        assertEquals(0, built.exitCode, built.text)
        assumeTrue(Simulator.isReady(), "the Connect IQ simulator is not running")

        // The compiler records real paths in the symbol file, and on macOS a temp directory is
        // reached through a symlink — a breakpoint on the unresolved path is silently never hit.
        val source = project.resolve("source/FixtureApp.mc").toRealPath()

        Session(sdk).use { session ->
            session.initialize()
            val stopped = session.awaitStopped()

            // `initialized` arrives while `launch` is still in flight, and breakpoints have to go
            // in between: the adapter only answers `launch` once configuration is done.
            session.onInitialized {
                session.server.setBreakpoints(
                    SetBreakpointsArguments().apply {
                        this.source = Source().apply {
                            name = "FixtureApp.mc"
                            path = source.toString()
                        }
                        breakpoints = arrayOf(SourceBreakpoint().apply { line = BREAKPOINT_LINE })
                    },
                ).get(30, TimeUnit.SECONDS)
                session.server.configurationDone(ConfigurationDoneArguments())
            }

            session.launch(
                mapOf(
                    "type" to "monkeyc",
                    "request" to "launch",
                    "name" to "live test",
                    "prg" to built.prg.toString(),
                    "prgDebugXml" to "${built.prg}.debug.xml",
                    "device" to device,
                    "stopAtLaunch" to false,
                ),
            )

            assertTrue(
                stopped.await(60, TimeUnit.SECONDS),
                "the app never reached FixtureApp.mc:$BREAKPOINT_LINE:\n${session.output}",
            )

            val frames = session.server
                .stackTrace(StackTraceArguments().apply { threadId = 0 })
                .get(30, TimeUnit.SECONDS)
                .stackFrames
            assertEquals("onUpdate", frames.first().name, "the top frame is where the breakpoint is")
        }
    }

    @Test
    fun `a test build reports each test through output events`(@TempDir temp: Path) {
        val sdk = LiveSdk.require()
        val project = LiveSdk.fixture(temp)
        val device = LiveSdk.device(sdk, listOf("fenix7", "fenix6"))

        val built = LiveBuild.run(sdk, project, device, kind = BuildKind.TESTS)
        assertEquals(0, built.exitCode, built.text)
        assumeTrue(Simulator.isReady(), "the Connect IQ simulator is not running")

        Session(sdk).use { session ->
            session.initialize()
            val finished = session.awaitOutput("Ran 2 tests")
            val configurable = session.awaitInitialized()
            session.onInitialized { session.server.configurationDone(ConfigurationDoneArguments()) }

            // No `tests` key: the extension only sends one when a subset was picked, and an empty
            // array runs nothing at all.
            session.launch(
                mapOf(
                    "type" to "monkeyc",
                    "request" to "launch",
                    "name" to "live test",
                    "prg" to built.prg.toString(),
                    "prgDebugXml" to "${built.prg}.debug.xml",
                    "device" to device,
                    "stopAtLaunch" to false,
                    "runTests" to true,
                ),
            )

            assertTrue(finished.await(90, TimeUnit.SECONDS), "no test results arrived:\n${session.output}")
            assertTrue(session.output.contains("Executing test fixturePasses"), session.output)
            assertTrue(session.output.contains("PASS"), session.output)
            assertTrue(session.output.contains("FAIL"), session.output)

            // The canary for why Debug is not offered on a test configuration. The adapter's own
            // code reads `if (!mRunTests && isForegroundApp) mClient.initialized();`, and without
            // that event no client ever registers a breakpoint. The day this assertion fails,
            // Garmin has made test debugging possible and MonkeyCRunConfiguration.canRun should
            // stop refusing it.
            assertFalse(
                configurable.await(2, TimeUnit.SECONDS),
                "the adapter now enters configuration mode for a test run: tests can be debugged",
            )
        }
    }

    /** The adapter process plus the client bookkeeping every test here needs. */
    private class Session(sdk: ConnectIqSdk) : AutoCloseable {

        private val process = ProcessBuilder(SdkServerCommands.debugAdapter(sdk, JavaLocator.resolve(null)))
            .redirectErrorStream(false)
            .start()

        private val text = StringBuilder()
        private var onInitialized: (() -> Unit)? = null
        private val initialized = CountDownLatch(1)
        private val waiters = mutableListOf<Pair<String, CountDownLatch>>()
        private val stopped = CountDownLatch(1)

        val server: IDebugProtocolServer

        val output: String
            get() = synchronized(text) { text.toString() }

        init {
            val client = object : IDebugProtocolClient {
                override fun initialized() {
                    initialized.countDown()
                    // On the adapter's reading thread, and the callback sends requests of its own —
                    // so it cannot block waiting for a reply that this thread would have to deliver.
                    Thread { onInitialized?.invoke() }.start()
                }

                override fun stopped(args: StoppedEventArguments) = stopped.countDown()

                override fun output(args: OutputEventArguments) {
                    synchronized(text) { text.append(args.output) }
                    val seen = output
                    synchronized(waiters) {
                        waiters.removeAll { (needle, latch) ->
                            seen.contains(needle).also { if (it) latch.countDown() }
                        }
                    }
                }
            }
            val launcher = DSPLauncher.createClientLauncher(client, process.inputStream, process.outputStream)
            launcher.startListening()
            server = launcher.remoteProxy
        }

        fun initialize(): Capabilities = server.initialize(
            InitializeRequestArguments().apply {
                clientID = "idea-monkeyc"
                adapterID = "monkeyc"
                linesStartAt1 = true
                columnsStartAt1 = true
                pathFormat = "path"
            },
        ).get(60, TimeUnit.SECONDS)

        fun onInitialized(action: () -> Unit) {
            onInitialized = action
        }

        /** Fire and forget: the adapter answers `launch` only after configuration is done. */
        fun launch(arguments: Map<String, Any>) {
            server.launch(arguments)
        }

        fun awaitStopped(): CountDownLatch = stopped

        /** Counts down when the adapter asks for configuration, which for a test run it never does. */
        fun awaitInitialized(): CountDownLatch = initialized

        fun awaitOutput(needle: String): CountDownLatch =
            CountDownLatch(1).also { synchronized(waiters) { waiters.add(needle to it) } }

        override fun close() {
            process.destroy()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    private companion object {
        /** `counter += 1;` in the fixture's `onUpdate`, which the simulator reaches on every frame. */
        const val BREAKPOINT_LINE = 28
    }
}
