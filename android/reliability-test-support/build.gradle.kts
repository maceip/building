plugins {
    `java-library`
}

// Optional fault tooling. Consume ONLY via debugImplementation/androidTestImplementation.
// No provider, service, instrumentation controller, or runtime dependency is packaged by this jar.
dependencies {
    compileOnly(libs.android.stub)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.android.stub)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
