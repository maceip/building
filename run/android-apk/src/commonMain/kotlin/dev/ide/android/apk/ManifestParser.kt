package dev.ide.android.apk

/**
 * Enough of Android's binary XML (AXML) to answer two questions the launcher asks every APK:
 * what is the package name, and which activity is the one the home screen would start.
 *
 * Resource references are left as `@0x7f...` markers — a guest that needs the real `strings.xml`
 * has not reached the point where a host stub would have to resolve it.
 */
object ManifestParser {

    data class Manifest(
        val packageName: String,
        val launchActivity: String?,
        val applicationLabel: String?,
    )

    fun parse(bytes: ByteArray): Manifest {
        if (bytes.size < 8) return Manifest("", null, null)
        // Text manifest (some test fixtures ship plaintext).
        if (bytes[0].toInt().toChar() == '<') {
            val text = bytes.decodeToString()
            val manifestTag = Regex("""<manifest\b[^>]*>""", RegexOption.DOT_MATCHES_ALL).find(text)?.value ?: ""
            val pkg = Regex("""\bpackage\s*=\s*"([^"]+)"""").find(manifestTag)?.groupValues?.get(1) ?: ""
            val appTag = Regex("""<application\b[^>]*>""", RegexOption.DOT_MATCHES_ALL).find(text)?.value ?: ""
            val appLabel = Regex("""\b(?:android:)?label\s*=\s*"([^"]+)"""").find(appTag)?.groupValues?.get(1)
            val act = findLauncherActivityFromTextXml(text)
            return Manifest(pkg, normalizeActivity(pkg, act), appLabel)
        }
        val r = LE(bytes)
        val magic = r.u16()
        if (magic != 0x0003) return Manifest("", null, null) // RES_XML_TYPE
        r.u16() // header size
        r.u32() // file size

        var strings: List<String> = emptyList()
        var packageName: String? = null
        var launchActivity: String? = null
        var applicationLabel: String? = null
        var inActivity = false
        var currentActivity: String? = null
        var sawLauncher = false

        while (r.pos + 8 <= bytes.size) {
            val chunkType = r.u16()
            val headerSize = r.u16()
            val chunkSize = r.u32().toInt()
            if (chunkSize < 8 || r.pos - 8 + chunkSize > bytes.size) break
            val chunkEnd = r.pos - 8 + chunkSize
            when (chunkType) {
                0x0001 -> { // RES_STRING_POOL_TYPE
                    strings = readStringPool(bytes, r.pos - 8)
                }
                0x0102 -> { // START_ELEMENT
                    r.u16() // line
                    r.u16() // comment
                    val ns = r.u32().toInt()
                    val name = r.u32().toInt()
                    val attrStart = r.u16()
                    val attrSize = r.u16()
                    val attrCount = r.u16()
                    r.u16() // idIndex
                    r.u16() // classIndex
                    r.u16() // styleIndex
                    val elName = str(strings, name)
                    val attrs = LinkedHashMap<String, String>()
                    val attrBase = r.pos - 8 + headerSize + (if (attrStart > 0) attrStart else 20)
                    var at = attrBase
                    repeat(attrCount) {
                        if (at + 20 > bytes.size) return@repeat
                        val ar = LE(bytes)
                        ar.pos = at
                        ar.u32() // ns
                        val aname = ar.u32().toInt()
                        val rawValue = ar.u32().toInt()
                        ar.u16() // value size
                        ar.u8() // res0
                        val dataType = ar.u8()
                        val data = ar.u32().toInt()
                        val key = str(strings, aname)
                        val value = when {
                            rawValue >= 0 -> str(strings, rawValue)
                            dataType == 0x03 -> str(strings, data)
                            dataType == 0x10 -> data.toString()
                            else -> "@0x${data.toUInt().toString(16)}"
                        }
                        attrs[key] = value
                        at += attrSize.takeIf { it > 0 } ?: 20
                    }
                    when (elName) {
                        "manifest" -> packageName = attrs["package"] ?: packageName
                        "application" -> applicationLabel = attrs["label"] ?: applicationLabel
                        "activity" -> {
                            inActivity = true
                            currentActivity = attrs["name"]
                            sawLauncher = false
                        }
                        "action" -> {
                            if (inActivity && attrs["name"] == "android.intent.action.MAIN") sawLauncher = true
                        }
                        "category" -> {
                            if (inActivity && sawLauncher && attrs["name"] == "android.intent.category.LAUNCHER") {
                                launchActivity = currentActivity
                            }
                        }
                    }
                }
                0x0103 -> { // END_ELEMENT
                    r.u16(); r.u16(); r.u32(); r.u32()
                    // activity ends when its end tag arrives; keep last launcher match
                    inActivity = false
                }
            }
            r.pos = chunkEnd
        }
        return Manifest(packageName ?: "", normalizeActivity(packageName, launchActivity), applicationLabel)
    }

    internal fun findLauncherActivityFromTextXml(text: String): String? {
        val blocks = extractXmlTagBlocks(text, "activity")
        val openTagRegex = Regex("""<activity\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
        val nameRegex = Regex("""(?:android:)?name\s*=\s*"([^"]+)"""")
        fun activityNameOf(block: String): String? {
            val header = openTagRegex.find(block)?.value ?: block
            return nameRegex.find(header)?.groupValues?.get(1)
        }

        // 1. Prefer <activity> with MAIN + LAUNCHER / LEANBACK_LAUNCHER / APP_MUSIC
        for (block in blocks) {
            if (block.contains("android.intent.action.MAIN") &&
                (block.contains("android.intent.category.LAUNCHER") ||
                    block.contains("android.intent.category.LEANBACK_LAUNCHER") ||
                    block.contains("android.intent.category.APP_MUSIC"))
            ) {
                activityNameOf(block)?.let { return it }
            }
        }
        // 2. Check <activity-alias> with MAIN + LAUNCHER targeting an activity
        val aliases = extractXmlTagBlocks(text, "activity-alias")
        val targetRegex = Regex("""(?:android:)?targetActivity\s*=\s*"([^"]+)"""")
        for (alias in aliases) {
            if (alias.contains("android.intent.action.MAIN")) {
                targetRegex.find(alias)?.groupValues?.get(1)?.let { return it }
            }
        }
        // 3. Prefer <activity> with MAIN action
        for (block in blocks) {
            if (block.contains("android.intent.action.MAIN")) {
                activityNameOf(block)?.let { return it }
            }
        }
        // 4. Prefer an activity named *MainActivity, *RouteActivity, or *WelcomeActivity
        val allNames = blocks.mapNotNull { activityNameOf(it) }
        allNames.firstOrNull {
            it.endsWith("MainActivity") || it.endsWith("RouteActivity") || it.endsWith("WelcomeActivity")
        }?.let { return it }

        return allNames.firstOrNull()
    }

    private fun extractXmlTagBlocks(xml: String, tagName: String): List<String> {
        val out = ArrayList<String>()
        val openToken = "<$tagName"
        val closeToken = "</$tagName>"
        var cursor = 0
        while (cursor < xml.length) {
            val start = xml.indexOf(openToken, cursor)
            if (start < 0) break
            val afterTag = start + openToken.length
            if (afterTag < xml.length) {
                val nextChar = xml[afterTag]
                if (!nextChar.isWhitespace() && nextChar != '>' && nextChar != '/') {
                    cursor = afterTag
                    continue
                }
            }
            val openEnd = xml.indexOf('>', afterTag)
            if (openEnd < 0) break
            // Self-closing tag: <activity ... />
            if (openEnd > start && xml[openEnd - 1] == '/') {
                out.add(xml.substring(start, openEnd + 1))
                cursor = openEnd + 1
                continue
            }
            val closeStart = xml.indexOf(closeToken, openEnd + 1)
            if (closeStart < 0) {
                out.add(xml.substring(start, openEnd + 1))
                cursor = openEnd + 1
            } else {
                val blockEnd = closeStart + closeToken.length
                out.add(xml.substring(start, blockEnd))
                cursor = blockEnd
            }
        }
        return out
    }

    internal fun normalizeActivity(pkg: String?, name: String?): String? {
        if (name.isNullOrBlank()) return null
        return when {
            name.startsWith(".") -> if (pkg.isNullOrBlank()) name.removePrefix(".") else "$pkg$name"
            name.contains('.') -> name
            else -> if (pkg.isNullOrBlank()) name else "$pkg.$name"
        }
    }

    private fun str(pool: List<String>, idx: Int): String =
        if (idx < 0 || idx >= pool.size) "" else pool[idx]

    private fun readStringPool(bytes: ByteArray, chunkAt: Int): List<String> {
        val r = LE(bytes)
        r.pos = chunkAt + 8
        val stringCount = r.u32().toInt()
        val styleCount = r.u32().toInt()
        r.u32() // flags (UTF8 flag 1<<8)
        val stringsStart = r.u32().toInt()
        r.u32() // stylesStart
        // flags is the 4th u32 after the chunk header.
        val flags = LE(bytes)
        flags.pos = chunkAt + 8 + 12
        val utf8 = (flags.u32() and 0x100) != 0L

        val offsets = IntArray(stringCount)
        val or = LE(bytes)
        or.pos = chunkAt + 8 + 28
        for (i in 0 until stringCount) offsets[i] = or.u32().toInt()

        val out = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val at = chunkAt + stringsStart + offsets[i]
            if (at < 0 || at >= bytes.size) {
                out.add("")
                continue
            }
            if (utf8) {
                var p = at
                // utf16 length (uleb or u8), then byte length
                if (bytes[p].toInt() and 0x80 != 0) p += 2 else p++
                val byteLen = if (bytes[p].toInt() and 0x80 != 0) {
                    val v = ((bytes[p].toInt() and 0x7F) shl 8) or (bytes[p + 1].toInt() and 0xFF)
                    p += 2
                    v
                } else {
                    bytes[p++].toInt() and 0xFF
                }
                out.add(bytes.decodeToString(p, p + byteLen))
            } else {
                var p = at
                var utf16Len = bytes[p].toInt() and 0xFF
                p++
                if (utf16Len and 0x80 != 0) {
                    utf16Len = ((utf16Len and 0x7F) shl 8) or (bytes[p].toInt() and 0xFF)
                    p++
                }
                val sb = StringBuilder(utf16Len)
                repeat(utf16Len) {
                    if (p + 1 < bytes.size) {
                        sb.append(((bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)).toChar())
                        p += 2
                    }
                }
                out.add(sb.toString())
            }
        }
        // `isUtf8` is unused: the same flag is re-read into `utf8` below (the first read was at the wrong offset).
        return out
    }

    private class LE(val bytes: ByteArray) {
        var pos = 0
        fun u8(): Int = bytes[pos++].toInt() and 0xFF
        fun u16(): Int {
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return v
        }
        fun u32(): Long {
            val v = (u16().toLong() and 0xFFFF) or ((u16().toLong() and 0xFFFF) shl 16)
            return v
        }
    }
}
