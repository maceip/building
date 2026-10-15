package dev.ide.gradle

import java.nio.file.Files
import java.nio.file.Path

/**
 * Prepares the JDK, the SDK aliases, and the wrapper, then asks the tooling server for a model.
 * A version that appears only after configure is materialized and configure runs once more.
 * A wrapper older than 8.6 is refused here, before a daemon starts.
 */
object GradleToolchain {

    class UnsupportedWrapper(message: String) : RuntimeException(message)

    fun sync(projectDir: Path, host: GradleHost): GradleProjectSnapshot {
        val scan = ProjectVersions.scan(projectDir)
        val decision = JdkSelection.select(scan.gradleVersion, scan.toolchainMajor, scan.toolchainVendor)
        if (!decision.supported || decision.image == null) throw UnsupportedWrapper(decision.reason)
        val javaHome = host.javaHome(decision.image)
            ?: throw IllegalStateException("No JDK ${decision.image} image is installed.")
        prepare(projectDir, host, scan, javaHome, decision)
        val scripts = host.prepareScriptEnvironment()
        val environment = GradleProcessEnvironment.create(host, javaHome, scripts)
        if (host.android) probe(host, javaHome, environment)
        val client = host.openClient(javaHome, environment)
        try {
            initialize(client, projectDir, host, javaHome, environment)
            val first = fetch(client)
            val known = scan.ndk + scan.buildTools + scan.cmake + scan.platforms
            val discovered = first.ndkVersions + first.buildToolsVersions + first.cmakeVersions + first.platforms
            if (discovered.any { it !in known }) {
                materialize(host, scan, first)
                return fetch(client)
            }
            return first
        } finally {
            client.close()
        }
    }

    fun execute(
        projectDir: Path,
        host: GradleHost,
        tasks: List<String>,
        isCanceled: () -> Boolean = { false },
        onEvent: (Map<String, Any?>) -> Unit,
    ): GradleProjectSnapshot? {
        val scan = ProjectVersions.scan(projectDir)
        val decision = JdkSelection.select(scan.gradleVersion, scan.toolchainMajor, scan.toolchainVendor)
        if (!decision.supported || decision.image == null) throw UnsupportedWrapper(decision.reason)
        val javaHome = host.javaHome(decision.image)
            ?: throw IllegalStateException("No JDK ${decision.image} image is installed.")
        prepare(projectDir, host, scan, javaHome, decision)
        val scripts = host.prepareScriptEnvironment()
        val environment = GradleProcessEnvironment.create(host, javaHome, scripts)
        if (host.android) probe(host, javaHome, environment)
        val client = host.openClient(javaHome, environment)
        try {
            initialize(client, projectDir, host, javaHome, environment)
            val cached = GradleProjectSnapshot.load(projectDir)
            val snapshot = cached?.also { materialize(host, scan, it) } ?: syncWith(client, scan, host)
            val reliabilityArgs = GradleReliabilityInjection.launchArgs(
                projectDir, GradleEnvironment.reliabilityPrivateCacheRoot ?: host.gradleUserHome(), snapshot, tasks,
            )
            // Execute-only args: model fetch/sync never registers pairings or instruments variants.
            client.execute(tasks, args = reliabilityArgs, isCanceled = isCanceled, onEvent = onEvent)
            GradleReliabilityInjection.verifySources(projectDir, reliabilityArgs)
            return snapshot
        } finally {
            client.close()
        }
    }

    private fun syncWith(client: ToolingClient, scan: ProjectVersions.Scan, host: GradleHost): GradleProjectSnapshot {
        val first = fetch(client)
        val known = scan.ndk + scan.buildTools + scan.cmake + scan.platforms
        val discovered = first.ndkVersions + first.buildToolsVersions + first.cmakeVersions + first.platforms
        if (discovered.any { it !in known }) {
            materialize(host, scan, first)
            return fetch(client)
        }
        return first
    }

    private fun fetch(client: ToolingClient): GradleProjectSnapshot {
        val call = client.fetchModel()
        val parsed = runCatching { GradleProjectSnapshot.parse(call.rawResult) }.getOrElse {
            val encoded = ToolingClient.encode(call.result)
            GradleProjectSnapshot.parse(encoded)
        }
        return parsed
    }

    private fun initialize(client: ToolingClient, projectDir: Path, host: GradleHost, javaHome: Path, environment: Map<String, String>) {
        val homes = listOfNotNull(
            host.javaHome(JdkSelection.Image.JDK_17),
            host.javaHome(JdkSelection.Image.JDK_21),
        ).distinct()
        val aapt2 = newestAapt2(host)
        val scan = ProjectVersions.scan(projectDir)
        val nativeBuild = scan.nativeBuild || scan.ndk.isNotEmpty() || scan.cmake.isNotEmpty()
        val cmakeArgs = host.cmakeVersion()?.takeIf { host.android && nativeBuild }
            ?.let { listOf("-Pdev.ide.cmake.version=$it") }.orEmpty()
        client.initialize(
            projectDir = projectDir.toAbsolutePath().toString(),
            distributionType = "WRAPPER",
            distributionValue = null,
            javaHome = javaHome.toString(),
            androidHome = host.androidHome().toString(),
            gradleUserHome = host.gradleUserHome().toString(),
            args = GradleUserProperties.launchArgs(
                host.ramBytes(), homes, aapt2, host.initScript(),
                host.gradleUserHome().resolve("abi-report.txt"),
            ) + cmakeArgs,
            jvmArgs = GradleUserProperties.jvmArgs(host.ramBytes()).split(' '),
            env = environment,
        )
    }

    private fun prepare(
        projectDir: Path,
        host: GradleHost,
        scan: ProjectVersions.Scan,
        javaHome: Path,
        decision: JdkSelection.Decision,
    ) {
        WrapperRepair.ensure(projectDir)
        Files.createDirectories(host.androidHome())
        Files.createDirectories(host.gradleUserHome())
        SdkLayout.ensureLicense(host.androidHome())
        SdkLayout.ensureSdkDir(projectDir, host.androidHome())
        materialize(host, scan, null)
        val homes = listOfNotNull(
            host.javaHome(JdkSelection.Image.JDK_17),
            host.javaHome(JdkSelection.Image.JDK_21),
        ).distinct()
        GradleUserProperties.write(host.gradleUserHome(), host.ramBytes(), homes, newestAapt2(host))
        if (host.android) {
            val prefix = host.prefix() ?: error("the Android Gradle host has no prefix")
            Prefix.linkJava(prefix, javaHome)
        }
        // auto-download is forced off in the properties above, including when a vendor pin names a
        // JDK we do not ship. [decision] is what the caller reports.
        check(!decision.autoDownload || !decision.supported)
    }

    private fun materialize(host: GradleHost, scan: ProjectVersions.Scan, reported: GradleProjectSnapshot?) {
        val ndks = LinkedHashSet<String>()
        val nativeBuild = scan.nativeBuild || scan.ndk.isNotEmpty() || scan.cmake.isNotEmpty() ||
            reported?.cmakeVersions.orEmpty().isNotEmpty()
        val packagingNdks = reported?.modules.orEmpty().filter { it.application }.mapNotNull { it.ndkVersion }
        if (nativeBuild || reported?.ndkVersions.orEmpty().isNotEmpty() || packagingNdks.isNotEmpty()) {
            ndks += scan.ndk
            ndks += reported?.ndkVersions.orEmpty()
            ndks += packagingNdks
            if (ndks.isEmpty()) ndks += ProjectVersions.defaultNdk(scan.agpVersion)
        }
        val tools = LinkedHashSet<String>()
        tools += scan.buildTools
        tools += reported?.buildToolsVersions.orEmpty()
        if (tools.isEmpty()) tools += ProjectVersions.defaultBuildTools(scan.agpVersion)
        val cmakes = LinkedHashSet<String>()
        cmakes += scan.cmake
        cmakes += reported?.cmakeVersions.orEmpty()
        if (cmakes.isEmpty() && nativeBuild) cmakes += ProjectVersions.defaultCmake(scan.agpVersion)
        if (cmakes.isNotEmpty()) host.cmakeVersion()?.let { version ->
            cmakes.clear()
            cmakes.add(version)
        }
        val home = host.androidHome()
        for (revision in ndks) {
            if (!host.installNdk(revision)) {
                ToolchainLayout.installNdk(home, revision, host.clangDriver(), host.toolchainOut())
            }
            ToolchainLayout.installMake(home, revision, host.make())
        }
        for (version in cmakes) ToolchainLayout.installCmake(home, version, host.cmake(), host.ninja())
        val extras = host.buildToolsExtras()
        for (version in tools) ToolchainLayout.installBuildTools(home, version, host.aapt2(), host.zipalign(), host.aidl(), extras)
    }

    private fun newestAapt2(host: GradleHost): Path? {
        val root = host.androidHome().resolve("build-tools")
        if (!Files.isDirectory(root)) return null
        return Files.list(root).use { stream ->
            stream.map { it.resolve("aapt2") }
                .filter { Files.isRegularFile(it) }
                .filter { !host.android || Files.isSymbolicLink(it) }
                .sorted { left, right ->
                    val a = left.parent.fileName.toString().split('.').map { it.toIntOrNull() ?: 0 }
                    val b = right.parent.fileName.toString().split('.').map { it.toIntOrNull() ?: 0 }
                    (0 until maxOf(a.size, b.size)).map { (b.getOrElse(it) { 0 }).compareTo(a.getOrElse(it) { 0 }) }
                        .firstOrNull { it != 0 } ?: 0
                }
                .findFirst()
                .orElse(null)
        }
    }

    private fun probe(host: GradleHost, javaHome: Path, environment: Map<String, String>) {
        val prefix = host.prefix() ?: return
        val linker = host.linker() ?: PrefixProbe.linkerFor(null)
        val java = runProbe(PrefixProbe.javaVersion(linker, javaHome, prefix).copy(env = environment))
        val shell = runProbe(PrefixProbe.shellVersion(linker, javaHome, prefix).copy(env = environment))
        if (!java.contains("version", ignoreCase = true) || !shell.contains("version", ignoreCase = true)) {
            throw IllegalStateException("The JDK did not print a version through the system linker.\n$java\n$shell")
        }
    }

    private fun runProbe(command: PrefixProbe.Command): String {
        val process = ProcessBuilder(command.argv)
            .apply { environment().clear(); environment().putAll(command.env); redirectErrorStream(true) }
            .start()
        val text = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            throw IllegalStateException(
                "linker probe failed ($code): ${command.argv.joinToString(" ")}\n$text",
            )
        }
        return text
    }

}
