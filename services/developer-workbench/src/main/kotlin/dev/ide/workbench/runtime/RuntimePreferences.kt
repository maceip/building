package dev.ide.workbench.runtime

/** User choices gate new IDE sessions, not subprocess permissions or installed payload ownership. */
enum class RuntimeFeature(val key: String, val title: String, val description: String) {
    NODE("node", "Node.js and package managers", "Run JavaScript, npm and pnpm projects, Node REPL sessions, and Node commands in Gradle builds."),
    PYTHON("python", "Python", "Run Python projects. Other enabled tools can still use Python as a build dependency."),
    SHELL("shell", "Shell and terminal", "Run shell scripts and open interactive shell sessions. Other enabled tools can still use a shell internally."),
    STATIC("static", "Static web preview", "Preview HTML projects using the built-in local web server."),
    XTOOL("xtool", "Swift & iOS (xtool)", "Build and compile SwiftPM packages and iOS applications on device."),
    ;

    val preferenceKey: String get() = "settings.$PAGE_ID.$key"

    companion object {
        const val PAGE_ID = "runtimes"
        fun forKind(kind: RuntimeKind): RuntimeFeature = when (kind) {
            RuntimeKind.NODE, RuntimeKind.NPM -> NODE
            RuntimeKind.PYTHON -> PYTHON
            RuntimeKind.SHELL -> SHELL
            RuntimeKind.STATIC -> STATIC
            RuntimeKind.XTOOL -> XTOOL
        }
    }
}

class RuntimePreferences(private val read: (String) -> String?) {
    fun enabled(feature: RuntimeFeature): Boolean = read(feature.preferenceKey)?.toBooleanStrictOrNull() ?: true
    fun disabledReason(kind: RuntimeKind): String? {
        val feature = RuntimeFeature.forKind(kind)
        return if (enabled(feature)) null else "${feature.title} is turned off. Enable it in Settings > SDK Manager."
    }
}
