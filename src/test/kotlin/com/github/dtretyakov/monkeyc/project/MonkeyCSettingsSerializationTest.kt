package com.github.dtretyakov.monkeyc.project

import com.intellij.util.xmlb.XmlSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The settings have to survive being written to `monkeyc.xml` and read back.
 *
 * `getState` returns `this`, so the serializer walks every public getter on the class — including
 * a computed one. That is how `target` came to be written as an empty `<MonkeyCTarget/>` that could
 * not be read back: the class it names has required constructor parameters and no defaults. The
 * component then failed to initialise *as a whole*, and that failure is silent in the only way that
 * matters — the settings quietly become their defaults. Two of the fields here, `exportedWithKey`
 * and `exportedAsApplicationId`, exist to refuse an export that would go out under a different key
 * or update a different store listing, so losing them disarms the check without saying so.
 *
 * A round trip is the only test that catches this. Nothing else notices: the code compiles, the
 * settings work for the rest of the session, and the loss happens at the *next* project open.
 */
class MonkeyCSettingsSerializationTest {

    @Test
    fun `everything set survives a write and a read`() {
        val original = MonkeyCSettings().apply {
            developerKeyPath = "/keys/developer_key.der"
            exportedWithKey = "ab:cd:ef"
            exportedAsApplicationId = "8f14e45fceea167a5a36dedd4bea2543"
            targetDevice = "fenix7"
            targetOnWatch = true
            rootsConfigured = true
        }

        val restored = MonkeyCSettings()
        restored.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(original), MonkeyCSettings::class.java))

        assertEquals("/keys/developer_key.der", restored.developerKeyPath)
        assertEquals("ab:cd:ef", restored.exportedWithKey)
        assertEquals("8f14e45fceea167a5a36dedd4bea2543", restored.exportedAsApplicationId)
        assertEquals("fenix7", restored.targetDevice)
        assertEquals(true, restored.targetOnWatch)
        assertEquals(true, restored.rootsConfigured)
        // The derived value comes back because the two fields under it did, not because it was
        // stored: storing it is the bug.
        assertEquals(MonkeyCTarget("fenix7", MonkeyCTarget.Destination.WATCH), restored.target)
    }

    @Test
    fun `nothing derived is written to the file`() {
        val xml = XmlSerializer.serialize(
            MonkeyCSettings().apply {
                targetDevice = "venu2"
                typeCheckLevel = TypeCheckLevel.STRICT.display
                optimizationLevel = OptimizationLevel.FAST.display
                debugLogLevel = DebugLogLevel.DEFAULT.display
            },
        )
        val text = com.intellij.openapi.util.JDOMUtil.write(xml)

        // Named individually rather than by a rule, because each is a computed property that the
        // serializer would otherwise write: one of them broke the whole component, and the next
        // one added without an annotation would do it again.
        listOf("target", "typeCheck", "optimization", "debugLog").forEach { derived ->
            assertFalse(text.contains("\"$derived\""), "$derived was written to the settings file:\n$text")
        }
    }

    /**
     * The shape that actually broke, kept as itself.
     *
     * A `monkeyc.xml` written by the version that serialised `target` is on disk in every project
     * that ran it, and it is committed, so it arrives on colleagues' machines too. Reading it must
     * not throw — the unknown element is ignored, and the fields beside it still load.
     */
    @Test
    fun `a file written by the broken version still loads`() {
        val onDisk = com.intellij.openapi.util.JDOMUtil.load(
            """
            <component name="MonkeyC">
              <option name="rootsConfigured" value="true" />
              <option name="target">
                <MonkeyCTarget />
              </option>
              <option name="targetDevice" value="descentmk1" />
            </component>
            """.trimIndent(),
        )

        val restored = XmlSerializer.deserialize(onDisk, MonkeyCSettings::class.java)

        assertEquals("descentmk1", restored.targetDevice)
        assertEquals(true, restored.rootsConfigured)
    }
}
