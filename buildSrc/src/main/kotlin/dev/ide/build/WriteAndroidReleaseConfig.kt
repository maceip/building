package dev.ide.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Verifies the final AGP variant, then packages only public configuration for release verification. */
abstract class WriteAndroidReleaseConfig : DefaultTask() {
    @get:Input abstract val values: MapProperty<String, String>
    @get:Input abstract val applicationId: Property<String>
    @get:Input abstract val versionCode: Property<Int>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val buildConfigFields: MapProperty<String, String>
    @get:Input abstract val admobAppId: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun write() {
        val config = AndroidReleaseConfig.resolve(values.get(), { null }, { null })
        check(applicationId.get() == config.applicationId && versionCode.get() == config.versionCode && versionName.get() == config.versionName) {
            "The release variant identity/version differs from the resolved configuration"
        }
        for ((key, value) in config.buildConfigFields) {
            check(buildConfigFields.get()[key] == value) { "Release BuildConfig $key differs from the resolved configuration" }
        }
        check(admobAppId.get() == config.values.getValue("ADMOB_APP_ID")) { "Release AdMob manifest ID differs from the resolved configuration" }
        val file = outputDirectory.file("building-release-config.json").get().asFile
        file.parentFile.mkdirs()
        file.writeText(config.values.toSortedMap().entries.joinToString(",\n", "{\n", "\n}\n") {
            "  ${AndroidReleaseConfig.javaString(it.key)}: ${AndroidReleaseConfig.javaString(it.value)}"
        })
    }
}
