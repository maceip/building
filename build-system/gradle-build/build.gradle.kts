plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// gradle-build — the IDE side of a real Gradle build. It does not compile against the Tooling API.
// The fat jar produced by :gradle-tooling is a resource this module extracts and execs.
dependencies {
    api(project(":build-engine"))
    implementation(project(":platform-json"))
    implementation(libs.commons.compress)
    implementation(libs.xz)

    testImplementation(gradleTestKit())
}

val stageToolingJar = tasks.register<Copy>("stageToolingJar") {
    dependsOn(":gradle-tooling:toolingJar")
    from(project(":gradle-tooling").layout.buildDirectory.file("tooling/gradle-tooling.jar"))
    into(layout.buildDirectory.dir("generated/gradle-tooling-resource/dev/ide/gradle"))
}

tasks.processResources {
    dependsOn(stageToolingJar)
    from(rootProject.file("gradle/wrapper/gradle-wrapper.jar")) { into("dev/ide/gradle") }
    from(rootProject.file("gradlew")) { into("dev/ide/gradle") }
    from(project(":gradle-tooling").file("src/main/resources/dev/ide/gradle/on-device.init.gradle")) {
        into("dev/ide/gradle")
    }
}

sourceSets.main.get().resources.srcDir(layout.buildDirectory.dir("generated/gradle-tooling-resource"))

tasks.named<Test>("test") {
    dependsOn(":gradle-tooling:toolingJar")
    val toolingJar = project(":gradle-tooling").layout.buildDirectory.file("tooling/gradle-tooling.jar")
    systemProperty("dev.ide.tooling.jar", toolingJar.get().asFile.absolutePath)
    systemProperty("dev.ide.gradle.home", gradle.gradleHomeDir.absolutePath)
    systemProperty(
        "dev.ide.init.script",
        project(":gradle-tooling").file("src/main/resources/dev/ide/gradle/on-device.init.gradle").absolutePath,
    )
}
