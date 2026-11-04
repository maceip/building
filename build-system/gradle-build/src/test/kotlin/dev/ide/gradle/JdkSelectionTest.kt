package dev.ide.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JdkSelectionTest {

    @Test
    fun gradle86WithoutAToolchainSelectsJdk17() {
        val decision = JdkSelection.select("8.6", null, null)
        assertEquals(JdkSelection.Image.JDK_17, decision.image)
        assertTrue(decision.supported)
        assertFalse(decision.autoDownload)
    }

    @Test
    fun aGradle9WrapperDoesNotByItselfSelectJdk21() {
        val decision = JdkSelection.select("9.5.1", null, null)
        assertEquals(JdkSelection.Image.JDK_17, decision.image)
        assertTrue(decision.supported)
        assertFalse(decision.autoDownload)
    }

    @Test
    fun toolchain21SelectsJdk21() {
        val decision = JdkSelection.select("8.6", 21, null)
        assertEquals(JdkSelection.Image.JDK_21, decision.image)
        assertTrue(decision.supported)
        assertFalse(decision.autoDownload)
    }

    @Test
    fun jetbrainsVendorStillUsesOurImageAndLeavesAutoDownloadOff() {
        val decision = JdkSelection.select("9.3.1", 21, "jetbrains")
        assertEquals(JdkSelection.Image.JDK_21, decision.image)
        assertTrue(decision.supported)
        assertFalse(decision.autoDownload)
        assertTrue(decision.reason.contains("jetbrains"))
    }

    @Test
    fun aWrapperOlderThan86IsNotSupported() {
        val decision = JdkSelection.select("8.5", 21, null)
        assertNull(decision.image)
        assertFalse(decision.supported)
        assertFalse(decision.autoDownload)
        assertTrue(decision.reason.contains("8.5"))
    }

    @Test
    fun eightTenIsAboveEightSix() {
        assertTrue(JdkSelection.atLeast("8.10", 8, 6))
        assertFalse(JdkSelection.atLeast("8.5", 8, 6))
        assertTrue(JdkSelection.atLeast("9.0", 8, 6))
    }
}
