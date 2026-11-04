plugins {
    `java-library`
}

// Native local-first reliability SDK, API 26+, Java 8. No bundled runtime libraries or JavaScript VM.
// The optional provider is inert without explicit per-project configuration and user consent.
// Framework types (including platform org.json) are compile-only, so CI_CORE_ONLY needs no Android SDK.
dependencies {
    compileOnly(libs.android.stub)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation("org.json:json:20080701")
    testCompileOnly(libs.android.stub)
    testRuntimeOnly(libs.android.stub)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

java {
    // Java 8 bytecode: D8 rejects class files newer than it supports, and these classes are dexed into every
    // instrumented debug APK (whatever the user's language level). No lambdas/records — plain 8.
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
