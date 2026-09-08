package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.project.ConnectIqEnvironment

/**
 * What the language server's failures actually mean.
 *
 * The server reports the worst of them by printing the exception's message, and the exception's
 * message is often `null`. `Failed to load SDK supplementary files: null.` and `ERROR: Unable to
 * load devices: null` are the two that reach the forums most; the thread that finally got to the
 * bottom of them found the cause was missing device data, and the advice was to delete the whole
 * of `%appdata%\garmin\connectiq` and download everything again.
 *
 * None of that is a mystery from inside the plugin. It already knows how many devices are
 * downloaded and which of their folders cannot be read, so it can answer the question the message
 * declines to. The strings matched here are the server's own, taken out of `LanguageServer.jar`
 * rather than out of a forum post.
 */
object ServerFailures {

    /** What the plugin can see about the machine, which is what turns a `null` into a cause. */
    data class Environment(
        val devicesDownloaded: Int,
        val unreadableDevices: List<String>,
        /** SDK and project paths holding characters outside ASCII. */
        val awkwardPaths: List<String>,
    )

    data class Cause(val headline: String, val detail: String, val fix: ConnectIqEnvironment.Fix?)

    /**
     * The cause behind one of the server's log lines, or null when the line is not one of these.
     *
     * Null is the common answer and has to stay cheap: this is called for every line the server
     * logs, and it logs a great many.
     */
    fun explain(message: String, environment: Environment): Cause? = when {
        message.contains(DEVICES) || message.contains(SUPPLEMENTARY) -> deviceData(environment)
        message.contains(JUNGLE) -> jungle()
        message.contains(FILE_CONTEXT) -> fileContext()
        else -> null
    }

    /**
     * Both device-data failures answered from the same facts, in the order that makes them
     * actionable: nothing downloaded first, then something downloaded but unreadable, then the
     * path, then the honest admission that we do not know.
     */
    private fun deviceData(environment: Environment): Cause = when {
        environment.devicesDownloaded == 0 -> Cause(
            "Code intelligence has no device data",
            "The language server could not read the devices, and none are downloaded. It compiles " +
                "against a device, so until one is downloaded it can answer nothing.",
            ConnectIqEnvironment.Fix.SDK_MANAGER,
        )

        environment.unreadableDevices.isNotEmpty() -> Cause(
            "Code intelligence could not read the device data",
            "${environment.unreadableDevices.size} of the downloaded devices could not be read " +
                "(${environment.unreadableDevices.take(3).joinToString()}). Downloading them again " +
                "usually settles it.",
            ConnectIqEnvironment.Fix.SDK_MANAGER,
        )

        environment.awkwardPaths.isNotEmpty() -> Cause(
            "Code intelligence could not read the device data",
            "The server reported no reason. One thing it is known to trip over is a path with " +
                "characters outside ASCII in it, and there is one here: " +
                environment.awkwardPaths.first() + ".",
            null,
        )

        else -> Cause(
            "Code intelligence could not read the device data",
            "The server reported no reason, which is its habit here. Downloading the devices again " +
                "through the SDK Manager is what usually settles it.",
            ConnectIqEnvironment.Fix.SDK_MANAGER,
        )
    }

    private fun jungle() = Cause(
        "Code intelligence could not read a jungle file",
        "The server builds its index from the jungle files, so until it can read them there is no " +
            "completion and no navigation. Check the jungle files named in the settings; a " +
            "relative path is dropped without a word.",
        null,
    )

    private fun fileContext() = Cause(
        "Code intelligence does not recognise this file",
        "The server matches open files against the ones the compiler resolved, and it resolves " +
            "through symlinks. A project reached by a name other than its real one gets this for " +
            "every file. Opening it by its real path is the reliable answer.",
        null,
    )

    /** Every string here is the server's own, read out of `LanguageServer.jar` for SDK 9.2.0. */
    private const val DEVICES = "Unable to load devices"
    private const val SUPPLEMENTARY = "Failed to load SDK supplementary files"
    private const val JUNGLE = "Failed to load jungle"
    private const val FILE_CONTEXT = "Could not find file context"

    /**
     * Whether a path is one the server has been reported to mishandle.
     *
     * Only non-ASCII, deliberately. The same advice on the forums also names spaces, but a space
     * in a path is the normal case on Windows — `C:\Users\John Smith` — and pointing at it would
     * send most of them chasing something that is almost certainly not their problem.
     */
    fun isAwkward(path: String): Boolean = path.any { it.code > 127 }
}
