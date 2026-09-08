package com.github.dtretyakov.monkeyc.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Machine-wide settings: where the SDK is and which `java` runs it.
 *
 * Both are normally left empty. The SDK Manager already records the chosen SDK in
 * `current-sdk.cfg`, and the IDE's own JVM can run the SDK's jars; these exist for the machine
 * where that is not true.
 */
@Service(Service.Level.APP)
@State(
    name = "MonkeyCApplication",
    storages = [Storage("monkeyc.xml")],
    category = SettingsCategory.TOOLS,
)
class MonkeyCAppSettings : PersistentStateComponent<MonkeyCAppSettings> {

    /** Overrides `current-sdk.cfg`. Empty means "whatever the SDK Manager has selected". */
    var sdkPath: String = ""

    /** A JDK home or a `java` executable. Empty means the IDE's own JVM. */
    var javaPath: String = ""

    /**
     * Path to `mtp-rs`, which is how a build reaches a watch that is not a disk. Empty means
     * "look for it".
     *
     * Needed because current Garmin devices speak MTP, and only older ones mount as storage. The
     * tool installs with `cargo install mtp-rs-cli`, so it lands in `~/.cargo/bin` — a directory
     * on the `PATH` of a shell but not necessarily of an IDE launched from the desktop, which is
     * why looking for it is worth doing rather than trusting the environment.
     */
    var mtpToolPath: String = ""

    override fun getState(): MonkeyCAppSettings = this

    override fun loadState(state: MonkeyCAppSettings) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        fun getInstance(): MonkeyCAppSettings = ApplicationManager.getApplication().service()
    }
}
