package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.project.ProjectLayout
import java.nio.file.Path
import kotlin.io.path.name

/** What the compiler will call this project's artifacts, which is its directory name, sanitised. */
object ProjectName {
    fun of(root: Path): String = ProjectLayout.artifactName(root.name)
}
