package com.github.dtretyakov.monkeyc.live

import com.github.dtretyakov.monkeyc.run.MtpInstaller
import com.github.dtretyakov.monkeyc.run.MtpLocator
import com.github.dtretyakov.monkeyc.run.MtpRelease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

/**
 * The real download, for this machine's platform: the pinned URL answers, the bytes match the
 * pinned checksum, the archive holds the tool, and the tool says it is the pinned version.
 *
 * Opt-in like every live test, and for the same reason: it reports the network and GitHub as much
 * as the code. Installs into a temporary directory, never into the plugin's real one.
 */
class MtpInstallerLiveTest {

    @Test
    fun `the pinned release installs and answers its version`(@TempDir temp: Path) {
        assumeTrue(LiveSdk.enabled, "run with -PliveTests to download the real mtp-rs")
        val asset = MtpRelease.forPlatform()
        assumeTrue(asset != null, "no mtp-rs build for this platform")
        val windows = System.getProperty("os.name").startsWith("Windows")

        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val tool = MtpInstaller.install(asset!!, temp.resolve("installed"), windows) { url, to ->
            val response = client.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofFile(to))
            check(response.statusCode() == 200) { "$url answered ${response.statusCode()}" }
        }

        assertEquals(MtpRelease.VERSION, MtpLocator.readVersion(tool))
    }
}
