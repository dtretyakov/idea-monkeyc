package com.github.dtretyakov.monkeyc.sdk

import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.exists

/** A kind of Connect IQ project, as `projectInfo.xml` names it. */
data class AppType(val id: String, val name: String, val description: String) {
    val isBarrel: Boolean get() = id == BARREL

    companion object {
        const val BARREL = "barrel"
    }
}

/** One file a template contributes, and how it must be treated when copied. */
data class TemplateFile(val path: String, val kind: Kind) {
    enum class Kind { SOURCE, MANIFEST, RESOURCE }

    val name: String get() = path.substringAfterLast('/')
}

/** A starting point for a new project: a directory in the SDK plus the list of files to take. */
data class ProjectTemplate(
    val appType: String,
    val name: String,
    val description: String,
    /** Relative to `bin/`, e.g. `templates/watchface/simple`. */
    val baseDir: String,
    val files: List<TemplateFile>,
)

/**
 * A `${name}` in a template file, and what it is replaced with.
 *
 * `appName` keeps the project's name as the user typed it; everything else is a class name, so it
 * takes the sanitised form plus a suffix — `${viewClassName}` in a project called "My Face" becomes
 * `My_FaceView`.
 */
data class TemplatePlaceholder(val key: String, val suffix: String, val sanitized: Boolean)

/**
 * What the SDK says about projects: their kinds, the templates for each, and the API levels a
 * project may target.
 *
 * All of it is read from `bin/projectInfo.xml` and `bin/compilerInfo.xml` rather than hard-coded,
 * because Garmin adds app types and API levels with the SDK and a list baked into the plugin would
 * be wrong by the next release.
 */
class ProjectInfo private constructor(
    val appTypes: List<AppType>,
    val templates: List<ProjectTemplate>,
    val placeholders: List<TemplatePlaceholder>,
    /** API levels a project may declare, oldest first. */
    val apiLevels: List<SdkVersion>,
    private val barrelCapableLevels: Set<String>,
) {
    fun templatesFor(appType: String): List<ProjectTemplate> = templates.filter { it.appType == appType }

    /** Barrels only run on API levels that support them, and most early ones do not. */
    fun apiLevelsFor(appType: AppType): List<SdkVersion> =
        if (appType.isBarrel) apiLevels.filter { it.raw in barrelCapableLevels } else apiLevels

    companion object {

        fun read(sdk: ConnectIqSdk): ProjectInfo? {
            val projectInfo = parse(sdk.root.resolve("bin/projectInfo.xml")) ?: return null
            val compilerInfo = parse(sdk.root.resolve("bin/compilerInfo.xml"))

            // `compilerInfo.xml` also holds a bare <version> for the compiler itself; the ones
            // that matter here are the API levels under <targetSdkVersions>.
            val versions = compilerInfo?.elements("targetSdkVersions")
                ?.flatMap { it.childElements("version") }
                .orEmpty()

            return ProjectInfo(
                appTypes = projectInfo.elements("appType").map {
                    AppType(it.getAttribute("id"), it.getAttribute("name"), it.getAttribute("description"))
                },
                templates = projectInfo.elements("newProjectFileMap").map { map ->
                    ProjectTemplate(
                        appType = map.getAttribute("appType"),
                        name = map.getAttribute("name"),
                        description = map.getAttribute("description"),
                        baseDir = map.getAttribute("baseDir"),
                        files = map.childElements("file").map { file ->
                            TemplateFile(
                                path = file.textContent.trim(),
                                kind = when (file.getAttribute("type")) {
                                    "source" -> TemplateFile.Kind.SOURCE
                                    "manifest" -> TemplateFile.Kind.MANIFEST
                                    else -> TemplateFile.Kind.RESOURCE
                                },
                            )
                        },
                    )
                },
                placeholders = projectInfo.elements("placeHolder").map {
                    TemplatePlaceholder(
                        key = it.textContent.trim(),
                        suffix = it.getAttribute("suffix"),
                        sanitized = it.getAttribute("sanitized") != "false",
                    )
                },
                apiLevels = versions.mapNotNull { SdkVersion.parse(it.textContent) },
                barrelCapableLevels = versions
                    .filter { it.getAttribute("supportsBarrels") == "true" }
                    .map { it.textContent.trim() }
                    .toSet(),
            )
        }

        private fun parse(path: Path): Element? {
            if (!path.exists()) return null
            return runCatching {
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                path.toFile().inputStream().use { factory.newDocumentBuilder().parse(it) }.documentElement
            }.getOrNull()
        }

        private fun Element.elements(tag: String): List<Element> = getElementsByTagName(tag).toList()

        private fun Element.childElements(tag: String): List<Element> =
            getElementsByTagName(tag).toList().filter { it.parentNode === this }

        private fun NodeList.toList(): List<Element> =
            (0 until length).mapNotNull { item(it) as? Element }
    }
}
