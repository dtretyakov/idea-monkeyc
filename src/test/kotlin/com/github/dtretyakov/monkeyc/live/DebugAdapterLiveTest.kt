package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.lsp.SdkServerCommands
import com.github.dtretyakov.monkeyc.sdk.JavaLocator
import org.eclipse.lsp4j.debug.Capabilities
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.launch.DSPLauncher
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Starts the SDK's debug adapter and shakes hands with it.
 *
 * The point is the classpath. `DebugAdapterProtocol` is in `monkeybrains.jar` as well, and started
 * from there it dies on a missing gson before it can answer anything — so "the adapter is in the
 * SDK" is only true of one jar, and this is the test that keeps that true.
 */
class DebugAdapterLiveTest {

    @Test
    fun `the adapter in the SDK answers initialize`() {
        val sdk = LiveSdk.require()

        val process = ProcessBuilder(SdkServerCommands.debugAdapter(sdk, JavaLocator.resolve(null)))
            .redirectErrorStream(false)
            .start()

        try {
            val launcher = DSPLauncher.createClientLauncher(
                object : IDebugProtocolClient {},
                process.inputStream,
                process.outputStream,
            )
            launcher.startListening()

            val capabilities: Capabilities = launcher.remoteProxy
                .initialize(
                    InitializeRequestArguments().apply {
                        clientID = "idea-monkeyc"
                        adapterID = "monkeyc"
                        linesStartAt1 = true
                        columnsStartAt1 = true
                        pathFormat = "path"
                    },
                )
                .get(60, TimeUnit.SECONDS)

            assertTrue(
                capabilities.supportsConfigurationDoneRequest == true,
                "the adapter must take breakpoints before the app starts",
            )
            assertTrue(
                capabilities.supportsEvaluateForHovers == true,
                "hovering a variable while stopped is most of what a debugger is for",
            )
        } finally {
            process.destroy()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }
}
