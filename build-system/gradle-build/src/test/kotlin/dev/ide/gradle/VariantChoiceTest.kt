package dev.ide.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VariantChoiceTest {

    @Test
    fun defaultDebugWinsOverTvDebug() {
        val chosen = VariantChoice.choose(
            listOf(
                variant("tvDebug", listOf("tv"), "assembleTvDebug", 0),
                variant("defaultDebug", listOf("default"), "assembleDefaultDebug", 1),
            ),
        )
        assertEquals(":app:android:assembleDefaultDebug", chosen!!.taskPath)
        assertEquals("defaultDebug", chosen.variant.name)
    }

    @Test
    fun theShortestFlavorListWinsAndAVariantNamedDebugBreaksATie() {
        val chosen = VariantChoice.choose(
            listOf(
                variant("tvDebug", listOf("tv"), "assembleTvDebug", 0),
                variant("debug", listOf("phone"), "assembleDebug", 1),
            ),
        )
        assertEquals(":app:android:assembleDebug", chosen!!.taskPath)
    }

    private fun variant(name: String, flavors: List<String>, task: String, order: Int) = VariantChoice.Variant(
        modulePath = ":app:android",
        moduleDir = "app/android",
        application = true,
        name = name,
        buildType = "debug",
        flavors = flavors,
        assembleTask = task,
        debuggable = true,
        order = order,
        namespace = "org.example.app",
    )
}
