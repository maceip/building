plugins {
    `java-library`
}

// gradle-tooling — the Gradle Tooling API client, a plain Java program launched as `java -jar`.
// The IDE never puts this module on its compile classpath: the fat jar is a resource the host
// extracts and execs. Shading the runtime classpath keeps the client's jars from clashing with
// whatever the project's own distribution loads into the daemon.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    implementation(libs.gradle.tooling.api)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.nop)
}

val toolingJar = tasks.register<Jar>("toolingJar") {
    group = "build"
    description = "Fat jar the host launches with `java -jar`. Main-Class is the length-prefixed JSON server."
    archiveFileName.set("gradle-tooling.jar")
    destinationDirectory.set(layout.buildDirectory.dir("tooling"))
    manifest {
        attributes["Main-Class"] = "dev.ide.gradle.tooling.Main"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(tasks.named("classes"))
    from({
        configurations.runtimeClasspath.get()
            .filter { it.isFile && it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/MANIFEST.MF",
        "META-INF/LICENSE",
        "META-INF/LICENSE.txt",
        "META-INF/NOTICE",
        "META-INF/NOTICE.txt",
    )
}

tasks.named("assemble") { dependsOn(toolingJar) }
