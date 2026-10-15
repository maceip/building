package dev.ide.android.workbench.runtime

import android.content.Context
import dalvik.system.BaseDexClassLoader
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Resolves only signed base/feature assets and native libraries installed by Play or Android. */
internal object RuntimePacks {
    const val NODE_MODULE = "developer_runtime_node"
    data class Part(val root: String, val manifest: JSONObject)

    fun catalog(context: Context): JSONObject? = try {
        read(context, "developer-runtime/delivery.json")
    } catch (_: java.io.FileNotFoundException) { null }

    fun context(context: Context): Context = context.createPackageContext(context.packageName, 0).also {
        com.google.android.play.core.splitcompat.SplitCompat.install(it)
    }

    fun nodeInstalled(context: Context): Boolean {
        val expected = catalog(context) ?: return true
        return try {
            read(context(context), "developer-runtime-node/manifest.json").getString("id") == expected.getString("id")
        } catch (_: java.io.IOException) { false }
    }

    fun parts(context: Context): List<Part> {
        val base = Part("developer-runtime", read(context, "developer-runtime/manifest.json"))
        val expected = catalog(context) ?: return listOf(base)
        val node = try { read(context, "developer-runtime-node/manifest.json") }
            catch (_: java.io.FileNotFoundException) { return listOf(base) }
        // During an app update a stale feature must not disable Python/shell or be executed.
        if (node.getString("id") != expected.getString("id") || node.getString("sourcePackId") != base.manifest.getString("sourcePackId")) return listOf(base)
        return listOf(base, Part("developer-runtime-node", node))
    }

    fun nativeLibrary(context: Context, filename: String): Path {
        val base = Paths.get(context.applicationInfo.nativeLibraryDir).resolve(filename)
        if (Files.isRegularFile(base)) return base
        // SplitCompat adds verified, extracted split libraries to the application class loader.
        val loader = context.applicationContext.classLoader as? BaseDexClassLoader
        val found = loader?.findLibrary(filename.removePrefix("lib").removeSuffix(".so"))
        return found?.let(Paths::get) ?: base
    }

    private fun read(context: Context, path: String): JSONObject = context.assets.open(path).use {
        JSONObject(it.bufferedReader().readText())
    }
}
