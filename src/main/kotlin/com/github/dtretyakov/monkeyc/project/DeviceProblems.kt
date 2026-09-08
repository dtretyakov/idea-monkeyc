package com.github.dtretyakov.monkeyc.project

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice

/**
 * Why a build cannot be made for a device, decided from the facts alone.
 *
 * Kept apart from [MonkeyCProject] so the rules can be tested as rules. They exist because all
 * three ways of being wrong about a device reach the user as one message from the compiler that
 * names the device and not the reason — and the commonest of the three, a device the manifest
 * declares that the SDK Manager never downloaded, is one the plugin can see coming.
 */
object DeviceProblems {

    /** How many devices to name before the list stops being a list and becomes a paragraph. */
    private const val NAMED = 5

    /** The reason [device] cannot be built for, or null when it can. */
    fun of(
        device: String,
        declared: List<String>,
        installed: ConnectIqDevice?,
        appType: String?,
    ): String? {
        if (device.isEmpty()) return null
        if (declared.isNotEmpty() && device !in declared) {
            return "$device is not one of the products ${ManifestFile.FILE_NAME} declares. " +
                "Add it there, or choose another device."
        }
        if (installed == null) {
            return "$device is declared in ${ManifestFile.FILE_NAME} but has not been downloaded. " +
                "Get it with the SDK Manager."
        }
        if (!installed.supports(appType)) {
            return "${installed.displayName} does not run a $appType. " +
                "Remove it from ${ManifestFile.FILE_NAME}, or change the application type."
        }
        return null
    }

    /**
     * Why there is no device at all to build for, naming them rather than counting them.
     *
     * "Get them with the SDK Manager" is a different errand for one device than for nine, and
     * knowing which ones is what makes it an errand rather than a search.
     */
    fun noneAvailable(declared: List<String>, undownloaded: List<String>): String = when {
        declared.isEmpty() ->
            "${ManifestFile.FILE_NAME} declares no products, so there is no device to build for."

        undownloaded.size == declared.size ->
            "None of the devices ${ManifestFile.FILE_NAME} declares has been downloaded " +
                "(${list(undownloaded)}). Get them with the SDK Manager."

        undownloaded.isNotEmpty() ->
            "The devices this project can be built for are not downloaded: " +
                "${list(undownloaded)}. Get them with the SDK Manager."

        else -> "None of the devices ${ManifestFile.FILE_NAME} declares can run this kind of app."
    }

    private fun list(devices: List<String>): String =
        devices.take(NAMED).joinToString() +
            if (devices.size > NAMED) ", and ${devices.size - NAMED} more" else ""
}
