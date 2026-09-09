package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice

/**
 * What the target popup offers below the devices, and what it says when it has none to offer.
 *
 * The list in that popup is the manifest's products intersected with what the SDK Manager has
 * downloaded, so "why is my watch not here" has two different answers and the popup used to give
 * the second one always: a single "Open SDK Manager" at the bottom, whatever the reason. For the
 * ordinary case that is the wrong door. The list is decided by the manifest, and the way to change
 * it is Edit Products — the SDK Manager only matters when a device is genuinely absent from the
 * machine.
 *
 * Which is the same shape the platform uses elsewhere: the trailing action in Android Studio's
 * device dropdown opens the Device Manager because that is where its list comes from, and Flutter's
 * offers to start a simulator for the same reason. Ours comes from the manifest.
 *
 * Kept apart from [EmptyReason], which answers a neighbouring question and not this one: that is
 * about what the products *dialog* can offer to tick, which is everything downloaded that is new
 * enough and runs this kind of app — the manifest does not narrow it. Folding the two together
 * would mean one rule with a flag deciding which of two questions it was answering.
 */
internal object DeviceMenu {

    /**
     * @param sdkManager whether to offer the SDK Manager, which is only when it is the real blocker
     * @param empty what to say in place of the list, or null when there is a list
     */
    data class Offer(val sdkManager: Boolean, val empty: String?)

    /**
     * Edit Products is not in here because it is unconditional: the manifest decides the list, so
     * the way to change the list is always worth offering.
     */
    fun of(declared: List<String>, installed: List<ConnectIqDevice>, buildable: List<ConnectIqDevice>): Offer {
        val installedIds = installed.map { it.id }.toSet()
        val missing = declared.filterNot { it in installedIds }

        return Offer(
            // Only when something is genuinely absent from this machine. A project whose products
            // are all downloaded has nothing to gain from the SDK Manager, and offering it there
            // taught people that it was the answer to a question it cannot answer.
            sdkManager = installed.isEmpty() || missing.isNotEmpty(),
            empty = when {
                buildable.isNotEmpty() -> null
                installed.isEmpty() -> "No devices are downloaded"
                // Declared, and not one of them is on this machine. Naming the count matters: the
                // remedy is to download those, not to pick different ones.
                declared.isNotEmpty() -> "None of the ${declared.size} declared devices is downloaded"
                // No products in the manifest and nothing offered anyway — a barrel, or an app
                // type none of the downloaded devices runs. Editing products is the way out of
                // the first; the second is the manifest's app type, which is on the same form.
                else -> "No downloaded device runs this app"
            },
        )
    }
}
