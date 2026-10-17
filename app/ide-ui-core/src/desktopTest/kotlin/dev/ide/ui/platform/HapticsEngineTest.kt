package dev.ide.ui.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HapticsEngineTest {

    @Test
    fun `waveform encode and decode round-trips timings amplitudes and fallback`() {
        val original = HapticEvent.CLONE_RATATAT_PULSE.defaultWaveform
        val encoded = original.encode()
        assertEquals("0:0,10:115,34:0,10:155,34:0,18:215|TICK", encoded)

        val decoded = HapticWaveform.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original, decoded)
        assertFalse(decoded.isOneShot)
    }

    @Test
    fun `waveform decode rejects malformed input gracefully`() {
        assertNull(HapticWaveform.decode(null))
        assertNull(HapticWaveform.decode(""))
        assertNull(HapticWaveform.decode("invalid"))
        assertNull(HapticWaveform.decode("10:abc|TAP"))
    }

    @Test
    fun `waveform scaleAmplitude scales non-zero amplitudes and preserves rest intervals`() {
        val original = HapticWaveform(
            timings = longArrayOf(0L, 20L, 40L, 15L),
            amplitudes = intArrayOf(100, 0, 200, 0),
            fallback = HapticFallbackKind.CONFIRM,
        )
        val half = original.scaleAmplitude(0.5f)
        assertEquals(listOf(50, 0, 100, 0), half.amplitudes.toList())
        assertEquals(original.timings.toList(), half.timings.toList())
        assertEquals(HapticFallbackKind.CONFIRM, half.fallback)

        val boosted = original.scaleAmplitude(1.5f)
        // 200 * 1.5 clamps at 255
        assertEquals(listOf(150, 0, 255, 0), boosted.amplitudes.toList())
    }

    @Test
    fun `hapticsStore round-trips config and custom per-event waveforms`() {
        val prefs = mutableMapOf<String, String>()
        val store = HapticsStore(prefs::get, prefs::put)

        // Default load when preference map is empty
        val defaults = store.load()
        assertTrue(defaults.enabled)
        assertEquals(HapticProfile.EXPRESSIVE, defaults.profile)
        assertEquals(100, defaults.intensityPercent)
        assertTrue(defaults.isEnabledFor(HapticCategory.GIT_CLONE))
        assertEquals(1750, defaults.clonePulseIntervalMs)

        // Save customized config with a custom rat-a-tat waveform
        val customPulse = HapticWaveform(
            timings = longArrayOf(0L, 12L, 25L, 12L),
            amplitudes = intArrayOf(180, 0, 240, 0),
            fallback = HapticFallbackKind.TICK,
        )
        val modified = HapticsConfig(
            enabled = true,
            profile = HapticProfile.BALANCED,
            intensityPercent = 80,
            uiInteractionsEnabled = false,
            gitCloneEnabled = true,
            buildEngineEnabled = true,
            gestureDetentsEnabled = false,
            clonePulseIntervalMs = 2250,
            customPatterns = mapOf(HapticEvent.CLONE_RATATAT_PULSE to customPulse),
        )
        store.save(modified)

        // Verify preference keys match SettingsStore ("settings.appearance.haptics*")
        assertEquals("balanced", prefs[HapticsStore.KEY_PROFILE])
        assertEquals("80", prefs[HapticsStore.KEY_INTENSITY])
        assertEquals("false", prefs[HapticsStore.KEY_UI_INTERACTIONS])
        assertEquals("2250", prefs[HapticsStore.KEY_CLONE_PULSE_MS])

        val reloaded = store.load()
        assertEquals(modified, reloaded)
        assertFalse(reloaded.isEnabledFor(HapticCategory.UI_INTERACTIONS))
        assertTrue(reloaded.isEnabledFor(HapticCategory.GIT_CLONE))
        assertEquals(customPulse, reloaded.customPatterns[HapticEvent.CLONE_RATATAT_PULSE])
    }

    @Test
    fun `configurableIdeHaptics dispatches scaled waveforms and respects category toggles`() {
        val dispatched = mutableListOf<HapticWaveform>()
        val engine = ConfigurableIdeHaptics(
            driver = HapticDriver { waveform -> dispatched.add(waveform) },
            initialConfig = HapticsConfig(
                enabled = true,
                profile = HapticProfile.SUBTLE, // 0.55x multiplier
                intensityPercent = 100,
                uiInteractionsEnabled = false,
                gitCloneEnabled = true,
            ),
        )

        // UI interactions are disabled -> should not fire driver
        engine.tap()
        engine.heavyTap()
        assertTrue(dispatched.isEmpty())

        // Git clone is enabled -> should fire scaled waveform
        engine.cloneRatatatPulse()
        assertEquals(1, dispatched.size)
        val played = dispatched.single()
        // Subtle profile scales first pulse amplitude 115 * 0.55 = 63, while initial delay stays 0
        assertEquals(0, played.amplitudes[0])
        assertEquals(63, played.amplitudes[1])

        // Master switch off -> nothing fires
        engine.config = engine.config.copy(enabled = false)
        engine.cloneComplete()
        assertEquals(1, dispatched.size)
    }
}
