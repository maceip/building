package dev.ide.gradle

import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.writeText

/**
 * The synthetic SDK tree AGP looks up. Directory names are the versions the project asked for.
 * The ELF inside is the bionic one we actually have: one clang, one cmake, one aapt2, aliased
 * under every version string. `source.properties` `Pkg.Revision` equals the directory name.
 *
 * `clang++` and the `llvm-*` names are symlinks. The file that is exec'd is named `clang`,
 * because the driver's argv[0] stem selects the tool. The NDK host tag stays `linux-x86_64`
 * even on an aarch64 device: CMake derives that tag from `CMAKE_HOST_SYSTEM_NAME`, which is Linux.
 */
object ToolchainLayout {

    private val CLANG_LINKS = listOf(
        "clang++", "clang-cl", "ld.lld", "llvm-ar", "llvm-nm", "llvm-strip",
        "llvm-objcopy", "llvm-ranlib", "llvm-readelf",
    )

    fun installNdk(androidHome: Path, revision: String, driver: Path?, toolchainOut: Path?) {
        val root = androidHome.resolve("ndk").resolve(revision)
        val bin = root.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
        Files.createDirectories(bin)
        // CMake's revision regex requires `Pkg.Desc` on the first line and three numeric components.
        root.resolve("source.properties").writeText(
            "Pkg.Desc = Android NDK\nPkg.Revision = $revision\n",
        )
        copyResourceTree("dev/ide/gradle/ndk-r27c", root)
        if (driver != null && Files.isRegularFile(driver)) {
            val clang = bin.resolve("clang")
            Files.copy(driver, clang, StandardCopyOption.REPLACE_EXISTING)
            clang.toFile().setExecutable(true)
            for (name in CLANG_LINKS) {
                val link = bin.resolve(name)
                Files.deleteIfExists(link)
                Files.createSymbolicLink(link, Path.of("clang"))
            }
        }
        if (toolchainOut != null && Files.isDirectory(toolchainOut)) {
            val sysroot = firstExisting(
                toolchainOut.resolve("sysroot"),
                toolchainOut.resolve("toolchains/llvm/prebuilt/linux-x86_64/sysroot"),
            )
            if (sysroot != null) {
                copyTree(sysroot, root.resolve("toolchains/llvm/prebuilt/linux-x86_64/sysroot"))
            }
            val resources = firstExisting(
                toolchainOut.resolve("lib/clang/18"),
                toolchainOut.resolve("lib-clang/18"),
                toolchainOut.resolve("toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18"),
            )
            if (resources != null) {
                copyTree(resources, root.resolve("toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18"))
            }
            val glue = firstExisting(
                toolchainOut.resolve("sources/android/native_app_glue"),
                toolchainOut.resolve("native_app_glue"),
            )
            if (glue != null) copyTree(glue, root.resolve("sources/android/native_app_glue"))
        }
    }

    fun installMake(androidHome: Path, revision: String, make: Path?) {
        if (make == null || !Files.isRegularFile(make)) return
        val bin = androidHome.resolve("ndk").resolve(revision).resolve("prebuilt/linux-x86_64/bin")
        Files.createDirectories(bin)
        val dest = bin.resolve("make")
        Files.copy(make, dest, StandardCopyOption.REPLACE_EXISTING)
        dest.toFile().setExecutable(true)
    }

    fun installCmake(androidHome: Path, version: String, cmake: Path?, ninja: Path?) {
        val bin = androidHome.resolve("cmake").resolve(version).resolve("bin")
        Files.createDirectories(bin)
        androidHome.resolve("cmake").resolve(version).resolve("source.properties").writeText(
            "Pkg.Revision = $version\n",
        )
        copyTool(cmake, bin.resolve("cmake"))
        copyTool(ninja, bin.resolve("ninja"))
    }

    /**
     * One build-tools payload under every version the project or its AGP names.
     * `aapt2` is a file whose name is `aapt2`. AGP's `Aapt2MavenUtils` rejects
     * `android.aapt2FromMavenOverride` unless the path's filename is `aapt2`, so the
     * jniLibs `libaapt2.so` is copied here rather than passed under its packaged name.
     * `zipalign` is copied only when a binary was actually supplied.
     */
    fun installBuildTools(androidHome: Path, version: String, aapt2: Path?, zipalign: Path?, aidl: Path?) {
        val dir = androidHome.resolve("build-tools").resolve(version)
        Files.createDirectories(dir)
        dir.resolve("source.properties").writeText("Pkg.Revision = $version\n")
        copyTool(aapt2, dir.resolve("aapt2"))
        if (zipalign != null && Files.isRegularFile(zipalign)) copyTool(zipalign, dir.resolve("zipalign"))
        if (aidl != null && Files.isRegularFile(aidl)) copyTool(aidl, dir.resolve("aidl"))
    }

    fun aapt2Override(androidHome: Path, version: String): Path =
        androidHome.resolve("build-tools").resolve(version).resolve("aapt2")

    private fun copyTool(source: Path?, dest: Path) {
        if (source == null || !Files.isRegularFile(source)) return
        Files.createDirectories(dest.parent)
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
        dest.toFile().setExecutable(true)
    }

    private fun firstExisting(vararg paths: Path): Path? = paths.firstOrNull { Files.exists(it) }

    private fun copyResourceTree(resourceRoot: String, dest: Path) {
        val url = ToolchainLayout::class.java.classLoader.getResource(resourceRoot) ?: return
        when (url.protocol) {
            "file" -> {
                val root = Path.of(url.toURI())
                if (Files.isDirectory(root)) copyTree(root, dest)
            }
            "jar" -> {
                val connection = url.openConnection() as? JarURLConnection ?: return
                val jar = connection.jarFile
                val prefix = resourceRoot.trimEnd('/') + "/"
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.name.startsWith(prefix) || entry.isDirectory) continue
                    val target = dest.resolve(entry.name.removePrefix(prefix))
                    Files.createDirectories(target.parent)
                    jar.getInputStream(entry).use { input ->
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
        val ndkBuild = dest.resolve("build/ndk-build")
        if (Files.isRegularFile(ndkBuild)) ndkBuild.toFile().setExecutable(true)
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { src ->
                val rel = from.relativize(src)
                val target = to.resolve(rel.toString())
                if (Files.isDirectory(src)) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
