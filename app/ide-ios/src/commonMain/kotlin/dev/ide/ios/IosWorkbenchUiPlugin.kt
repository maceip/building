package dev.ide.ios

import dev.ide.ui.backend.UiActionPlaces
import dev.ide.ui.ext.SimpleUiAction
import dev.ide.ui.ext.UiContributionScope
import dev.ide.ui.ext.UiPlugin

/**
 * Workbench UI contributions for iOS: run actions in More menu, command palette, and tool stripe.
 */
object IosWorkbenchUiPlugin : UiPlugin {
    override val id = "developer-workbench"

    override fun contributeUi(scope: UiContributionScope) {
        val more = UiActionPlaces.MORE_MENU
        val palette = UiActionPlaces.COMMAND_PALETTE
        val stripe = UiActionPlaces.TOOL_STRIPE
        val overflow = UiActionPlaces.MAIN_OVERFLOW

        scope.action(
            SimpleUiAction(
                id = "ui.runNodeProject",
                text = "Run Node project",
                places = setOf(more, overflow, stripe, palette),
                description = "Run a package script or JavaScript file",
                iconId = "run",
                order = 60,
                visible = { runCatching { it.backend.project.isNode }.getOrDefault(false) },
            ) { context ->
                context.backend.build.runBuild()
            },
        )

        scope.action(
            SimpleUiAction(
                id = "ui.developerWorkbench",
                text = "Run project",
                places = setOf(more, palette),
                description = "Run multi-runtime scripts (Python, Node, Swift, Shell)",
                iconId = "run",
                order = 65,
                visible = { !runCatching { it.backend.project.isNode }.getOrDefault(false) },
            ) { context ->
                context.backend.build.runBuild()
            },
        )

        scope.action(
            SimpleUiAction(
                id = "ui.runtimeDiagnostics",
                text = "Diagnostics",
                places = setOf(palette, more),
                description = "Review local incidents and project checks",
                iconId = "logs",
                order = 75,
            ) { context ->
                context.backend.build.runBuild()
            },
        )
    }
}
