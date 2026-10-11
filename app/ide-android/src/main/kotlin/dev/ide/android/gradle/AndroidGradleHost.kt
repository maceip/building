package dev.ide.android.gradle

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import dev.ide.android.AndroidIde
import dev.ide.android.plugins.ApkPluginSource
import dev.ide.gradle.Arm64Sysroot
import dev.ide.gradle.GradleHost
import dev.ide.gradle.JdkSelection
import dev.ide.gradle.NdkPluginTree
import dev.ide.gradle.Prefix
import dev.ide.gradle.PrefixProbe
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
class AndroidGradleHost(private val context: Context) : GradleHost {

    private val app = context.applicationContext
    private val files = app.filesDir.toPath()

    override val android: Boolean = true

    override fun available(): Boolean = javaHome(JdkSelection.Image.JDK_17) != null

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

    override fun aapt2(): Path? = native("libaapt2.so")

    override fun zipalign(): Path? = native("libzipalign.so")

    override fun aidl(): Path? = prefixBin("aidl")

    override fun clangDriver(): Path? = native("libllvmtools.so")

    override fun lld(): Path? = native("libld-gnu-lld.so")

    override fun toolchainOut(): Path? {
        val unpacked = NdkPluginTree.unpacked(AndroidIde.appHomeDir(app).toPath())
        if (NdkPluginTree.isReady(unpacked)) return unpacked
        val staged = files.resolve("ndk-toolchain")
        if (NdkPluginTree.isReady(staged)) return staged
        val apk = ndkPluginApk() ?: return null
        NdkPluginTree.unpackFromApk(apk, staged)
        return staged.takeIf { NdkPluginTree.isReady(it) }
    }

    override fun installSysrootExtras(ndkRoot: Path, minSdk: Int?) {
        if (minSdk == null || Arm64Sysroot.apiDirPresent(ndkRoot, minSdk)) return
        val cache = files.resolve("arm64-sysroot")
        Arm64Sysroot.ensure(cache, Arm64Sysroot.catalog()) { url, dest -> downloadTo(url, dest) }
        val sysroot = ndkRoot.resolve("toolchains/llvm/prebuilt/linux-x86_64/sysroot")
        Arm64Sysroot.overlay(sysroot, cache)
    }

    override fun cmake(): Path? = prefixBin("cmake")

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

    override fun openClient(javaHome: Path): ToolingClient {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "The Gradle tooling JVM is started off the main thread."
        }
        val prefix = prefix()
        val env = LinkedHashMap(PrefixProbe.environment(prefix, javaHome, libraryPath()))
        env["ANDROID_HOME"] = androidHome().toString()
        env["ANDROID_SDK_ROOT"] = androidHome().toString()
        env["GRADLE_USER_HOME"] = gradleUserHome().toString()
        val process = GradleBuildService.acquire(app, javaHome, toolingJar(), env)
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

    private fun ndkPluginApk(): Path? {
        val matches = try {
            app.packageManager.queryIntentActivities(
                Intent(ApkPluginSource.PLUGIN_ACTION),
                PackageManager.GET_META_DATA,
            )
        } catch (_: Throwable) {
            return null
        }
        for (info in matches) {
            val apk = info.activityInfo?.applicationInfo?.sourceDir ?: continue
            val path = Path.of(apk)
            if (NdkPluginTree.containsToolchain(path)) return path
        }
        return null
    }

    private fun downloadTo(url: String, dest: Path) {
        val connection = URL(url).openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 10 * 60 * 1000
        Files.createDirectories(dest.parent)
        connection.getInputStream().use { input ->
            Files.newOutputStream(dest).use { output -> input.copyTo(output) }
        }
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
