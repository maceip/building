package dev.ide.android.gradle

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Looper
import dev.ide.gradle.GradleHost
import dev.ide.gradle.GradleProcessEnvironment
import dev.ide.gradle.GradleScriptEnvironment
import dev.ide.gradle.JdkSelection
import dev.ide.gradle.Prefix
import dev.ide.gradle.PrefixProbe
import dev.ide.gradle.NdkPackages
import dev.ide.gradle.ToolingClient
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The on-device Gradle host. The prefix is `filesDir/usr`, which is a noexec mount. The tooling
 * JVM is started by the system linker with `LD_PRELOAD` set on that process only. This process
 * does not set `LD_PRELOAD` on itself.
 *
 * `JAVA_HOME` is the directory that contains `lib/server/libjvm.so`. The Termux package installs
 * there (`lib/jvm/java-17-openjdk`, `lib/jvm/java-21-openjdk`), and a copied constant that points
 * somewhere else would not start.
 */
class AndroidGradleHost(
    private val context: Context,
    private val prepareScripts: () -> GradleScriptEnvironment = AndroidGradleRuntimeBridge(context)::prepare,
) : GradleHost {

    private val app = context.applicationContext
    private val files = app.filesDir.toPath()

    init {
        Files.createDirectories(files.resolve("tmp"))
    }

    override val android: Boolean = true

    override fun available(): Boolean {
        val prefix = prefix()
        val stamp = prefix.resolve(".bootstrap-stamp")
        return javaHome(JdkSelection.Image.JDK_17) != null &&
            Files.isRegularFile(prefix.resolve("lib/libtermux-exec.so")) &&
            Files.isRegularFile(prefix.resolve("bin/sh")) &&
            runCatching {
                Files.isRegularFile(stamp) && stamp.readText() ==
                    Prefix.catalog().packages.joinToString("\n") { "${it.name} ${it.sha256}" }
            }.getOrDefault(false)
    }

    override fun javaHome(image: JdkSelection.Image): Path? {
        val name = when (image) {
            JdkSelection.Image.JDK_17 -> "java-17-openjdk"
            JdkSelection.Image.JDK_21 -> "java-21-openjdk"
        }
        val home = prefix().resolve("lib/jvm").resolve(name)
        return if (Files.isRegularFile(home.resolve("lib/server/libjvm.so"))) home else null
    }

    override fun androidHome(): Path = files.resolve("android-sdk")

    override fun gradleUserHome(): Path = files.resolve("gradle-user-home")

    override fun toolingJar(): Path = extractToolingJar()

    override fun initScript(): Path {
        val dest = files.resolve("tooling/on-device.init.gradle")
        Files.createDirectories(dest.parent)
        ZipFile(toolingJar().toFile()).use { zip ->
            val entry = zip.getEntry("dev/ide/gradle/on-device.init.gradle")
                ?: error("gradle-tooling.jar has no on-device.init.gradle")
            zip.getInputStream(entry).use { input ->
                Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return dest
    }

    override fun prefix(): Path = files.resolve("usr")

    // Gradle's child launcher uses the system linker; it requires a dynamic PIE executable.
    // The static APK executable remains available to the separate ART build pipeline.
    override fun aapt2(): Path? = prefixBin("aapt2")

    override fun zipalign(): Path? = native("libsdk-zipalign.so")

    override fun aidl(): Path? = prefixBin("aidl")

    override fun buildToolsExtras(): Map<String, Path> {
        val stubs = files.resolve("tooling/core-lambda-stubs.jar")
        Files.createDirectories(stubs.parent)
        app.assets.open("core-lambda-stubs.jar").use {
            Files.copy(it, stubs, StandardCopyOption.REPLACE_EXISTING)
        }
        return linkedMapOf(
            "aapt" to checkNotNull(native("libsdk-aapt.so")) { "SDK aapt is missing from the APK" },
            "dexdump" to checkNotNull(native("libsdk-dexdump.so")) { "SDK dexdump is missing from the APK" },
            "split-select" to checkNotNull(native("libsdk-split-select.so")) { "SDK split-select is missing from the APK" },
            "core-lambda-stubs.jar" to stubs,
        )
    }

    override fun clangDriver(): Path? = native("libllvmtools.so")

    override fun toolchainOut(): Path? {
        val out = files.resolve("ndk-toolchain")
        return if (Files.isDirectory(out)) out else null
    }

    override fun installNdk(revision: String): Boolean {
        NdkPackages.install(androidHome(), revision, prefix())
        return true
    }

    override fun cmake(): Path? = prefixBin("cmake")

    override fun cmakeVersion(): String = Prefix.catalog().packages.first { it.name == "cmake" }.version

    override fun ninja(): Path? = prefixBin("ninja")

    override fun make(): Path? = prefixBin("make")

    override fun ramBytes(): Long {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    override fun linker(): String = PrefixProbe.linkerFor(Build.SUPPORTED_ABIS.firstOrNull())

    override fun libraryPath(): List<Path> {
        val dir = app.applicationInfo.nativeLibraryDir ?: return emptyList()
        val path = Path.of(dir)
        return if (Files.isDirectory(path)) listOf(path) else emptyList()
    }

    override fun prepareScriptEnvironment(): GradleScriptEnvironment {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "The Gradle script environment is prepared off the main thread."
        }
        return prepareScripts()
    }

    override fun openClient(javaHome: Path): ToolingClient =
        openClient(javaHome, GradleProcessEnvironment.create(this, javaHome, prepareScriptEnvironment()))

    override fun openClient(javaHome: Path, environment: Map<String, String>): ToolingClient {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "The Gradle tooling JVM is started off the main thread."
        }
        val process = GradleBuildService.acquire(app, javaHome, toolingJar(), environment)
        return ToolingClient(process)
    }

    /**
     * Download the pinned prefix when the JDK 17 image is not already there. A checksum mismatch
     * fails the setup and is not retried. Safe to call more than once; a matching stamp skips the
     * download. The caller runs this off the main thread.
     */
    fun installIfNeeded(log: (String) -> Unit = {}) {
        synchronized(installLock) {
            val prefix = prefix()
            val catalog = Prefix.catalog()
            val expected = catalog.packages.joinToString("\n") { "${it.name} ${it.sha256}" }
            val stamp = prefix.resolve(".bootstrap-stamp")
            val ready = javaHome(JdkSelection.Image.JDK_17) != null &&
                Files.isRegularFile(prefix.resolve("lib/libtermux-exec.so")) &&
                Files.exists(prefix.resolve("bin/sh")) &&
                Files.isRegularFile(stamp) && stamp.readText() == expected
            if (ready) return
            check(dev.ide.android.BuildConfig.NATIVE_TOOLCHAIN_DOWNLOADS_ENABLED) {
                "Native toolchain downloads are disabled in this distribution. Use an APK-delivered toolchain or a developer build."
            }
            log("Installing the build prefix")
            Prefix.install(catalog, prefix) { url ->
                log(url.substringAfterLast('/'))
                fetch(url)
            }
            Files.createDirectories(prefix)
            stamp.writeText(expected)
        }
    }

    private fun extractToolingJar(): Path {
        val dest = files.resolve("tooling/gradle-tooling.jar")
        val stamp = dest.resolveSibling("gradle-tooling.stamp")
        val version = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("0")
        if (Files.isRegularFile(dest) && dest.toFile().length() > 0L &&
            Files.isRegularFile(stamp) && stamp.readText() == version
        ) {
            return dest
        }
        Files.createDirectories(dest.parent)
        app.assets.open(TOOLING_ASSET).use { input ->
            Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
        }
        stamp.writeText(version)
        return dest
    }

    private fun native(name: String): Path? {
        val dir = app.applicationInfo.nativeLibraryDir ?: return null
        val path = File(dir, name).toPath()
        return if (Files.isRegularFile(path)) path else null
    }

    private fun prefixBin(name: String): Path? {
        val path = prefix().resolve("bin").resolve(name)
        return if (Files.isRegularFile(path) || Files.isSymbolicLink(path)) path else null
    }

    private fun fetch(url: String): ByteArray {
        val connection = URL(url).openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 10 * 60 * 1000
        return connection.getInputStream().use { it.readBytes() }
    }

    private companion object {
        const val TOOLING_ASSET = "dev/ide/gradle/gradle-tooling.jar"
        val installLock = Any()
    }
}
