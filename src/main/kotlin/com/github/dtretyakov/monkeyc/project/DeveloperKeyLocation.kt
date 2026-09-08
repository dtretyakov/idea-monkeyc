package com.github.dtretyakov.monkeyc.project

import java.nio.file.Path

/**
 * Whether a signing key is somewhere it can be lost or leaked.
 *
 * Both risks are real and only one of them is obvious. Leaking it is the obvious one: a key
 * committed to a public repository lets anyone sign an app as you. Losing it is the expensive one —
 * Garmin keeps no copy, an update signed with a different key is rejected, and the app has to be
 * republished as a new listing.
 *
 * The check that earns its place is the **combination**: a key inside the project that version
 * control is not ignoring. A key inside the project is perfectly normal and common — a real project
 * keeps one in its root with a one-line `.gitignore` and is entirely fine — so warning on location
 * alone would fire on people who have already got it right, which is how a checklist teaches people
 * to stop reading it.
 */
object DeveloperKeyLocation {

    sealed interface Verdict {
        /** Outside the project, or inside and ignored. Nothing to say. */
        object Fine : Verdict

        /** Inside a project directory and not ignored: one commit away from being published. */
        data class Committable(val key: Path, val root: Path) : Verdict
    }

    /**
     * @param ignored whether version control is ignoring the key. Null when the answer is unknown —
     *   the project is not under version control, or nothing could say — in which case this stays
     *   quiet rather than guessing, because a false alarm here is worse than silence.
     */
    fun check(key: Path?, roots: List<Path>, ignored: Boolean?): Verdict {
        if (key == null || ignored != false) return Verdict.Fine
        val root = roots.firstOrNull { runCatching { key.normalize().startsWith(it.normalize()) }.getOrDefault(false) }
        return if (root == null) Verdict.Fine else Verdict.Committable(key, root)
    }

    fun describe(verdict: Verdict.Committable): String =
        "${verdict.key.fileName} is inside the project and not ignored by version control. " +
            "Committing a signing key lets anyone sign an app as you, and this is the only copy " +
            "Garmin will ever accept for updates to apps signed with it — add it to .gitignore, " +
            "and keep a backup somewhere the project is not."
}
