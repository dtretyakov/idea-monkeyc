package com.github.dtretyakov.monkeyc.project

import com.github.dtretyakov.monkeyc.sdk.SdkVersion
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists

/**
 * The `manifest.xml` of a Connect IQ project, read for the few things the IDE needs from it: what
 * kind of app this is, which devices it declares, and the oldest SDK it claims to run on.
 *
 * Parsed with the JDK's own XML rather than the IDE's PSI so it can be unit tested and used from a
 * background thread without a read action.
 */
data class ManifestFile(
    val appType: String?,
    val entry: String?,
    val devices: List<String>,
    val minSdkVersion: SdkVersion?,
    val barrelVersion: String?,
) {
    val isBarrel: Boolean get() = appType == null && barrelVersion != null

    companion object {
        const val FILE_NAME = "manifest.xml"

        fun parse(path: Path): ManifestFile? {
            if (!path.exists()) return null
            return runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    // A manifest is a local project file, but it costs nothing to refuse
                    // external entities, and it keeps a malformed one from reaching the network.
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    isNamespaceAware = true
                }
                val document = path.toFile().inputStream().use { factory.newDocumentBuilder().parse(it) }

                val application = document.getElementsByTagNameNS("*", "application").item(0)
                val barrel = document.getElementsByTagNameNS("*", "barrel").item(0)
                val holder = application ?: barrel

                val products = document.getElementsByTagNameNS("*", "product")
                val devices = (0 until products.length).mapNotNull { index ->
                    products.item(index).attributes?.getNamedItem("id")?.nodeValue
                }

                ManifestFile(
                    appType = holder?.attributes?.getNamedItem("type")?.nodeValue?.takeIf { application != null },
                    entry = holder?.attributes?.getNamedItem("entry")?.nodeValue,
                    devices = devices,
                    minSdkVersion = SdkVersion.parse(
                        holder?.attributes?.getNamedItem("minSdkVersion")?.nodeValue,
                    ),
                    barrelVersion = barrel?.attributes?.getNamedItem("version")?.nodeValue,
                )
            }.getOrNull()
        }
    }
}
