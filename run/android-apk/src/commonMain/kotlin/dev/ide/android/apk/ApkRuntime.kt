package dev.ide.android.apk

/**
 * The launcher: open an APK, read its manifest, load every `classes*.dex` into a [DexVm], and
 * walk the Activity lifecycle far enough that a host can see the app start.
 *
 * This is the same product cut as apkenv's `apkenv --install` + module entry, Husk's
 * `husk_java_launch`, and Mac.apk's "first launch is slow" install: get the guest running on the
 * host's own code, then grow what it can call.
 */
class ApkRuntime(private val log: (String) -> Unit = {}) {

    data class LaunchResult(
        val ok: Boolean,
        val packageName: String,
        val activity: String?,
        val message: String,
        val dexClasses: Int,
        val nativeAbis: List<String>,
    )

    fun launch(bytes: ByteArray, preferActivity: String? = null): LaunchResult {
        val pkg = ApkPackage.open(bytes)
            ?: return LaunchResult(false, "", null, "not a zip/apk", 0, emptyList())
        return launch(pkg, preferActivity)
    }

    fun launch(apk: ApkPackage, preferActivity: String? = null): LaunchResult {
        val manifestBytes = apk.readManifestBytes()
        val manifest = if (manifestBytes != null) ManifestParser.parse(manifestBytes)
        else ManifestParser.Manifest("", null, null)
        val activityName = preferActivity ?: manifest.launchActivity
        log("apk: package=${manifest.packageName.ifEmpty { "(unknown)" }} activity=$activityName")
        log("apk: dex=${apk.dexEntries} abis=${apk.nativeAbis()}")

        if (apk.dexEntries.isEmpty()) {
            return LaunchResult(
                ok = false,
                packageName = manifest.packageName,
                activity = activityName,
                message = "no classes.dex (native-only APK needs the .so path)",
                dexClasses = 0,
                nativeAbis = apk.nativeAbis(),
            )
        }

        var classCount = 0
        val stubs = AndroidStubs(log)
        for (entry in apk.dexEntries) {
            val bytes = apk.readDex(entry) ?: continue
            val dex = DexFile.parse(bytes).getOrElse {
                return LaunchResult(
                    false, manifest.packageName, activityName,
                    "dex parse failed for $entry: ${it.message}",
                    classCount, apk.nativeAbis(),
                )
            }
            classCount += dex.classes.size
            log("apk: $entry — ${dex.classes.size} classes, ${dex.methodIds.size} methods")
            val vm = DexVm(dex)
            vm.host = stubs
            vm.log = log

            val activityType = activityName?.let { "L${it.replace('.', '/')};" }
            val launched = activityType?.let { runActivity(vm, dex, it, stubs) }
            if (launched != null) {
                return LaunchResult(
                    ok = true,
                    packageName = manifest.packageName,
                    activity = activityName,
                    message = launched,
                    dexClasses = classCount,
                    nativeAbis = apk.nativeAbis(),
                )
            }
        }

        // No Activity matched: still prove the dex loaded and walked a <clinit>/main if present.
        val first = classCount
        return LaunchResult(
            ok = true,
            packageName = manifest.packageName,
            activity = activityName,
            message = "dex loaded ($first classes); no onCreate ran" +
                (if (activityName == null) " (no launcher activity in manifest)" else " (missing $activityName)"),
            dexClasses = classCount,
            nativeAbis = apk.nativeAbis(),
        )
    }

    private fun runActivity(vm: DexVm, dex: DexFile, type: String, stubs: AndroidStubs): String? {
        val ctor = dex.findMethod(type, "<init>", "()V")
        val onCreate = dex.findMethod(type, "onCreate", "Landroid/os/Bundle;")
            ?: dex.findMethod(type, "onCreate", "()V")
        if (ctor == null && onCreate == null) {
            log("apk: $type has no <init>/onCreate")
            return null
        }
        stubs.activity.let { it.extras["class"] = type }
        val recv = stubs.instantiate(vm, type) ?: DexVm.GuestObject(type)
        try {
            if (ctor != null) vm.invoke(ctor, recv, emptyList())
            if (onCreate != null) {
                val desc = dex.protoDescriptor(dex.methodIds[onCreate.methodIdx].protoIdx)
                val args = if (desc.startsWith("(Landroid/os/Bundle;)")) {
                    listOf(DexVm.DvmValue.ObjV(stubs.instantiate(vm, "Landroid/os/Bundle;")))
                } else emptyList()
                vm.invoke(onCreate, recv, args)
            }
            return "started $type (onCreate returned)"
        } catch (t: Throwable) {
            log("apk: $type threw ${t.message}")
            return "started $type with guest exception: ${t.message}"
        }
    }
}
