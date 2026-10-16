// Build-host acquisition only. Android extracts signed APK assets and never downloads native code.
val runtimePackOutput = layout.buildDirectory.dir("developer-runtime-pack")
val runtimeBaseOutput = layout.buildDirectory.dir("developer-runtime-base")
val stageDeveloperRuntimePack = tasks.register<Exec>("stageDeveloperRuntimePack") {
    group = "build setup"
    description = "Stage checksum-pinned Node, npm, dash and Python from APK-delivered runtime inputs."
    val sources = rootProject.file("tools/runtime-pack")
    inputs.files(
        sources.resolve("node-launcher.c"),
        sources.resolve("build.py"), sources.resolve("packages.lock.json"), sources.resolve("recipes.lock.json"), sources.resolve("runtime-launcher.c"), sources.resolve("runtime-env.c"),
        sources.resolve("build_android_spawn.py"), sources.resolve("android-spawn.source.lock.json"), sources.resolve("optional-notices.lock.json"), sources.resolve("npm-tools.lock.json"),
        rootProject.file("build-system/gradle-build/src/main/resources/dev/ide/gradle/bootstrap-catalog.json"),
    )
    outputs.dir(runtimeBaseOutput)
    // Reuse the NDK provisioned by the app's existing helper build; never compete with it.
    dependsOn("buildNativeSdkTools")
    commandLine("python3", sources.resolve("build.py"), "--output", runtimeBaseOutput.get().asFile)
    if (gradle.startParameter.isOffline) args("--offline")
}

val stageXtoolRuntimePack = tasks.register<Exec>("stageXtoolRuntimePack") {
    group = "build setup"
    description = "Stage Swift and XTool runtime into the developer runtime pack."
    val sources = rootProject.file("tools/runtime-pack")
    val xtool = providers.environmentVariable("XTOOL_ROOT").orElse(rootProject.projectDir.parentFile.resolve("xtool").absolutePath)
    inputs.files(sources.resolve("stage_xtool.py"), sources.resolve("xtool-inputs.lock.json"), sources.resolve("build.py"))
    inputs.dir(runtimeBaseOutput)
    inputs.dir(file(xtool.get()).resolve("Android/output"))
    inputs.dir(file(xtool.get()).resolve(".build/android-debs"))
    outputs.dir(runtimePackOutput)
    dependsOn(stageDeveloperRuntimePack)
    commandLine("python3", sources.resolve("stage_xtool.py"), "--source", runtimeBaseOutput.get().asFile,
        "--output", runtimePackOutput.get().asFile, "--xtool-root", xtool.get())
}

val partitionDeveloperRuntimePack = tasks.register<Exec>("partitionDeveloperRuntimePack") {
    dependsOn(stageXtoolRuntimePack)
    inputs.dir(runtimePackOutput)
    inputs.file(rootProject.file("tools/runtime-pack/partition.py"))
    outputs.dir(layout.buildDirectory.dir("developer-runtime-splits"))
    commandLine("python3", rootProject.file("tools/runtime-pack/partition.py"),
        "--source", runtimePackOutput.get().asFile,
        "--output", layout.buildDirectory.dir("developer-runtime-splits").get().asFile)
}

tasks.named("preBuild").configure {
    dependsOn(if (providers.gradleProperty("IDE_RUNTIME_DELIVERY").getOrElse("bundled") == "play")
        partitionDeveloperRuntimePack else stageXtoolRuntimePack)
}
