package com.github.dtretyakov.monkeyc.build

import com.github.dtretyakov.monkeyc.project.ManifestFile
import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import com.github.dtretyakov.monkeyc.sdk.SdkVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What an export is about to leave out.
 *
 * Every finding here is something the compiler either says badly or does not say at all. A device
 * dropped for being below the minimum API level is silent; the store's rejection over part numbers
 * arrives days later; and the language narrowing is invisible everywhere, though the data has been
 * in a file this plugin already opens all along.
 */
class ExportPreflightTest {

    private fun device(
        id: String,
        sdkVersion: String? = "5.0.0",
        languages: List<Set<String>> = listOf(setOf("eng", "fre", "ukr")),
    ) = ConnectIqDevice(
        id = id,
        displayName = id,
        group = null,
        family = null,
        isTouch = false,
        sdkVersion = sdkVersion?.let { SdkVersion.parse(it) },
        memoryLimits = mapOf("watchApp" to 65_536L),
        languagesByPartNumber = languages,
    )

    private fun manifest(
        devices: List<String> = listOf("venu2"),
        languages: List<String> = listOf("eng"),
        minSdkVersion: String? = null,
        appType: String? = "watch-app",
        trialMode: Boolean = false,
        unlockUrl: String? = null,
    ) = ManifestFile(
        appType = appType,
        entry = "App",
        applicationId = "id",
        displayName = "App",
        module = null,
        launcherIcon = null,
        devices = devices,
        permissions = emptyList(),
        languages = languages,
        minSdkVersion = minSdkVersion?.let { SdkVersion.parse(it) },
        barrelVersion = null,
        trialMode = trialMode,
        unlockUrl = unlockUrl,
    )

    @Test
    fun `an export with nothing to say says nothing`() {
        val findings = ExportPreflight.check(manifest(), listOf(device("venu2")))

        assertEquals(emptyList<ExportPreflight.Finding>(), findings)
    }

    @Test
    fun `a declared device that is not downloaded is an error, and is named`() {
        val findings = ExportPreflight.check(
            manifest(devices = listOf("venu2", "epix2pro47mm")),
            listOf(device("venu2")),
        )

        val finding = findings.single()
        assertEquals(ExportPreflight.Severity.ERROR, finding.severity)
        assertTrue(finding.text.contains("epix2pro47mm"), finding.text)
    }

    @Test
    fun `a device below the minimum API level is dropped silently by the compiler, so we say it`() {
        val findings = ExportPreflight.check(
            manifest(devices = listOf("venu2", "fenix5"), minSdkVersion = "5.0.0"),
            listOf(device("venu2", sdkVersion = "5.2.0"), device("fenix5", sdkVersion = "3.2.0")),
        )

        val finding = findings.single { it.text.contains("API level") }
        assertTrue(finding.text.contains("fenix5"), finding.text)
        assertTrue(finding.text.contains("left out of the package"), finding.text)
    }

    @Test
    fun `a language no declared device supports is named with both counts`() {
        // The decision is a trade: keeping the language costs watches, dropping it costs users.
        // Neither number is shown anywhere else, so both are shown here.
        val findings = ExportPreflight.check(
            manifest(devices = listOf("venu2", "fenix5"), languages = listOf("eng", "ukr")),
            listOf(
                device("venu2", languages = listOf(setOf("eng"))),
                device("fenix5", languages = listOf(setOf("eng"))),
            ),
        )

        val finding = findings.single()
        assertTrue(finding.text.startsWith("ukr"), finding.text)
        assertTrue(finding.text.contains("2 of 2"), finding.text)
    }

    @Test
    fun `a language only some hardware variants support gets its own sentence`() {
        // A product id can map to several SKUs — world-wide and APAC — and they do not carry the
        // same fonts. Folding this into "the device does not support it" would be wrong: it does,
        // on some of the hardware.
        val findings = ExportPreflight.check(
            manifest(languages = listOf("ara")),
            listOf(device("venu2", languages = listOf(setOf("eng", "ara"), setOf("eng")))),
        )

        val finding = findings.single()
        assertTrue(finding.text.contains("some hardware variants"), finding.text)
        assertTrue(finding.text.contains("venu2"), finding.text)
    }

    @Test
    fun `a language every variant supports is not mentioned`() {
        val findings = ExportPreflight.check(
            manifest(languages = listOf("eng")),
            listOf(device("venu2", languages = listOf(setOf("eng"), setOf("eng")))),
        )

        assertEquals(emptyList<ExportPreflight.Finding>(), findings)
    }

    @Test
    fun `a trial on a watch face is refused, because the store refuses it`() {
        val findings = ExportPreflight.check(
            manifest(appType = "watchface", trialMode = true),
            listOf(device("venu2")),
        )

        val finding = findings.single { it.text.contains("watch face") }
        assertEquals(ExportPreflight.Severity.ERROR, finding.severity)
    }

    @Test
    fun `an unlock URL that is not HTTPS is refused before the upload refuses it`() {
        val findings = ExportPreflight.check(
            manifest(trialMode = true, unlockUrl = "http://unlock.example.com"),
            listOf(device("venu2")),
        )

        val finding = findings.single()
        assertEquals(ExportPreflight.Severity.ERROR, finding.severity)
        assertTrue(finding.text.contains("HTTPS"), finding.text)
    }

    @Test
    fun `a trial that follows the rules is not mentioned`() {
        val findings = ExportPreflight.check(
            manifest(trialMode = true, unlockUrl = "https://unlock.example.com"),
            listOf(device("venu2")),
        )

        assertEquals(emptyList<ExportPreflight.Finding>(), findings)
    }

    @Test
    fun `a long list of devices is trimmed rather than dumped into a sentence`() {
        val many = (1..9).map { "device$it" }

        val findings = ExportPreflight.check(manifest(devices = many), emptyList())

        val finding = findings.single()
        assertTrue(finding.text.contains("device5"), finding.text)
        assertTrue(finding.text.contains("and 4 more"), finding.text)
    }
}
