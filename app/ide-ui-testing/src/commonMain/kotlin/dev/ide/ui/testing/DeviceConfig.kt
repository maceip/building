package dev.ide.ui.testing

/**
 * Cash App Paparazzi-inspired multiplatform device configuration preset.
 *
 * Controls viewport dimensions (in pixels), display density, font scale, and dark/light theme mode
 * for deterministic off-screen Compose rendering and interactive semantics testing.
 */
data class DeviceConfig(
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val density: Float = 2.0f,
    val fontScale: Float = 1.0f,
    val darkMode: Boolean = true,
) {
    fun withDarkMode(dark: Boolean): DeviceConfig = copy(darkMode = dark)
    fun withFontScale(scale: Float): DeviceConfig = copy(fontScale = scale)
    fun withHeight(heightPx: Int): DeviceConfig = copy(heightPx = heightPx)
    fun landscape(): DeviceConfig = copy(
        name = "${name}_landscape",
        widthPx = heightPx,
        heightPx = widthPx,
    )

    companion object {
        val NEXUS_5 = DeviceConfig(
            name = "nexus_5",
            widthPx = 1080,
            heightPx = 1920,
            density = 3.0f,
        )
        val PIXEL_6 = DeviceConfig(
            name = "pixel_6",
            widthPx = 1080,
            heightPx = 2400,
            density = 2.625f,
        )
        val PIXEL_9_PRO = DeviceConfig(
            name = "pixel_9_pro",
            widthPx = 820,
            heightPx = 2200,
            density = 2.0f,
        )
        val PIXEL_FOLD = DeviceConfig(
            name = "pixel_fold_unfolded",
            widthPx = 2208,
            heightPx = 1840,
            density = 2.625f,
        )
        val PIXEL_TABLET = DeviceConfig(
            name = "pixel_tablet",
            widthPx = 2560,
            heightPx = 1600,
            density = 2.0f,
        )
        val DESKTOP_HD = DeviceConfig(
            name = "desktop_hd",
            widthPx = 1440,
            heightPx = 900,
            density = 1.0f,
        )
    }
}
