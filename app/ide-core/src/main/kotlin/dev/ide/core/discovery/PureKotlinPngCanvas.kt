package dev.ide.core.discovery

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Self-contained pure-Kotlin 2D software rasterizer and standard RGBA PNG encoder.
 *
 * Works identically across Android ART, Desktop JVM, and build-time Gradle tasks with zero
 * AWT (`BufferedImage`) or Android framework (`android.graphics.Bitmap`) dependencies.
 * Powers:
 * 1. Local GUI `ViewNode` tree rasterization (SwiftUI Cupertino Bridge & NodeGUI windows).
 * 2. Local Compose `@Preview` / `@Composable` & Android XML layout preview rasterization.
 * 3. Dynamic source-code-driven visual preview synthesis (when a repository has no screenshot).
 */
class PureKotlinPngCanvas(
    val width: Int,
    val height: Int,
    backgroundColor: Int = 0xFF0F172A.toInt()
) {
    val pixels = IntArray(width * height) { backgroundColor }

    fun clear(argb: Int) {
        pixels.fill(argb)
    }

    fun blendPixel(x: Int, y: Int, srcArgb: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val sa = (srcArgb ushr 24) and 0xFF
        if (sa == 0) return
        val idx = y * width + x
        if (sa == 255) {
            pixels[idx] = srcArgb
            return
        }
        val dst = pixels[idx]
        val sr = (srcArgb ushr 16) and 0xFF
        val sg = (srcArgb ushr 8) and 0xFF
        val sb = srcArgb and 0xFF

        val dr = (dst ushr 16) and 0xFF
        val dg = (dst ushr 8) and 0xFF
        val db = dst and 0xFF

        val invA = 255 - sa
        val or = (sr * sa + dr * invA) / 255
        val og = (sg * sa + dg * invA) / 255
        val ob = (sb * sa + db * invA) / 255
        pixels[idx] = (0xFF shl 24) or (or shl 16) or (og shl 8) or ob
    }

    fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        val x0 = max(0, x)
        val y0 = max(0, y)
        val x1 = min(width, x + w)
        val y1 = min(height, y + h)
        for (py in y0 until y1) {
            for (px in x0 until x1) {
                blendPixel(px, py, argb)
            }
        }
    }

    fun fillVerticalGradient(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        topColor: Int,
        bottomColor: Int
    ) {
        fillLinearGradient(x, y, w, h, topColor, bottomColor, diagonal = false)
    }

    fun drawHorizontalLine(x0: Int, x1: Int, y: Int, argb: Int) {
        drawLine(x0, y, x1, y, 1, argb)
    }

    fun fillLinearGradient(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        topColor: Int,
        bottomColor: Int,
        diagonal: Boolean = false
    ) {
        val x0 = max(0, x)
        val y0 = max(0, y)
        val x1 = min(width, x + w)
        val y1 = min(height, y + h)
        val spanY = max(1, h - 1)
        val spanX = max(1, w - 1)
        for (py in y0 until y1) {
            for (px in x0 until x1) {
                val t = if (diagonal) {
                    (((py - y).toFloat() / spanY) * 0.65f + ((px - x).toFloat() / spanX) * 0.35f).coerceIn(0f, 1f)
                } else {
                    ((py - y).toFloat() / spanY).coerceIn(0f, 1f)
                }
                blendPixel(px, py, lerpColor(topColor, bottomColor, t))
            }
        }
    }

    fun fillRoundedRect(x: Int, y: Int, w: Int, h: Int, radius: Int, argb: Int) {
        if (w <= 0 || h <= 0) return
        val r = radius.coerceIn(0, min(w, h) / 2)
        if (r <= 1) {
            fillRect(x, y, w, h, argb)
            return
        }
        val x0 = max(0, x)
        val y0 = max(0, y)
        val x1 = min(width, x + w)
        val y1 = min(height, y + h)
        val rf = r.toFloat()

        for (py in y0 until y1) {
            for (px in x0 until x1) {
                val lx = px - x
                val ly = py - y
                val cx = when {
                    lx < r -> r - lx - 0.5f
                    lx >= w - r -> lx - (w - r - 1) - 0.5f
                    else -> 0f
                }
                val cy = when {
                    ly < r -> r - ly - 0.5f
                    ly >= h - r -> ly - (h - r - 1) - 0.5f
                    else -> 0f
                }
                if (cx <= 0f && cy <= 0f) {
                    blendPixel(px, py, argb)
                } else {
                    val dist = sqrt(cx * cx + cy * cy)
                    if (dist <= rf - 0.5f) {
                        blendPixel(px, py, argb)
                    } else if (dist < rf + 0.5f) {
                        val cov = (rf + 0.5f - dist).coerceIn(0f, 1f)
                        blendPixel(px, py, withAlphaFactor(argb, cov))
                    }
                }
            }
        }
    }

    fun strokeRoundedRect(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Int,
        strokeWidth: Int,
        argb: Int
    ) {
        if (w <= 0 || h <= 0 || strokeWidth <= 0) return
        val r = radius.coerceIn(0, min(w, h) / 2)
        val x0 = max(0, x)
        val y0 = max(0, y)
        val x1 = min(width, x + w)
        val y1 = min(height, y + h)
        val outerR = r.toFloat()
        val innerR = max(0f, (r - strokeWidth).toFloat())

        for (py in y0 until y1) {
            for (px in x0 until x1) {
                val lx = px - x
                val ly = py - y
                val inInnerBox = lx >= strokeWidth && lx < w - strokeWidth &&
                    ly >= strokeWidth && ly < h - strokeWidth
                if (r <= 1) {
                    if (!inInnerBox) blendPixel(px, py, argb)
                    continue
                }
                val cx = when {
                    lx < r -> r - lx - 0.5f
                    lx >= w - r -> lx - (w - r - 1) - 0.5f
                    else -> 0f
                }
                val cy = when {
                    ly < r -> r - ly - 0.5f
                    ly >= h - r -> ly - (h - r - 1) - 0.5f
                    else -> 0f
                }
                if (cx > 0f && cy > 0f) {
                    val dist = sqrt(cx * cx + cy * cy)
                    if (dist in innerR..outerR) {
                        blendPixel(px, py, argb)
                    }
                } else if (!inInnerBox) {
                    blendPixel(px, py, argb)
                }
            }
        }
    }

    fun fillCircle(cx: Int, cy: Int, radius: Int, argb: Int) {
        if (radius <= 0) return
        val rf = radius.toFloat()
        for (py in max(0, cy - radius - 1)..min(height - 1, cy + radius + 1)) {
            for (px in max(0, cx - radius - 1)..min(width - 1, cx + radius + 1)) {
                val dx = px - cx
                val dy = py - cy
                val dist = sqrt((dx * dx + dy * dy).toFloat())
                if (dist <= rf - 0.5f) {
                    blendPixel(px, py, argb)
                } else if (dist < rf + 0.5f) {
                    blendPixel(px, py, withAlphaFactor(argb, (rf + 0.5f - dist).coerceIn(0f, 1f)))
                }
            }
        }
    }

    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, thickness: Int, argb: Int) {
        val steps = max(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
        val half = max(0, thickness / 2)
        for (i in 0..steps) {
            val px = x0 + (x1 - x0) * i / steps
            val py = y0 + (y1 - y0) * i / steps
            for (dy in -half..half) {
                for (dx in -half..half) {
                    blendPixel(px + dx, py + dy, argb)
                }
            }
        }
    }

    fun measureTextWidth(text: String, scale: Int = 2): Int {
        if (text.isEmpty()) return 0
        val s = scale.coerceAtLeast(1)
        return text.length * (6 * s) - s
    }

    /**
     * Renders crisp 5x7 bitmap font characters at integer [scale].
     */
    fun drawText(
        text: String,
        x: Int,
        y: Int,
        argb: Int,
        scale: Int = 2,
        maxWidth: Int = width - x
    ) {
        val s = scale.coerceAtLeast(1)
        val charAdvance = 6 * s
        var cursorX = x
        val limitX = x + maxWidth
        val clean = sanitizeAscii(text)
        for (ch in clean) {
            if (cursorX + 5 * s > limitX) break
            val glyph = Font5x7.glyphFor(ch)
            for (row in 0 until 7) {
                val rowBits = glyph[row]
                if (rowBits == 0) continue
                for (col in 0 until 5) {
                    if ((rowBits and (1 shl (4 - col))) != 0) {
                        fillRect(cursorX + col * s, y + row * s, s, s, argb)
                    }
                }
            }
            cursorX += charAdvance
        }
    }

    fun drawBadge(
        label: String,
        x: Int,
        y: Int,
        bgColor: Int,
        textColor: Int,
        scale: Int = 1,
        padX: Int = 7,
        padY: Int = 5,
        radius: Int = 6
    ): Int {
        val textW = measureTextWidth(label, scale)
        val textH = 7 * scale
        val boxW = textW + padX * 2
        val boxH = textH + padY * 2
        fillRoundedRect(x, y, boxW, boxH, radius, bgColor)
        drawText(label, x + padX, y + padY, textColor, scale = scale, maxWidth = textW + 4)
        return boxW
    }

    /**
     * Encodes the current [pixels] buffer into a standard, lossless RGBA PNG [ByteArray].
     */
    fun encodePng(): ByteArray {
        val rawScanlines = ByteArray((width * 4 + 1) * height)
        var outIdx = 0
        for (y in 0 until height) {
            rawScanlines[outIdx++] = 0 // Filter type 0 (None)
            val rowOffset = y * width
            for (x in 0 until width) {
                val argb = pixels[rowOffset + x]
                rawScanlines[outIdx++] = ((argb ushr 16) and 0xFF).toByte() // R
                rawScanlines[outIdx++] = ((argb ushr 8) and 0xFF).toByte()  // G
                rawScanlines[outIdx++] = (argb and 0xFF).toByte()           // B
                rawScanlines[outIdx++] = ((argb ushr 24) and 0xFF).toByte() // A
            }
        }

        val compressed = deflate(rawScanlines)
        val baos = ByteArrayOutputStream(compressed.size + 128)
        val dos = DataOutputStream(baos)

        // PNG 8-byte signature
        dos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        // IHDR chunk
        val ihdr = ByteArrayOutputStream(13)
        val ihdrOut = DataOutputStream(ihdr)
        ihdrOut.writeInt(width)
        ihdrOut.writeInt(height)
        ihdrOut.writeByte(8) // Bit depth: 8
        ihdrOut.writeByte(6) // Color type: 6 (Truecolor with alpha RGBA)
        ihdrOut.writeByte(0) // Compression: deflate
        ihdrOut.writeByte(0) // Filter: adaptive
        ihdrOut.writeByte(0) // Interlace: none
        writeChunk(dos, "IHDR", ihdr.toByteArray())

        // IDAT chunk
        writeChunk(dos, "IDAT", compressed)

        // IEND chunk
        writeChunk(dos, "IEND", ByteArray(0))

        dos.flush()
        return baos.toByteArray()
    }

    private fun writeChunk(dos: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        dos.writeInt(data.size)
        dos.write(typeBytes)
        dos.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        dos.writeInt(crc.value.toInt())
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream(input.size / 2 + 64)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            if (count > 0) out.write(buf, 0, count) else break
        }
        deflater.end()
        return out.toByteArray()
    }

    companion object {
        fun lerpColor(c0: Int, c1: Int, t: Float): Int {
            val a0 = (c0 ushr 24) and 0xFF
            val r0 = (c0 ushr 16) and 0xFF
            val g0 = (c0 ushr 8) and 0xFF
            val b0 = c0 and 0xFF

            val a1 = (c1 ushr 24) and 0xFF
            val r1 = (c1 ushr 16) and 0xFF
            val g1 = (c1 ushr 8) and 0xFF
            val b1 = c1 and 0xFF

            val a = (a0 + ((a1 - a0) * t).toInt()).coerceIn(0, 255)
            val r = (r0 + ((r1 - r0) * t).toInt()).coerceIn(0, 255)
            val g = (g0 + ((g1 - g0) * t).toInt()).coerceIn(0, 255)
            val b = (b0 + ((b1 - b0) * t).toInt()).coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun withAlphaFactor(argb: Int, factor: Float): Int {
            val a = (((argb ushr 24) and 0xFF) * factor).toInt().coerceIn(0, 255)
            return (argb and 0x00FFFFFF) or (a shl 24)
        }

        fun withAlpha(rgb: Int, alpha: Int): Int {
            return (rgb and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
        }

        private fun sanitizeAscii(input: String): String {
            val sb = StringBuilder(input.length)
            for (c in input) {
                when (c) {
                    '\u2605', '\u2B50' -> sb.append('*')
                    '\u2022', '\u00B7' -> sb.append('.')
                    '\u2014', '\u2013' -> sb.append('-')
                    '\u2192' -> sb.append("->")
                    '\u2713', '\u2714' -> sb.append('v')
                    in ' '..'~' -> sb.append(c)
                    else -> sb.append(' ')
                }
            }
            return sb.toString()
        }
    }
}

private object Font5x7 {
    private val SPACE = intArrayOf(0, 0, 0, 0, 0, 0, 0)

    private val GLYPHS: Map<Char, IntArray> = mapOf(
        ' ' to SPACE,
        'A' to intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
        'B' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110),
        'C' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110),
        'D' to intArrayOf(0b11100, 0b10010, 0b10001, 0b10001, 0b10001, 0b10010, 0b11100),
        'E' to intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111),
        'F' to intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000),
        'G' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01110),
        'H' to intArrayOf(0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
        'I' to intArrayOf(0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
        'J' to intArrayOf(0b00111, 0b00010, 0b00010, 0b00010, 0b10010, 0b10010, 0b01100),
        'K' to intArrayOf(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001),
        'L' to intArrayOf(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111),
        'M' to intArrayOf(0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001),
        'N' to intArrayOf(0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001),
        'O' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
        'P' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000),
        'Q' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101),
        'R' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001),
        'S' to intArrayOf(0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110),
        'T' to intArrayOf(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100),
        'U' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
        'V' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100),
        'W' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001),
        'X' to intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001),
        'Y' to intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100),
        'Z' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111),
        'a' to intArrayOf(0b00000, 0b00000, 0b01110, 0b00001, 0b01111, 0b10001, 0b01111),
        'b' to intArrayOf(0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b10001, 0b11110),
        'c' to intArrayOf(0b00000, 0b00000, 0b01110, 0b10000, 0b10000, 0b10001, 0b01110),
        'd' to intArrayOf(0b00001, 0b00001, 0b01111, 0b10001, 0b10001, 0b10001, 0b01111),
        'e' to intArrayOf(0b00000, 0b00000, 0b01110, 0b10001, 0b11111, 0b10000, 0b01110),
        'f' to intArrayOf(0b00110, 0b01001, 0b01000, 0b11100, 0b01000, 0b01000, 0b01000),
        'g' to intArrayOf(0b00000, 0b01111, 0b10001, 0b10001, 0b01111, 0b00001, 0b01110),
        'h' to intArrayOf(0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b10001, 0b10001),
        'i' to intArrayOf(0b00100, 0b00000, 0b01100, 0b00100, 0b00100, 0b00100, 0b01110),
        'j' to intArrayOf(0b00010, 0b00000, 0b00110, 0b00010, 0b00010, 0b10010, 0b01100),
        'k' to intArrayOf(0b10000, 0b10000, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010),
        'l' to intArrayOf(0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
        'm' to intArrayOf(0b00000, 0b00000, 0b11010, 0b10101, 0b10101, 0b10001, 0b10001),
        'n' to intArrayOf(0b00000, 0b00000, 0b11110, 0b10001, 0b10001, 0b10001, 0b10001),
        'o' to intArrayOf(0b00000, 0b00000, 0b01110, 0b10001, 0b10001, 0b10001, 0b01110),
        'p' to intArrayOf(0b00000, 0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000),
        'q' to intArrayOf(0b00000, 0b01111, 0b10001, 0b10001, 0b01111, 0b00001, 0b00001),
        'r' to intArrayOf(0b00000, 0b00000, 0b10110, 0b11001, 0b10000, 0b10000, 0b10000),
        's' to intArrayOf(0b00000, 0b00000, 0b01111, 0b10000, 0b01110, 0b00001, 0b11110),
        't' to intArrayOf(0b01000, 0b01000, 0b11100, 0b01000, 0b01000, 0b01001, 0b00110),
        'u' to intArrayOf(0b00000, 0b00000, 0b10001, 0b10001, 0b10001, 0b10011, 0b01101),
        'v' to intArrayOf(0b00000, 0b00000, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100),
        'w' to intArrayOf(0b00000, 0b00000, 0b10001, 0b10001, 0b10101, 0b10101, 0b01010),
        'x' to intArrayOf(0b00000, 0b00000, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001),
        'y' to intArrayOf(0b00000, 0b10001, 0b10001, 0b10001, 0b01111, 0b00001, 0b01110),
        'z' to intArrayOf(0b00000, 0b00000, 0b11111, 0b00010, 0b00100, 0b01000, 0b11111),
        '0' to intArrayOf(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110),
        '1' to intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
        '2' to intArrayOf(0b01110, 0b10001, 0b00001, 0b00110, 0b01000, 0b10000, 0b11111),
        '3' to intArrayOf(0b11110, 0b00001, 0b00001, 0b01110, 0b00001, 0b00001, 0b11110),
        '4' to intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010),
        '5' to intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110),
        '6' to intArrayOf(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110),
        '7' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000),
        '8' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110),
        '9' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100),
        '.' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100),
        ',' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00110, 0b00100, 0b01000),
        ':' to intArrayOf(0b00000, 0b01100, 0b01100, 0b00000, 0b01100, 0b01100, 0b00000),
        ';' to intArrayOf(0b00000, 0b00110, 0b00110, 0b00000, 0b00110, 0b00100, 0b01000),
        '-' to intArrayOf(0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000),
        '_' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b11111),
        '+' to intArrayOf(0b00000, 0b00100, 0b00100, 0b11111, 0b00100, 0b00100, 0b00000),
        '=' to intArrayOf(0b00000, 0b00000, 0b11111, 0b00000, 0b11111, 0b00000, 0b00000),
        '/' to intArrayOf(0b00001, 0b00010, 0b00010, 0b00100, 0b01000, 0b01000, 0b10000),
        '(' to intArrayOf(0b00010, 0b00100, 0b01000, 0b01000, 0b01000, 0b00100, 0b00010),
        ')' to intArrayOf(0b01000, 0b00100, 0b00010, 0b00010, 0b00010, 0b00100, 0b01000),
        '[' to intArrayOf(0b01110, 0b01000, 0b01000, 0b01000, 0b01000, 0b01000, 0b01110),
        ']' to intArrayOf(0b01110, 0b00010, 0b00010, 0b00010, 0b00010, 0b00010, 0b01110),
        '{' to intArrayOf(0b00110, 0b00100, 0b00100, 0b01000, 0b00100, 0b00100, 0b00110),
        '}' to intArrayOf(0b01100, 0b00100, 0b00100, 0b00010, 0b00100, 0b00100, 0b01100),
        '<' to intArrayOf(0b00010, 0b00100, 0b01000, 0b10000, 0b01000, 0b00100, 0b00010),
        '>' to intArrayOf(0b01000, 0b00100, 0b00010, 0b00001, 0b00010, 0b00100, 0b01000),
        '@' to intArrayOf(0b01110, 0b10001, 0b10111, 0b10101, 0b10111, 0b10000, 0b01110),
        '#' to intArrayOf(0b01010, 0b01010, 0b11111, 0b01010, 0b11111, 0b01010, 0b01010),
        '$' to intArrayOf(0b00100, 0b01111, 0b10100, 0b01110, 0b00101, 0b11110, 0b00100),
        '%' to intArrayOf(0b11001, 0b11010, 0b00010, 0b00100, 0b01000, 0b01011, 0b10011),
        '&' to intArrayOf(0b01100, 0b10010, 0b10100, 0b01000, 0b10101, 0b10010, 0b01101),
        '*' to intArrayOf(0b00000, 0b00100, 0b10101, 0b01110, 0b10101, 0b00100, 0b00000),
        '!' to intArrayOf(0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00000, 0b00100),
        '?' to intArrayOf(0b01110, 0b10001, 0b00001, 0b00110, 0b00100, 0b00000, 0b00100),
        '"' to intArrayOf(0b01010, 0b01010, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000),
        '\'' to intArrayOf(0b00100, 0b00100, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000),
        '|' to intArrayOf(0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100)
    )

    fun glyphFor(ch: Char): IntArray = GLYPHS[ch] ?: GLYPHS[ch.uppercaseChar()] ?: SPACE
}
