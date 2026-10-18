package dev.ide.ios.android

import dev.ide.ios.IosFiles

/**
 * Parsed data from an Android project's AndroidManifest.xml.
 */
data class AndroidManifestData(
    val packageName: String,
    val applicationName: String?,
    val appLabel: String?,
    val appTheme: String?,
    val mainActivity: String?,
    val permissions: List<String>,
)

/**
 * Resolved resource table for an Android project.
 */
class AndroidResourceTable(
    val strings: Map<String, String> = emptyMap(),
    val colors: Map<String, Long> = emptyMap(),
    val dimens: Map<String, String> = emptyMap(),
    val drawables: Map<String, String> = emptyMap(), // name -> relative path
) {
    /** Resolves a reference like `@string/app_name` or `@color/primary` to its underlying value. */
    fun resolve(ref: String): String? {
        if (!ref.startsWith("@")) return ref
        val parts = ref.removePrefix("@").split('/', limit = 2)
        if (parts.size != 2) return null
        val (type, name) = parts
        return when (type) {
            "string" -> strings[name]
            "color" -> colors[name]?.let { "#" + it.toString(16).uppercase().padStart(8, '0') }
            "dimen" -> dimens[name]
            "drawable", "mipmap" -> drawables[name]
            else -> null
        }
    }

    fun getString(name: String): String? = strings[name] ?: resolve(name)

    fun getColor(name: String): Long? = colors[name] ?: parseHexColor(name)

    companion object {
        fun parseHexColor(value: String): Long? {
            val clean = value.trim().removePrefix("#")
            return when (clean.length) {
                6 -> ("FF$clean").toLongOrNull(16)
                8 -> clean.toLongOrNull(16)
                3 -> {
                    // #RGB -> #RRGGBB
                    val r = clean[0]; val g = clean[1]; val b = clean[2]
                    ("FF$r$r$g$g$b$b").toLongOrNull(16)
                }
                else -> null
            }
        }
    }
}

/**
 * Parser for Android project manifests and resources.
 */
object AndroidProjectParser {

    /**
     * Locate AndroidManifest.xml in the project.
     * Checks standard Android Gradle project structures:
     * - app/src/main/AndroidManifest.xml
     * - src/main/AndroidManifest.xml
     * - AndroidManifest.xml
     */
    fun findManifestFile(projectRoot: String): String? {
        val candidates = listOf(
            IosFiles.join(projectRoot, "app", "src", "main", "AndroidManifest.xml"),
            IosFiles.join(projectRoot, "src", "main", "AndroidManifest.xml"),
            IosFiles.join(projectRoot, "composeApp", "src", "androidMain", "AndroidManifest.xml"),
            IosFiles.join(projectRoot, "AndroidManifest.xml"),
        )
        return candidates.firstOrNull { IosFiles.exists(it) }
    }

    /**
     * Locate the primary `res` directory.
     */
    fun findResDir(projectRoot: String): String? {
        val candidates = listOf(
            IosFiles.join(projectRoot, "app", "src", "main", "res"),
            IosFiles.join(projectRoot, "src", "main", "res"),
            IosFiles.join(projectRoot, "composeApp", "src", "androidMain", "res"),
            IosFiles.join(projectRoot, "res"),
        )
        return candidates.firstOrNull { IosFiles.exists(it) && IosFiles.isDirectory(it) }
    }

    /**
     * Parses an AndroidManifest.xml file into [AndroidManifestData].
     */
    fun parseManifest(manifestText: String): AndroidManifestData {
        var pkg = extractAttribute(manifestText, "package")
        if (pkg == null) {
            // fallback: look for package in manifest tag
            val manifestTag = manifestText.substringBefore(">", "")
            pkg = extractAttribute(manifestTag, "package")
        }

        // Application tag
        val appTagStart = manifestText.indexOf("<application")
        val appTag = if (appTagStart >= 0) {
            val appTagEnd = manifestText.indexOf('>', appTagStart)
            if (appTagEnd >= 0) manifestText.substring(appTagStart, appTagEnd + 1) else ""
        } else ""

        val appName = extractAttribute(appTag, "android:name") ?: extractAttribute(appTag, "name")
        val appLabel = extractAttribute(appTag, "android:label") ?: extractAttribute(appTag, "label")
        val appTheme = extractAttribute(appTag, "android:theme") ?: extractAttribute(appTag, "theme")

        // Find main activity: look for <activity> containing android.intent.action.MAIN
        val activityRegex = Regex("<activity[\\s\\S]*?<\\/activity>|<activity[\\s\\S]*?\\/>")
        var mainActivity: String? = null
        for (match in activityRegex.findAll(manifestText)) {
            val content = match.value
            if ("android.intent.action.MAIN" in content || "Intent.ACTION_MAIN" in content) {
                mainActivity = extractAttribute(content, "android:name") ?: extractAttribute(content, "name")
                break
            }
        }
        // If not found with MAIN action, pick the first activity found
        if (mainActivity == null) {
            for (match in activityRegex.findAll(manifestText)) {
                mainActivity = extractAttribute(match.value, "android:name") ?: extractAttribute(match.value, "name")
                if (mainActivity != null) break
            }
        }

        // Full qualification of activity name if it starts with '.'
        if (mainActivity != null && mainActivity.startsWith(".") && pkg != null) {
            mainActivity = "$pkg$mainActivity"
        }

        // Permissions
        val permissions = mutableListOf<String>()
        val permRegex = Regex("<uses-permission[^>]*android:name=[\"']([^\"']+)[\"']")
        for (match in permRegex.findAll(manifestText)) {
            permissions.add(match.groupValues[1])
        }

        return AndroidManifestData(
            packageName = pkg ?: "dev.ide.app",
            applicationName = appName,
            appLabel = appLabel,
            appTheme = appTheme,
            mainActivity = mainActivity,
            permissions = permissions,
        )
    }

    /**
     * Loads the project resources from the `res` directory.
     */
    fun loadResources(resDir: String): AndroidResourceTable {
        val strings = mutableMapOf<String, String>()
        val colors = mutableMapOf<String, Long>()
        val dimens = mutableMapOf<String, String>()
        val drawables = mutableMapOf<String, String>()

        val valuesDirs = IosFiles.list(resDir).filter { it == "values" || it.startsWith("values-") }
        for (vd in valuesDirs) {
            val dirPath = IosFiles.join(resDir, vd)
            val files = IosFiles.list(dirPath)
            for (file in files) {
                if (!file.endsWith(".xml")) continue
                val filePath = IosFiles.join(dirPath, file)
                val text = IosFiles.readText(filePath)
                parseXmlValues(text, strings, colors, dimens)
            }
        }

        // Drawables and mipmaps
        val drawableDirs = IosFiles.list(resDir).filter { it.startsWith("drawable") || it.startsWith("mipmap") }
        for (dd in drawableDirs) {
            val dirPath = IosFiles.join(resDir, dd)
            val files = IosFiles.list(dirPath)
            for (file in files) {
                val nameWithoutExt = file.substringBeforeLast('.')
                if (nameWithoutExt !in drawables) drawables[nameWithoutExt] = IosFiles.join(dd, file)
            }
        }

        return AndroidResourceTable(strings, colors, dimens, drawables)
    }

    private fun parseXmlValues(
        xml: String,
        strings: MutableMap<String, String>,
        colors: MutableMap<String, Long>,
        dimens: MutableMap<String, String>,
    ) {
        // Strings: <string name="app_name">My App</string>
        val stringRegex = Regex("<string[^>]*name=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)<\\/string>")
        for (match in stringRegex.findAll(xml)) {
            val name = match.groupValues[1]
            val content = match.groupValues[2].trim()
            if (name !in strings) strings[name] = content
        }

        // Colors: <color name="purple_500">#FF6200EE</color>
        val colorRegex = Regex("<color[^>]*name=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)<\\/color>")
        for (match in colorRegex.findAll(xml)) {
            val name = match.groupValues[1]
            val content = match.groupValues[2].trim()
            val parsed = AndroidResourceTable.parseHexColor(content)
            if (parsed != null && name !in colors) colors[name] = parsed
        }

        // Dimens: <dimen name="fab_margin">16dp</dimen>
        val dimenRegex = Regex("<dimen[^>]*name=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)<\\/dimen>")
        for (match in dimenRegex.findAll(xml)) {
            val name = match.groupValues[1]
            val content = match.groupValues[2].trim()
            if (name !in dimens) dimens[name] = content
        }
    }

    private fun extractAttribute(text: String, attrName: String): String? {
        val pattern = Regex("$attrName=[\"']([^\"']+)[\"']")
        return pattern.find(text)?.groupValues?.get(1)
    }
}
