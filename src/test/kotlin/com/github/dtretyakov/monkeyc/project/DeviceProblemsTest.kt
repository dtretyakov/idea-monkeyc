package com.github.dtretyakov.monkeyc.project

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Saying which of the three device problems it is, before the compiler says none of them.
 *
 * A device the manifest declares that was never downloaded, a device that is downloaded but not
 * declared, and a device that cannot run this kind of app all reach the compiler as one message
 * that names the device and not the reason. Two of the three are the plugin's to see coming.
 */
class DeviceProblemsTest {

    private val fenix7 = ConnectIqDevice(
        id = "fenix7",
        displayName = "fenix 7",
        group = null,
        family = null,
        isTouch = false,
        sdkVersion = null,
        memoryLimits = mapOf("watchApp" to 786_432L),
    )

    @Test
    fun `a downloaded device the manifest declares is no problem at all`() {
        assertNull(DeviceProblems.of("fenix7", listOf("fenix7"), fenix7, "watch-app"))
    }

    @Test
    fun `no device chosen yet is not a problem to report here`() {
        assertNull(DeviceProblems.of("", listOf("fenix7"), null, "watch-app"))
    }

    @Test
    fun `a declared device that was never downloaded says so, and where to get it`() {
        val problem = DeviceProblems.of("epix2pro47mm", listOf("epix2pro47mm"), null, "watch-app")!!

        assertTrue(problem.contains("epix2pro47mm"), problem)
        assertTrue(problem.contains("not been downloaded"), problem)
        assertTrue(problem.contains("SDK Manager"), problem)
    }

    @Test
    fun `a device the manifest does not declare is a different problem with a different remedy`() {
        val problem = DeviceProblems.of("fr965", listOf("fenix7"), fenix7, "watch-app")!!

        assertTrue(problem.contains("not one of the products"), problem)
    }

    @Test
    fun `a device that cannot run this kind of app is named by its display name`() {
        val problem = DeviceProblems.of("fenix7", listOf("fenix7"), fenix7, "datafield")!!

        assertTrue(problem.contains("fenix 7"), problem)
        assertTrue(problem.contains("datafield"), problem)
    }

    @Test
    fun `a barrel declares nothing and is held to nothing`() {
        // No products in the manifest means no list to be absent from.
        assertNull(DeviceProblems.of("fenix7", declared = emptyList(), installed = fenix7, appType = null))
    }

    @Test
    fun `a manifest with no products says that, rather than blaming the downloads`() {
        val reason = DeviceProblems.noneAvailable(declared = emptyList(), undownloaded = emptyList())

        assertTrue(reason.contains("declares no products"), reason)
    }

    @Test
    fun `when nothing is downloaded the devices are named`() {
        val declared = listOf("fenix7", "fr965")

        val reason = DeviceProblems.noneAvailable(declared, undownloaded = declared)

        assertTrue(reason.contains("fenix7") && reason.contains("fr965"), reason)
    }

    @Test
    fun `a long list of missing devices is trimmed rather than dumped`() {
        val declared = (1..9).map { "device$it" }

        val reason = DeviceProblems.noneAvailable(declared, undownloaded = declared)

        assertTrue(reason.contains("device5"), reason)
        assertTrue(reason.contains("and 4 more"), reason)
    }

    @Test
    fun `everything downloaded but nothing buildable blames the app type`() {
        val reason = DeviceProblems.noneAvailable(declared = listOf("fenix7"), undownloaded = emptyList())

        assertTrue(reason.contains("can run this kind of app"), reason)
    }
}
