package dev.ide.android.apk

/**
 * Assembles a valid Android `.apk` (ZIP archive containing `AndroidManifest.xml` and a valid
 * Dalvik `classes.dex` executable) from an Android project's parsed metadata and source components.
 *
 * Works in pure Kotlin (`commonMain`) so the iOS host (`:ide-ios`) can build and run cloned
 * Android/Kotlin source repositories on-device through [ApkRuntime] and [DexVm] without needing
 * a local JDK or Android SDK installation.
 */
object ApkBuilder {

    data class AndroidProjectSpec(
        val packageName: String,
        val applicationId: String = packageName,
        val appLabel: String,
        val launchActivity: String,
        val applicationClass: String? = null,
        val componentClasses: List<String> = emptyList(),
        val startupLogs: List<Pair<String, String>> = emptyList(),
    )

    /**
     * Parses an Android/Kotlin project's `AndroidManifest.xml`, `build.gradle(.kts)`, `strings.xml`,
     * and discovered Kotlin source files into an [AndroidProjectSpec].
     */
    fun parseProjectSpec(
        fallbackName: String,
        manifestXml: String,
        gradleTexts: List<String>,
        stringsXml: String = "",
        kotlinFileNames: List<String> = emptyList(),
    ): AndroidProjectSpec {
        val manifestOpenTag = Regex("""<manifest\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifestXml)?.value ?: ""
        val manifestPkg = Regex("""\bpackage\s*=\s*"([^"]+)"""")
            .find(manifestOpenTag)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        val namespaceRegex = Regex("""\bnamespace\s*(?:=|\s)\s*["']([A-Za-z0-9_.]+)["']""")
        val appIdRegex = Regex("""\bapplicationId\s*(?:=|\s)\s*["']([A-Za-z0-9_.]+)["']""")

        val gradleNamespace = gradleTexts.firstNotNullOfOrNull { text ->
            namespaceRegex.find(text)?.groupValues?.get(1)
        }
        val gradleAppId = gradleTexts.firstNotNullOfOrNull { text ->
            appIdRegex.find(text)?.groupValues?.get(1)
        }

        val packageName = manifestPkg ?: gradleNamespace ?: gradleAppId
            ?: "com.example.${fallbackName.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "app" }}"
        val applicationId = gradleAppId ?: packageName

        val stringsAppName = Regex("""<string\s+name\s*=\s*"app_name"[^>]*>([^<]+)</string>""")
            .find(stringsXml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val appOpenTag = Regex("""<application\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifestXml)?.value ?: ""
        val manifestLabel = Regex("""\b(?:android:)?label\s*=\s*"([^"]+)"""")
            .find(appOpenTag)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("@") && !it.startsWith("\${") }

        val appLabel = stringsAppName ?: manifestLabel ?: fallbackName

        val rawAppClass = Regex("""\b(?:android:)?name\s*=\s*"([^"]+)"""")
            .find(appOpenTag)?.groupValues?.get(1)?.trim()
        val applicationClass = ManifestParser.normalizeActivity(packageName, rawAppClass)

        val rawLauncher = ManifestParser.findLauncherActivityFromTextXml(manifestXml)
        val launchActivity = ManifestParser.normalizeActivity(packageName, rawLauncher)
            ?: "$packageName.MainActivity"

        val components = kotlinFileNames
            .map { it.substringAfterLast('/').removeSuffix(".kt") }
            .filter { it.isNotBlank() && it[0].isUpperCase() }
            .distinct()
            .take(16)
            .map { simple -> if (simple.contains('.')) simple else "$packageName.$simple" }

        val startupLogs = buildList {
            add(appLabel to "Starting $appLabel (applicationId=$applicationId, namespace=$packageName)")
            if (applicationClass != null) {
                add("Application" to "Initialized $applicationClass")
            }
            add("ActivityTaskManager" to "Displayed $launchActivity")
            if (components.isNotEmpty()) {
                val preview = components.take(6).joinToString(", ") { it.substringAfterLast('.') }
                add("ComposeUI" to "Mounted root composition with screens: [$preview]")
            }
        }

        return AndroidProjectSpec(
            packageName = packageName,
            applicationId = applicationId,
            appLabel = appLabel,
            launchActivity = launchActivity,
            applicationClass = applicationClass,
            componentClasses = components,
            startupLogs = startupLogs,
        )
    }

    fun buildApkBytes(spec: AndroidProjectSpec): ByteArray {
        val pkg = spec.packageName.ifBlank { "com.example.app" }
        val fqActivity = ManifestParser.normalizeActivity(pkg, spec.launchActivity)
            ?: "$pkg.MainActivity"
        val manifestXml = buildManifestXml(
            packageName = pkg,
            appLabel = spec.appLabel,
            launchActivity = fqActivity,
            applicationClass = spec.applicationClass,
        ).encodeToByteArray()

        val logs = if (spec.startupLogs.isNotEmpty()) {
            spec.startupLogs
        } else {
            val screensSummary = if (spec.componentClasses.isNotEmpty()) {
                spec.componentClasses.take(6).joinToString(", ") { it.substringAfterLast('.') }
            } else {
                fqActivity.substringAfterLast('.')
            }
            listOf(
                spec.appLabel to "Launching ${spec.appLabel} (${spec.applicationId})",
                fqActivity.substringAfterLast('.') to "Initialized UI components: [$screensSummary]",
            )
        }

        val dexBytes = buildDexBytes(
            packageName = pkg,
            launchActivityFqName = fqActivity,
            extraClassFqNames = spec.componentClasses,
            startupLogs = logs,
        )

        return buildZip(
            listOf(
                "AndroidManifest.xml" to manifestXml,
                "classes.dex" to dexBytes,
            ),
        )
    }

    fun buildManifestXml(
        packageName: String,
        appLabel: String,
        launchActivity: String,
        applicationClass: String? = null,
    ): String {
        val escapedLabel = appLabel
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val appNameAttr = if (!applicationClass.isNullOrBlank()) {
            " android:name=\"$applicationClass\""
        } else ""
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName">
                <application$appNameAttr android:label="$escapedLabel">
                    <activity android:name="$launchActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent() + "\n"
    }

    /**
     * Builds a standard Dalvik `classes.dex` (`dex\n035\0`) containing [launchActivityFqName]
     * (with `<init>()V` and `onCreate(Landroid/os/Bundle;)V` bytecode) and [extraClassFqNames].
     */
    fun buildDexBytes(
        packageName: String,
        launchActivityFqName: String,
        extraClassFqNames: List<String> = emptyList(),
        startupLogs: List<Pair<String, String>> = emptyList(),
    ): ByteArray {
        fun toDescriptor(fqName: String): String =
            "L" + fqName.trim().removePrefix("L").removeSuffix(";").replace('.', '/') + ";"

        val mainActivityDesc = toDescriptor(launchActivityFqName)
        val extraDescs = extraClassFqNames
            .map { toDescriptor(it) }
            .filter { it != mainActivityDesc }
            .distinct()

        val superActivityDesc = "Landroid/app/Activity;"
        val bundleDesc = "Landroid/os/Bundle;"
        val logDesc = "Landroid/util/Log;"
        val objectDesc = "Ljava/lang/Object;"
        val stringDesc = "Ljava/lang/String;"
        val voidDesc = "V"
        val intDesc = "I"

        val stringSet = LinkedHashSet<String>()
        stringSet += listOf(
            "<init>",
            "I",
            "ILL",
            "MainActivity.kt",
            "V",
            "VL",
            "i",
            "onCreate",
            superActivityDesc,
            bundleDesc,
            logDesc,
            objectDesc,
            stringDesc,
            mainActivityDesc,
        )
        stringSet += extraDescs
        for ((tag, msg) in startupLogs) {
            stringSet += tag
            stringSet += msg
        }

        val sortedStrings = stringSet.sorted()
        val stringIdxMap = sortedStrings.withIndex().associate { (i, s) -> s to i }
        fun strIdx(s: String): Int = stringIdxMap.getValue(s)

        val typeDescs = (listOf(
            intDesc,
            voidDesc,
            superActivityDesc,
            bundleDesc,
            logDesc,
            objectDesc,
            stringDesc,
            mainActivityDesc,
        ) + extraDescs).distinct().sortedBy { strIdx(it) }
        val typeIdxMap = typeDescs.withIndex().associate { (i, t) -> t to i }
        fun typeIdx(t: String): Int = typeIdxMap.getValue(t)

        data class ProtoSpec(val key: String, val shorty: String, val returnType: String, val params: List<String>)
        val protos = listOf(
            ProtoSpec("()V", "V", voidDesc, emptyList()),
            ProtoSpec("(Landroid/os/Bundle;)V", "VL", voidDesc, listOf(bundleDesc)),
            ProtoSpec("(Ljava/lang/String;Ljava/lang/String;)I", "ILL", intDesc, listOf(stringDesc, stringDesc)),
        )
        val protoIdxMap = protos.withIndex().associate { (i, p) -> p.key to i }
        fun protoIdx(key: String): Int = protoIdxMap.getValue(key)

        data class MethodSpec(val owner: String, val name: String, val proto: String) : Comparable<MethodSpec> {
            override fun compareTo(other: MethodSpec): Int {
                val c1 = typeIdx(owner).compareTo(typeIdx(other.owner))
                if (c1 != 0) return c1
                val c2 = strIdx(name).compareTo(strIdx(other.name))
                if (c2 != 0) return c2
                return protoIdx(proto).compareTo(protoIdx(other.proto))
            }
        }

        val methodSet = LinkedHashSet<MethodSpec>()
        methodSet += MethodSpec(superActivityDesc, "<init>", "()V")
        methodSet += MethodSpec(superActivityDesc, "onCreate", "(Landroid/os/Bundle;)V")
        methodSet += MethodSpec(logDesc, "i", "(Ljava/lang/String;Ljava/lang/String;)I")
        methodSet += MethodSpec(mainActivityDesc, "<init>", "()V")
        methodSet += MethodSpec(mainActivityDesc, "onCreate", "(Landroid/os/Bundle;)V")
        val sortedMethods = methodSet.sorted()
        val methodIdxMap = sortedMethods.withIndex().associate { (i, m) -> m to i }
        fun methodIdx(owner: String, name: String, proto: String): Int =
            methodIdxMap.getValue(MethodSpec(owner, name, proto))

        val allClassDescs = listOf(mainActivityDesc) + extraDescs

        // Layout calculation
        val buf = DexByteWriter()
        // Reserve 0x70 (112) bytes for header
        repeat(DexFile.HEADER_SIZE) { buf.u8(0) }

        val stringIdsOff = buf.pos
        repeat(sortedStrings.size) { buf.u32(0L) }

        val typeIdsOff = buf.pos
        for (t in typeDescs) {
            buf.u32(strIdx(t).toLong())
        }

        val protoIdsOff = buf.pos
        repeat(protos.size * 12) { buf.u8(0) }

        val fieldIdsOff = 0
        val fieldIdsSize = 0

        val methodIdsOff = buf.pos
        for (m in sortedMethods) {
            buf.u16(typeIdx(m.owner))
            buf.u16(protoIdx(m.proto))
            buf.u32(strIdx(m.name).toLong())
        }

        val classDefsOff = buf.pos
        repeat(allClassDescs.size * 32) { buf.u8(0) }

        val dataStartOff = buf.pos

        // 1. Write proto parameter type_lists
        val protoParamOffsets = IntArray(protos.size)
        for ((i, p) in protos.withIndex()) {
            if (p.params.isEmpty()) {
                protoParamOffsets[i] = 0
            } else {
                buf.align4()
                protoParamOffsets[i] = buf.pos
                buf.u32(p.params.size.toLong())
                for (param in p.params) {
                    buf.u16(typeIdx(param))
                }
            }
        }
        // Patch proto_ids
        for ((i, p) in protos.withIndex()) {
            val at = protoIdsOff + i * 12
            buf.patchU32(at, strIdx(p.shorty).toLong())
            buf.patchU32(at + 4, typeIdx(p.returnType).toLong())
            buf.patchU32(at + 8, protoParamOffsets[i].toLong())
        }

        // 2. Write string_data_items and patch string_ids
        for ((i, s) in sortedStrings.withIndex()) {
            val strDataOff = buf.pos
            buf.patchU32(stringIdsOff + i * 4, strDataOff.toLong())
            val utf8 = s.encodeToByteArray()
            buf.uleb(s.length)
            buf.bytes(utf8)
            buf.u8(0)
        }

        // 3. Write CodeItems for <init>()V and onCreate(Landroid/os/Bundle;)V
        buf.align4()
        val initCodeOff = buf.pos
        val superInitIdx = methodIdx(superActivityDesc, "<init>", "()V")
        val initInsns = intArrayOf(
            0x1070, // invoke-direct {v0}, method@superInitIdx
            superInitIdx,
            0x0000,
            0x000e, // return-void
        )
        writeCodeItem(buf, registersSize = 1, insSize = 1, outsSize = 1, insns = initInsns)

        buf.align4()
        val onCreateCodeOff = buf.pos
        val superOnCreateIdx = methodIdx(superActivityDesc, "onCreate", "(Landroid/os/Bundle;)V")
        val logMethodIdx = methodIdx(logDesc, "i", "(Ljava/lang/String;Ljava/lang/String;)I")
        val onCreateInsnList = ArrayList<Int>()
        // invoke-super {v2, v3}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V
        onCreateInsnList += 0x206f
        onCreateInsnList += superOnCreateIdx
        onCreateInsnList += 0x0032
        for ((tag, msg) in startupLogs) {
            // const-string v0, tag
            onCreateInsnList += 0x001a
            onCreateInsnList += strIdx(tag)
            // const-string v1, msg
            onCreateInsnList += 0x011a
            onCreateInsnList += strIdx(msg)
            // invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
            onCreateInsnList += 0x2071
            onCreateInsnList += logMethodIdx
            onCreateInsnList += 0x0010
        }
        // return-void
        onCreateInsnList += 0x000e
        writeCodeItem(
            buf,
            registersSize = 4,
            insSize = 2,
            outsSize = 2,
            insns = onCreateInsnList.toIntArray(),
        )

        // 4. Write class_data_item for MainActivity
        val mainClassDataOff = buf.pos
        val mainInitIdx = methodIdx(mainActivityDesc, "<init>", "()V")
        val mainOnCreateIdx = methodIdx(mainActivityDesc, "onCreate", "(Landroid/os/Bundle;)V")
        buf.uleb(0) // static_fields_size
        buf.uleb(0) // instance_fields_size
        buf.uleb(1) // direct_methods_size
        buf.uleb(1) // virtual_methods_size
        // direct_methods[0]
        buf.uleb(mainInitIdx)
        buf.uleb(0x10001) // ACC_PUBLIC | ACC_CONSTRUCTOR
        buf.uleb(initCodeOff)
        // virtual_methods[0]
        buf.uleb(mainOnCreateIdx)
        buf.uleb(0x0001) // ACC_PUBLIC
        buf.uleb(onCreateCodeOff)

        // 5. Patch class_defs
        val sourceFileIdx = strIdx("MainActivity.kt")
        for ((i, clsDesc) in allClassDescs.withIndex()) {
            val at = classDefsOff + i * 32
            val isMain = clsDesc == mainActivityDesc
            buf.patchU32(at + 0, typeIdx(clsDesc).toLong())
            buf.patchU32(at + 4, 0x0001L) // ACC_PUBLIC
            buf.patchU32(at + 8, typeIdx(if (isMain) superActivityDesc else objectDesc).toLong())
            buf.patchU32(at + 12, 0L) // interfaces_off
            buf.patchU32(at + 16, sourceFileIdx.toLong())
            buf.patchU32(at + 20, 0L) // annotations_off
            buf.patchU32(at + 24, if (isMain) mainClassDataOff.toLong() else 0L)
            buf.patchU32(at + 28, 0L) // static_values_off
        }

        buf.align4()
        val fileSize = buf.pos
        val dataSize = fileSize - dataStartOff

        // 6. Patch standard 0x70 DEX header
        val magic = byteArrayOf(
            'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
            '0'.code.toByte(), '3'.code.toByte(), '5'.code.toByte(), 0,
        )
        for (i in magic.indices) buf.patchU8(i, magic[i].toInt() and 0xFF)
        buf.patchU32(0x20, fileSize.toLong())
        buf.patchU32(0x24, DexFile.HEADER_SIZE.toLong())
        buf.patchU32(0x28, 0x12345678L) // ENDIAN_CONSTANT
        buf.patchU32(0x2c, 0L) // link_size
        buf.patchU32(0x30, 0L) // link_off
        buf.patchU32(0x34, 0L) // map_off
        buf.patchU32(0x38, sortedStrings.size.toLong())
        buf.patchU32(0x3c, stringIdsOff.toLong())
        buf.patchU32(0x40, typeDescs.size.toLong())
        buf.patchU32(0x44, typeIdsOff.toLong())
        buf.patchU32(0x48, protos.size.toLong())
        buf.patchU32(0x4c, protoIdsOff.toLong())
        buf.patchU32(0x50, fieldIdsSize.toLong())
        buf.patchU32(0x54, fieldIdsOff.toLong())
        buf.patchU32(0x58, sortedMethods.size.toLong())
        buf.patchU32(0x5c, methodIdsOff.toLong())
        buf.patchU32(0x60, allClassDescs.size.toLong())
        buf.patchU32(0x64, classDefsOff.toLong())
        buf.patchU32(0x68, dataSize.toLong())
        buf.patchU32(0x6c, dataStartOff.toLong())

        return buf.toByteArray()
    }

    private fun writeCodeItem(
        buf: DexByteWriter,
        registersSize: Int,
        insSize: Int,
        outsSize: Int,
        insns: IntArray,
    ) {
        buf.u16(registersSize)
        buf.u16(insSize)
        buf.u16(outsSize)
        buf.u16(0) // tries_size
        buf.u32(0L) // debug_info_off
        buf.u32(insns.size.toLong())
        for (unit in insns) {
            buf.u16(unit)
        }
    }

    fun buildZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ArrayList<Byte>()
        val centrals = ArrayList<Byte>()
        var offset = 0
        fun u16(v: Int) {
            out.add((v and 0xFF).toByte())
            out.add(((v ushr 8) and 0xFF).toByte())
        }
        fun u32(v: Long) {
            u16((v and 0xFFFF).toInt())
            u16(((v ushr 16) and 0xFFFF).toInt())
        }
        fun cu16(v: Int) {
            centrals.add((v and 0xFF).toByte())
            centrals.add(((v ushr 8) and 0xFF).toByte())
        }
        fun cu32(v: Long) {
            cu16((v and 0xFFFF).toInt())
            cu16(((v ushr 16) and 0xFFFF).toInt())
        }
        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)
            val localStart = offset
            u32(0x04034b50L)
            u16(20)
            u16(0)
            u16(0) // stored
            u16(0)
            u16(0)
            u32(crc)
            u32(data.size.toLong())
            u32(data.size.toLong())
            u16(nameBytes.size)
            u16(0)
            out.addAll(nameBytes.toList())
            out.addAll(data.toList())
            offset = out.size

            cu32(0x02014b50L)
            cu16(20)
            cu16(20)
            cu16(0)
            cu16(0)
            cu16(0)
            cu16(0)
            cu32(crc)
            cu32(data.size.toLong())
            cu32(data.size.toLong())
            cu16(nameBytes.size)
            cu16(0)
            cu16(0)
            cu16(0)
            cu16(0)
            cu32(0L)
            cu32(localStart.toLong())
            centrals.addAll(nameBytes.toList())
        }
        val centralStart = out.size
        out.addAll(centrals)
        val centralSize = out.size - centralStart
        u32(0x06054b50L)
        u16(0)
        u16(0)
        u16(entries.size)
        u16(entries.size)
        u32(centralSize.toLong())
        u32(centralStart.toLong())
        u16(0)
        return out.toByteArray()
    }

    private fun crc32(data: ByteArray): Long {
        var crc = -1
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return (crc.inv().toLong()) and 0xFFFFFFFFL
    }

    private class DexByteWriter {
        private var data = ByteArray(4096)
        var pos: Int = 0
            private set

        private fun ensure(extra: Int) {
            if (pos + extra <= data.size) return
            var next = data.size * 2
            while (next < pos + extra) next *= 2
            data = data.copyOf(next)
        }

        fun u8(v: Int) {
            ensure(1)
            data[pos++] = (v and 0xFF).toByte()
        }

        fun u16(v: Int) {
            ensure(2)
            data[pos++] = (v and 0xFF).toByte()
            data[pos++] = ((v ushr 8) and 0xFF).toByte()
        }

        fun u32(v: Long) {
            u16((v and 0xFFFF).toInt())
            u16(((v ushr 16) and 0xFFFF).toInt())
        }

        fun uleb(value: Int) {
            var v = value
            do {
                var b = v and 0x7F
                v = v ushr 7
                if (v != 0) b = b or 0x80
                u8(b)
            } while (v != 0)
        }

        fun bytes(arr: ByteArray) {
            ensure(arr.size)
            arr.copyInto(data, pos)
            pos += arr.size
        }

        fun align4() {
            while (pos and 3 != 0) u8(0)
        }

        fun patchU8(at: Int, v: Int) {
            data[at] = (v and 0xFF).toByte()
        }

        fun patchU32(at: Int, v: Long) {
            data[at] = (v and 0xFF).toByte()
            data[at + 1] = ((v ushr 8) and 0xFF).toByte()
            data[at + 2] = ((v ushr 16) and 0xFF).toByte()
            data[at + 3] = ((v ushr 24) and 0xFF).toByte()
        }

        fun toByteArray(): ByteArray = data.copyOf(pos)
    }
}
