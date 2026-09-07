package com.github.dtretyakov.monkeyc.lsp

import com.github.dtretyakov.monkeyc.sdk.ConnectIqSdk
import java.nio.file.Path

/**
 * How the two servers in the Connect IQ SDK are started.
 *
 * Both are plain Java programs speaking a JSON-RPC protocol over stdio, and both come out of
 * `LanguageServer.jar`. Kept together, and apart from the IDE, because they are the plugin's two
 * load-bearing facts about the SDK and the live tests run exactly these commands.
 */
object SdkServerCommands {

    private const val LANGUAGE_SERVER_MAIN = "com.garmin.monkeybrains.languageserver.LSLauncher"

    private const val DEBUG_ADAPTER_MAIN = "com.garmin.monkeybrains.monkeydodo.DebugAdapterProtocol"

    fun languageServer(sdk: ConnectIqSdk, java: Path): List<String> = listOf(
        java.toString(),
        // Without this the server bounces a Java icon in the macOS dock on every start.
        "-Dapple.awt.UIElement=true",
        "-classpath",
        sdk.languageServerJar.toString(),
        LANGUAGE_SERVER_MAIN,
    )

    /**
     * The debug adapter.
     *
     * `DebugAdapterProtocol` is in `monkeybrains.jar` too, and starting it from there fails at once
     * with `NoClassDefFoundError: com/google/gson/TypeAdapterFactory` — that jar has the adapter and
     * lsp4j but not the gson between them. `LanguageServer.jar` has all three.
     */
    fun debugAdapter(sdk: ConnectIqSdk, java: Path): List<String> = listOf(
        java.toString(),
        "-classpath",
        sdk.languageServerJar.toString(),
        DEBUG_ADAPTER_MAIN,
    )
}
