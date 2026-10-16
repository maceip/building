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
    const val XTOOL_MODULE = "developer_runtime_xtool"
    data class Part(val root: String, val manifest: JSONObject)

    fun catalog(context: Context): JSONObject? = try {
        read(context, "developer-runtime/delivery.json")
    } catch (_: java.io.FileNotFoundException) { null }

    fun context(context: Context): Context = context.createPackageContext(context.packageName, 0).also {
        com.google.android.play.core.splitcompat.SplitCompat.install(it)
    }

    fun catalogs(context: Context): List<JSONObject> {
        val catalog = catalog(context) ?: return emptyList()
        val modules = catalog.optJSONArray("modules") ?: return listOf(catalog)
        return (0 until modules.length()).map { modules.getJSONObject(it) }
    }

    fun nodeInstalled(context: Context): Boolean = installed(context, NODE_MODULE)
    fun xtoolInstalled(context: Context): Boolean = installed(context, XTOOL_MODULE)

    fun requestModules(context: Context, module: String): List<String> {
        val entry = catalogs(context).firstOrNull { it.getString("module") == module } ?: return listOf(module)
        val modules = entry.optJSONArray("requestModules") ?: return listOf(module)
        return (0 until modules.length()).map { modules.getString(it) }
    }

    fun installed(context: Context, module: String): Boolean {
        if (catalog(context) == null) return true
        val entries = catalogs(context).associateBy { it.getString("module") }
        val installedContext = context(context)
        return requestModules(context, module).all { requested ->
            val expected = entries[requested] ?: return@all false
            try {
                val actual = read(installedContext, expected.getString("assetRoot") + "/manifest.json")
                actual.getString("id") == expected.getString("id") && actual.getString("sourcePackId") == expected.getString("sourcePackId")
            } catch (_: java.io.IOException) { false }
        }
    }

    fun parts(context: Context): List<Part> {
        val base = Part("developer-runtime", read(context, "developer-runtime/manifest.json"))
        // During an app update a stale feature must not disable Python/shell or be executed.
        val available = catalogs(context).mapNotNull { expected ->
            val root = expected.getString("assetRoot")
            val actual = try { read(context, "$root/manifest.json") }
                catch (_: java.io.FileNotFoundException) { return@mapNotNull null }
            if (actual.getString("id") != expected.getString("id") || actual.getString("sourcePackId") != base.manifest.getString("sourcePackId")) null
            else expected.getString("module") to Part(root, actual)
        }.toMap()
        // A multi-module compiler becomes usable atomically, never with half its resources.
        return listOf(base) + available.filterKeys { module -> requestModules(context, module).all { it in available } }.values
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
