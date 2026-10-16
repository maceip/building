plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val repo = rootDir.resolve("../..").canonicalFile
val sharedModel = tasks.register<Sync>("reuseEditorModel") {
    from(repo.resolve("app/ide-ui-core/src/commonMain/kotlin/dev/ide/ui/editor/core")) {
        include("EditorDocument.kt", "Rope.kt")
    }
    into(layout.buildDirectory.dir("generated/editor-model"))
}
val stagePayload = tasks.register<Exec>("stageNodePayload") {
    inputs.files(rootDir.resolve("stage_node.py"), repo.resolve("tools/android-sdk/exec-prefix.c"),
        repo.resolve("tools/runtime-pack/node-launcher.c"))
    inputs.dir(repo.resolve("app/ide-android/build/developer-runtime-splits/base"))
    inputs.dir(repo.resolve("app/ide-android/build/developer-runtime-splits/node"))
    outputs.dir(rootDir.resolve("build/payload"))
    commandLine("python3", rootDir.resolve("stage_node.py"))
}

android {
    namespace = "ai.building.loader"
    compileSdk = 36
    compileSdkMinor = 1
    defaultConfig {
        applicationId = "ai.building.loader.prototype"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-proof"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "ai.building.loader.ProofRunner"
    }
    buildFeatures { compose = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Isolated prototype, never signed with the product upload key.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testBuildType = "release"
    dynamicFeatures += setOf(":node_runtime")
    sourceSets.getByName("main") {
        kotlin.directories += layout.buildDirectory.dir("generated/editor-model").get().asFile.absolutePath
        jniLibs.srcDir(rootDir.resolve("build/payload/base/jniLibs"))
    }
    packaging.jniLibs.useLegacyPackaging = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
tasks.named("preBuild").configure { dependsOn(sharedModel, stagePayload) }
dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation("com.google.android.play:feature-delivery:2.1.0")
    constraints {
        implementation("androidx.fragment:fragment:1.5.7") {
            because("Play's legacy transitive Fragment must support ActivityResult document pickers")
        }
    }
}
