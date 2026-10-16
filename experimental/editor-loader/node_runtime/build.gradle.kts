plugins { id("com.android.dynamic-feature") }
android {
    namespace = "ai.building.loader.node"
    compileSdk = 36
    compileSdkMinor = 1
    defaultConfig { minSdk = 26 }
    sourceSets.getByName("main") {
        assets.srcDir(rootDir.resolve("build/payload/node/assets"))
        jniLibs.srcDir(rootDir.resolve("build/payload/node/jniLibs"))
    }
    packaging.jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += "**/libdevruntime_*.so"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.named("preBuild").configure { dependsOn(":app:stageNodePayload") }
dependencies {
    implementation(project(":app"))
    implementation("com.google.android.play:feature-delivery:2.1.0")
}
