package dev.ide.gradle

/**
 * The one Android application variant a Run builds.
 *
 * Modules that apply `com.android.application` are eligible. Variants whose build type is debug
 * (or that are debuggable) are kept. Among those, the shortest flavor list wins, then a variant
 * named `debug`, then `defaultDebug`, then the model's own order. The task is `assemble<Variant>`
 * on that module (`:app:assembleDebug`, animeko's `:app:android:assembleDefaultDebug`).
 */
object VariantChoice {

    data class Variant(
        val modulePath: String,
        val moduleDir: String,
        val application: Boolean,
        val name: String,
        val buildType: String,
        val flavors: List<String>,
        val assembleTask: String,
        val debuggable: Boolean,
        val order: Int,
        val namespace: String? = null,
        val applicationId: String? = null,
    )

    data class Chosen(val variant: Variant, val taskPath: String)

    fun choose(variants: List<Variant>): Chosen? {
        val apps = variants.filter { it.application }
        val debug = apps.filter { it.buildType == "debug" || it.debuggable }
        val pool = debug.ifEmpty { apps }
        val picked = if (pool.isEmpty()) {
            variants.firstOrNull { it.name == "main" } ?: variants.firstOrNull()
        } else {
            val shortest = pool.minOf { it.flavors.size }
            val tied = pool.filter { it.flavors.size == shortest }
            tied.firstOrNull { it.name == "debug" }
                ?: tied.firstOrNull { it.name == "defaultDebug" }
                ?: tied.minBy { it.order }
        } ?: return null
        return Chosen(picked, taskPath(picked.modulePath, picked.assembleTask))
    }

    fun taskPath(modulePath: String, task: String): String = when {
        modulePath.isEmpty() || modulePath == ":" -> ":$task"
        modulePath.endsWith(":") -> modulePath + task
        else -> "$modulePath:$task"
    }

    /** `assembleDefaultDebug` → `bundleDefaultDebug`, on the same module path. */
    fun bundleTask(assemblePath: String): String = assemblePath.replace(":assemble", ":bundle")
}
