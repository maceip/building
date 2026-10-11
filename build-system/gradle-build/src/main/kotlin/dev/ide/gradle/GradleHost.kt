package dev.ide.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * What a shell (desktop, CLI, Android) provides so one [GradleBuildSystem] can launch the
 * tooling jar. Desktop has no prefix and no linker. Android does. The host is looked up when
 * a build or a sync runs, so it can be installed after the IDE's services are constructed.
 */
interface GradleHost {
    val android: Boolean
    fun available(): Boolean
    fun javaHome(image: JdkSelection.Image): Path?
    fun androidHome(): Path
    fun gradleUserHome(): Path
    fun toolingJar(): Path
    fun initScript(): Path
    fun prefix(): Path?
    fun aapt2(): Path?
    fun zipalign(): Path?
    fun aidl(): Path?
    fun clangDriver(): Path?
    fun toolchainOut(): Path?
    fun cmake(): Path?
    fun ninja(): Path?
    fun make(): Path?
    fun ramBytes(): Long
    fun linker(): String?
    /** Extra library directories the Gradle process needs (clang's `libLLVM.so`, for example). */
    fun libraryPath(): List<Path> = emptyList()
    fun openClient(javaHome: Path): ToolingClient
}

/**
 * Off until a shell opts in. Unit tests of the static Gradle reader keep confidence 10 and
 * do not start a tooling JVM just because this module is on the classpath.
 */
object GradleEnvironment {
    @Volatile var enabled: Boolean = false
    @Volatile var host: GradleHost? = null

    /** Set by the Android shell once [dev.ide] has an installer. Desktop leaves it null. */
    @Volatile var installAndLaunch: (suspend (apk: Path, packageName: String, log: (String) -> Unit) -> Unit)? = null
}

/** Desktop and the CLI: the host JDK, no linker, no prefix. */
class DesktopGradleHost(
    private val home: Path = Path.of(System.getProperty("user.home"), ".codeassist"),
) : GradleHost {
    override val android: Boolean = false

    override fun available(): Boolean =
        Files.isRegularFile(Path.of(System.getProperty("java.home"), "bin", "java"))

    override fun javaHome(image: JdkSelection.Image): Path = Path.of(System.getProperty("java.home"))

    override fun androidHome(): Path = home.resolve("android-sdk")

    override fun gradleUserHome(): Path = home.resolve("gradle-user-home")

    override fun toolingJar(): Path = extract("dev/ide/gradle/gradle-tooling.jar", home.resolve("tooling/gradle-tooling.jar"))

    override fun initScript(): Path = extract("dev/ide/gradle/on-device.init.gradle", home.resolve("tooling/on-device.init.gradle"))

    override fun prefix(): Path? = null
    override fun aapt2(): Path? = null
    override fun zipalign(): Path? = null
    override fun aidl(): Path? = null
    override fun clangDriver(): Path? = null
    override fun toolchainOut(): Path? = null
    override fun cmake(): Path? = null
    override fun ninja(): Path? = null
    override fun make(): Path? = null

    override fun ramBytes(): Long {
        val meminfo = Path.of("/proc/meminfo")
        if (Files.isRegularFile(meminfo)) {
            val total = Files.readAllLines(meminfo).firstOrNull { it.startsWith("MemTotal:") }
            val kb = total?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
            if (kb != null) return kb * 1024L
        }
        return 8L * 1024L * 1024L * 1024L
    }

    override fun linker(): String? = null

    override fun openClient(javaHome: Path): ToolingClient {
        val jar = toolingJar()
        val process = ProcessBuilder(javaHome.resolve("bin/java").toString(), "-jar", jar.toString())
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        return ToolingClient(process)
    }

    private fun extract(resource: String, dest: Path): Path {
        val stream = DesktopGradleHost::class.java.classLoader.getResourceAsStream(resource)
            ?: error("missing resource $resource")
        Files.createDirectories(dest.parent)
        stream.use { input -> Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING) }
        return dest
    }
}
