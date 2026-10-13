package dev.ide.ui.screens

import dev.ide.ui.IdeUiState
import dev.ide.ui.StubBackend
import dev.ide.ui.ext.ScreenContribution
import dev.ide.ui.ext.ScreenRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContributedScreenNavigationTest {
    @Test
    fun anEditorActionCanOpenAContributedScreenAndCloseItsMenu() {
        val state = IdeUiState(
            StubBackend(),
            mainDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        var closed = false
        var destination: String? = null
        val host = editorActionHost(
            state = state,
            onToggleTheme = {},
            onOpenHub = {},
            onOpenIconManager = {},
            onOpenDependencies = {},
            onOpenModuleConfig = {},
            onCloseProject = {},
            onDone = { closed = true },
            onOpenPlugin = { destination = it },
        )
        try {
            host.navigate("developer.workbench")
            assertTrue(closed)
            assertEquals("developer.workbench", destination)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun toolsVisibilityIsOptInAndUnregistersWithTheScreen() {
        val screen = ScreenContribution("test.workbench.tools", "Workbench") { }
        assertFalse(screen.showInTools)
        screen.showInTools = true
        screen.toolDescription = "Local development tools"
        val registration = ScreenRegistry.register(screen)
        try {
            assertEquals(screen, ScreenRegistry.find(screen.id))
            assertTrue(ScreenRegistry.all().filter { it.showInTools }.contains(screen))
        } finally {
            registration.dispose()
        }
        assertNull(ScreenRegistry.find(screen.id))
    }
}
