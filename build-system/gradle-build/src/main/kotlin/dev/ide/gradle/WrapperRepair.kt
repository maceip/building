package dev.ide.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.writeText

/**
 * A missing wrapper is generated pointing at Gradle 9.5.1, the distribution this repo's own
 * wrapper pins. The jar and the `gradlew` script are copied from resources (the repo already
 * contains them). A distribution zip is not packed into the APK; the wrapper downloads it
 * into `GRADLE_USER_HOME` when the project is built.
 *
 * An existing `gradle-wrapper.properties` is left alone, so a project that pinned 8.6 or 9.7
 * keeps that pin. Only a missing jar or a missing `gradlew` is filled in beside it.
 */
object WrapperRepair {

    const val PINNED_URL = "https\\://services.gradle.org/distributions/gradle-9.5.1-bin.zip"

    fun ensure(projectDir: Path) {
        val wrapperDir = projectDir.resolve("gradle/wrapper")
        val properties = wrapperDir.resolve("gradle-wrapper.properties")
        val jar = wrapperDir.resolve("gradle-wrapper.jar")
        val gradlew = projectDir.resolve("gradlew")
        val propertiesMissing = !Files.isRegularFile(properties)
        val jarMissing = !Files.isRegularFile(jar)
        val scriptMissing = !Files.isRegularFile(gradlew)
        if (!propertiesMissing && !jarMissing && !scriptMissing) return
        Files.createDirectories(wrapperDir)
        if (propertiesMissing) {
            properties.writeText(
                """
                distributionBase=GRADLE_USER_HOME
                distributionPath=wrapper/dists
                distributionUrl=$PINNED_URL
                networkTimeout=10000
                validateDistributionUrl=true
                zipStoreBase=GRADLE_USER_HOME
                zipStorePath=wrapper/dists
                """.trimIndent() + "\n",
            )
        }
        if (jarMissing) copyResource("/dev/ide/gradle/gradle-wrapper.jar", jar)
        if (scriptMissing) {
            copyResource("/dev/ide/gradle/gradlew", gradlew)
            gradlew.toFile().setExecutable(true)
        }
    }

    private fun copyResource(resource: String, dest: Path) {
        val stream = WrapperRepair::class.java.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        stream.use { input ->
            Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
