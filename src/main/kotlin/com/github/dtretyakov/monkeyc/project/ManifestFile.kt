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
    val applicationId: String?,
    val displayName: String?,
    val launcherIcon: String?,
    val devices: List<String>,
    val permissions: List<String>,
    val languages: List<String>,
    val minSdkVersion: SdkVersion?,
    val barrelVersion: String?,
) {
    val isBarrel: Boolean get() = appType == null && barrelVersion != null

    companion object {
        const val FILE_NAME = "manifest.xml"

        fun parse(path: Path): ManifestFile? {
            if (!path.exists()) return null
            return runCatching { path.toFile().readText() }.getOrNull()?.let { parseText(it) }
        }

        /** Parses a manifest that has not been saved yet — what the form editor reads. */
        fun parseText(xml: String): ManifestFile? {
            return runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    // A manifest is a local project file, but it costs nothing to refuse
                    // external entities, and it keeps a malformed one from reaching the network.
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    isNamespaceAware = true
                }
                val document = xml.byteInputStream().use { factory.newDocumentBuilder().parse(it) }

                val application = document.getElementsByTagNameNS("*", "application").item(0)
                val barrel = document.getElementsByTagNameNS("*", "barrel").item(0)
                val holder = application ?: barrel

                fun attribute(name: String) = holder?.attributes?.getNamedItem(name)?.nodeValue

                fun ids(tag: String, attribute: String?) =
                    document.getElementsByTagNameNS("*", tag).let { nodes ->
                        (0 until nodes.length).mapNotNull { index ->
                            val node = nodes.item(index)
                            if (attribute == null) {
                                node.textContent?.trim()?.takeIf { it.isNotEmpty() }
                            } else {
                                node.attributes?.getNamedItem(attribute)?.nodeValue
                            }
                        }
                    }

                ManifestFile(
                    appType = attribute("type")?.takeIf { application != null },
                    entry = attribute("entry"),
                    applicationId = attribute("id"),
                    displayName = attribute("name"),
                    launcherIcon = attribute("launcherIcon"),
                    devices = ids("product", "id"),
                    permissions = ids("uses-permission", "id"),
                    languages = ids("language", null),
                    // The attribute was renamed between manifest versions and both are in the
                    // wild — the templates write minApiLevel, the samples minSdkVersion.
                    minSdkVersion = SdkVersion.parse(attribute("minSdkVersion") ?: attribute("minApiLevel")),
                    barrelVersion = barrel?.attributes?.getNamedItem("version")?.nodeValue,
                )
            }.getOrNull()
        }
    }
}
