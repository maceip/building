// SPDX-License-Identifier: GPL-3.0-or-later
plugins { id("com.android.library") }

val payload = rootProject.layout.projectDirectory.dir("tools/runtime-engine/build/payloads/python")
val stageEngine = tasks.register<Exec>("stagePythonEngine") {
    group = "build setup"
    description = "Build official pinned CPython Android and selected source dependencies"
    // Corresponding-source output contains these actual files, not just native code.
    inputs.files(rootProject.fileTree("android/runtime-engine") { exclude("**/build/**", "**/.gradle/**") })
    inputs.files(rootProject.fileTree("tools/runtime-engine") { exclude("build/**", "**/__pycache__/**") })
    inputs.dir(rootProject.file("services/developer-workbench/src/main/kotlin/dev/ide/workbench/appengine"))
    inputs.dir(rootProject.file("android/applog-runtime/src/main/java"))
    inputs.files(rootProject.file("android/applog-runtime/build.gradle.kts"), rootProject.file("LICENSE"), rootProject.file("LICENSE-EXCEPTION"))
    outputs.dir(payload)
    commandLine("python3", rootProject.file("tools/runtime-engine/build.py"), "--engine", "python",
        "--abi", "arm64-v8a", "--output", payload.asFile)
    if (gradle.startParameter.isOffline) args("--offline")
}
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "dev.ide.runtime.payload.python"
    compileSdk = 36
    defaultConfig { minSdk = 26; ndk { abiFilters.add("arm64-v8a") } }
    sourceSets.getByName("main").apply {
        assets.srcDir(payload.dir("assets")); jniLibs.srcDir(payload.dir("jniLibs"))
    }
    androidResources { ignoreAssetsPattern = "!*.engine-ignore" }
}
tasks.named("preBuild").configure { dependsOn(stageEngine) }
