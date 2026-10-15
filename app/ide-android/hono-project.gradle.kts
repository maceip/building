val honoProjectRoot = rootProject.file("samples/hono-node-server")
val bundleHonoProject = tasks.register<Zip>("bundleHonoProject") {
    group = "build setup"
    description = "Bundle the pinned upstream Hono Node adapter source, license and unchanged tests."
    archiveFileName.set("hono-node-server.zip")
    destinationDirectory.set(layout.buildDirectory.dir("hono-project-asset/workbench-projects"))
    from(honoProjectRoot) {
        exclude("node_modules/**", "dist/**", ".git/**", ".github/**", ".vscode/**")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val bundleHonoWorkbench = tasks.register<Zip>("bundleHonoWorkbench") {
    group = "build setup"
    description = "Bundle the persistent Hono workbench application source and HTTP tests."
    archiveFileName.set("hono-workbench.zip")
    destinationDirectory.set(layout.buildDirectory.dir("hono-project-asset/workbench-projects"))
    from(rootProject.file("samples/hono-workbench")) {
        exclude("node_modules/**", "dist/**", ".data/**", ".env", ".git/**")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.named("preBuild").configure { dependsOn(bundleHonoProject, bundleHonoWorkbench) }
