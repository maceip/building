package dev.ide.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ide.ui.HomeTab
import dev.ide.ui.components.NavDestination
import dev.ide.ui.components.RareCounter
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.home_store
import dev.ide.ui.generated.resources.projects
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.theme.Motion
import dev.ide.ui.theme.Symbol
import org.jetbrains.compose.resources.stringResource

/** From this width the home tabs sit beside a sidebar instead of above a bottom bar. */
private val HomeSidebarBreakpoint = 840.dp
private val HomeSidebarWidth = 220.dp

/**
 * True inside the home tabs while the sidebar is showing, so a tab can leave out what the sidebar already
 * offers (the Projects tab drops its inbox and account buttons).
 */
val LocalHomeSidebarShown = staticCompositionLocalOf { false }

/**
 * The home/landing scaffold: the selected [HomeTab]'s content with navigation between the project manager,
 * the Projects Store, Learn, and the daily challenge. Each tab's content is supplied by the host (so all the
 * picker/store/learn wiring stays in one place) and crossfades on switch. Only shown on `Screen.Projects`;
 * full-screen destinations (editor, settings, run) push over it without the navigation.
 *
 * On a phone the content fills the screen — the Projects tab carries its own Explore entry in the header
 * (the store's destination), so no bottom bar is spent on one tab switch. From [HomeSidebarBreakpoint] up
 * (a tablet, a desktop window, a phone in landscape) it is a left sidebar instead, IntelliJ's
 * welcome-screen layout, with [projectCount] beside Projects and [sidebarFooter] (the inbox and account
 * entries) at its foot.
 */
@Composable
fun HomeScreen(
    tab: HomeTab,
    onSelectTab: (HomeTab) -> Unit,
    projectsContent: @Composable () -> Unit,
    storeContent: @Composable () -> Unit,
    learnContent: @Composable () -> Unit = {},
    challengesContent: @Composable () -> Unit = {},
    projectCount: Int? = null,
    sidebarFooter: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val destinations = listOf(
        NavDestination(HomeTab.Projects.name, stringResource(Res.string.projects), CaSymbols.folderOpen),
        NavDestination(HomeTab.Store.name, stringResource(Res.string.home_store), CaSymbols.travelExplore),
    )
    // Matched against the entries rather than `valueOf`, which THROWS on an id that is not a tab name. The
    // navigation speaks in strings, so any id that does not round-trip (a destination another build
    // contributed, a tab this build no longer has) reaches here, and a tap must never be able to take the app
    // down. An unknown id selects nothing.
    val select: (String) -> Unit = { id -> HomeTab.entries.firstOrNull { it.name == id }?.let(onSelectTab) }
    val content: @Composable (Modifier) -> Unit = { modifier ->
        Crossfade(
            targetState = tab,
            animationSpec = tween(Motion.BASE, easing = Motion.soft),
            label = "homeTab",
            modifier = modifier,
        ) { t ->
            Box(Modifier.fillMaxSize()) {
                when (t) {
                    HomeTab.Projects -> projectsContent()
                    HomeTab.Store -> storeContent()
                    else -> projectsContent()
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= HomeSidebarBreakpoint) {
            Row(Modifier.fillMaxSize()) {
                HomeSidebar(
                    destinations = destinations,
                    selectedId = tab.name,
                    onSelect = select,
                    counts = mapOf(HomeTab.Projects.name to projectCount),
                    footer = sidebarFooter,
                )
                CompositionLocalProvider(LocalHomeSidebarShown provides true) {
                    content(Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            // No bottom bar: the phone home is one screen. The keyboard-inset dance the bar needed is gone
            // with it; the app still pads for the keyboard at its root (`safeDrawing`).
            Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The wide home's left sidebar: the app's name, one row per destination, and [footer] pinned at the foot. */
@Composable
private fun HomeSidebar(
    destinations: List<NavDestination>,
    selectedId: String,
    onSelect: (String) -> Unit,
    counts: Map<String, Int?>,
    footer: (@Composable ColumnScope.() -> Unit)?,
) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(HomeSidebarWidth).fillMaxHeight().background(c.surfaceContainerLow)
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "building",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = c.onSurface,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 16.dp),
            )
            val centers = remember { mutableStateMapOf<Int, Float>() }
            val density = LocalDensity.current
            val selectedIndex = destinations.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
            val targetY = centers[selectedIndex] ?: (selectedIndex * 38f + 15f)
            val markerY by animateFloatAsState(
                targetValue = targetY,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = 300f),
                label = "sidebarBounceMarker",
            )
            Box(Modifier.selectableGroup()) {
                Box(
                    Modifier
                        .offset { IntOffset(2.dp.roundToPx(), markerY.dp.roundToPx()) }
                        .size(5.dp)
                        .background(c.primary, CircleShape),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    destinations.forEachIndexed { index, d ->
                        Box(
                            Modifier.onGloballyPositioned { coordinates ->
                                centers[index] = with(density) {
                                    (coordinates.positionInParent().y + coordinates.size.height / 2f).toDp().value - 2.5f
                                }
                            },
                        ) {
                            HomeSidebarItem(
                                glyph = d.glyph,
                                label = d.label,
                                selected = d.id == selectedId,
                                count = counts[d.id],
                                role = Role.Tab,
                                onClick = { onSelect(d.id) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            footer?.invoke(this)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.outlineVariant))
    }
}

/**
 * One row of the home sidebar: a glyph, a label, and an optional count or badge. [selected] fills it with the
 * secondary container, the same selection the editor's tool window stripes use.
 */
@Composable
fun HomeSidebarItem(
    glyph: Char,
    label: String,
    selected: Boolean = false,
    count: Int? = null,
    /** A count drawn as an alert (the inbox's unread notifications) rather than a quiet total. */
    countIsAlert: Boolean = false,
    role: Role = Role.Button,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) c.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) c.onSecondaryContainer else c.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().height(36.dp).semantics {
            this.role = role
            if (role == Role.Tab) this.selected = selected
        },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Symbol(glyph, contentDescription = null, size = 18.dp, filled = selected)
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count != null && count > 0) {
                Box(
                    modifier = if (countIsAlert) {
                        Modifier.background(c.error, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                    } else {
                        Modifier
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    RareCounter(
                        value = count,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (countIsAlert) c.onError else c.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
