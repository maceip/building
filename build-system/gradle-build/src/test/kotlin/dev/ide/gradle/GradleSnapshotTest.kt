package dev.ide.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

class GradleSnapshotTest {
    @Test
    fun invalidatesModelsWrittenBeforeConfiguredVariantIdsWereRecorded() {
        val project = Files.createTempDirectory("gradle-model-cache")
        GradleProjectSnapshot.store(project, """{"name":"old-import","modules":[]}""")
        assertNull(GradleProjectSnapshot.load(project))
    }

    @Test
    fun keepsTheInstalledApplicationIdSeparateFromTheSourceNamespace() {
        val project = Files.createTempDirectory("gradle-application-id")
        GradleProjectSnapshot.store(project, """
            {"schemaVersion":2,"name":"fixture","modules":[{
              "name":"app","path":":app","application":true,"namespace":"example.sources",
              "variants":[{"name":"debug","applicationId":"example.installed.dev","debuggable":true}]
            }]}
        """.trimIndent())
        val chosen = VariantChoice.choose(GradleProjectSnapshot.load(project)!!.variants())!!
        assertEquals("example.installed.dev", chosen.variant.applicationId)
        assertEquals("example.sources", chosen.variant.namespace)
    }
}
