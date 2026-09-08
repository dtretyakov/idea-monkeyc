package com.github.dtretyakov.monkeyc.project

import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The file conventions of a Connect IQ project, kept in one place because the compiler, the
 * language server and the debugger all have to agree on them — a `.prg` built under one name and
 * looked for under another simply fails to launch.
 */
object ProjectLayout {

    const val DEFAULT_JUNGLE = "monkey.jungle"

    /** A jungle the SDK picks up on its own when barrels are configured. */
    const val BARRELS_JUNGLE = "barrels.jungle"

    /** Where the compiler writes by default, and where the run configurations look. */
    const val OUTPUT_DIRECTORY = "bin"

    /**
     * Where the SDK's templates put `.mc` files, and the compiler's own default.
     *
     * A jungle can say otherwise with `sourcePath`, and a project that does is left alone rather
     * than guessed at — resolving a jungle properly means resolving its variables and its
     * per-device overrides, which is the compiler's job and not worth reimplementing to colour a
     * folder.
     */
    const val SOURCE_DIRECTORY = "source"

    /** Where the SDK's templates put strings, drawables and layouts. */
    const val RESOURCE_DIRECTORY = "resources"

    /**
     * Is this directory a Connect IQ project?
     *
     * A manifest alone is enough: a project can be built from a jungle with any name, but it cannot
     * be built without a manifest.
     */
    fun isProjectRoot(directory: Path): Boolean = directory.resolve(ManifestFile.FILE_NAME).exists()

    /**
     * The jungle files to hand the compiler and the language server, as absolute paths.
     *
     * Absolute matters: given a relative path the language server answers
     * `Jungle file 'monkey.jungle' ... does not exist` and then indexes nothing, with no other sign
     * that anything is wrong.
     */
    fun jungleFiles(root: Path, configured: String?): List<Path> {
        val names = configured?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(DEFAULT_JUNGLE)

        val files = names.map { name ->
            val path = Path.of(name)
            if (path.isAbsolute) path else root.resolve(name)
        }.toMutableList()

        val barrels = root.resolve(BARRELS_JUNGLE)
        if (barrels.exists() && files.none { it == barrels }) {
            files.add(barrels)
        }
        return files
    }

    /**
     * The base name of the built artifact.
     *
     * Garmin strips everything but letters, digits and underscores from the project directory's
     * name; a project in `~/My Watch Face!` builds `MyWatchFace.prg`.
     */
    fun artifactName(projectName: String): String = projectName.replace(Regex("[^a-zA-Z0-9_]+"), "")

    fun appPrg(root: Path, projectName: String): Path =
        root.resolve(OUTPUT_DIRECTORY).resolve("${artifactName(projectName)}.prg")

    /**
     * A build for the watch itself, which gets its own name.
     *
     * A device build and a simulator build of the same project are different binaries that will
     * not run in each other's place, so they must not share `bin/Name.prg` — and the device in the
     * name is what tells you which watch the file on your desk belongs to.
     */
    fun devicePrg(root: Path, projectName: String, device: String): Path =
        root.resolve(OUTPUT_DIRECTORY).resolve("${artifactName(projectName)}-$device.prg")

    /** Test builds get their own name so a test `.prg` never overwrites the app's. */
    fun testPrg(root: Path, projectName: String, device: String): Path =
        root.resolve(OUTPUT_DIRECTORY).resolve("test_${device}_${artifactName(projectName)}.prg")

    /** The symbol file `monkeyc -g` writes next to the `.prg`; the debugger cannot work without it. */
    fun debugXml(prg: Path): Path = prg.resolveSibling("${prg.fileName}.debug.xml")

    /** App settings the simulator should preload, if the build produced any. */
    fun settingsJson(prg: Path): Path? =
        prg.resolveSibling(prg.fileName.toString().removeSuffix(".prg") + "-settings.json")
            .takeIf { it.exists() }
}
