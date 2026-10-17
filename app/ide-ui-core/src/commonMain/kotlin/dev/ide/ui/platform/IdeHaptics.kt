package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.roundToInt

/**
 * Functional domain categories of tactile feedback across the IDE.
 * Users can toggle individual categories independently (e.g., keeping multitasking Git Clone and
 * Build Engine haptics active while muting everyday button taps).
 */
enum class HapticCategory(val id: String) {
    /** Elevated COSS buttons, filter chips, tab switches, and primary actions. */
    UI_INTERACTIONS("uiInteractions"),

    /** Git clone initiation, rhythmic "rat-a-tat" streaming heartbeat, and clone completion burst. */
    GIT_CLONE("gitClone"),

    /** Build engine ignition, background compiler/dex step detents, and build success/failure alerts. */
    BUILD_ENGINE("buildEngine"),

    /** Predictive-back half-screen split and code-canvas shrink threshold detents. */
    GESTURES("gestures");

    companion object {
        fun fromId(id: String?): HapticCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Global tactile intensity profile preset.
 */
enum class HapticProfile(val id: String, val amplitudeMultiplier: Float) {
    OFF("off", 0f),
    SUBTLE("subtle", 0.55f),
    BALANCED("balanced", 0.80f),
    EXPRESSIVE("expressive", 1.0f),
    CUSTOM("custom", 1.0f);

    companion object {
        fun fromId(id: String?): HapticProfile =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: EXPRESSIVE
    }
}

/**
 * Platform-neutral fallback semantic constant used when the host hardware or OS API does not
 * support custom amplitude/timing waveforms.
 */
enum class HapticFallbackKind {
    TAP,
    HEAVY_TAP,
    TICK,
    LONG_PRESS,
    CONFIRM,
    REJECT,
    GESTURE_THRESHOLD,
}

/**
 * Hardware-independent tactile waveform specification.
 *
 * [timings] alternates `[offDurationMs, onDurationMs, offDurationMs, onDurationMs, ...]` and
 * [amplitudes] specifies the corresponding motor drive strength (`0..255`, where `0` is off).
 *
 * Supports lossless string encoding (`encode` / `decode`) so user-customized patterns per
 * [HapticEvent] can be persisted in the flat preference store (`prefs.properties` / `NSUserDefaults`).
 */
@Immutable
class HapticWaveform(
    timings: LongArray,
    amplitudes: IntArray,
    val fallback: HapticFallbackKind = HapticFallbackKind.TAP,
) {
    private val _timings: LongArray = timings.copyOf()
    private val _amplitudes: IntArray = amplitudes.copyOf()

    init {
        require(_timings.isNotEmpty() && _timings.size == _amplitudes.size) {
            "HapticWaveform timings (${_timings.size}) and amplitudes (${_amplitudes.size}) must be non-empty and equal length"
        }
    }

    val timings: LongArray get() = _timings.copyOf()
    val amplitudes: IntArray get() = _amplitudes.copyOf()

    /** True when this waveform is a single contiguous pulse (`[0L, durationMs]`). */
    val isOneShot: Boolean
        get() = _timings.size == 2 && _timings[0] == 0L && _timings[1] > 0L

    val oneShotDurationMs: Long
        get() = if (_timings.size >= 2) _timings[1] else _timings[0]

    val oneShotAmplitude: Int
        get() = if (_amplitudes.size >= 2) _amplitudes[1] else _amplitudes[0]

    /**
     * Returns a copy of this waveform with all non-zero amplitudes scaled by [multiplier] and
     * clamped to the valid actuator range `1..255`.
     */
    fun scaleAmplitude(multiplier: Float): HapticWaveform {
        if (multiplier == 1f) return this
        val scaled = IntArray(_amplitudes.size) { i ->
            val raw = _amplitudes[i]
            if (raw <= 0) 0 else (raw * multiplier).roundToInt().coerceIn(1, 255)
        }
        return HapticWaveform(_timings, scaled, fallback)
    }

    /**
     * Compact preference-safe serialization: `"0:0,10:115,34:0,10:155|TICK"`.
     */
    fun encode(): String {
        val pairs = _timings.indices.joinToString(",") { i -> "${_timings[i]}:${_amplitudes[i]}" }
        return "$pairs|${fallback.name}"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HapticWaveform) return false
        return _timings.contentEquals(other._timings) &&
            _amplitudes.contentEquals(other._amplitudes) &&
            fallback == other.fallback
    }

    override fun hashCode(): Int {
        var result = _timings.contentHashCode()
        result = 31 * result + _amplitudes.contentHashCode()
        result = 31 * result + fallback.hashCode()
        return result
    }

    companion object {
        fun oneShot(
            durationMs: Long,
            amplitude: Int,
            fallback: HapticFallbackKind = HapticFallbackKind.TAP,
        ): HapticWaveform = HapticWaveform(
            timings = longArrayOf(0L, durationMs.coerceAtLeast(1L)),
            amplitudes = intArrayOf(0, amplitude.coerceIn(1, 255)),
            fallback = fallback,
        )

        fun decode(encoded: String?): HapticWaveform? {
            val raw = encoded?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parts = raw.split('|')
            val pairsPart = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            val fallback = parts.getOrNull(1)
                ?.let { name -> HapticFallbackKind.entries.firstOrNull { it.name == name } }
                ?: HapticFallbackKind.TAP
            val tokens = pairsPart.split(',')
            if (tokens.isEmpty()) return null
            val timings = LongArray(tokens.size)
            val amplitudes = IntArray(tokens.size)
            for (i in tokens.indices) {
                val kv = tokens[i].split(':')
                if (kv.size != 2) return null
                val t = kv[0].trim().toLongOrNull() ?: return null
                val a = kv[1].trim().toIntOrNull() ?: return null
                if (t < 0L || a !in 0..255) return null
                timings[i] = t
                amplitudes[i] = a
            }
            return HapticWaveform(timings, amplitudes, fallback)
        }
    }
}

/**
 * Catalogue of semantic tactile events across the IDE, each paired with its [HapticCategory] and
 * default physical signature.
 */
enum class HapticEvent(
    val id: String,
    val category: HapticCategory,
    val defaultWaveform: HapticWaveform,
) {
    /** Crisp micro-tap for standard elevated buttons and chips. */
    TAP(
        id = "tap",
        category = HapticCategory.UI_INTERACTIONS,
        defaultWaveform = HapticWaveform.oneShot(10L, 115, HapticFallbackKind.TAP),
    ),

    /** Deeper physical click for primary actions (Run, Clone, Confirm). */
    HEAVY_TAP(
        id = "heavyTap",
        category = HapticCategory.UI_INTERACTIONS,
        defaultWaveform = HapticWaveform.oneShot(18L, 200, HapticFallbackKind.HEAVY_TAP),
    ),

    /** Distinct double-tap wood/mechanical initiation when a Git clone begins. */
    CLONE_START(
        id = "cloneStart",
        category = HapticCategory.GIT_CLONE,
        defaultWaveform = HapticWaveform(
            timings = longArrayOf(0L, 14L, 42L, 22L),
            amplitudes = intArrayOf(0, 150, 0, 230),
            fallback = HapticFallbackKind.LONG_PRESS,
        ),
    ),

    /** Signature "rat-a-tat" triple rhythmic pulse emitted periodically while a Git clone streams. */
    CLONE_RATATAT_PULSE(
        id = "cloneRatatatPulse",
        category = HapticCategory.GIT_CLONE,
        defaultWaveform = HapticWaveform(
            timings = longArrayOf(0L, 10L, 34L, 10L, 34L, 18L),
            amplitudes = intArrayOf(0, 115, 0, 155, 0, 215),
            fallback = HapticFallbackKind.TICK,
        ),
    ),

    /** Rising 3-tap rat-a-tat + resonant settle when a Git clone finishes and is ready to open. */
    CLONE_COMPLETE(
        id = "cloneComplete",
        category = HapticCategory.GIT_CLONE,
        defaultWaveform = HapticWaveform(
            timings = longArrayOf(0L, 11L, 28L, 13L, 28L, 16L, 52L, 34L),
            amplitudes = intArrayOf(0, 130, 0, 175, 0, 220, 0, 255),
            fallback = HapticFallbackKind.CONFIRM,
        ),
    ),

    /** Ignition pulse when a project build starts and the streaming build console opens. */
    BUILD_START(
        id = "buildStart",
        category = HapticCategory.BUILD_ENGINE,
        defaultWaveform = HapticWaveform(
            timings = longArrayOf(0L, 12L, 36L, 28L),
            amplitudes = intArrayOf(0, 145, 0, 235),
            fallback = HapticFallbackKind.HEAVY_TAP,
        ),
    ),

    /** Subtle mechanical tick as each build task/step completes in the background. */
    BUILD_STEP_TICK(
        id = "buildStepTick",
        category = HapticCategory.BUILD_ENGINE,
        defaultWaveform = HapticWaveform.oneShot(8L, 95, HapticFallbackKind.TICK),
    ),

    /** Crisp ascending double-chime tactile confirmation when a build succeeds. */
    BUILD_SUCCESS(
        id = "buildSuccess",
        category = HapticCategory.BUILD_ENGINE,
        defaultWaveform = HapticWaveform(
            timings = longArrayOf(0L, 14L, 46L, 30L),
            amplitudes = intArrayOf(0, 165, 0, 255),
            fallback = HapticFallbackKind.CONFIRM,
        ),
    ),

    /** Double heavy thud when a build or clone fails. */
    BUILD_FAILED(
        id = "buildFailed",
        category = HapticCategory.BUILD_ENGINE,
        defaultWaveform = HapticWaveform(
            timings = longArrayOf(0L, 42L, 60L, 58L),
            amplitudes = intArrayOf(0, 235, 0, 255),
            fallback = HapticFallbackKind.REJECT,
        ),
    ),

    /** Detent snap when a predictive-back gesture crosses the half-screen split or code-shrink threshold. */
    PREDICTIVE_BACK_THRESHOLD(
        id = "predictiveBackThreshold",
        category = HapticCategory.GESTURES,
        defaultWaveform = HapticWaveform.oneShot(11L, 145, HapticFallbackKind.GESTURE_THRESHOLD),
    );

    companion object {
        fun fromId(id: String?): HapticEvent? = entries.firstOrNull { it.id == id }
    }
}

/**
 * User-configurable settings for the IDE Haptics Engine, persisted via [HapticsStore] and `SettingsStore`.
 */
@Immutable
data class HapticsConfig(
    /** Master switch for all IDE haptics. */
    val enabled: Boolean = true,
    /** Tactile profile preset ([HapticProfile.SUBTLE], [HapticProfile.BALANCED], [HapticProfile.EXPRESSIVE], [HapticProfile.OFF]). */
    val profile: HapticProfile = HapticProfile.EXPRESSIVE,
    /** Overall motor amplitude percentage (`20..150`, default `100`). */
    val intensityPercent: Int = DEFAULT_INTENSITY_PERCENT,
    /** Enable tactile taps on COSS elevated buttons, filter chips, and primary CTAs. */
    val uiInteractionsEnabled: Boolean = true,
    /** Enable Git clone initiation, rhythmic rat-a-tat streaming heartbeat, and completion burst. */
    val gitCloneEnabled: Boolean = true,
    /** Enable build start ignition, background compiler step ticks, and build completion signatures. */
    val buildEngineEnabled: Boolean = true,
    /** Enable predictive-back half-screen and code-shrink gesture threshold detents. */
    val gestureDetentsEnabled: Boolean = true,
    /** Cadence in milliseconds between rhythmic rat-a-tat pulses while a Git clone streams (`500..5000`). */
    val clonePulseIntervalMs: Int = DEFAULT_CLONE_PULSE_MS,
    /** Optional user-defined waveform overrides per [HapticEvent]. */
    val customPatterns: Map<HapticEvent, HapticWaveform> = emptyMap(),
) {
    /** Whether haptics are currently active for [category]. */
    fun isEnabledFor(category: HapticCategory): Boolean {
        if (!enabled || profile == HapticProfile.OFF || intensityPercent <= 0) return false
        return when (category) {
            HapticCategory.UI_INTERACTIONS -> uiInteractionsEnabled
            HapticCategory.GIT_CLONE -> gitCloneEnabled
            HapticCategory.BUILD_ENGINE -> buildEngineEnabled
            HapticCategory.GESTURES -> gestureDetentsEnabled
        }
    }

    /** Combined amplitude scale factor from [profile] and [intensityPercent]. */
    fun effectiveAmplitudeScale(): Float {
        if (!enabled || profile == HapticProfile.OFF) return 0f
        val clamped = intensityPercent.coerceIn(0, MAX_INTENSITY_PERCENT)
        return (clamped / 100f) * profile.amplitudeMultiplier
    }

    /**
     * Resolves the effective [HapticWaveform] to play for [event], applying any user-configured
     * custom pattern override and scaling amplitudes by [effectiveAmplitudeScale].
     * Returns `null` when [event]'s category or master haptics are disabled.
     */
    fun resolveWaveform(event: HapticEvent): HapticWaveform? {
        if (!isEnabledFor(event.category)) return null
        val scale = effectiveAmplitudeScale()
        if (scale <= 0f) return null
        val base = customPatterns[event] ?: event.defaultWaveform
        return base.scaleAmplitude(scale)
    }

    companion object {
        const val DEFAULT_INTENSITY_PERCENT = 100
        const val MIN_INTENSITY_PERCENT = 20
        const val MAX_INTENSITY_PERCENT = 150

        const val DEFAULT_CLONE_PULSE_MS = 1750
        const val MIN_CLONE_PULSE_MS = 500
        const val MAX_CLONE_PULSE_MS = 5000
    }
}

/**
 * Persistence store for [HapticsConfig] over the app-wide key/value preference seam
 * (`SettingsService.preference` / `setPreference` — backed by `prefs.properties` on Android & Desktop
 * and `NSUserDefaults` on iOS).
 *
 * Keys share the `settings.appearance.haptics*` namespace with `SettingsStore` and `BuiltInSettingsPages`
 * so changes made through the Settings UI, `SettingsStore`, or directly through [HapticsStore] stay
 * in sync. Per-event custom waveforms are stored under `settings.haptics.pattern.<eventId>`.
 */
class HapticsStore(
    private val get: (String) -> String?,
    private val set: (String, String) -> Unit,
) {
    fun load(): HapticsConfig {
        val d = HapticsConfig()
        val custom = LinkedHashMap<HapticEvent, HapticWaveform>()
        for (event in HapticEvent.entries) {
            val raw = get(patternKey(event))
            val decoded = HapticWaveform.decode(raw)
            if (decoded != null) {
                custom[event] = decoded
            }
        }
        return HapticsConfig(
            enabled = bool(KEY_ENABLED, d.enabled),
            profile = HapticProfile.fromId(get(KEY_PROFILE)),
            intensityPercent = int(KEY_INTENSITY, d.intensityPercent)
                .coerceIn(HapticsConfig.MIN_INTENSITY_PERCENT, HapticsConfig.MAX_INTENSITY_PERCENT),
            uiInteractionsEnabled = bool(KEY_UI_INTERACTIONS, d.uiInteractionsEnabled),
            gitCloneEnabled = bool(KEY_GIT_CLONE, d.gitCloneEnabled),
            buildEngineEnabled = bool(KEY_BUILD_ENGINE, d.buildEngineEnabled),
            gestureDetentsEnabled = bool(KEY_GESTURE_DETENTS, d.gestureDetentsEnabled),
            clonePulseIntervalMs = int(KEY_CLONE_PULSE_MS, d.clonePulseIntervalMs)
                .coerceIn(HapticsConfig.MIN_CLONE_PULSE_MS, HapticsConfig.MAX_CLONE_PULSE_MS),
            customPatterns = custom,
        )
    }

    fun save(config: HapticsConfig) {
        set(KEY_ENABLED, config.enabled.toString())
        set(KEY_PROFILE, config.profile.id)
        set(
            KEY_INTENSITY,
            config.intensityPercent.coerceIn(HapticsConfig.MIN_INTENSITY_PERCENT, HapticsConfig.MAX_INTENSITY_PERCENT).toString(),
        )
        set(KEY_UI_INTERACTIONS, config.uiInteractionsEnabled.toString())
        set(KEY_GIT_CLONE, config.gitCloneEnabled.toString())
        set(KEY_BUILD_ENGINE, config.buildEngineEnabled.toString())
        set(KEY_GESTURE_DETENTS, config.gestureDetentsEnabled.toString())
        set(
            KEY_CLONE_PULSE_MS,
            config.clonePulseIntervalMs.coerceIn(HapticsConfig.MIN_CLONE_PULSE_MS, HapticsConfig.MAX_CLONE_PULSE_MS).toString(),
        )
        for (event in HapticEvent.entries) {
            val override = config.customPatterns[event]
            if (override != null) {
                set(patternKey(event), override.encode())
            } else if (get(patternKey(event)) != null) {
                set(patternKey(event), "")
            }
        }
    }

    fun setEnabled(enabled: Boolean) = set(KEY_ENABLED, enabled.toString())

    fun setProfile(profile: HapticProfile) = set(KEY_PROFILE, profile.id)

    fun setIntensityPercent(percent: Int) = set(
        KEY_INTENSITY,
        percent.coerceIn(HapticsConfig.MIN_INTENSITY_PERCENT, HapticsConfig.MAX_INTENSITY_PERCENT).toString(),
    )

    fun setCategoryEnabled(category: HapticCategory, enabled: Boolean) {
        val key = when (category) {
            HapticCategory.UI_INTERACTIONS -> KEY_UI_INTERACTIONS
            HapticCategory.GIT_CLONE -> KEY_GIT_CLONE
            HapticCategory.BUILD_ENGINE -> KEY_BUILD_ENGINE
            HapticCategory.GESTURES -> KEY_GESTURE_DETENTS
        }
        set(key, enabled.toString())
    }

    fun setClonePulseIntervalMs(intervalMs: Int) = set(
        KEY_CLONE_PULSE_MS,
        intervalMs.coerceIn(HapticsConfig.MIN_CLONE_PULSE_MS, HapticsConfig.MAX_CLONE_PULSE_MS).toString(),
    )

    fun setCustomPattern(event: HapticEvent, waveform: HapticWaveform?) {
        set(patternKey(event), waveform?.encode().orEmpty())
    }

    fun resetCustomPatterns() {
        for (event in HapticEvent.entries) {
            if (get(patternKey(event)) != null) {
                set(patternKey(event), "")
            }
        }
    }

    private fun bool(key: String, def: Boolean): Boolean = get(key)?.toBooleanStrictOrNull() ?: def
    private fun int(key: String, def: Int): Int = get(key)?.trim()?.toIntOrNull() ?: def
    private fun patternKey(event: HapticEvent): String = "$PATTERN_PREFIX${event.id}"

    companion object {
        const val KEY_ENABLED = "settings.appearance.hapticsEnabled"
        const val KEY_PROFILE = "settings.appearance.hapticsProfile"
        const val KEY_INTENSITY = "settings.appearance.hapticsIntensity"
        const val KEY_UI_INTERACTIONS = "settings.appearance.hapticsUiInteractions"
        const val KEY_GIT_CLONE = "settings.appearance.hapticsGitClone"
        const val KEY_BUILD_ENGINE = "settings.appearance.hapticsBuildEngine"
        const val KEY_GESTURE_DETENTS = "settings.appearance.hapticsGestureDetents"
        const val KEY_CLONE_PULSE_MS = "settings.appearance.hapticsClonePulseMs"
        const val PATTERN_PREFIX = "settings.haptics.pattern."
    }
}

/**
 * Low-level platform vibration actuator consumed by [ConfigurableIdeHaptics].
 */
fun interface HapticDriver {
    fun perform(waveform: HapticWaveform)

    companion object {
        val NoOp = HapticDriver { }
    }
}

/**
 * Rich multiplatform tactile feedback engine designed for multitasking awareness and user configurability.
 *
 * Each IDE operation dispatches a typed [HapticEvent] through [perform], which checks [config]
 * (master switch, category toggles, intensity scaling, and user custom patterns) before delegating
 * to the platform [HapticDriver].
 */
@Stable
interface IdeHaptics {
    /** Active user configuration governing category filters, intensity scaling, and pulse cadence. */
    val config: HapticsConfig
        get() = HapticsConfig()

    /** Dispatches [event] if enabled in [config], applying profile/intensity scaling and custom overrides. */
    fun perform(event: HapticEvent)

    /** Crisp micro-tap for standard elevated buttons and chips. */
    fun tap() = perform(HapticEvent.TAP)

    /** Deeper physical click for primary actions (Run, Clone, Confirm). */
    fun heavyTap() = perform(HapticEvent.HEAVY_TAP)

    /** Distinct double-tap wood/mechanical initiation when a Git clone begins. */
    fun cloneStart() = perform(HapticEvent.CLONE_START)

    /** Signature "rat-a-tat" triple rhythmic pulse emitted periodically while a Git clone is actively streaming. */
    fun cloneRatatatPulse() = perform(HapticEvent.CLONE_RATATAT_PULSE)

    /** Rising 3-tap rat-a-tat + resonant settle when a Git clone finishes and is ready to open. */
    fun cloneComplete() = perform(HapticEvent.CLONE_COMPLETE)

    /** Ignition pulse when a project build starts and the streaming build console opens. */
    fun buildStart() = perform(HapticEvent.BUILD_START)

    /** Subtle mechanical tick as each build task/step completes in the background. */
    fun buildStepTick() = perform(HapticEvent.BUILD_STEP_TICK)

    /** Crisp ascending double-chime tactile confirmation when a build succeeds. */
    fun buildSuccess() = perform(HapticEvent.BUILD_SUCCESS)

    /** Double heavy thud when a build or clone fails. */
    fun buildFailed() = perform(HapticEvent.BUILD_FAILED)

    /** Detent snap when a predictive-back gesture crosses the half-screen split or code-shrink threshold. */
    fun predictiveBackThreshold() = perform(HapticEvent.PREDICTIVE_BACK_THRESHOLD)

    companion object {
        val NoOp: IdeHaptics = object : IdeHaptics {
            override val config: HapticsConfig = HapticsConfig(enabled = false, profile = HapticProfile.OFF)
            override fun perform(event: HapticEvent) {}
        }
    }
}

/**
 * Stateful, configurable [IdeHaptics] implementation backed by a platform [HapticDriver] and reactive [HapticsConfig].
 */
@Stable
class ConfigurableIdeHaptics(
    private val driver: HapticDriver,
    initialConfig: HapticsConfig = HapticsConfig(),
) : IdeHaptics {
    override var config: HapticsConfig by mutableStateOf(initialConfig)

    override fun perform(event: HapticEvent) {
        val resolved = config.resolveWaveform(event) ?: return
        driver.perform(resolved)
    }
}

/**
 * App-wide [IdeHaptics] CompositionLocal provided at the root of `CodeAssistApp`.
 */
val LocalIdeHaptics = staticCompositionLocalOf<IdeHaptics> { IdeHaptics.NoOp }

/**
 * Remembers the low-level platform [HapticDriver] for the current host (Android VibratorManager, iOS UIFeedbackGenerator, or Desktop NoOp).
 */
@Composable
expect fun rememberPlatformHapticDriver(): HapticDriver

/**
 * Creates and remembers a [ConfigurableIdeHaptics] bound to [config] for providing via [LocalIdeHaptics] at the app root.
 */
@Composable
fun rememberConfiguredIdeHaptics(config: HapticsConfig): IdeHaptics {
    val driver = rememberPlatformHapticDriver()
    val engine = remember(driver) { ConfigurableIdeHaptics(driver, config) }
    SideEffect {
        engine.config = config
    }
    return engine
}

/**
 * Returns the app-wide [IdeHaptics] engine from [LocalIdeHaptics], or remembers a default platform-backed
 * [ConfigurableIdeHaptics] when composed in an isolated preview/test outside `CodeAssistApp`.
 */
@Composable
fun rememberIdeHaptics(): IdeHaptics {
    val provided = LocalIdeHaptics.current
    if (provided !== IdeHaptics.NoOp) return provided
    val driver = rememberPlatformHapticDriver()
    return remember(driver) { ConfigurableIdeHaptics(driver) }
}
