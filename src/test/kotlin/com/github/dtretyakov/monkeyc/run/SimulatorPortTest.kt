package com.github.dtretyakov.monkeyc.run

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory

/**
 * Telling the simulator apart from whatever else has taken its port.
 *
 * 1234 is a popular port, and the tools cannot tell. On the forums that cost one developer a
 * fourteen-reply thread before finding Hyper-V had reserved the range; what they saw until then
 * was an app that would not launch, which is a question about the app.
 */
class SimulatorPortTest {

    /**
     * An SDK nothing was ever started from, under a data root holding no SDKs at all — so no
     * running process can be traced to it, whatever is on this machine.
     */
    private val unknownSdk = createTempDirectory("monkeyc-no-sdks").let { ConnectIqSdk.at(it.resolve("sdk"), it) }

    @Test
    fun `a stranger on the port is reported as a stranger`() {
        val port = SIMULATOR_PORTS.firstOrNull { isFree(it) }
            ?: return // The real simulator holds them all; nothing to prove here.

        ServerSocket(port).use {
            assertTrue(Simulator.isReady(), "the port answers")
            assertTrue(
                Simulator.strangerHoldsPort(unknownSdk),
                "no simulator could have come from ${unknownSdk.root}, so whatever answers is a stranger",
            )
        }
    }

    @Test
    fun `a silent port range is not a stranger`() {
        if (Simulator.isReady()) return // Something real is listening; this machine cannot say.

        assertFalse(Simulator.strangerHoldsPort(unknownSdk), "nothing is listening, so nobody holds it")
    }

    private fun isFree(port: Int): Boolean =
        runCatching { ServerSocket(port).close() }.isSuccess

    private companion object {
        val SIMULATOR_PORTS = 1234..1238
    }
}
