package dev.ide.android.apk

/**
 * A Dalvik `classes.dex`: the string/type/proto/field/method tables and every `class_def` with its code.
 *
 * Little-endian throughout, same as the format. Indices in method/field ids are into the tables above;
 * `code_off` of 0 means abstract or native. Only what the interpreter needs is kept — no debug info,
 * no annotation set, no map list — because a guest that crashes on a missing annotation does not care.
 */
class DexFile private constructor(
    val bytes: ByteArray,
    val strings: List<String>,
    val typeIds: IntArray,
    val protoIds: Array<ProtoId>,
    val fieldIds: Array<FieldId>,
    val methodIds: Array<MethodId>,
    val classes: List<ClassDef>,
) {
    class ProtoId(val shorty: Int, val returnType: Int, val parameters: IntArray)

    class FieldId(val classIdx: Int, val typeIdx: Int, val nameIdx: Int)

    class MethodId(val classIdx: Int, val protoIdx: Int, val nameIdx: Int)

    class ClassDef(
        val classIdx: Int,
        val accessFlags: Int,
        val superIdx: Int,
        val interfaces: IntArray,
        val sourceIdx: Int,
        val staticFields: List<EncodedField>,
        val instanceFields: List<EncodedField>,
        val directMethods: List<EncodedMethod>,
        val virtualMethods: List<EncodedMethod>,
    )

    class EncodedField(val fieldIdx: Int, val accessFlags: Int)

    class EncodedMethod(val methodIdx: Int, val accessFlags: Int, val code: CodeItem?)

    class CodeItem(
        val registersSize: Int,
        val insSize: Int,
        val outsSize: Int,
        val insns: IntArray,
    )

    fun typeName(idx: Int): String =
        if (idx < 0 || idx >= typeIds.size) "V" else strings[typeIds[idx]]

    fun methodName(idx: Int): String =
        if (idx < 0 || idx >= methodIds.size) "" else strings[methodIds[idx].nameIdx]

    fun methodOwner(idx: Int): String =
        if (idx < 0 || idx >= methodIds.size) "" else typeName(methodIds[idx].classIdx)

    fun fieldName(idx: Int): String =
        if (idx < 0 || idx >= fieldIds.size) "" else strings[fieldIds[idx].nameIdx]

    fun fieldOwner(idx: Int): String =
        if (idx < 0 || idx >= fieldIds.size) "" else typeName(fieldIds[idx].classIdx)

    fun protoDescriptor(idx: Int): String {
        if (idx < 0 || idx >= protoIds.size) return "()V"
        val p = protoIds[idx]
        val sb = StringBuilder("(")
        for (t in p.parameters) sb.append(typeName(t))
        sb.append(')')
        sb.append(typeName(p.returnType))
        return sb.toString()
    }

    fun findClass(typeName: String): ClassDef? = classes.firstOrNull { typeName(it.classIdx) == typeName }

    fun findMethod(owner: String, name: String, descriptor: String? = null): EncodedMethod? {
        val def = findClass(owner) ?: return null
        val all = def.directMethods + def.virtualMethods
        return all.firstOrNull {
            methodName(it.methodIdx) == name &&
                (descriptor == null || protoDescriptor(methodIds[it.methodIdx].protoIdx) == descriptor)
        }
    }

    companion object {
        const val HEADER_SIZE = 0x70

        fun parse(bytes: ByteArray): Result<DexFile> = runCatching {
            require(bytes.size >= HEADER_SIZE) { "dex too small" }
            require(bytes[0].toInt().toChar() == 'd' && bytes[1].toInt().toChar() == 'e' &&
                bytes[2].toInt().toChar() == 'x' && bytes[3].toInt().toChar() == '\n') { "not a dex" }
            val r = DexReader(bytes)
            r.at(0x24)
            val stdHeaderCandidate = r.u32()
            val stringIdsOff: Int
            val stringIdsSize: Int
            val typeIdsOff: Int
            val typeIdsSize: Int
            val protoIdsOff: Int
            val protoIdsSize: Int
            val fieldIdsOff: Int
            val fieldIdsSize: Int
            val methodIdsOff: Int
            val methodIdsSize: Int
            val classDefsOff: Int
            val classDefsSize: Int
            if (stdHeaderCandidate == HEADER_SIZE.toLong()) {
                // Standard Android DEX header layout (magic[8] + checksum[4] + sig[20] = 32)
                r.at(0x20)
                val fileSize = r.u32()
                require(fileSize <= bytes.size) { "dex header file_size $fileSize > ${bytes.size}" }
                val headerSize = r.u32()
                require(headerSize == HEADER_SIZE.toLong()) { "unexpected header size $headerSize" }
                r.skip(16) // endian_tag (4) + link_size (4) + link_off (4) + map_off (4)
                stringIdsSize = r.u32().toInt()
                stringIdsOff = r.u32().toInt()
                typeIdsSize = r.u32().toInt()
                typeIdsOff = r.u32().toInt()
                protoIdsSize = r.u32().toInt()
                protoIdsOff = r.u32().toInt()
                fieldIdsSize = r.u32().toInt()
                fieldIdsOff = r.u32().toInt()
                methodIdsSize = r.u32().toInt()
                methodIdsOff = r.u32().toInt()
                classDefsSize = r.u32().toInt()
                classDefsOff = r.u32().toInt()
            } else {
                r.at(36) // legacy synthetic layout
                val fileSize = r.u32()
                require(fileSize <= bytes.size) { "dex header file_size $fileSize > ${bytes.size}" }
                val headerSize = r.u32()
                require(headerSize == HEADER_SIZE.toLong()) { "unexpected header size $headerSize" }
                r.skip(4) // endian_tag
                stringIdsOff = r.u32().toInt()
                stringIdsSize = r.u32().toInt()
                typeIdsOff = r.u32().toInt()
                typeIdsSize = r.u32().toInt()
                protoIdsOff = r.u32().toInt()
                protoIdsSize = r.u32().toInt()
                fieldIdsOff = r.u32().toInt()
                fieldIdsSize = r.u32().toInt()
                methodIdsOff = r.u32().toInt()
                methodIdsSize = r.u32().toInt()
                classDefsOff = r.u32().toInt()
                classDefsSize = r.u32().toInt()
            }

            val strings = ArrayList<String>(stringIdsSize)
            for (i in 0 until stringIdsSize) {
                r.at(stringIdsOff + i * 4)
                val dataOff = r.u32().toInt()
                strings.add(r.mutf8(dataOff))
            }

            val typeIds = IntArray(typeIdsSize)
            r.at(typeIdsOff)
            for (i in 0 until typeIdsSize) typeIds[i] = r.u32().toInt()

            val protoIds = Array(protoIdsSize) { i ->
                r.at(protoIdsOff + i * 12)
                val shorty = r.u32().toInt()
                val ret = r.u32().toInt()
                val paramsOff = r.u32().toInt()
                val params = if (paramsOff == 0) IntArray(0) else {
                    val save = r.pos
                    r.at(paramsOff)
                    val n = r.u32().toInt()
                    IntArray(n) { r.u16() }.also { r.pos = save }
                }
                ProtoId(shorty, ret, params)
            }

            val fieldIds = Array(fieldIdsSize) { i ->
                r.at(fieldIdsOff + i * 8)
                FieldId(r.u16(), r.u16(), r.u32().toInt())
            }

            val methodIds = Array(methodIdsSize) { i ->
                r.at(methodIdsOff + i * 8)
                MethodId(r.u16(), r.u16(), r.u32().toInt())
            }

            val classes = ArrayList<ClassDef>(classDefsSize)
            for (i in 0 until classDefsSize) {
                r.at(classDefsOff + i * 32)
                val classIdx = r.u32().toInt()
                val accessFlags = r.u32().toInt()
                val superIdx = r.u32().toInt()
                val interfacesOff = r.u32().toInt()
                val sourceIdx = r.u32().toInt()
                r.u32() // annotations_off
                val classDataOff = r.u32().toInt()
                r.u32() // static_values_off
                val interfaces = if (interfacesOff == 0) IntArray(0) else {
                    val save = r.pos
                    r.at(interfacesOff)
                    val n = r.u32().toInt()
                    IntArray(n) { r.u16() }.also { r.pos = save }
                }
                var staticFields = emptyList<EncodedField>()
                var instanceFields = emptyList<EncodedField>()
                var directMethods = emptyList<EncodedMethod>()
                var virtualMethods = emptyList<EncodedMethod>()
                if (classDataOff != 0) {
                    r.at(classDataOff)
                    val staticFieldsSize = r.uleb()
                    val instanceFieldsSize = r.uleb()
                    val directMethodsSize = r.uleb()
                    val virtualMethodsSize = r.uleb()
                    var fieldIdx = 0
                    fun readFields(n: Int): List<EncodedField> {
                        val out = ArrayList<EncodedField>(n)
                        var idx = 0
                        repeat(n) {
                            idx += r.uleb()
                            val flags = r.uleb()
                            out.add(EncodedField(idx, flags))
                        }
                        fieldIdx = idx
                        return out
                    }
                    staticFields = readFields(staticFieldsSize)
                    instanceFields = readFields(instanceFieldsSize)
                    fun readMethods(n: Int): List<EncodedMethod> {
                        val out = ArrayList<EncodedMethod>(n)
                        var idx = 0
                        repeat(n) {
                            idx += r.uleb()
                            val flags = r.uleb()
                            val codeOff = r.uleb()
                            out.add(EncodedMethod(idx, flags, if (codeOff != 0) readCode(r, codeOff) else null))
                        }
                        return out
                    }
                    directMethods = readMethods(directMethodsSize)
                    virtualMethods = readMethods(virtualMethodsSize)
                }
                classes.add(
                    ClassDef(
                        classIdx, accessFlags, superIdx, interfaces, sourceIdx,
                        staticFields, instanceFields, directMethods, virtualMethods,
                    ),
                )
            }
            DexFile(bytes, strings, typeIds, protoIds, fieldIds, methodIds, classes)
        }

        private fun readCode(r: DexReader, off: Int): CodeItem {
            val save = r.pos
            r.at(off)
            val registersSize = r.u16()
            val insSize = r.u16()
            val outsSize = r.u16()
            r.u16() // tries_size
            r.u32() // debug_info_off
            val insnsSize = r.u32().toInt()
            val insns = IntArray(insnsSize) { r.u16() }
            r.pos = save
            return CodeItem(registersSize, insSize, outsSize, insns)
        }
    }
}

internal class DexReader(val bytes: ByteArray) {
    var pos = 0

    fun at(index: Int) {
        pos = index
    }

    fun skip(n: Int) {
        pos += n
    }

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

    fun uleb(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 28) break
        }
        return result
    }

    /** Modified UTF-8: length-prefixed by uleb128, then bytes until a 0 that is not a wide char. */
    fun mutf8(at: Int): String {
        pos = at
        uleb() // utf16 size; we re-decode from the bytes
        val start = pos
        var end = start
        while (bytes[end] != 0.toByte()) end++
        return mutf8Decode(bytes, start, end - start)
    }

    companion object {
        fun mutf8Decode(bytes: ByteArray, off: Int, len: Int): String {
            val out = StringBuilder(len)
            var i = off
            val end = off + len
            while (i < end) {
                val b = bytes[i].toInt() and 0xFF
                when {
                    b == 0 -> break
                    b < 0x80 -> {
                        out.append(b.toChar())
                        i++
                    }
                    b and 0xE0 == 0xC0 -> {
                        val c = ((b and 0x1F) shl 6) or ((bytes[i + 1].toInt() and 0xFF) and 0x3F)
                        out.append(c.toChar())
                        i += 2
                    }
                    else -> {
                        val c = ((b and 0x0F) shl 12) or
                            (((bytes[i + 1].toInt() and 0xFF) and 0x3F) shl 6) or
                            ((bytes[i + 2].toInt() and 0xFF) and 0x3F)
                        out.append(c.toChar())
                        i += 3
                    }
                }
            }
            return out.toString()
        }
    }
}
