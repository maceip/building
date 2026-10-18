package dev.ide.ios.android

import dev.ide.ios.IosFiles

/**
 * Activity lifecycle states corresponding to the Android Activity lifecycle.
 */
enum class ActivityLifecycleState {
    INITIALIZED,
    CREATED,
    STARTED,
    RESUMED,
    PAUSED,
    STOPPED,
    DESTROYED,
}

/**
 * Virtual SharedPreferences implementation persisted to disk.
 */
class VirtualSharedPreferences(private val filePath: String) {
    private val data = mutableMapOf<String, String>()

    init {
        load()
    }

    private fun load() {
        if (!IosFiles.exists(filePath)) return
        val text = IosFiles.readText(filePath)
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                data[key] = value
            }
        }
    }

    private fun save() {
        IosFiles.parentOf(filePath)?.let { if (!IosFiles.exists(it)) IosFiles.mkdirs(it) }
        val sb = StringBuilder()
        sb.appendLine("# Virtual SharedPreferences")
        for ((k, v) in data) {
            sb.appendLine("$k=$v")
        }
        IosFiles.writeText(filePath, sb.toString())
    }

    fun getString(key: String, defValue: String?): String? = data[key] ?: defValue
    fun putString(key: String, value: String?): VirtualSharedPreferences {
        if (value != null) data[key] = value else data.remove(key)
        return this
    }

    fun getInt(key: String, defValue: Int): Int = data[key]?.toIntOrNull() ?: defValue
    fun putInt(key: String, value: Int): VirtualSharedPreferences {
        data[key] = value.toString()
        return this
    }

    fun getLong(key: String, defValue: Long): Long = data[key]?.toLongOrNull() ?: defValue
    fun putLong(key: String, value: Long): VirtualSharedPreferences {
        data[key] = value.toString()
        return this
    }

    fun getBoolean(key: String, defValue: Boolean): Boolean = data[key]?.toBooleanStrictOrNull() ?: defValue
    fun putBoolean(key: String, value: Boolean): VirtualSharedPreferences {
        data[key] = value.toString()
        return this
    }

    fun contains(key: String): Boolean = key in data
    fun remove(key: String): VirtualSharedPreferences {
        data.remove(key)
        return this
    }
    fun clear(): VirtualSharedPreferences {
        data.clear()
        return this
    }

    fun apply() = save()
    fun commit(): Boolean {
        save()
        return true
    }
}

/**
 * Virtual Android Context providing package information, files/cache directories,
 * resources, and SharedPreferences to hosted Android code.
 */
class VirtualAndroidContext(
    val rootPath: String,
    val manifest: AndroidManifestData,
    val resources: AndroidResourceTable,
) {
    val packageName: String get() = manifest.packageName
    val appName: String get() = manifest.appLabel?.let { resources.resolve(it) } ?: manifest.packageName

    val filesDir: String by lazy {
        val path = IosFiles.join(IosFiles.supportDir(), "android_sandbox", packageName, "files")
        if (!IosFiles.exists(path)) IosFiles.mkdirs(path)
        path
    }

    val cacheDir: String by lazy {
        val path = IosFiles.join(IosFiles.supportDir(), "android_sandbox", packageName, "cache")
        if (!IosFiles.exists(path)) IosFiles.mkdirs(path)
        path
    }

    private val prefsCache = mutableMapOf<String, VirtualSharedPreferences>()

    fun getSharedPreferences(name: String): VirtualSharedPreferences =
        prefsCache.getOrPut(name) {
            val prefFile = IosFiles.join(IosFiles.supportDir(), "android_sandbox", packageName, "shared_prefs", "$name.xml")
            VirtualSharedPreferences(prefFile)
        }
}

/**
 * Virtual Android Activity instance managing lifecycle transitions, back-stack,
 * and view/compose root hosting.
 */
class VirtualActivity(
    val context: VirtualAndroidContext,
    val activityName: String,
    private val onLog: (String) -> Unit = {},
) {
    var state: ActivityLifecycleState = ActivityLifecycleState.INITIALIZED
        private set

    val backStack = mutableListOf<String>()

    fun onCreate(savedInstanceState: Map<String, Any?>? = null) {
        state = ActivityLifecycleState.CREATED
        onLog("Activity $activityName: onCreate() [savedInstanceState=${savedInstanceState != null}]")
    }

    fun onStart() {
        state = ActivityLifecycleState.STARTED
        onLog("Activity $activityName: onStart()")
    }

    fun onResume() {
        state = ActivityLifecycleState.RESUMED
        onLog("Activity $activityName: onResume() - Interactive and visible")
    }

    fun onPause() {
        state = ActivityLifecycleState.PAUSED
        onLog("Activity $activityName: onPause()")
    }

    fun onStop() {
        state = ActivityLifecycleState.STOPPED
        onLog("Activity $activityName: onStop()")
    }

    fun onDestroy() {
        state = ActivityLifecycleState.DESTROYED
        onLog("Activity $activityName: onDestroy()")
    }

    fun pushRoute(route: String) {
        backStack.add(route)
        onLog("Activity $activityName: navigate -> $route (stack depth: ${backStack.size})")
    }

    /**
     * Handles back press.
     * @return true if an internal route was popped, false if activity should finish.
     */
    fun onBackPressed(): Boolean {
        if (backStack.isNotEmpty()) {
            val popped = backStack.removeAt(backStack.size - 1)
            onLog("Activity $activityName: onBackPressed() popped $popped (remaining: ${backStack.size})")
            return true
        }
        onLog("Activity $activityName: onBackPressed() back stack empty -> finishing activity")
        onPause()
        onStop()
        onDestroy()
        return false
    }
}

/**
 * The host engine for running an Android application process in the iOS app container.
 */
class AndroidAppHost(
    val rootPath: String,
    val onLog: (String) -> Unit = {},
) {
    var context: VirtualAndroidContext? = null
        private set

    var currentActivity: VirtualActivity? = null
        private set

    var isRunning: Boolean = false
        private set

    /**
     * Bootstraps the application environment from the project's AndroidManifest.xml
     * and resource table.
     */
    fun initialize(): Boolean {
        val manifestFile = AndroidProjectParser.findManifestFile(rootPath)
        val manifest = if (manifestFile != null) {
            val text = IosFiles.readText(manifestFile)
            AndroidProjectParser.parseManifest(text)
        } else {
            AndroidManifestData(
                packageName = "dev.ide.app",
                applicationName = null,
                appLabel = IosFiles.nameOf(rootPath),
                appTheme = null,
                mainActivity = "MainActivity",
                permissions = emptyList(),
            )
        }

        val resDir = AndroidProjectParser.findResDir(rootPath)
        val resources = if (resDir != null) {
            AndroidProjectParser.loadResources(resDir)
        } else {
            AndroidResourceTable()
        }

        val ctx = VirtualAndroidContext(rootPath, manifest, resources)
        this.context = ctx
        onLog("Initialized Virtual Android Context:")
        onLog("  Package: ${ctx.packageName}")
        onLog("  App Name: ${ctx.appName}")
        onLog("  Files Dir: ${ctx.filesDir}")
        onLog("  Resources: ${resources.strings.size} strings, ${resources.colors.size} colors, ${resources.drawables.size} drawables")
        if (manifest.permissions.isNotEmpty()) {
            onLog("  Permissions: ${manifest.permissions.joinToString(", ")}")
        }
        return true
    }

    /**
     * Launches the main activity through its lifecycle.
     */
    fun launch(): VirtualActivity {
        val ctx = context ?: throw IllegalStateException("AndroidAppHost must be initialized before launch")
        val mainActivityName = ctx.manifest.mainActivity ?: "MainActivity"
        onLog("Launching Android Application: ${ctx.appName} (${ctx.packageName})")
        val activity = VirtualActivity(ctx, mainActivityName, onLog)
        currentActivity = activity

        activity.onCreate(null)
        activity.onStart()
        activity.onResume()
        isRunning = true
        return activity
    }

    /**
     * Terminates the current activity.
     */
    fun terminate() {
        val act = currentActivity
        if (act != null && act.state != ActivityLifecycleState.DESTROYED) {
            act.onPause()
            act.onStop()
            act.onDestroy()
        }
        currentActivity = null
        isRunning = false
        onLog("Android Application terminated.")
    }
}
