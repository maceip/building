package dev.ide.android.workbench.runtime

import dev.ide.ui.backend.SettingsService
import dev.ide.ui.backend.UiSettingControl
import dev.ide.ui.backend.UiSettingsPage
import dev.ide.workbench.runtime.RuntimeFeature
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model tests: no activity, subprocess, native pack, installation, or network is needed. */
class AndroidDeveloperToolsTest {
    private val manifest = """
        {"format":1,"packages":[
          {"name":"nodejs-lts","version":"24.9.1-2"},
          {"name":"npm","version":"11.7.3"},
          {"name":"python","version":"3.14.2-4"},
          {"name":"python-pip","version":"25.9.1"},
          {"name":"dash","version":"0.5.12-3"}
        ],"npmTools":[{"name":"pnpm","version":"10.29.0"}]}
    """.trimIndent()

    @Test fun versionsComeFromTheManifestAndPackageManagersStaySecondary() {
        val inventory = parseDeveloperRuntimeInventory(manifest)
        val node = requireNotNull(inventory.tool(RuntimeFeature.NODE))
        assertEquals("24.9.1-2", node.version)
        assertEquals("npm 11.7.3 · pnpm 10.29.0", node.secondary)
        assertTrue(node.description.startsWith("Bundled · "))
        assertFalse(node.description.contains("verified", ignoreCase = true))
        assertEquals("3.14.2-4", inventory.tool(RuntimeFeature.PYTHON)?.version)
        assertEquals("pip 25.9.1", inventory.tool(RuntimeFeature.PYTHON)?.secondary)
        assertEquals("0.5.12-3", inventory.tool(RuntimeFeature.SHELL)?.version)
        assertNull(inventory.tool(RuntimeFeature.STATIC))
        val changed = parseDeveloperRuntimeInventory(manifest.replace("24.9.1-2", "30.1.0"))
        assertEquals("30.1.0", changed.tool(RuntimeFeature.NODE)?.version)
    }

    @Test fun missingPackagesDoNotInventInstalledCapabilitiesOrVersions() {
        val empty = parseDeveloperRuntimeInventory("""{"format":1,"packages":[]}""")
        RuntimeFeature.entries.forEach { assertNull(empty.tool(it)) }
        val partial = parseDeveloperRuntimeInventory("""{"format":1,"packages":[{"name":"nodejs","version":"26.0"}]}""")
        assertEquals("26.0", partial.tool(RuntimeFeature.NODE)?.version)
        assertNull(partial.tool(RuntimeFeature.NODE)?.secondary)
        assertNull(partial.tool(RuntimeFeature.PYTHON))
        val shell = parseDeveloperRuntimeInventory("""{"format":1,"packages":[{"name":"bash","version":"5.9"}]}""")
        assertEquals("5.9", shell.tool(RuntimeFeature.SHELL)?.version)
    }

    @Test fun malformedUnknownAndAmbiguousDeclarationsFailVisibly() {
        listOf(
            "not json",
            """{"format":2,"packages":[]}""",
            """{"format":1}""",
            """{"format":1,"packages":[{"name":"python"}]}""",
            """{"format":1,"packages":[{"name":"python","version":""}]}""",
            """{"format":1,"packages":[{"name":"python","version":314}]}""",
            """{"format":1,"packages":[{"name":"python","version":"3.14"},{"name":"python","version":"3.15"}]}""",
            """{"format":1,"packages":[],"npmTools":null}""",
        ).forEach { text -> assertThrows(Exception::class.java) { parseDeveloperRuntimeInventory(text) } }
    }

    @Test fun manifestLoadingLeavesTheCallingThreadAndDoesNotExecuteTools() = runBlocking {
        val caller = Thread.currentThread()
        var reader: Thread? = null
        var reads = 0
        val result = loadDeveloperRuntimeInventory {
            reader = Thread.currentThread()
            reads++
            manifest
        }
        assertFalse(caller === reader)
        assertEquals(1, reads)
        assertEquals("24.9.1-2", result.tool(RuntimeFeature.NODE)?.version)
    }

    @Test fun missingPreferencesDefaultTrueAndAreReadFresh() {
        val stored = mutableMapOf<String, String>()
        val settings = object : SettingsService {
            override fun preference(key: String): String? = stored[key]
        }
        assertTrue(developerToolPreferences(settings).values.all { it })
        stored[RuntimeFeature.NODE.preferenceKey] = "false"
        stored[RuntimeFeature.PYTHON.preferenceKey] = "invalid"
        assertFalse(developerToolPreferences(settings).getValue(RuntimeFeature.NODE))
        assertTrue(developerToolPreferences(settings).getValue(RuntimeFeature.PYTHON))
        stored[RuntimeFeature.NODE.preferenceKey] = "true"
        assertTrue(developerToolPreferences(settings).getValue(RuntimeFeature.NODE))
    }

    @Test fun genericPageControlsAreAuthoritativeAndUseExistingKeys() {
        var node = false
        var write: Triple<String, String, String>? = null
        val settings = object : SettingsService {
            override fun settingsPages() = listOf(UiSettingsPage(
                id = RuntimeFeature.PAGE_ID, title = "Runtimes", iconId = "terminal", scope = "app",
                controls = listOf(UiSettingControl.Toggle(key = RuntimeFeature.NODE.key, title = "Node", value = node)),
            ))
            override fun preference(key: String): String? = "true"
            override fun setSetting(pageId: String, key: String, value: String) {
                write = Triple(pageId, key, value)
                node = value.toBooleanStrict()
            }
        }
        assertFalse(developerToolPreferences(settings).getValue(RuntimeFeature.NODE))
        assertTrue(developerToolPreferences(settings).getValue(RuntimeFeature.SHELL))
        settings.setSetting(RuntimeFeature.PAGE_ID, RuntimeFeature.NODE.key, "true")
        assertEquals(Triple("runtimes", "node", "true"), write)
        assertTrue(developerToolPreferences(settings).getValue(RuntimeFeature.NODE))
    }

    @Test fun preferenceFailuresAreNotSilentlyReportedAsEnabled() {
        val settings = object : SettingsService {
            override fun settingsPages(): List<UiSettingsPage> = error("unavailable")
        }
        assertThrows(IllegalStateException::class.java) { developerToolPreferences(settings) }
    }
}
