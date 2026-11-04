// SPDX-License-Identifier: GPL-3.0-or-later
plugins { id("com.android.dynamic-feature") }

// Dynamic features resolve the base's dependency graph independently. Keep these
// two deliberate base-app conflict rules aligned; neither affects engine payloads.
configurations.matching {
    it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath")
}.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-parcelize-runtime")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
    resolutionStrategy.capabilitiesResolution.withCapability("com.google.guava:guava") {
        candidates.firstOrNull { "jre" in it.variantName }?.let { jre ->
            select(jre)
            because("Match base app: dexed bundletool requires Guava JRE Predicate")
        }
    }
}

val engine = providers.gradleProperty("IDE_APP_ENGINE").orElse("python").get()
require(engine in setOf("node", "python")) { "IDE_APP_ENGINE must select exactly node or python" }
android {
    namespace = "dev.ide.runtime.feature"
    compileSdk = 36
    defaultConfig { minSdk = 26 } // ABI selection belongs to the base application.
    // Engine ABI resolves only installed, signed nativeLibraryDir files. A feature
    // cannot silently use the default unextracted ZIP-native layout.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies {
    implementation(project(":ide-android"))
    implementation(project(":runtime-engine-$engine"))
}
