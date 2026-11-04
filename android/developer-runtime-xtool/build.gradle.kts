plugins { id("com.android.dynamic-feature") }

// Match the base application's dependency conflict rules, including the release
// lint model, which resolves base dependencies on its own checks classpath.
configurations.matching {
    it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath") ||
        it.name.endsWith("LintChecksClasspath")
}.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-parcelize-runtime")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
    resolutionStrategy.capabilitiesResolution.withCapability("com.google.guava:guava") {
        candidates.firstOrNull { "jre" in it.variantName }?.let { select(it) }
    }
}
android {
    namespace = "dev.ide.runtime.xtoolpack"
    compileSdk = 36
    compileSdkMinor = 1
    defaultConfig { minSdk = 26 }
    packaging.jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += "**/libdevruntime_*.so" // Manifest hashes cover the exact pinned bytes.
    }
    val staged = project(":ide-android").layout.buildDirectory.dir("developer-runtime-splits/xtool")
    sourceSets.getByName("main").assets.srcDir(staged.get().dir("assets").asFile)
    sourceSets.getByName("main").jniLibs.srcDir(staged.get().dir("jniLibs").asFile)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
tasks.named("preBuild").configure { dependsOn(":ide-android:partitionDeveloperRuntimePack") }
dependencies { implementation(project(":ide-android")) }
