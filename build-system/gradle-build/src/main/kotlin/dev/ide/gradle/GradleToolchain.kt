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
        val cmakeVersion = readCmakeVersion(host, javaHome)
        prepare(projectDir, host, scan, javaHome, decision, cmakeVersion)
        if (host.android) probe(host, javaHome)
        return withCmakeRetry(cmakeVersion) { fallback ->
            val client = host.openClient(javaHome)
            try {
                initialize(client, projectDir, host, javaHome, fallback, cmakeVersion)
                val first = fetch(client)
                val known = scan.ndk + scan.buildTools + scan.cmake + scan.platforms
                val discovered = first.ndkVersions + first.buildToolsVersions + first.cmakeVersions + first.platforms
                if (discovered.any { it !in known }) {
                    materialize(host, scan, first, cmakeVersion)
                    fetch(client)
                } else {
                    first
                }
            } finally {
                client.close()
            }
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
        val cmakeVersion = readCmakeVersion(host, javaHome)
        prepare(projectDir, host, scan, javaHome, decision, cmakeVersion)
        if (host.android) probe(host, javaHome)
        return withCmakeRetry(cmakeVersion) { fallback ->
            val client = host.openClient(javaHome)
            try {
                initialize(client, projectDir, host, javaHome, fallback, cmakeVersion)
                val cached = GradleProjectSnapshot.load(projectDir)
                val snapshot = cached ?: syncWith(client, scan, host, cmakeVersion)
                client.execute(tasks, isCanceled = isCanceled, onEvent = onEvent)
                snapshot
            } finally {
                client.close()
            }
        }
    }

    /**
     * The alias CMake directory is the first configure. When AGP rejects it because `cmake --version`
     * disagrees with that directory, the init script is told the binary's real version and configure
     * runs once more.
     */
    private fun <T> withCmakeRetry(cmakeVersion: String?, block: (fallback: Boolean) -> T): T {
        try {
            return block(false)
        } catch (e: ToolingClient.ToolingException) {
            if (cmakeVersion.isNullOrBlank() || !CmakeFallback.rejected(e.message, e.stderr)) throw e
            return block(true)
        }
    }

    private fun syncWith(
        client: ToolingClient,
        scan: ProjectVersions.Scan,
        host: GradleHost,
        cmakeVersion: String?,
    ): GradleProjectSnapshot {
        val first = fetch(client)
        val known = scan.ndk + scan.buildTools + scan.cmake + scan.platforms
        val discovered = first.ndkVersions + first.buildToolsVersions + first.cmakeVersions + first.platforms
        if (discovered.any { it !in known }) {
            materialize(host, scan, first, cmakeVersion)
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

    private fun initialize(
        client: ToolingClient,
        projectDir: Path,
        host: GradleHost,
        javaHome: Path,
        cmakeFallback: Boolean,
        cmakeVersion: String?,
    ) {
        val homes = listOfNotNull(
            host.javaHome(JdkSelection.Image.JDK_17),
            host.javaHome(JdkSelection.Image.JDK_21),
        ).distinct()
        val aapt2 = newestAapt2(host)
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
            ),
            jvmArgs = GradleUserProperties.jvmArgs(host.ramBytes()).split(' '),
            env = childEnv(host, javaHome, cmakeFallback, cmakeVersion),
        )
    }

    private fun prepare(
        projectDir: Path,
        host: GradleHost,
        scan: ProjectVersions.Scan,
        javaHome: Path,
        decision: JdkSelection.Decision,
        cmakeVersion: String?,
    ) {
        WrapperRepair.ensure(projectDir)
        Files.createDirectories(host.androidHome())
        Files.createDirectories(host.gradleUserHome())
        SdkLayout.ensureLicense(host.androidHome())
        SdkLayout.ensureSdkDir(projectDir, host.androidHome())
        materialize(host, scan, null, cmakeVersion)
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

    private fun materialize(
        host: GradleHost,
        scan: ProjectVersions.Scan,
        reported: GradleProjectSnapshot?,
        cmakeVersion: String?,
    ) {
        val ndks = LinkedHashSet<String>()
        ndks += scan.ndk
        ndks += reported?.ndkVersions.orEmpty()
        if (ndks.isEmpty()) ndks += ProjectVersions.defaultNdk(scan.agpVersion)
        val tools = LinkedHashSet<String>()
        tools += scan.buildTools
        tools += reported?.buildToolsVersions.orEmpty()
        if (tools.isEmpty()) tools += ProjectVersions.defaultBuildTools(scan.agpVersion)
        val cmakes = LinkedHashSet<String>()
        cmakes += scan.cmake
        cmakes += reported?.cmakeVersions.orEmpty()
        if (cmakeVersion != null) cmakes += cmakeVersion
        if (cmakes.isEmpty()) cmakes += ProjectVersions.defaultCmake(scan.agpVersion)
        val home = host.androidHome()
        val minSdk = minSdk(scan, reported)
        for (revision in ndks) {
            val root = home.resolve("ndk").resolve(revision)
            ToolchainLayout.installNdk(home, revision, host.clangDriver(), host.toolchainOut(), host.lld())
            ToolchainLayout.installMake(home, revision, host.make())
            host.installSysrootExtras(root, minSdk)
        }
        for (version in cmakes) ToolchainLayout.installCmake(home, version, host.cmake(), host.ninja())
        for (version in tools) ToolchainLayout.installBuildTools(home, version, host.aapt2(), host.zipalign(), host.aidl())
    }

    private fun newestAapt2(host: GradleHost): Path? {
        val root = host.androidHome().resolve("build-tools")
        if (!Files.isDirectory(root)) return null
        return Files.list(root).use { stream ->
            stream.map { it.resolve("aapt2") }
                .filter { Files.isRegularFile(it) }
                .findFirst()
                .orElse(null)
        }
    }

    private fun probe(host: GradleHost, javaHome: Path) {
        val prefix = host.prefix() ?: return
        val linker = host.linker() ?: PrefixProbe.linkerFor(null)
        val java = runProbe(PrefixProbe.javaVersion(linker, javaHome, prefix))
        val shell = runProbe(PrefixProbe.shellVersion(linker, javaHome, prefix))
        if (!java.contains("version", ignoreCase = true) || !shell.contains("version", ignoreCase = true)) {
            throw IllegalStateException("The JDK did not print a version through the system linker.\n$java\n$shell")
        }
    }

    private fun runProbe(command: PrefixProbe.Command): String {
        val process = ProcessBuilder(command.argv)
            .apply { environment().putAll(command.env); redirectErrorStream(true) }
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

    private fun readCmakeVersion(host: GradleHost, javaHome: Path): String? {
        if (!host.android) return null
        val cmake = host.cmake() ?: return null
        val prefix = host.prefix() ?: return null
        val linker = host.linker() ?: return null
        val text = runCatching {
            runProbe(PrefixProbe.execVersion(linker, cmake, prefix, javaHome))
        }.getOrNull() ?: return null
        return CmakeFallback.parseVersion(text)
    }

    private fun minSdk(scan: ProjectVersions.Scan, reported: GradleProjectSnapshot?): Int? {
        val fromModel = reported?.modules?.mapNotNull { module ->
            module.minSdk?.substringBefore('.')?.toIntOrNull()
        }?.minOrNull()
        return listOfNotNull(scan.minSdk, fromModel).minOrNull()
    }

    private fun childEnv(
        host: GradleHost,
        javaHome: Path,
        cmakeFallback: Boolean,
        cmakeVersion: String?,
    ): Map<String, String> {
        val base = if (host.android) {
            val prefix = host.prefix() ?: error("the Android Gradle host has no prefix")
            PrefixProbe.environment(prefix, javaHome, host.libraryPath())
        } else {
            HashMap(System.getenv())
        }
        val env = LinkedHashMap(base)
        env["JAVA_HOME"] = javaHome.toString()
        env["ANDROID_HOME"] = host.androidHome().toString()
        env["ANDROID_SDK_ROOT"] = host.androidHome().toString()
        env["GRADLE_USER_HOME"] = host.gradleUserHome().toString()
        if (!cmakeVersion.isNullOrBlank()) env["DEV_IDE_CMAKE_VERSION"] = cmakeVersion
        if (cmakeFallback) env["DEV_IDE_CMAKE_FALLBACK"] = "true"
        return env
    }
}
