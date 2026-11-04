package dev.ide.gradle

/**
 * AGP names a CMake directory after the version the project asked for, then runs `cmake --version`
 * and rejects the binary when the two disagree. The Termux package is 4.4.4. The alias directory
 * (AGP's default `3.22.1`) is the first try. A rejection configures once more with the binary's
 * real version, which the init script writes at `projectsEvaluated`.
 */
object CmakeFallback {

    fun parseVersion(output: String): String? =
        Regex("""cmake version (\d+\.\d+\.\d+)""").find(output)?.groupValues?.get(1)

    fun rejected(message: String?, stderr: String = ""): Boolean {
        val text = (message.orEmpty() + "\n" + stderr).lowercase()
        if (!text.contains("cmake")) return false
        return text.contains("version") || text.contains("cmake.dir")
    }
}
