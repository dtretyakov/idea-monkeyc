package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.build.BuildKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The barrel branches of the build, run for real.
 *
 * They are a separate compiler entry point with rules of their own, and the rules are not the ones
 * a reading of the app path would suggest: `barrelbuild` signs nothing and has no device, and
 * `barreltest` refuses the `_sim` device suffix that `monkeyc` insists on. Both of those were
 * found by running it, and neither would be caught by a test on the argument list alone.
 */
class BarrelLiveTest {

    @Test
    fun `a barrel builds without a device and without a key`(@TempDir temp: Path) {
        val sdk = LiveSdk.require()
        val project = LiveSdk.fixture(temp, "fixture-barrel")

        val built = LiveBuild.run(sdk, project, device = "", kind = BuildKind.BARREL)

        assertEquals(0, built.exitCode, built.text)
        assertTrue(built.prg.exists(), "expected ${built.prg}")
    }

    @Test
    fun `a barrel's tests build into a runnable prg`(@TempDir temp: Path) {
        val sdk = LiveSdk.require()
        val project = LiveSdk.fixture(temp, "fixture-barrel")
        val device = LiveSdk.device(sdk, listOf("fenix7", "fenix6"))

        val built = LiveBuild.run(sdk, project, device, kind = BuildKind.BARREL_TESTS)

        assertEquals(
            0,
            built.exitCode,
            "barreltest rejects the _sim suffix that monkeyc requires:\n${built.text}",
        )
        assertTrue(built.prg.exists(), "expected ${built.prg}")
    }
}
