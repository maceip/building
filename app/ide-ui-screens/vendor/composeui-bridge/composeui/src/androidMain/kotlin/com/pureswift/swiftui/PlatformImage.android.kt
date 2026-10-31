package com.pureswift.swiftui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

object LocalAssetResolver {
    val searchPaths = java.util.concurrent.CopyOnWriteArrayList<File>()

    fun addSearchPath(file: File) {
        if (!searchPaths.contains(file)) {
            searchPaths.add(file)
        }
    }

    fun findAssetFile(name: String, baseDirs: List<File>): File? {
        val cleanName = name.substringBeforeLast('.')
        val directFile = File(name)
        if (directFile.isFile) return directFile

        val allDirs = (searchPaths + baseDirs).filter { it.exists() }.distinct()
        for (dir in allDirs) {
            val candidates = listOf(
                File(dir, name),
                File(dir, "$name.png"),
                File(dir, "$name.jpg"),
                File(dir, "$name.jpeg"),
                File(dir, "$name.webp"),
                File(dir, "$name@2x.png"),
                File(dir, "$name@3x.png"),
            )
            for (cand in candidates) {
                if (cand.isFile) return cand
            }
            val found = findInDirectory(dir, cleanName)
            if (found != null) return found
        }
        return null
    }

    private fun findInDirectory(dir: File, assetName: String, depth: Int = 0): File? {
        if (depth > 6 || !dir.isDirectory) return null
        val files = dir.listFiles() ?: return null
        // 1. Check for <assetName>.imageset directory in xcassets
        for (f in files) {
            if (f.isDirectory && f.name.equals("$assetName.imageset", ignoreCase = true)) {
                val img = f.listFiles { _, n ->
                    val lower = n.lowercase()
                    lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")
                }?.maxByOrNull { it.length() }
                if (img != null && img.isFile) return img
            }
        }
        // 2. Check for exact name match in this directory
        for (f in files) {
            if (f.isFile) {
                val base = f.nameWithoutExtension.removeSuffix("@2x").removeSuffix("@3x")
                if (base.equals(assetName, ignoreCase = true)) {
                    val lower = f.extension.lowercase()
                    if (lower in setOf("png", "jpg", "jpeg", "webp")) return f
                }
            }
        }
        // 3. Recurse into subdirectories (skip hidden/build dirs)
        for (f in files) {
            if (f.isDirectory && !f.name.startsWith(".") && f.name != "build") {
                val found = findInDirectory(f, assetName, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }
}

// Asset images come from the app's drawable resources, looked up by name,
// or from local asset catalogs (.xcassets) in the active project / shadow workspace.
@Composable
internal actual fun rememberAssetPainter(name: String): Painter? {
    val context = LocalContext.current
    return remember(name) {
        val id = runCatching {
            context.resources.getIdentifier(name, "drawable", context.packageName)
        }.getOrDefault(0)

        val baseDirs = mutableListOf<File>()
        context.getExternalFilesDir(null)?.let { baseDirs.add(it) }
        context.filesDir?.let { baseDirs.add(it) }
        File("/sdcard/Android/data/${context.packageName}/files").takeIf { it.exists() }?.let { baseDirs.add(it) }
        File("/sdcard/Download").takeIf { it.exists() }?.let { baseDirs.add(it) }

        val assetFile = LocalAssetResolver.findAssetFile(name, baseDirs)
        if (assetFile != null && assetFile.isFile) {
            runCatching {
                BitmapFactory.decodeFile(assetFile.absolutePath)?.asImageBitmap()?.let {
                    BitmapPainter(it)
                }
            }.getOrNull()
        } else if (id != 0) {
            runCatching {
                BitmapFactory.decodeResource(context.resources, id)?.asImageBitmap()?.let {
                    BitmapPainter(it)
                }
            }.getOrNull()
        } else {
            null
        }
    }
}

internal actual suspend fun loadRemoteImage(url: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = fetchImageBytes(url)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
