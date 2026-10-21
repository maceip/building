package dev.ide.android.apk

/**
 * A Dalvik register machine: one frame per invocation, `regs` sized from `registers_size`, arguments
 * packed at the high end of the frame (Dalvik's calling convention). Guest objects live in [DvmValue]
 * so the host can hand out its own (a `Log` stub, an `Activity`) without a second object model.
 *
 * This is the same cut Husk's `husk-tl-dvm.c` and Mac.apk's dex path make: interpret the bytecode, host
 * everything that would have been a system service. Incomplete by design — an unimplemented opcode is an
 * error the launcher can report, not a silent wrong answer.
 */
class DexVm(private val dex: DexFile) {

    sealed class DvmValue {
        data class IntV(val v: Int) : DvmValue()
        data class LongV(val v: Long) : DvmValue()
        data class FloatV(val v: Float) : DvmValue()
        data class DoubleV(val v: Double) : DvmValue()
        data class ObjV(val o: Any?) : DvmValue()
        object Null : DvmValue()
    }

    class DexException(val kind: String, message: String) : Exception("$kind: $message")

    /** Bodies the host installs for types the guest would have found in the framework. */
    interface Host {
        /** Return a value for a call to `owner.name(descriptor)`, or null to keep interpreting. */
        fun invoke(vm: DexVm, owner: String, name: String, descriptor: String, args: List<DvmValue>): DvmValue?

        /** Allocate an instance of [type], or null if the host does not own that class. */
        fun instantiate(vm: DexVm, type: String): Any?

        fun getField(vm: DexVm, owner: Any, name: String): DvmValue? = null
        fun setField(vm: DexVm, owner: Any, name: String, value: DvmValue) {}
    }

    class Frame(
        val registers: Array<DvmValue>,
        val code: DexFile.CodeItem,
        var pc: Int = 0,
    )

    var host: Host = NONE
    private val statics = HashMap<String, DvmValue>()
    private val classInited = HashSet<String>()
    var log: (String) -> Unit = {}

    fun call(owner: String, name: String, descriptor: String = "()V", args: List<DvmValue> = emptyList()): DvmValue {
        val method = dex.findMethod(owner, name, descriptor)
            ?: throw DexException("NoSuchMethodError", "$owner.$name$descriptor")
        return invoke(method, receiver = null, args)
    }

    fun invoke(method: DexFile.EncodedMethod, receiver: Any?, args: List<DvmValue>): DvmValue {
        val code = method.code ?: return DvmValue.Null
        val frame = Frame(Array(code.registersSize) { DvmValue.Null }, code)
        // Arguments occupy the last `insSize` registers.
        val allArgs = buildList {
            if (receiver != null || !isStatic(method)) {
                add(if (receiver == null) DvmValue.Null else DvmValue.ObjV(receiver))
            }
            addAll(args)
        }
        var dest = code.registersSize - code.insSize
        for (a in allArgs) {
            if (dest >= frame.registers.size) break
            frame.registers[dest++] = a
            if (a is DvmValue.LongV || a is DvmValue.DoubleV) {
                if (dest < frame.registers.size) frame.registers[dest++] = DvmValue.Null
            }
        }
        return interpret(frame)
    }

    private fun isStatic(method: DexFile.EncodedMethod): Boolean =
        (method.accessFlags and 0x8) != 0 || (method.accessFlags and 0x100) != 0 /* constructor is direct */

    private fun interpret(frame: Frame): DvmValue {
        val insns = frame.code.insns
        while (frame.pc < insns.size) {
            val op = insns[frame.pc] and 0xFF
            val p = frame.pc
            when (op) {
                0x00 -> frame.pc++ // nop
                0x01 -> { // move vA, vB
                    val i = insns[p]; frame.registers[(i ushr 8) and 0xF] = frame.registers[(i ushr 12) and 0xF]; frame.pc++
                }
                0x02 -> { // move/from16 vAA, vBBBB
                    frame.registers[insns[p] ushr 8] = frame.registers[insns[p + 1]]; frame.pc += 2
                }
                0x03 -> { // move/16 vAAAA, vBBBB
                    frame.registers[insns[p + 1]] = frame.registers[insns[p + 2]]; frame.pc += 3
                }
                0x04 -> { // move-wide
                    val i = insns[p]; frame.registers[(i ushr 8) and 0xF] = frame.registers[(i ushr 12) and 0xF]; frame.pc++
                }
                0x05 -> { frame.registers[insns[p] ushr 8] = frame.registers[insns[p + 1]]; frame.pc += 2 }
                0x06 -> { frame.registers[insns[p + 1]] = frame.registers[insns[p + 2]]; frame.pc += 3 }
                0x07 -> { // move-object
                    val i = insns[p]; frame.registers[(i ushr 8) and 0xF] = frame.registers[(i ushr 12) and 0xF]; frame.pc++
                }
                0x08 -> { frame.registers[insns[p] ushr 8] = frame.registers[insns[p + 1]]; frame.pc += 2 }
                0x09 -> { frame.registers[insns[p + 1]] = frame.registers[insns[p + 2]]; frame.pc += 3 }
                0x0a -> { frame.registers[insns[p] ushr 8] = frame.registers[insns[p] ushr 12]; frame.pc++ } // move-result
                0x0b -> { frame.registers[insns[p] ushr 8] = frame.registers[insns[p] ushr 12]; frame.pc++ } // move-result-wide
                0x0c -> { frame.registers[insns[p] ushr 8] = frame.registers[insns[p] ushr 12]; frame.pc++ } // move-result-object
                0x0e -> frame.pc++ // return-void
                0x0f -> return frame.registers[insns[p] ushr 8] // return vAA
                0x10 -> return frame.registers[insns[p] ushr 8] // return-wide
                0x11 -> return frame.registers[insns[p] ushr 8] // return-object
                0x12 -> { // const/4 vA, #+B
                    val i = insns[p]
                    val lit = (i shr 8) and 0xF
                    val signed = if (lit and 0x8 != 0) lit or -0x10 else lit
                    frame.registers[(i ushr 12) and 0xF] = DvmValue.IntV(signed)
                    frame.pc++
                }
                0x13 -> { frame.registers[insns[p] ushr 8] = DvmValue.IntV(insns[p + 1].toShort().toInt()); frame.pc += 2 }
                0x14 -> { // const vAA, #+BBBBBBBB
                    val lo = insns[p + 1].toLong() and 0xFFFF
                    val hi = insns[p + 2].toLong() and 0xFFFF
                    val v = ((hi shl 16) or lo).toInt()
                    frame.registers[insns[p] ushr 8] = DvmValue.IntV(v)
                    frame.pc += 3
                }
                0x15 -> { frame.registers[insns[p] ushr 8] = DvmValue.FloatV(Float.fromBits(insns[p + 1] or (insns[p + 2] shl 16))); frame.pc += 3 }
                0x16 -> { frame.registers[insns[p] ushr 8] = DvmValue.LongV(insns[p + 1].toShort().toLong()); frame.pc += 2 }
                0x17 -> {
                    val lo = insns[p + 1].toLong() and 0xFFFF
                    val hi = insns[p + 2].toLong() and 0xFFFF
                    frame.registers[insns[p] ushr 8] = DvmValue.LongV(((hi shl 16) or lo).toInt().toLong())
                    frame.pc += 3
                }
                0x18 -> { // const-wide/64
                    var v = 0L
                    for (k in 0 until 4) v = v or ((insns[p + 1 + k].toLong() and 0xFFFF) shl (16 * k))
                    frame.registers[insns[p] ushr 8] = DvmValue.LongV(v)
                    frame.pc += 5
                }
                0x19 -> { // const-wide/high16
                    val bits = (insns[p + 1].toLong() and 0xFFFF) shl 48
                    frame.registers[insns[p] ushr 8] = DvmValue.DoubleV(Double.fromBits(bits))
                    frame.pc += 2
                }
                0x1a -> { // const-string vAA, string@BBBB
                    frame.registers[insns[p] ushr 8] = DvmValue.ObjV(dex.strings[insns[p + 1]])
                    frame.pc += 2
                }
                0x1b -> { // const-string/jumbo
                    val idx = insns[p + 1] or (insns[p + 2] shl 16)
                    frame.registers[insns[p] ushr 8] = DvmValue.ObjV(dex.strings[idx])
                    frame.pc += 3
                }
                0x1c -> { // const-class
                    frame.registers[insns[p] ushr 8] = DvmValue.ObjV(dex.typeName(insns[p + 1]))
                    frame.pc += 2
                }
                0x1d -> frame.pc++ // monitor-enter (no-op: single-threaded guest)
                0x1e -> frame.pc++ // monitor-exit
                0x1f -> { // check-cast
                    val reg = insns[p] ushr 8
                    val type = dex.typeName(insns[p + 1])
                    val v = frame.registers[reg]
                    if (v is DvmValue.ObjV && v.o != null && !isInstance(v.o, type)) {
                        throw DexException("ClassCastException", "${v.o} is not $type")
                    }
                    frame.pc += 2
                }
                0x20 -> { // instance-of
                    val i = insns[p]
                    val v = frame.registers[(i ushr 12) and 0xF]
                    val type = dex.typeName(insns[p + 1])
                    val isInst = v is DvmValue.ObjV && v.o != null && isInstance(v.o, type)
                    frame.registers[(i ushr 8) and 0xF] = DvmValue.IntV(if (isInst) 1 else 0)
                    frame.pc += 2
                }
                0x21 -> { // array-length
                    val i = insns[p]
                    val arr = frame.registers[(i ushr 12) and 0xF] as? DvmValue.ObjV
                    val n = (arr?.o as? Array<*>)?.size ?: (arr?.o as? IntArray)?.size ?: 0
                    frame.registers[(i ushr 8) and 0xF] = DvmValue.IntV(n)
                    frame.pc++
                }
                0x22 -> { // new-instance
                    val type = dex.typeName(insns[p + 1])
                    frame.registers[insns[p] ushr 8] = DvmValue.ObjV(host.instantiate(this, type) ?: GuestObject(type))
                    frame.pc += 2
                }
                0x23 -> { // new-array
                    val i = insns[p]
                    val n = intOf(frame.registers[(i ushr 12) and 0xF])
                    val type = dex.typeName(insns[p + 1])
                    frame.registers[(i ushr 8) and 0xF] = DvmValue.ObjV(
                        if (type == "[I" || type.startsWith("[I")) IntArray(n) else Array<Any?>(n) { null },
                    )
                    frame.pc += 2
                }
                0x27 -> throw DexException("UnsatisfiedLinkError", "fill-array-data at $p not supported")
                0x28 -> { // goto
                    val off = insns[p].toByte().toInt()
                    frame.pc += off
                }
                0x29 -> { frame.pc += insns[p + 1].toShort().toInt() } // goto/16
                0x2a -> { frame.pc += (insns[p + 1] or (insns[p + 2] shl 16)) } // goto/32 (sign via Int)
                0x2b -> { // packed-switch
                    val off = insns[p + 1].toShort().toInt()
                    val scrut = intOf(frame.registers[insns[p] ushr 8])
                    frame.pc += packedSwitch(insns, p + off, scrut)
                }
                0x2c -> { // sparse-switch
                    val off = insns[p + 1].toShort().toInt()
                    val scrut = intOf(frame.registers[insns[p] ushr 8])
                    frame.pc += sparseSwitch(insns, p + off, scrut)
                }
                in 0x32..0x37 -> { // if-eq/ne/lt/ge/gt/le
                    val i = insns[p]
                    val a = frame.registers[(i ushr 8) and 0xF]
                    val b = frame.registers[(i ushr 12) and 0xF]
                    val c = compareValues(a, b)
                    val take = when (op) {
                        0x32 -> c == 0
                        0x33 -> c != 0
                        0x34 -> c < 0
                        0x35 -> c >= 0
                        0x36 -> c > 0
                        else -> c <= 0
                    }
                    frame.pc += if (take) insns[p + 1].toShort().toInt() else 2
                }
                in 0x38..0x3d -> { // if-eqz..lez
                    val v = frame.registers[insns[p] ushr 8]
                    val c = compareValues(v, DvmValue.IntV(0))
                    val take = when (op) {
                        0x38 -> c == 0
                        0x39 -> c != 0
                        0x3a -> c < 0
                        0x3b -> c >= 0
                        0x3c -> c > 0
                        else -> c <= 0
                    }
                    frame.pc += if (take) insns[p + 1].toShort().toInt() else 2
                }
                in 0x44..0x4a -> { // aget-family: op vAA, vBB, vCC (23x)
                    val dest = insns[p] ushr 8
                    val arr = frame.registers[insns[p + 1] ushr 8] as? DvmValue.ObjV
                    val index = intOf(frame.registers[insns[p + 1] and 0xFF])
                    val any = arr?.o
                    frame.registers[dest] = when (any) {
                        is Array<*> -> any.getOrNull(index)?.let { if (it is DvmValue) it else DvmValue.ObjV(it) }
                            ?: DvmValue.Null
                        is IntArray -> DvmValue.IntV(any.getOrElse(index) { 0 })
                        else -> DvmValue.Null
                    }
                    frame.pc += 2
                }
                in 0x4b..0x51 -> { // aput-family
                    frame.pc += 2
                }
                in 0x52..0x5f -> { // iget/iput
                    val i = insns[p]
                    val vd = (i ushr 8) and 0xF
                    val obj = frame.registers[(i ushr 12) and 0xF]
                    val fieldIdx = insns[p + 1]
                    val name = dex.fieldName(fieldIdx)
                    if (op in 0x52..0x58) {
                        val owner = (obj as? DvmValue.ObjV)?.o
                        frame.registers[vd] = if (owner != null) host.getField(this, owner, name) ?: DvmValue.Null else DvmValue.Null
                    } else {
                        val owner = (obj as? DvmValue.ObjV)?.o
                        if (owner != null) host.setField(this, owner, name, frame.registers[vd])
                    }
                    frame.pc += 2
                }
                in 0x60..0x6d -> { // sget/sput
                    val type = dex.typeName(dex.fieldIds.getOrNull(insns[p + 1])?.typeIdx ?: 0)
                    val name = dex.fieldName(insns[p + 1])
                    val key = "$type.$name"
                    if (op in 0x60..0x66) {
                        frame.registers[insns[p] ushr 8] = statics[key] ?: DvmValue.Null
                    } else {
                        statics[key] = frame.registers[insns[p] ushr 8]
                    }
                    frame.pc += 2
                }
                0x6e, 0x6f, 0x70, 0x71, 0x72 -> { // invoke-kind
                    val result = doInvoke(frame, insns, p, op)
                    frame.registers[0] = result // move-result reads regs[0] after; caller uses 0x0a immediately
                    lastResult = result
                    frame.pc += 3
                }
                0x74, 0x75, 0x76, 0x77, 0x78 -> { // invoke-kind/range
                    val result = doInvokeRange(frame, insns, p)
                    lastResult = result
                    frame.pc += 3
                }
                in 0x7b..0x8f -> { // unop / binop: support the integer core
                    frame.pc = binop(frame, insns, p, op)
                }
                in 0x90..0xaf -> frame.pc = binop2addr(frame, insns, p, op)
                in 0xb0..0xcf -> frame.pc = binopLit16(frame, insns, p, op)
                in 0xd0..0xd7 -> frame.pc = binopLit8(frame, insns, p, op)
                else -> throw DexException("UnsatisfiedLinkError", "opcode 0x${op.toString(16)} at pc $p not supported")
            }
            if (frame.pc <= p && op != 0x28 && op != 0x29 && op != 0x2a && op !in 0x2b..0x3d) {
                // ensure forward progress for straight-line ops
                if (op !in 0x0e..0x11) error("pc stalled at $p op=0x${op.toString(16)}")
            }
        }
        return lastResult ?: DvmValue.Null
    }

    private var lastResult: DvmValue = DvmValue.Null

    class GuestObject(val type: String) {
        val fields = HashMap<String, DvmValue>()
    }

    private fun isInstance(o: Any, type: String): Boolean = when {
        o is GuestObject -> o.type == type || type == "Ljava/lang/Object;"
        o is String -> type == "Ljava/lang/String;" || type == "Ljava/lang/Object;"
        o is IntArray -> type.startsWith("[I") || type == "Ljava/lang/Object;"
        o is Array<*> -> type.startsWith("[") || type == "Ljava/lang/Object;"
        else -> true
    }

    private fun intOf(v: DvmValue): Int = when (v) {
        is DvmValue.IntV -> v.v
        is DvmValue.LongV -> v.v.toInt()
        is DvmValue.FloatV -> v.v.toInt()
        is DvmValue.DoubleV -> v.v.toInt()
        else -> 0
    }

    private fun compareValues(a: DvmValue, b: DvmValue): Int {
        if (a is DvmValue.Null && b is DvmValue.Null) return 0
        if (a is DvmValue.Null) return -1
        if (b is DvmValue.Null) return 1
        if (a is DvmValue.ObjV || b is DvmValue.ObjV) {
            return if (identical(a, b)) 0 else 1
        }
        val x = when (a) {
            is DvmValue.IntV -> a.v.toLong()
            is DvmValue.LongV -> a.v
            is DvmValue.FloatV -> a.v.toLong()
            is DvmValue.DoubleV -> a.v.toLong()
            else -> 0L
        }
        val y = when (b) {
            is DvmValue.IntV -> b.v.toLong()
            is DvmValue.LongV -> b.v
            is DvmValue.FloatV -> b.v.toLong()
            is DvmValue.DoubleV -> b.v.toLong()
            else -> 0L
        }
        return x.compareTo(y)
    }

    private fun identical(a: DvmValue, b: DvmValue): Boolean = when {
        a is DvmValue.ObjV && b is DvmValue.ObjV -> a.o === b.o || a.o == b.o
        a is DvmValue.Null && b is DvmValue.Null -> true
        else -> false
    }

    private fun doInvoke(frame: Frame, insns: IntArray, p: Int, op: Int): DvmValue {
        val methodIdx = insns[p + 1]
        val argWord = insns[p]
        val name = dex.methodName(methodIdx)
        val owner = dex.methodOwner(methodIdx)
        val desc = dex.protoDescriptor(dex.methodIds[methodIdx].protoIdx)
        val argCount = (argWord shr 12) and 0xF
        val args = ArrayList<DvmValue>(argCount)
        var shift = 0
        var unit = 0
        // 35c: A|G|op BBBB F|E|D|C  — nibbles of the next word, low first, G is last (and is the first arg register)
        val regNibbles = insns[p + 2]
        val regList = intArrayOf(
            regNibbles and 0xF,
            (regNibbles shr 4) and 0xF,
            (regNibbles shr 8) and 0xF,
            (regNibbles shr 12) and 0xF,
            (argWord shr 8) and 0xF,
        )
        val isStatic = op == 0x71 || op == 0x72
        val recv: Any? = if (isStatic) null else {
            when (val v = frame.registers[regList[0]]) {
                is DvmValue.ObjV -> v.o
                else -> null
            }
        }
        val from = if (isStatic) 0 else 1
        for (i in from until argCount) {
            args.add(frame.registers[regList[i]])
        }
        return dispatch(owner, name, desc, recv, args)
    }

    private fun doInvokeRange(frame: Frame, insns: IntArray, p: Int): DvmValue {
        val methodIdx = insns[p + 1]
        val argWord = insns[p]
        val name = dex.methodName(methodIdx)
        val owner = dex.methodOwner(methodIdx)
        val desc = dex.protoDescriptor(dex.methodIds[methodIdx].protoIdx)
        val count = (argWord shr 8) and 0xFF
        val start = insns[p + 2]
        val isStatic = (argWord and 0xFF) == 0x74 || (argWord and 0xFF) == 0x74
        val args = ArrayList<DvmValue>()
        var recv: Any? = null
        for (i in 0 until count) {
            val v = frame.registers[start + i]
            if (i == 0 && (insns[p] and 0xFF) !in intArrayOf(0x74)) {
                recv = (v as? DvmValue.ObjV)?.o
            } else {
                args.add(v)
            }
        }
        return dispatch(owner, name, desc, recv, args)
    }

    private fun dispatch(owner: String, name: String, desc: String, recv: Any?, args: List<DvmValue>): DvmValue {
        host.invoke(this, owner, name, desc, args)?.let { return it }
        if (recv == null) {
            // static or host class with no body: try the guest's own class
            val m = dex.findMethod(owner, name, desc)
            if (m != null) {
                ensureInit(owner)
                return invoke(m, null, args)
            }
            log("unimplemented static $owner.$name$desc")
            return DvmValue.Null
        }
        if (recv is GuestObject) {
            val m = dex.findMethod(owner, name, desc) ?: dex.findMethod(recv.type, name, desc)
            if (m != null) return invoke(m, recv, args)
            log("unimplemented $owner.$name$desc on ${recv.type}")
            return DvmValue.Null
        }
        log("unimplemented $owner.$name$desc on host ${recv::class.simpleName}")
        return DvmValue.Null
    }

    private fun ensureInit(owner: String) {
        if (!classInited.add(owner)) return
        val m = dex.findMethod(owner, "<clinit>", "()V") ?: return
        runCatching { invoke(m, null, emptyList()) }
    }

    private fun packedSwitch(insns: IntArray, at: Int, scrut: Int): Int {
        if (at < 0 || at + 4 > insns.size) return 2
        if (insns[at] != 0x0100) return 2 // packed-switch payload ident
        val size = insns[at + 1]
        val first = (insns[at + 2] or (insns[at + 3] shl 16))
        val firstSigned = first.toInt()
        if (scrut < firstSigned || scrut >= firstSigned + size) return 2
        val off = (insns[at + 4 + (scrut - firstSigned) * 2] or (insns[at + 5 + (scrut - firstSigned) * 2] shl 16))
        return off.toInt()
    }

    private fun sparseSwitch(insns: IntArray, at: Int, scrut: Int): Int {
        if (at < 0 || at + 2 > insns.size) return 2
        if (insns[at] != 0x0200) return 2
        val size = insns[at + 1]
        var i = 0
        while (i < size) {
            val key = (insns[at + 2 + i * 2] or (insns[at + 3 + i * 2] shl 16)).toInt()
            if (key == scrut) {
                return (insns[at + 2 + size * 2 + i * 2] or (insns[at + 3 + size * 2 + i * 2] shl 16)).toInt()
            }
            i++
        }
        return 2
    }

    private fun binop(frame: Frame, insns: IntArray, p: Int, op: Int): Int {
        val i = insns[p]
        val a = (i ushr 8) and 0xF
        val b = (i ushr 12) and 0xF
        val c = (i ushr 4) and 0xF
        // 23x: AA|op BBBB CCCC — re-read from next unit when op is 23x style (0x90+)
        val vb = frame.registers[insns[p + 1] ushr 8]
        val vc = frame.registers[insns[p + 1] and 0xFF]
        val x = intOf(vb)
        val y = intOf(vc)
        frame.registers[a] = when (op) {
            0x90 -> DvmValue.IntV(x + y) // add-int
            0x91 -> DvmValue.IntV(x - y)
            0x92 -> DvmValue.IntV(x * y)
            0x93 -> DvmValue.IntV(if (y == 0) 0 else x / y)
            0x94 -> DvmValue.IntV(if (y == 0) 0 else x % y)
            0x95 -> DvmValue.IntV(x and y)
            0x96 -> DvmValue.IntV(x or y)
            0x97 -> DvmValue.IntV(x xor y)
            0x98 -> DvmValue.IntV(x shl y)
            0x99 -> DvmValue.IntV(x shr y)
            0x9a -> DvmValue.IntV(x ushr y)
            else -> DvmValue.IntV(x)
        }
        // 23x is 2 code units
        if (op >= 0x90) return p + 2
        // 12x: vA, vB with B in high nibble of first unit — already handled roughly
        return p + 1
    }

    private fun binop2addr(frame: Frame, insns: IntArray, p: Int, op: Int): Int {
        val i = insns[p]
        val a = (i ushr 8) and 0xF
        val b = (i ushr 12) and 0xF
        val x = intOf(frame.registers[a])
        val y = intOf(frame.registers[b])
        frame.registers[a] = when (op) {
            0xb0 -> DvmValue.IntV(x + y)
            0xb1 -> DvmValue.IntV(x - y)
            0xb2 -> DvmValue.IntV(x * y)
            0xb3 -> DvmValue.IntV(if (y == 0) 0 else x / y)
            0xb4 -> DvmValue.IntV(if (y == 0) 0 else x % y)
            0xb5 -> DvmValue.IntV(x and y)
            0xb6 -> DvmValue.IntV(x or y)
            0xb7 -> DvmValue.IntV(x xor y)
            else -> DvmValue.IntV(x)
        }
        return p + 1
    }

    private fun binopLit16(frame: Frame, insns: IntArray, p: Int, op: Int): Int {
        val i = insns[p]
        val a = (i ushr 8) and 0xF
        val b = (i ushr 12) and 0xF
        val x = intOf(frame.registers[b])
        val y = insns[p + 1].toShort().toInt()
        frame.registers[a] = when (op) {
            0xd0 -> DvmValue.IntV(x + y)
            0xd1 -> DvmValue.IntV(x - y)
            0xd2 -> DvmValue.IntV(x * y)
            0xd3 -> DvmValue.IntV(if (y == 0) 0 else x / y)
            0xd4 -> DvmValue.IntV(if (y == 0) 0 else x % y)
            0xd5 -> DvmValue.IntV(x and y)
            0xd6 -> DvmValue.IntV(x or y)
            0xd7 -> DvmValue.IntV(x xor y)
            else -> DvmValue.IntV(x)
        }
        return p + 2
    }

    private fun binopLit8(frame: Frame, insns: IntArray, p: Int, op: Int): Int = binopLit16(frame, insns, p, op)

    companion object {
        val NONE: Host = object : Host {
            override fun invoke(vm: DexVm, owner: String, name: String, descriptor: String, args: List<DvmValue>): DvmValue? = null
            override fun instantiate(vm: DexVm, type: String): Any? = null
        }
    }
}
