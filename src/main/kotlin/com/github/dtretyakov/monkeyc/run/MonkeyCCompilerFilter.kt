package com.github.dtretyakov.monkeyc.run

import com.github.dtretyakov.monkeyc.build.CompilerOutputParser
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Turns the file and line in a compiler diagnostic into a link.
 *
 * The compiler prints absolute paths, so the only work is finding where in the line the path
 * starts and how far it runs — which is what makes the link land on the file rather than on the
 * whole line.
 */
class MonkeyCCompilerFilter(private val project: Project) : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val message = CompilerOutputParser.parseLine(line) ?: return null
        val path = message.file ?: return null
        val lineNumber = message.line ?: return null

        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return null

        val start = line.indexOf(path)
        if (start < 0) return null
        // Cover the location, "App.mc:30,8", not just the path: that is what reads as a link.
        val end = line.indexOf(':', start + path.length + 1).let { if (it < 0) start + path.length else it }

        val offset = entireLength - line.length
        return Filter.Result(
            offset + start,
            offset + end,
            OpenFileHyperlinkInfo(
                project,
                file,
                // The compiler counts lines from one and columns from zero.
                lineNumber - 1,
                message.column ?: 0,
            ),
        )
    }
}
