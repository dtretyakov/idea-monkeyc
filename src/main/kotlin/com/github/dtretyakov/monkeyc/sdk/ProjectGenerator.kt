package com.github.dtretyakov.monkeyc.sdk

import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** What the wizard collected, and everything the generator needs. */
data class NewProject(
    val name: String,
    val directory: Path,
    val appType: AppType,
    val template: ProjectTemplate,
    val minApiLevel: SdkVersion,
    val devices: List<String>,
)

/**
 * Creates a project from one of the SDK's templates.
 *
 * The rules — which files get their names rewritten, what a placeholder expands to, which manifest
 * attributes are filled in — are Garmin's, and are followed exactly rather than improved on. A
 * project generated here has to be one the SDK's own tools, and anyone opening it in VS Code,
 * recognise.
 */
object ProjectGenerator {

    fun generate(sdk: ConnectIqSdk, info: ProjectInfo, project: NewProject): Path {
        val fileName = project.name.replace(" ", "")
        val className = classNameOf(fileName)
        val templateRoot = sdk.root.resolve("bin").resolve(project.template.baseDir)

        project.directory.createDirectories()

        project.template.files.forEach { file ->
            val source = templateRoot.resolve(file.path)
            if (!source.exists()) return@forEach

            val destination = destinationOf(project.directory, file, fileName, project.appType)
            destination.parent?.createDirectories()

            if (isText(file)) {
                var text = substitute(source.readText(), info.placeholders, project.name, className)
                if (file.kind == TemplateFile.Kind.MANIFEST) {
                    text = fillManifest(text, project, className)
                }
                destination.writeText(text)
            } else {
                source.copyTo(destination, overwrite = true)
            }
        }

        return project.directory
    }

    /**
     * A class name the compiler will accept: anything that is not a word character becomes `_`, and
     * a leading digit gets one in front of it.
     */
    fun classNameOf(fileName: String): String {
        val sanitized = fileName.replace(Regex("[^\\p{L}\\p{N}_]"), "_")
        return if (sanitized.firstOrNull()?.isDigit() == true) "_$sanitized" else sanitized
    }

    /**
     * Source files are renamed after the project — a project called "Sunrise" gets `SunriseApp.mc`
     * and `SunriseView.mc` — because a whole SDK's worth of projects otherwise all have a file
     * called `App.mc`. A barrel has one source file, and it takes the project's name outright.
     */
    private fun destinationOf(root: Path, file: TemplateFile, fileName: String, appType: AppType): Path {
        val relative = root.resolve(file.path)
        if (file.kind != TemplateFile.Kind.SOURCE) return relative

        val renamed = if (appType.isBarrel) {
            fileName + "." + file.name.substringAfterLast('.')
        } else {
            fileName + file.name
        }
        return relative.resolveSibling(renamed)
    }

    /** Only source, the manifest and `strings.xml` carry placeholders; the rest is copied as it is. */
    private fun isText(file: TemplateFile): Boolean = when (file.kind) {
        TemplateFile.Kind.SOURCE, TemplateFile.Kind.MANIFEST -> true
        TemplateFile.Kind.RESOURCE -> file.name == "strings.xml"
    }

    private fun substitute(
        text: String,
        placeholders: List<TemplatePlaceholder>,
        projectName: String,
        className: String,
    ): String = placeholders.fold(text) { current, placeholder ->
        val value = (if (placeholder.sanitized) className else projectName) + placeholder.suffix
        current.replace("\${${placeholder.key}}", value)
    }

    /**
     * The template manifest ships with its attributes empty for the editor to fill in. These are
     * the values Garmin's own wizard writes, including the app id, which is a fresh UUID — two apps
     * sharing one would be the same app to the store.
     */
    private fun fillManifest(text: String, project: NewProject, className: String): String {
        val level = project.minApiLevel.toString()
        val filled = text
            .replace("id=\"\"", "id=\"${UUID.randomUUID()}\"")
            .replace("type=\"\"", "type=\"${project.appType.id}\"")
            .replace("name=\"\"", "name=\"@Strings.AppName\"")
            .replace("entry=\"\"", "entry=\"${className}App\"")
            .replace("launcherIcon=\"\"", "launcherIcon=\"@Drawables.LauncherIcon\"")
            .replace("minSdkVersion=\"\"", "minSdkVersion=\"$level\"")
            .replace("minApiLevel=\"\"", "minApiLevel=\"$level\"")
            .replace("module=\"\"", "module=\"$className\"")
            .replace("version=\"\"", "version=\"0.0.0\"")

        if (project.devices.isEmpty()) return filled

        val products = project.devices.sorted().joinToString("\n") { "            <iq:product id=\"$it\"/>" }
        // The template leaves the element empty, but not with any particular whitespace in it.
        return filled.replace(
            Regex("<iq:products>\\s*</iq:products>"),
            "<iq:products>\n$products\n        </iq:products>",
        )
    }
}
