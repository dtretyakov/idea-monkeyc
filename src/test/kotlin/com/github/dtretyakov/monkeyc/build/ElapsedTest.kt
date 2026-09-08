package com.github.dtretyakov.monkeyc.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How long the build took, in a unit that says something.
 *
 * The figure exists to be compared — against yesterday's, and against the same build on another
 * JVM. A four-hour export on the forums became two minutes on nothing but a change of JRE, and the
 * developer found that by timing builds with a stopwatch.
 */
class ElapsedTest {

    @Test
    fun `a fast build keeps its decimal, because seconds are all it has`() {
        assertEquals("0.8 s", MonkeyCBuildSession.elapsed(800))
        assertEquals("3.5 s", MonkeyCBuildSession.elapsed(3_512))
    }

    @Test
    fun `a build of a few seconds drops the decimal`() {
        assertEquals("12 s", MonkeyCBuildSession.elapsed(12_400))
        assertEquals("89 s", MonkeyCBuildSession.elapsed(89_000))
    }

    @Test
    fun `a long build is minutes and seconds`() {
        assertEquals("1 min 30 s", MonkeyCBuildSession.elapsed(90_000))
        assertEquals("4 min 5 s", MonkeyCBuildSession.elapsed(245_000))
        assertEquals("240 min 0 s", MonkeyCBuildSession.elapsed(4 * 60 * 60 * 1000L))
    }

    @Test
    fun `the decimal is a point wherever the machine is`() {
        // English sentence, technical figure. A decimal comma here would make the same build read
        // differently on two developers' machines.
        assertEquals("0.1 s", MonkeyCBuildSession.elapsed(100))
    }
}
