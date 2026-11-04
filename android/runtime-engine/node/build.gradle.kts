// SPDX-License-Identifier: GPL-3.0-or-later
plugins { id("com.android.library") }

val payload = rootProject.layout.projectDirectory.dir("tools/runtime-engine/build/payloads/node")
val stageEngine = tasks.register<Exec>("stageNodeEngine") {
    group = "build setup"
    description = "Build pinned source Android libnode; never download a native runtime binary"
    // Corresponding-source output contains these actual files, not just native code.
    inputs.files(rootProject.fileTree("android/runtime-engine") { exclude("**/build/**", "**/.gradle/**") })
    inputs.files(rootProject.fileTree("tools/runtime-engine") { exclude("build/**", "**/__pycache__/**") })
    inputs.dir(rootProject.file("services/developer-workbench/src/main/kotlin/dev/ide/workbench/appengine"))
    inputs.dir(rootProject.file("android/applog-runtime/src/main/java"))
    inputs.files(rootProject.file("android/applog-runtime/build.gradle.kts"), rootProject.file("LICENSE"), rootProject.file("LICENSE-EXCEPTION"))
    outputs.dir(payload)
    doFirst {
        check(providers.gradleProperty("ALLOW_UNQUALIFIED_NODE_ANDROID").orNull == "true") {
            "Node Android is not supported upstream. -PALLOW_UNQUALIFIED_NODE_ANDROID=true is required for internal qualification."
        }
    }
    commandLine("python3", rootProject.file("tools/runtime-engine/build.py"), "--engine", "node",
        "--abi", "arm64-v8a", "--output", payload.asFile, "--allow-unqualified-node")
    if (gradle.startParameter.isOffline) args("--offline")
}
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "dev.ide.runtime.payload.node"
    compileSdk = 36
    defaultConfig { minSdk = 26; ndk { abiFilters.add("arm64-v8a") } }
    sourceSets.getByName("main").apply {
        assets.srcDir(payload.dir("assets")); jniLibs.srcDir(payload.dir("jniLibs"))
    }
    // Python/module names beginning '_' must never be silently discarded by AAPT defaults.
    androidResources { ignoreAssetsPattern = "!*.engine-ignore" }
}
tasks.named("preBuild").configure { dependsOn(stageEngine) }
