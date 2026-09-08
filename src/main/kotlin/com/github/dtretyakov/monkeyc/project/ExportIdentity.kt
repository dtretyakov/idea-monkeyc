package com.github.dtretyakov.monkeyc.project

/**
 * Whether a release is going out as the same thing the last one did.
 *
 * Two facts decide that, and both are recorded the first time a project exports and compared every
 * time after: the key it is signed with, and the application id inside it. Getting either wrong is
 * unrecoverable in the same way — the store either refuses the upload or accepts it as a different
 * app — and nothing else anywhere notices before it happens.
 *
 * Kept apart from the project service so the rules can be tested as rules, which is how the setup
 * checklist and the device diagnostics are already arranged. One comparison, two sets of words,
 * because what the two mistakes cost is not the same.
 */
object ExportIdentity {

    sealed interface Verdict {
        /** Nothing to say: either it matches, or there is nothing to compare against yet. */
        object Fine : Verdict

        /** The first export of this project. Worth recording, not worth interrupting for. */
        data class FirstExport(val fingerprint: String) : Verdict

        /** The key changed. This is the one that has to stop and ask. */
        data class Changed(val expected: String, val actual: String) : Verdict
    }

    /**
     * Compares the key in hand against the one recorded, given both fingerprints.
     *
     * An unreadable key is [Fine] here rather than an alarm: whatever is wrong with it is the
     * developer key check's business, and reporting it twice in different words helps nobody.
     */
    fun check(recorded: String, current: String?): Verdict = when {
        current.isNullOrEmpty() -> Verdict.Fine
        recorded.isEmpty() -> Verdict.FirstExport(current)
        recorded == current -> Verdict.Fine
        else -> Verdict.Changed(recorded, current)
    }

    /** What to tell the user when the key has changed, in the words the situation deserves. */
    fun describeKey(changed: Verdict.Changed): String =
        "This project last exported with developer key ${changed.expected}, but the key configured " +
            "now is ${changed.actual}. The Connect IQ Store rejects an update signed with a " +
            "different key, and there is no way to recover the listing — an app signed with a new " +
            "key has to be published again from scratch, losing its ratings, its download count " +
            "and its installed users. Use the original key, or clear the recorded fingerprint if " +
            "this really is a new app."

    /**
     * What to tell the user when the application id has changed.
     *
     * The commonest way this happens is Garmin's own beta mechanism, which requires "an alternate
     * app id in your manifest" and is promoted by editing the id back afterwards. Forgetting either
     * edit is silent: exporting with the beta id still produces a valid package, and uploading it
     * updates the wrong listing — or creates a second one nobody can find, since "URLs to the beta
     * will not be visible outside of your account".
     */
    fun describeApplicationId(changed: Verdict.Changed): String =
        "This project last exported as application id ${changed.expected}, but the manifest now " +
            "says ${changed.actual}. The store treats an application id as the app's identity: a " +
            "package built with a different one updates a different listing, or creates a new one. " +
            "If this is the beta id, put the production id back before exporting a release; if the " +
            "app really has a new identity, clear the recorded id."
}
