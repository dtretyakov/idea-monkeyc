package com.github.dtretyakov.monkeyc.project

/**
 * Where the next run goes: a device, and whether it goes to the simulator or to a watch.
 *
 * Two axes rather than one, and that is not an oversight. Android Studio's device dropdown collapses
 * them because there a device *is* the destination; here a Connect IQ binary is compiled for a
 * particular watch whether or not that watch is attached — building a `.prg` for a device you do
 * not own is an ordinary thing to do — so "which device" and "where it goes" are genuinely
 * separate questions and both belong in the one control that answers "where does Run put this".
 *
 * The rules live here rather than in the widget so they can be tested as rules, which is how the
 * setup checklist and the device diagnostics are already arranged.
 */
data class MonkeyCTarget(val device: String, val destination: Destination) {

    enum class Destination {
        /** Built with the `_sim` suffix and pushed into the Connect IQ simulator. */
        SIMULATOR,

        /** Built for the hardware, to be installed over USB. What the old checkbox meant. */
        WATCH,
    }

    val onWatch: Boolean get() = destination == Destination.WATCH

    /** "fenix7" or "fenix7 on the watch" — what the chip says, and what a message can quote. */
    fun describe(displayName: String = device): String =
        if (onWatch) "$displayName on the watch" else displayName

    companion object {

        /**
         * The target a run will actually use.
         *
         * The configuration wins when it names a device, because pinning a configuration to one
         * watch is the reason that field exists. Everything else follows the toolbar. Returning the
         * *effective* answer rather than the project setting is the whole point: the chip is next
         * to the Run button, so it has to describe the next click and not a preference that the
         * next click might override.
         */
        fun resolve(
            /** The run configuration's override, or null when it follows the toolbar. */
            pinned: MonkeyCTarget?,
            /** What the toolbar says. */
            chosen: MonkeyCTarget?,
            /** The device to fall back on when nothing has been chosen yet. */
            default: String?,
        ): MonkeyCTarget? {
            pinned?.takeIf { it.device.isNotEmpty() }?.let { return it }
            chosen?.takeIf { it.device.isNotEmpty() }?.let { return it }
            return default?.takeIf { it.isNotEmpty() }?.let { MonkeyCTarget(it, Destination.SIMULATOR) }
        }

        /**
         * A configuration's own target, from the two options it stores.
         *
         * `forDevice` is no longer edited by hand; it is read so that a configuration saved before
         * the destination was part of the target keeps meaning what it meant.
         */
        fun ofOptions(device: String, forDevice: Boolean): MonkeyCTarget? =
            device.takeIf { it.isNotEmpty() }
                ?.let { MonkeyCTarget(it, if (forDevice) Destination.WATCH else Destination.SIMULATOR) }
    }
}
