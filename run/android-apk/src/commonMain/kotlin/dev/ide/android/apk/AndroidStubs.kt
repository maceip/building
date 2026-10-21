package dev.ide.android.apk

/**
 * Host bodies for the types a guest touches before it paints: `android.util.Log`, a writable
 * `android.util.Log`-shaped sink, and a tiny `Activity` whose `onCreate` the launcher calls.
 *
 * The shape is touchHLE's `Host`/`export_c_func` cut and Husk's JNI HLE tables: one `invoke` that
 * answers for a class+method, null to keep interpreting. Grow by need, not by API surface.
 */
class AndroidStubs(
    val log: (String) -> Unit = {},
) : DexVm.Host {

    class HostActivity(val name: String) {
        val extras = LinkedHashMap<String, String>()
        var finished = false
    }

    val activity = HostActivity("")

    override fun invoke(
        vm: DexVm,
        owner: String,
        name: String,
        descriptor: String,
        args: List<DexVm.DvmValue>,
    ): DexVm.DvmValue? {
        val arg = { i: Int -> args.getOrNull(i) ?: DexVm.DvmValue.Null }
        val asString = { i: Int ->
            when (val v = arg(i)) {
                is DexVm.DvmValue.ObjV -> v.o?.toString() ?: ""
                else -> ""
            }
        }
        return when {
            owner == "android/util/Log" && name == "d" || owner == "android/util/Log" && name == "i" ||
                owner == "android/util/Log" && name == "w" || owner == "android/util/Log" && name == "e" -> {
                log("${name.uppercase()}/${asString(0)}: ${asString(1)}")
                DexVm.DvmValue.IntV(0)
            }

            owner == "android/util/Log" && name == "println" -> {
                log(asString(1))
                DexVm.DvmValue.IntV(0)
            }

            owner == "java/lang/System" && name == "out" && descriptor.contains("PrintStream") -> {
                // getstatic-like via sget is handled in the VM; this is invoke.
                DexVm.DvmValue.Null
            }

            owner == "java/io/PrintStream" && name == "println" -> {
                log(asString(0))
                DexVm.DvmValue.Null
            }

            owner.endsWith("/Activity") || owner == "android/app/Activity" -> when (name) {
                "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy" -> {
                    log("$owner.$name")
                    DexVm.DvmValue.Null
                }
                "finish" -> {
                    activity.finished = true
                    DexVm.DvmValue.Null
                }
                "getLocalClassName", "getComponentName" -> DexVm.DvmValue.ObjV(activity.name)
                else -> {
                    log("stub $owner.$name$descriptor")
                    DexVm.DvmValue.Null
                }
            }

            owner == "android/os/Bundle" -> when (name) {
                "putString" -> {
                    activity.extras[asString(0)] = asString(1)
                    DexVm.DvmValue.Null
                }
                "getString" -> DexVm.DvmValue.ObjV(activity.extras[asString(0)] ?: "")
                else -> DexVm.DvmValue.Null
            }

            owner == "java/lang/StringBuilder" && name == "append" -> {
                DexVm.DvmValue.ObjV(asString(0) + (arg(1).let {
                    when (it) {
                        is DexVm.DvmValue.ObjV -> it.o?.toString() ?: "null"
                        is DexVm.DvmValue.IntV -> it.v.toString()
                        is DexVm.DvmValue.LongV -> it.v.toString()
                        else -> ""
                    }
                }))
            }

            owner == "java/lang/StringBuilder" && name == "toString" -> arg(0)

            else -> null
        }
    }

    override fun instantiate(vm: DexVm, type: String): Any? = when {
        type.contains("Activity") -> activity
        type == "Ljava/lang/StringBuilder;" -> StringBuilder()
        type == "Landroid/os/Bundle;" -> LinkedHashMap<String, String>()
        else -> null
    }

    override fun getField(vm: DexVm, owner: Any, name: String): DexVm.DvmValue? {
        if (owner is HostActivity && name == "mFinished") return DexVm.DvmValue.IntV(if (owner.finished) 1 else 0)
        return null
    }

    override fun setField(vm: DexVm, owner: Any, name: String, value: DexVm.DvmValue) {
        if (owner is HostActivity && name == "mTitle") {
            activity.extras["title"] = (value as? DexVm.DvmValue.ObjV)?.o?.toString() ?: ""
        }
    }
}
