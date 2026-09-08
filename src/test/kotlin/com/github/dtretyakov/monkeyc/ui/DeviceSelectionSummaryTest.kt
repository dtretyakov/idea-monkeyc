package com.github.dtretyakov.monkeyc.ui

import com.github.dtretyakov.monkeyc.sdk.ConnectIqDevice
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one line that says what a set of devices commits you to.
 *
 * The ticked boxes answer "which watches". They answer none of the questions that decide whether
 * the choice is a good one, and the two that matter most have real consequences: how many resource
 * families you have signed up to draw, and the smallest memory budget the code now has to fit.
 */
class DeviceSelectionSummaryTest {

    private fun device(
        id: String,
        family: String? = "round-240x240",
        memory: Long? = 65_536L,
        touch: Boolean = true,
        bits: Int? = 16,
    ) = ConnectIqDevice(
        id = id,
        displayName = id,
        group = null,
        family = family,
        isTouch = touch,
        sdkVersion = null,
        memoryLimits = memory?.let { mapOf("watchApp" to it) } ?: emptyMap(),
        bitsPerPixel = bits,
    )

    @Test
    fun `nothing chosen says nothing`() {
        assertNull(DeviceSelectionSummary.of(emptyList(), "watch-app"))
    }

    @Test
    fun `the count of resource families is the work the choice signs you up for`() {
        val selected = listOf(
            device("a", family = "round-240x240"),
            device("b", family = "round-240x240"),
            device("c", family = "rectangle-240x400"),
        )

        val line = DeviceSelectionSummary.of(selected, "watch-app")!!

        assertTrue(line.contains("3 selected"), line)
        assertTrue(line.contains("2 resource families"), line)
    }

    @Test
    fun `one family is singular, because a line that says families is a line nobody trusts`() {
        val line = DeviceSelectionSummary.of(listOf(device("a")), "watch-app")!!

        assertTrue(line.contains("1 resource family"), line)
    }

    @Test
    fun `memory is a range, because only its bottom constrains the code`() {
        val selected = listOf(
            device("small", memory = 65_536L),
            device("large", memory = 786_432L),
        )

        val line = DeviceSelectionSummary.of(selected, "watch-app")!!

        assertTrue(line.contains("memory 64 KB–768 KB"), line)
    }

    @Test
    fun `one budget is stated once rather than as a range of itself`() {
        val line = DeviceSelectionSummary.of(listOf(device("a", memory = 65_536L)), "watch-app")!!

        assertTrue(line.contains("memory 64 KB"), line)
        assertTrue(!line.contains("–"), line)
    }

    @Test
    fun `devices without touch are counted, because an app built on taps excludes them`() {
        val selected = listOf(device("a"), device("b", touch = false), device("c", touch = false))

        val line = DeviceSelectionSummary.of(selected, "watch-app")!!

        assertTrue(line.contains("2 without touch"), line)
    }

    @Test
    fun `a set that is all touch does not mention touch`() {
        val line = DeviceSelectionSummary.of(listOf(device("a")), "watch-app")!!

        assertTrue(!line.contains("touch"), line)
    }

    @Test
    fun `a mix of colour depths warns about the worst screen the artwork must survive`() {
        val selected = listOf(device("rich", bits = 16), device("poor", bits = 4))

        val line = DeviceSelectionSummary.of(selected, "watch-app")!!

        assertTrue(line.contains("colour down to 4-bit"), line)
    }

    @Test
    fun `one colour depth is not worth a clause`() {
        val selected = listOf(device("a", bits = 16), device("b", bits = 16))

        assertTrue(!DeviceSelectionSummary.of(selected, "watch-app")!!.contains("colour"))
    }

    @Test
    fun `a clause the data cannot support is dropped, not shown as a zero`() {
        // A profile that does not say how many colours a screen has must not be reported as having
        // none, and a barrel has no app type to have a budget for.
        val silent = listOf(device("a", family = null, memory = null, bits = null))

        val line = DeviceSelectionSummary.of(silent, manifestAppType = null)!!

        assertTrue(line == "1 selected", line)
    }
}
