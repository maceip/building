package ai.building.loader.node

import ai.building.loader.RuntimeFeature
import android.content.Context
import com.google.android.play.core.splitcompat.SplitCompat
import dalvik.system.BaseDexClassLoader
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Real native runtime, loaded only after the signed optional feature is installed. */
class NodeRuntime : RuntimeFeature {
    override fun run(context: Context, source: String): String {
        val fresh = context.createPackageContext(context.packageName, 0)
        SplitCompat.install(fresh)
        val manifest = fresh.assets.open("node-runtime/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val native = File(context.applicationInfo.nativeLibraryDir)
        val prefix = File(context.filesDir, "node-prefix").apply { mkdirs() }
        val loader = context.applicationContext.classLoader as BaseDexClassLoader
        val records = manifest.getJSONArray("native")
        var node: File? = null
        val splitDirs = mutableSetOf<String>()
        for (i in 0 until records.length()) {
            val record = records.getJSONObject(i)
            val name = record.getString("library")
            val binary = File(native, name).takeIf { it.isFile }
                ?: loader.findLibrary(name.removePrefix("lib").removeSuffix(".so"))?.let(::File)
                ?: error("Installed runtime file missing: $name")
            val hash = binary.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            check(hash == record.getString("sha256")) { "Runtime integrity check failed" }
            val path = record.getString("path")
            require(path.startsWith("lib/") || path == "bin/node")
            val link = File(prefix, path)
            check(link.toPath().normalize().startsWith(prefix.toPath()))
            link.parentFile!!.mkdirs()
            Files.deleteIfExists(link.toPath())
            Files.createSymbolicLink(link.toPath(), binary.toPath())
            splitDirs += binary.parent!!
            if (path == "bin/node") node = binary
        }
        val program = File(context.filesDir, "editor-run.js").apply { writeText(source) }
        val output = File(context.cacheDir, "node-output.txt")
        val binary = checkNotNull(node)
        val launcher = File(native, "libdevruntime-node.so")
        val process = ProcessBuilder(launcher.path, program.path)
            .directory(context.filesDir)
            .redirectErrorStream(true).redirectOutput(output)
            .apply {
                environment().clear()
                environment().putAll(mapOf(
                    "HOME" to context.filesDir.path, "TMPDIR" to context.cacheDir.path,
                    "PATH" to "/system/bin", "LANG" to "C.UTF-8",
                    "LD_LIBRARY_PATH" to File(prefix, "lib").path,
                    "LD_PRELOAD" to File(native, "libsdk-exec-prefix.so").path,
                    "TERMUX__PREFIX" to prefix.path,
                    "BUILDING_RUNTIME_NODE_BINARY" to binary.path,
                    "BUILDING_RUNTIME_NODE_LAUNCHER" to launcher.path,
                    "BUILDING_RUNTIME_NATIVE_DIR" to native.path,
                    "BUILDING_RUNTIME_SPLIT_NATIVE_DIRS" to splitDirs.joinToString(":"),
                    "TERMUX_APP__DATA_DIR" to context.dataDir.path,
                    "TERMUX_APP__LEGACY_DATA_DIR" to context.dataDir.path.replaceFirst("/data/user/0/", "/data/data/")
                ))
            }.start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Program exceeded the prototype's 15-second limit")
        }
        val text = output.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            String(buffer, 0, size, Charsets.UTF_8)
        }
        return "Exit ${process.exitValue()}\n$text"
    }
}
