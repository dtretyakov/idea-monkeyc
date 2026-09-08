package com.github.dtretyakov.monkeyc.project

/**
 * Whether the key about to sign a release is the key that signed the last one.
 *
 * Kept apart from the project service so the rule can be tested as a rule, which is how the setup
 * checklist and the device diagnostics are already arranged.
 *
 * The rule is worth more than it looks. Garmin's documentation is unusually blunt about this —
 * "Your upload will be rejected If the developer key has changed since your last upload", and "If
 * you lose your original signing key you will not be able to update your app" — and their staff
 * have given the same answer on the forums since 2018: there is no recovery, only republishing
 * under a new listing and abandoning the ratings, the downloads and every installed user.
 */
object SigningIdentity {

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
    fun describe(changed: Verdict.Changed): String =
        "This project last exported with developer key ${changed.expected}, but the key configured " +
            "now is ${changed.actual}. The Connect IQ Store rejects an update signed with a " +
            "different key, and there is no way to recover the listing — an app signed with a new " +
            "key has to be published again from scratch, losing its ratings, its download count " +
            "and its installed users. Use the original key, or clear the recorded fingerprint if " +
            "this really is a new app."
}
