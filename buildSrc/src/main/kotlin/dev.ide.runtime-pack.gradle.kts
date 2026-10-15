// Build-host acquisition only. Android extracts signed APK assets and never downloads native code.
val runtimePackOutput = layout.buildDirectory.dir("developer-runtime-pack")
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
    outputs.dir(runtimePackOutput)
    // Reuse the NDK provisioned by the app's existing helper build; never compete with it.
    dependsOn("buildNativeSdkTools")
    commandLine("python3", sources.resolve("build.py"), "--output", runtimePackOutput.get().asFile)
    if (gradle.startParameter.isOffline) args("--offline")
}

val partitionDeveloperRuntimePack = tasks.register<Exec>("partitionDeveloperRuntimePack") {
    dependsOn(stageDeveloperRuntimePack)
    inputs.dir(runtimePackOutput)
    inputs.file(rootProject.file("tools/runtime-pack/partition.py"))
    outputs.dir(layout.buildDirectory.dir("developer-runtime-splits"))
    commandLine("python3", rootProject.file("tools/runtime-pack/partition.py"),
        "--source", runtimePackOutput.get().asFile,
        "--output", layout.buildDirectory.dir("developer-runtime-splits").get().asFile)
}
tasks.named("preBuild").configure {
    dependsOn(if (providers.gradleProperty("IDE_RUNTIME_DELIVERY").getOrElse("bundled") == "play")
        partitionDeveloperRuntimePack else stageDeveloperRuntimePack)
}
