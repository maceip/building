package dev.ide.gradle

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Google's versioned NDK data, paired with the pinned Android-host LLVM in our private prefix. */
object NdkPackages {
    private const val REPOSITORY = "https://dl.google.com/android/repository/"
    private const val HOST = "toolchains/llvm/prebuilt/linux-x86_64/"
    private const val STAMP = ".building-ndk-data"

    fun install(androidHome: Path, revision: String, prefix: Path) {
        require(revision.matches(Regex("[0-9]+(?:\\.[0-9]+){2}"))) { "Invalid NDK revision: $revision" }
        val compiler = prefix.resolve("bin/clang")
        check(Files.isRegularFile(compiler)) { "The Android LLVM compiler is not installed. Run build environment setup." }
        val root = androidHome.resolve("ndk/$revision")
        val marker = root.resolve(STAMP)
        if (!Files.isRegularFile(marker) || marker.readText() != revision) {
            val pkg = findPackage(revision)
            val downloads = androidHome.resolve("downloads")
            Files.createDirectories(downloads)
            val archive = downloads.resolve("ndk-$revision.zip")
            download(pkg, archive)
            val staging = Files.createTempDirectory(root.parent.also { Files.createDirectories(it) }, ".$revision-")
            try {
                extractData(archive, staging)
                check(Files.isRegularFile(staging.resolve("meta/platforms.json"))) { "NDK archive has no platform metadata" }
                check(Files.isRegularFile(staging.resolve("${HOST}sysroot/usr/include/android/api-level.h"))) { "NDK archive has no Android sysroot" }
                val properties = Properties().apply { Files.newInputStream(staging.resolve("source.properties")).use { load(it) } }
                check(properties.getProperty("Pkg.Revision").substringBefore('-') == revision) { "NDK revision does not match $revision" }
                staging.resolve(STAMP).writeText(revision)
                // This directory is app-owned SDK state. Replace earlier incomplete aliases only after validation.
                deleteTree(root)
                Files.move(staging, root, StandardCopyOption.REPLACE_EXISTING)
                Files.deleteIfExists(archive)
            } finally {
                deleteTree(staging)
            }
        }
        installCompiler(root, prefix)
    }

    internal data class Package(val url: String, val sha1: String)

    private fun findPackage(revision: String): Package {
        val xml = open(REPOSITORY + "repository2-3.xml").bufferedReader().use { it.readText() }
        // Android's DOM factory does not implement Xerces-specific feature switches.
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) { "SDK repository must not contain a DTD" }
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val document = builder.parse(InputSource(StringReader(xml)))
        val packages = document.getElementsByTagName("remotePackage")
        for (i in 0 until packages.length) {
            val pkg = packages.item(i) as Element
            if (pkg.getAttribute("path") != "ndk;$revision") continue
            val archives = pkg.getElementsByTagName("archive")
            for (j in 0 until archives.length) {
                val archive = archives.item(j) as Element
                if (archive.getElementsByTagName("host-os").item(0)?.textContent != "linux") continue
                val url = archive.getElementsByTagName("url").item(0).textContent.trim()
                val checksum = archive.getElementsByTagName("checksum").item(0) as Element
                check(checksum.getAttribute("type") == "sha1") { "Unsupported SDK checksum" }
                check(url.matches(Regex("android-ndk-[a-zA-Z0-9.-]+-linux\\.zip"))) { "Unexpected NDK archive name" }
                return Package(REPOSITORY + url, checksum.textContent.trim())
            }
        }
        error("NDK $revision is not available in Google's SDK repository")
    }

    private fun open(url: String) = URL(url).openConnection().apply {
        connectTimeout = 30_000
        readTimeout = 120_000
    }.getInputStream()

    private fun download(pkg: Package, archive: Path) {
        fun digest(path: Path): String = Files.newInputStream(path).use { input ->
            val hash = MessageDigest.getInstance("SHA-1")
            DigestInputStream(input, hash).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (stream.read(buffer) != -1) { /* stream updates the digest */ }
            }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        if (Files.isRegularFile(archive) && digest(archive) == pkg.sha1) return
        val temp = archive.resolveSibling(archive.fileName.toString() + ".part")
        try {
            open(pkg.url).use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
            check(digest(temp) == pkg.sha1) { "NDK download checksum mismatch" }
            Files.move(temp, archive, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Extract data only; Google's desktop executables cannot run on Android. */
    internal fun extractData(archive: Path, dest: Path) {
        ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val relative = entry.name.substringAfter('/', "")
                val included = relative.startsWith("build/") || relative.startsWith("meta/") ||
                    relative.startsWith("sources/") || relative == "source.properties" || relative == "NOTICE" ||
                    relative.startsWith("${HOST}sysroot/") || relative.startsWith("${HOST}lib/clang/")
                if (!included) continue
                val out = dest.resolve(relative).normalize()
                require(out.startsWith(dest.normalize())) { "NDK archive path escapes its destination" }
                Files.createDirectories(out.parent)
                zip.getInputStream(entry).use { Files.copy(it, out, StandardCopyOption.REPLACE_EXISTING) }
                if (relative.startsWith("build/") && !relative.substringAfterLast('/').contains('.')) {
                    out.toFile().setExecutable(true)
                }
            }
        }
    }

    private fun installCompiler(root: Path, prefix: Path) {
        val version = Prefix.catalog().packages.first { it.name == "clang" }.version.substringBefore('-')
        // Android-host CMake reports Android, which Google's desktop-only host detection omits.
        // The directory retains Google's data layout; its executables are replaced below.
        for (name in listOf("android.toolchain.cmake", "android-legacy.toolchain.cmake")) {
            val script = root.resolve("build/cmake/$name")
            if (Files.isRegularFile(script)) {
                script.writeText(script.readText().replace(
                    "if(CMAKE_HOST_SYSTEM_NAME STREQUAL Linux)",
                    "if(CMAKE_HOST_SYSTEM_NAME STREQUAL Linux OR CMAKE_HOST_SYSTEM_NAME STREQUAL Android)",
                ))
            }
        }
        val host = root.resolve(HOST)
        val bin = host.resolve("bin")
        Files.createDirectories(bin)
        // Intrinsic headers encode compiler-specific builtin IDs. Mixing the NDK's older
        // Clang headers with our Android-host compiler breaks ARM NEON compilation.
        // Keep compiler resources with their compiler; --sysroot still selects NDK APIs/STL.
        val resources = prefix.resolve("lib/clang/${version.substringBefore('.')}")
        check(Files.isRegularFile(resources.resolve("include/arm_neon.h"))) {
            "The Android LLVM $version compiler resources are missing. Run build environment setup."
        }
        val ndkResources = Files.list(host.resolve("lib/clang")).use { paths ->
            paths.filter { Files.isDirectory(it) }.findFirst()
                .orElseThrow { IllegalStateException("NDK runtime libraries are missing") }
        }
        // The supported Android build ABI is arm64. Google's NDK keeps libatomic,
        // libunwind and libomp here, outside its sysroot and our compiler resources.
        val ndkLibraries = ndkResources.resolve("lib/linux/aarch64")
        check(Files.isRegularFile(ndkLibraries.resolve("libunwind.a"))) { "NDK arm64 runtime libraries are missing" }
        fun quote(path: Path) = "'" + path.toAbsolutePath().toString().replace("'", "'\\''") + "'"
        for (name in listOf("clang", "clang++")) {
            val mode = if (name == "clang++") " --driver-mode=g++" else ""
            val script = bin.resolve(name)
            Files.deleteIfExists(script)
            script.writeText(buildString {
                appendLine("#!/system/bin/sh")
                // The Android-host driver names its default runtime c++_shared even under
                // -Bstatic. Honor the NDK's static STL selection with its actual archives.
                appendLine("static_cxx=false; compile_only=false")
                appendLine("for arg do")
                appendLine("  case \"\$arg\" in")
                appendLine("    -static-libstdc++|-static) static_cxx=true ;;")
                appendLine("    -c|-S|-E|-fsyntax-only) compile_only=true ;;")
                appendLine("  esac")
                appendLine("done")
                appendLine("if ! \$compile_only; then set -- \"\$@\" -L${quote(ndkLibraries)}; fi")
                if (name == "clang++") {
                    appendLine("if \$static_cxx && ! \$compile_only; then")
                    appendLine("  set -- \"\$@\" -nostdlib++ -lc++_static -lc++abi")
                    appendLine("fi")
                }
                appendLine("exec ${quote(prefix.resolve("bin/clang"))}$mode -resource-dir ${quote(resources)} --ld-path=${quote(prefix.resolve("bin/ld.lld"))} \"\$@\"")
            })
            script.toFile().setExecutable(true)
        }
        for (name in listOf("ld.lld", "llvm-ar", "llvm-nm", "llvm-strip", "llvm-objcopy", "llvm-ranlib", "llvm-readelf")) {
            val source = prefix.resolve("bin/$name")
            check(Files.isRegularFile(source)) { "Missing Android LLVM tool $name" }
            val link = bin.resolve(name)
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, source)
        }
        root.resolve("building-host-toolchain.properties").writeText(
            "compiler=Android-host LLVM $version\nndkData=${root.fileName}\nresourceDir=${resources.fileName}\n",
        )
        root.resolve("build/cmake/compiler_id.cmake").writeText(
            listOf("ASM", "C", "CXX").joinToString("\n", postfix = "\n") {
                "set(CMAKE_${it}_COMPILER_VERSION $version)"
            },
        )
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
}
