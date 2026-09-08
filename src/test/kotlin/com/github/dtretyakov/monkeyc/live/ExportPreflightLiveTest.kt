package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.build.ExportPreflight
import com.github.dtretyakov.monkeyc.sdk.DeviceCatalog
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The language data, against the devices the SDK Manager actually downloaded.
 *
 * The rule is only worth having if Garmin really does ship per-part-number language sets, and if
 * they really do differ. A fixture cannot answer either question — it would only prove the fixture
 * was written to match the rule. This asks the SDK.
 */
class ExportPreflightLiveTest {

    @Test
    fun `every downloaded device declares the languages its part numbers support`() {
        val sdk = LiveSdk.require()
        val devices = DeviceCatalog(sdk.devicesRoot).devices()
        assumeTrue(devices.isNotEmpty(), "no devices are downloaded")

        val silent = devices.filter { it.languagesByPartNumber.isEmpty() || it.languages.isEmpty() }
        assertTrue(
            silent.isEmpty(),
            "no language data for ${silent.map { it.id }.take(10)} — if Garmin stopped shipping it, " +
                "the export pre-flight would quietly stop reporting language gaps",
        )
    }

    @Test
    fun `language support really does differ between devices`() {
        val sdk = LiveSdk.require()
        val devices = DeviceCatalog(sdk.devicesRoot).devices()
        assumeTrue(devices.size > 20, "too few devices downloaded to say")

        // If every device supported every language this check would be dead weight. It does not:
        // English is universal, and the long tail is not.
        val everywhere = devices.count { "eng" in it.languages }
        assertTrue(everywhere == devices.size, "English is on every device, or the data changed shape")

        val counts = devices.flatMap { it.languages }.toSet().associateWith { language ->
            devices.count { language in it.languages }
        }
        val partial = counts.filterValues { it < devices.size }
        assertTrue(
            partial.isNotEmpty(),
            "no language is missing from any device, which would make this check pointless",
        )
    }

    @Test
    fun `a project declaring a thinly supported language is told which devices lack it`() {
        val sdk = LiveSdk.require()
        val devices = DeviceCatalog(sdk.devicesRoot).devices()
        assumeTrue(devices.size > 20, "too few devices downloaded to say")

        // The least widely supported language the SDK knows about, whatever it happens to be.
        val thin = devices.flatMap { it.languages }.toSet()
            .minByOrNull { language -> devices.count { language in it.languages } }!!
        val lacking = devices.filterNot { thin in it.languages }
        assumeTrue(lacking.isNotEmpty(), "every device supports every language on this machine")

        val findings = ExportPreflight.check(
            manifestDeclaring(devices.map { it.id }, listOf("eng", thin)),
            devices,
        )

        val finding = findings.singleOrNull { it.text.startsWith(thin) }
        assertTrue(finding != null, "no finding for $thin, which ${lacking.size} devices lack")
        assertTrue(finding!!.text.contains("${lacking.size} of ${devices.size}"), finding.text)
    }

    private fun manifestDeclaring(devices: List<String>, languages: List<String>) =
        com.github.dtretyakov.monkeyc.project.ManifestFile(
            appType = "watch-app",
            entry = "App",
            applicationId = "id",
            displayName = "App",
            module = null,
            launcherIcon = null,
            devices = devices,
            permissions = emptyList(),
            languages = languages,
            minSdkVersion = null,
            barrelVersion = null,
        )
}
