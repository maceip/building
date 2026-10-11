package dev.ide.gradle

/**
 * Which bionic JDK image a project gets.
 *
 * JDK 17 is the floor for AGP 8.4 through 9.x. A wrapper of 8.5, or of 9.x, does not by itself
 * select 21. JDK 21 is used when a Java or Kotlin toolchain asks for a major above 17. A vendor
 * pin (JetBrains JBR) still uses our image of that major, with toolchain auto-download left off.
 * A wrapper older than Gradle 8.6 is not built here; the project stays on the static reader.
 */
object JdkSelection {

    enum class Image { JDK_17, JDK_21 }

    data class Decision(
        val image: Image?,
        val autoDownload: Boolean,
        val supported: Boolean,
        val reason: String,
    )

    fun select(gradleVersion: String?, toolchainMajor: Int?, toolchainVendor: String?): Decision {
        if (gradleVersion != null && !atLeast(gradleVersion, 8, 6)) {
            return Decision(
                image = null,
                autoDownload = false,
                supported = false,
                reason = "Gradle $gradleVersion is older than 8.6. This project stays on the static Gradle reader.",
            )
        }
        val image = if (toolchainMajor != null && toolchainMajor > 17) Image.JDK_21 else Image.JDK_17
        val foreignVendor = toolchainVendor?.takeIf { pin ->
            pin.isNotBlank() && !pin.equals("adoptium", ignoreCase = true) && !pin.equals("temurin", ignoreCase = true)
        }
        val reason = buildString {
            if (image == Image.JDK_21) append("Toolchain ${toolchainMajor} selects JDK 21.")
            else append("JDK 17 is the floor.")
            if (foreignVendor != null) {
                append(" Vendor '$foreignVendor' is not shipped; auto-download stays off and the bionic image of the same major is used.")
            }
        }
        return Decision(image = image, autoDownload = false, supported = true, reason = reason)
    }

    /** True when [version] is at least [major].[minor]. `8.10` is above `8.6`. */
    fun atLeast(version: String, major: Int, minor: Int): Boolean {
        val parts = version.split('.', '-', '+')
        val maj = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return maj > major || (maj == major && min >= minor)
    }
}
