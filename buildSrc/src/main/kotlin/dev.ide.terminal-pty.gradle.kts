// Kept independent of runtime-pack and native-sdk sources/locks; uses their already pinned NDK.
val terminalOutput = layout.buildDirectory.dir("runtime-terminal")
val buildRuntimeTerminalPty = tasks.register<Exec>("buildRuntimeTerminalPty") {
    group = "build setup"
    description = "Build the app-compatible JNI controlling PTY and package terminal licenses."
    dependsOn("buildNativeSdkTools")
    inputs.files(rootProject.file("tools/runtime-terminal/build.py"), rootProject.file("tools/runtime-terminal/pty.c"), rootProject.file("tools/runtime-terminal/atomic-files.c"))
    inputs.dir(layout.projectDirectory.dir("vendor/termux-terminal"))
    outputs.dir(terminalOutput)
    commandLine("python3", rootProject.file("tools/runtime-terminal/build.py"), "--output", terminalOutput.get().asFile)
}

tasks.named("preBuild").configure { dependsOn(buildRuntimeTerminalPty) }
