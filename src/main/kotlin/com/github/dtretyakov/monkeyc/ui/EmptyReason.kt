package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.SdkVersion

/**
 * Why there is nothing to tick, when there is nothing to tick.
 *
 * Garmin's own VS Code extension has this bug on file: a user who had downloaded their watch found
 * "Edit Products" empty, because the app's minimum API level was above what that watch supports and
 * the device was filtered out silently. An empty list has to say which of the two things happened,
 * because the two have different remedies.
 */
internal sealed interface EmptyReason {
    object None : EmptyReason
    object NothingDownloaded : EmptyReason
    data class AllBelowMinimum(val installed: Int, val minimum: SdkVersion) : EmptyReason
    data class NoneRunsThisKind(val installed: Int, val appType: String) : EmptyReason

    companion object {
        /**
         * Asked in the order the filters run, so the reason given is the one that actually emptied
         * the list rather than whichever is checked first.
         */
        fun of(
            installed: Int,
            newEnough: Int,
            eligible: Int,
            minimum: SdkVersion?,
            appType: String?,
        ): EmptyReason = when {
            eligible > 0 -> None
            installed == 0 -> NothingDownloaded
            newEnough == 0 && minimum != null -> AllBelowMinimum(installed, minimum)
            newEnough == 0 -> NothingDownloaded
            appType != null -> NoneRunsThisKind(newEnough, appType)
            else -> NothingDownloaded
        }
    }
}
