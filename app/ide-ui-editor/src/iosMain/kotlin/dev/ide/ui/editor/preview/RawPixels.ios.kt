@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ide.ui.editor.preview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile

actual fun imageFromRgba(pixels: ByteArray, width: Int, height: Int): ImageBitmap? = runCatching {
    if (pixels.size >= 4 && pixels[0] == 0x89.toByte() && pixels[1] == 0x50.toByte() && pixels[2] == 0x4E.toByte() && pixels[3] == 0x47.toByte()) {
        Image.makeFromEncoded(pixels).toComposeImageBitmap()
    } else {
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        Image.makeRaster(info, pixels, width * 4).toComposeImageBitmap()
    }
}.getOrNull()

/**
 * Reads a frame produced by an interpreted program or runner on iOS and deletes the file.
 */
actual fun readRunFrame(path: String): ByteArray? = runCatching {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val size = data.length.toInt()
    if (size == 0) return null
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
    }
    NSFileManager.defaultManager.removeItemAtPath(path, null)
    bytes
}.getOrNull()
