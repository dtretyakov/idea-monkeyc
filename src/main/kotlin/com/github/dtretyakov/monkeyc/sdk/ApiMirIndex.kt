package com.github.dtretyakov.monkeyc.sdk

import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Every symbol in the Toybox API, and where in `bin/api.mir` it is declared.
 *
 * There is no Monkey C source for Toybox in the SDK, and `api.mir` is the closest thing to it:
 * Garmin's own intermediate representation, one file, in which every module, class, function,
 * constant and type appears with its documentation comment and its full signature, and only the
 * bodies are empty. Garmin's language server reads it, and lands Type and Call Hierarchy in it —
 * but not go-to-definition, which has no api.mir branch at all. That is why F12 on `WatchUi.Menu2`
 * does nothing, here and in VS Code alike, and why the IDE has to build this itself.
 *
 * The format is machine-generated and strictly regular: four spaces per level, one declaration per
 * line, no continuations. So scope is taken from indentation rather than from counting braces,
 * which would have to contend with dictionary types, annotation blocks and `{}` bodies sharing a
 * line with the declaration they close.
 */
class ApiMirIndex private constructor(
    private val byQualifiedName: Map<String, Declaration>,
    private val bySimpleName: Map<String, List<Declaration>>,
    private val byContainer: Map<String, List<Declaration>>,
) {

    enum class Kind { MODULE, CLASS, FUNCTION, VARIABLE, CONSTANT, TYPE }

    data class Declaration(
        val qualifiedName: String,
        val kind: Kind,
        /** Character offset of the declared name itself, so navigation puts the caret on it. */
        val offset: Int,
        val line: Int,
        /**
         * Where this declaration's body ends, for the things that need a range rather than a
         * point: folding, and highlighting the region a structure-view row stands for. A
         * declaration that holds nothing ends at its own line.
         */
        val endOffset: Int = offset,
    ) {
        val simpleName: String get() = qualifiedName.substringAfterLast('.')

        /** The scope this was declared in: `Toybox.Graphics` for `Toybox.Graphics.Dc`. */
        val container: String get() = qualifiedName.substringBeforeLast('.', "")
    }

    val size: Int get() = byQualifiedName.size

    fun all(): Collection<Declaration> = byQualifiedName.values

    fun exact(qualifiedName: String): Declaration? = byQualifiedName[qualifiedName]

    /**
     * What the user could mean by a dotted chain under the caret.
     *
     * Monkey C code almost never writes the full path: it is `WatchUi.Menu2` after
     * `import Toybox.WatchUi`, or a bare `Menu2` inside the module itself. So a chain matches any
     * declaration whose qualified name ends with it on a segment boundary, and when more than one
     * does, all of them are returned and the platform shows the user the choice.
     */
    fun resolve(chain: String): List<Declaration> {
        val trimmed = chain.trim('.', '$', ' ')
        if (trimmed.isEmpty()) return emptyList()

        exact(trimmed)?.let { return listOf(it) }
        exact("$ROOT.$trimmed")?.let { return listOf(it) }

        val suffix = ".$trimmed"
        return bySimpleName[trimmed.substringAfterLast('.')]
            .orEmpty()
            .filter { it.qualifiedName.endsWith(suffix) }
    }

    /**
     * Everything declared directly inside a scope, in the order the file declares it.
     *
     * Grouped up front rather than filtered on demand: the structure view asks this once per node
     * it expands, and a scan of all three and a half thousand declarations each time adds up.
     */
    fun membersOf(qualifiedName: String): List<Declaration> = byContainer[qualifiedName].orEmpty()

    companion object {

        private const val ROOT = "Toybox"
        private const val INDENT = 4

        /**
         * The level an `enum` occupies in the file but not in the language.
         *
         * `Graphics.COLOR_WHITE` is declared inside `enum ColorValue`, and there is no
         * `Graphics.ColorValue.COLOR_WHITE` to write — enum members are hoisted into the scope
         * around the enum. Keeping a placeholder on the stack keeps indentation and scope depth in
         * step while leaving the enum's name out of every qualified name built from it.
         */
        private const val ENUM_LEVEL = "<enum>"

        private val DECLARATION = Regex(
            "^(\\s*)(?:(?:public|hidden|protected|private|static)\\s+)*" +
                "(module|class|function|var|const|enum|type)\\s+([A-Za-z_][A-Za-z0-9_]*)",
        )

        /** An enum's members, which are `NAME = value,` and are the constants most code reaches for. */
        private val ENUM_MEMBER = Regex("^(\\s*)([A-Z][A-Z0-9_]*)\\s*=")

        private val CONTAINERS = setOf("module", "class", "enum")

        fun of(sdk: ConnectIqSdk): ApiMirIndex? = at(fileIn(sdk))

        fun fileIn(sdk: ConnectIqSdk): Path = sdk.root.resolve("bin/api.mir")

        fun at(file: Path): ApiMirIndex? =
            file.takeIf { it.isRegularFile() }
                ?.runCatching { readText() }
                ?.getOrNull()
                ?.let { parse(it) }

        /** A stamp that changes when the file does, so one index can be cached per SDK. */
        fun stampOf(file: Path): Long = file.runCatching {
            getLastModifiedTime().toMillis() * 31 + fileSize()
        }.getOrDefault(0L)

        fun parse(text: String): ApiMirIndex {
            val declarations = LinkedHashMap<String, Declaration>()

            // What is open at each level of indentation; index i holds the name opened at depth i.
            val scope = ArrayList<String>()

            // The qualified name opened at each level, so its end can be filled in when it closes.
            // Parallel to `scope` because an enum occupies a level without contributing a name.
            val openedAt = ArrayList<String?>()

            fun close(downTo: Int, end: Int) {
                while (scope.size > downTo) {
                    scope.removeLast()
                    openedAt.removeLast()?.let { name ->
                        declarations[name]?.let { declarations[name] = it.copy(endOffset = end) }
                    }
                }
            }
            var offset = 0
            var line = 0

            // Where the most recent closing brace ended. A scope's end is that, not the start of
            // whatever declaration comes next — between the two sit the next declaration's own
            // documentation comments, and folding the previous scope over them looks broken.
            // Every scope in this file closes with a `}` alone on its line; in 9.2.0 there are
            // 1127 of them, exactly one per module, class, enum and `<init>` block.
            var lastBraceEnd = 0

            text.lineSequence().forEach { raw ->
                line++
                val start = offset
                offset += raw.length + 1

                // A scope closes on its own brace, at the indentation of whatever opened it.
                // Waiting for the next declaration instead would give every scope that ends here
                // the same end, and a module would fold over its siblings.
                //
                // `<init> {` opens a brace that is not a scope in this model; its closing brace
                // sits at the depth of the scope around it, so the pop below finds nothing to do
                // and it costs nothing to ignore.
                if (raw.trim() == "}") {
                    lastBraceEnd = start + raw.length
                    close((raw.length - raw.trimStart().length) / INDENT, lastBraceEnd)
                }

                DECLARATION.find(raw)?.let { match ->
                    val depth = match.groupValues[1].length / INDENT
                    val keyword = match.groupValues[2]
                    val name = match.groupValues[3]

                    close(depth, lastBraceEnd)

                    if (keyword != "enum") {
                        val qualified = (visible(scope) + name).joinToString(".")
                        declarations[qualified] = Declaration(
                            qualifiedName = qualified,
                            kind = kindOf(keyword),
                            offset = start + match.groups[3]!!.range.first,
                            line = line,
                        )
                    }

                    // Only these hold anything; a function or a variable never opens a scope.
                    if (keyword in CONTAINERS) {
                        scope.add(if (keyword == "enum") ENUM_LEVEL else name)
                        openedAt.add(if (keyword == "enum") null else (visible(scope.dropLast(1)) + name).joinToString("."))
                    }
                    return@forEach
                }

                ENUM_MEMBER.find(raw)?.let { match ->
                    val depth = match.groupValues[1].length / INDENT
                    close(depth, lastBraceEnd)
                    if (scope.isEmpty()) return@forEach

                    val name = match.groupValues[2]
                    val qualified = (visible(scope) + name).joinToString(".")
                    declarations.putIfAbsent(
                        qualified,
                        Declaration(
                            qualifiedName = qualified,
                            kind = Kind.CONSTANT,
                            offset = start + match.groups[2]!!.range.first,
                            line = line,
                        ),
                    )
                }
            }

            close(0, maxOf(lastBraceEnd, maxOf(offset - 1, 0)))

            return ApiMirIndex(
                byQualifiedName = declarations,
                bySimpleName = declarations.values.groupBy { it.simpleName },
                byContainer = declarations.values.groupBy { it.container },
            )
        }

        private fun visible(scope: List<String>): List<String> = scope.filterNot { it == ENUM_LEVEL }

        private fun kindOf(keyword: String): Kind = when (keyword) {
            "module" -> Kind.MODULE
            "class" -> Kind.CLASS
            "function" -> Kind.FUNCTION
            "var" -> Kind.VARIABLE
            "const" -> Kind.CONSTANT
            else -> Kind.TYPE
        }
    }
}
