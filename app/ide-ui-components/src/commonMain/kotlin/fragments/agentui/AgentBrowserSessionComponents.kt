package fragments.agentui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fragments.chrome.AgentUiBaseTokens
import fragments.components.UiButton
import fragments.components.UiButtonVariant
import fragments.components.UiDivider
import fragments.components.UiText
import fragments.components.cossButtonElevation
import fragments.components.cossInputElevation
import fragments.components.cossSurfaceElevation

/** Lifecycle states for inline and expanded browser sessions (Boxes 3, 4, 6A, 6B). */
enum class AgentBrowserState {
    Starting,
    Working,
    ActionRequired,
    Completed,
}

/** Interactive tools in the floating takeover capsule toolbar (Box 2B). */
enum class AgentBrowserControlTool {
    Pointer,
    Copy,
    Paste,
}

/** Live status variants for the floating agent avatar header pill (Box 7). */
enum class AgentLiveStatusKind {
    Working,
    SigningIn,
    NeedsApproval,
    Idle,
}

/** User decisions on the site login / browser permission card (Box 10). */
enum class AgentBrowserPermissionDecision {
    AllowOnce,
    AlwaysAllowSite,
    Deny,
}

/** Submitted credential payload from the password modal sheet (Box 9). */
@Immutable
data class AgentCredentialSubmission(
    val site: String,
    val username: String,
    val password: String,
    val saveToStore: Boolean,
)

/**
 * Pill-shaped button used across browser sheets, permission cards, and password prompts.
 */
@Composable
fun AgentPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: UiButtonVariant = UiButtonVariant.Secondary,
    enabled: Boolean = true,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val textColor by animateColorAsState(
        targetValue = when {
            !enabled -> palette.muted
            variant == UiButtonVariant.Primary -> palette.accentText
            variant == UiButtonVariant.Destructive -> Color.White
            variant == UiButtonVariant.DestructiveOutline -> palette.danger
            else -> palette.text
        },
        animationSpec = tween(110),
        label = "pill button text color",
    )
    val sizing = AgentUiTheme.sizing
    Box(
        modifier
            .height(sizing.pillButtonHeight)
            .cossButtonElevation(
                palette = palette,
                variant = variant,
                radius = AgentUiBaseTokens.RadiusPill,
                pressed = pressed,
                hovered = hovered,
                enabled = enabled,
            )
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        UiText(
            value = label,
            palette = palette.copy(text = textColor),
            size = 13,
            weight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Boxes 1A & 2A — Full-screen / sheet browser top bar supporting both Agent-in-Control
 * (close `✕` + status title e.g. `"Task completed"` + `"Completed · domain"` + share `↑` button)
 * and User-in-Control (close `✕` + `"You're in control"` + domain + checkmark `✓` done button) states.
 */
@Composable
fun AgentBrowserTopBar(
    title: String = "Task completed",
    domain: String,
    userInControl: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    statusLabel: String = "Completed",
    onShare: (() -> Unit)? = null,
    onDoneControl: (() -> Unit)? = null,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val cleanDomain = domain
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore("/")
        .ifBlank { domain }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .testTag("agent-browser-top-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircleIconButton(
            onClick = onClose,
            palette = palette,
            size = 40.dp,
            modifier = Modifier.testTag("agent-browser-top-close"),
        ) {
            AgentIcon(AgentIconId.Close, size = 16.dp, color = palette.text)
        }
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            UiText(
                value = if (userInControl) "You're in control" else title,
                palette = palette,
                size = 15,
                weight = FontWeight.SemiBold,
                maxLines = 1,
            )
            UiText(
                value = if (userInControl) cleanDomain else "$statusLabel · $cleanDomain",
                palette = palette,
                muted = true,
                size = 12,
                maxLines = 1,
            )
        }
        if (userInControl) {
            CircleIconButton(
                onClick = { onDoneControl?.invoke() },
                palette = palette,
                size = 40.dp,
                modifier = Modifier.testTag("agent-browser-top-done"),
            ) {
                AgentIcon(AgentIconId.Check, size = 16.dp, color = palette.text)
            }
        } else {
            CircleIconButton(
                onClick = { onShare?.invoke() },
                palette = palette,
                size = 40.dp,
                modifier = Modifier.testTag("agent-browser-top-share"),
            ) {
                BrowserShareIcon(size = 16.dp, color = palette.text)
            }
        }
    }
}

/**
 * Box 2B — Floating quick-action capsule toolbar shown over the bottom of the webpage
 * when the user is in control (Pointer/Hand mode, Copy, Paste).
 */
@Composable
fun AgentBrowserFloatingToolbar(
    activeTool: AgentBrowserControlTool,
    onSelectTool: (AgentBrowserControlTool) -> Unit,
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val sizing = AgentUiTheme.sizing
    val buttonSize = if (sizing.isMobile) 36.dp else 32.dp
    Row(
        modifier
            .cossSurfaceElevation(palette = palette, radius = AgentUiBaseTokens.RadiusPill)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("agent-browser-floating-toolbar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentBrowserControlTool.entries.forEach { tool ->
            val selected = tool == activeTool
            Box(
                Modifier
                    .size(buttonSize)
                    .clip(CircleShape)
                    .background(if (selected) palette.subtle else Color.Transparent, CircleShape)
                    .clickable { onSelectTool(tool) }
                    .testTag("agent-browser-tool-${tool.name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                val tint = if (selected) palette.text else palette.muted
                when (tool) {
                    AgentBrowserControlTool.Pointer -> BrowserPointerHandIcon(size = 15.dp, color = tint)
                    AgentBrowserControlTool.Copy -> AgentIcon(AgentIconId.Copy, size = 15.dp, color = tint)
                    AgentBrowserControlTool.Paste -> BrowserClipboardIcon(size = 15.dp, color = tint)
                }
            }
        }
    }
}

/**
 * Boxes 1B & 2B — Browser bottom control bar.
 * - When `userInControl == false` (Box 1B): renders side-by-side equal-width `"Stop task"` and `"Take control"` pill buttons.
 * - When `userInControl == true` (Box 2B): renders the `"Stop task"` pill alongside the floating toolbar.
 */
@Composable
fun AgentBrowserBottomBar(
    userInControl: Boolean,
    onStopTask: () -> Unit,
    onTakeControl: () -> Unit,
    modifier: Modifier = Modifier,
    activeTool: AgentBrowserControlTool = AgentBrowserControlTool.Pointer,
    onSelectTool: (AgentBrowserControlTool) -> Unit = {},
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    if (!userInControl) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .testTag("agent-browser-bottom-bar"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentPillButton(
                label = "Stop task",
                onClick = onStopTask,
                modifier = Modifier.weight(1f).height(46.dp).testTag("agent-browser-stop-task"),
                variant = UiButtonVariant.Secondary,
                palette = palette,
            )
            AgentPillButton(
                label = "Take control",
                onClick = onTakeControl,
                modifier = Modifier.weight(1f).height(46.dp).testTag("agent-browser-take-control"),
                variant = UiButtonVariant.Primary,
                palette = palette,
            )
        }
    } else {
        Box(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .testTag("agent-browser-bottom-bar"),
        ) {
            AgentPillButton(
                label = "Stop task",
                onClick = onStopTask,
                modifier = Modifier.align(Alignment.CenterStart).height(44.dp).testTag("agent-browser-stop-task"),
                variant = UiButtonVariant.Secondary,
                palette = palette,
            )
            AgentBrowserFloatingToolbar(
                activeTool = activeTool,
                onSelectTool = onSelectTool,
                modifier = Modifier.align(Alignment.Center),
                palette = palette,
            )
        }
    }
}

/**
 * Full-screen / sheet browser chrome combining [AgentBrowserTopBar], the webpage viewport,
 * and [AgentBrowserBottomBar] (Boxes 1A, 1B, 2A, 2B).
 */
@Composable
fun AgentBrowserSheetChrome(
    title: String = "Task completed",
    domain: String,
    initialUserInControl: Boolean = false,
    onUserInControlChanged: ((Boolean) -> Unit)? = null,
    onStopTask: () -> Unit = {},
    onClose: () -> Unit = {},
    onShare: (() -> Unit)? = null,
    onToolAction: ((AgentBrowserControlTool) -> Unit)? = null,
    modifier: Modifier = Modifier,
    statusLabel: String = "Completed",
    palette: AgentUiPalette = AgentUiTheme.palette,
    page: @Composable (Modifier) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var userInControl by remember(title, domain) { mutableStateOf(initialUserInControl) }
    var activeTool by remember { mutableStateOf(AgentBrowserControlTool.Pointer) }
    Column(
        modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(10.dp)
            .testTag("agent-browser-sheet-chrome"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AgentBrowserTopBar(
            title = title,
            domain = domain,
            userInControl = userInControl,
            statusLabel = statusLabel,
            onClose = onClose,
            onShare = {
                clipboard.setText(AnnotatedString(domain))
                onShare?.invoke()
            },
            onDoneControl = {
                userInControl = false
                onUserInControlChanged?.invoke(false)
            },
            palette = palette,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .cossSurfaceElevation(palette = palette, radius = 18.dp)
                .testTag("agent-browser-sheet-viewport"),
        ) {
            page(Modifier.fillMaxSize())
        }
        AgentBrowserBottomBar(
            userInControl = userInControl,
            onStopTask = onStopTask,
            onTakeControl = {
                userInControl = true
                onUserInControlChanged?.invoke(true)
            },
            activeTool = activeTool,
            onSelectTool = { tool ->
                activeTool = tool
                if (tool == AgentBrowserControlTool.Copy) {
                    clipboard.setText(AnnotatedString(domain))
                }
                onToolAction?.invoke(tool)
            },
            palette = palette,
        )
    }
}

/**
 * Dedicated Mobile Embedded Browser View (`AgentMobileBrowserSheet`).
 */
@Composable
fun AgentMobileBrowserSheet(
    part: ConversationPart.Browser,
    palette: AgentUiPalette = AgentUiTheme.palette,
    modifier: Modifier = Modifier,
    state: AgentBrowserState = AgentBrowserState.Completed,
    statusTitle: String? = null,
    initialUserInControl: Boolean = false,
    onTakeoverChanged: ((Boolean) -> Unit)? = null,
    onNavigate: ((String) -> Unit)? = null,
    onShare: ((String) -> Unit)? = null,
    onStopTask: (() -> Unit)? = null,
    onClose: () -> Unit = {},
    page: @Composable (Modifier) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var userInControl by remember(part.sessionId) { mutableStateOf(initialUserInControl) }
    var activeTool by remember(part.sessionId) { mutableStateOf(AgentBrowserControlTool.Pointer) }
    val initialUrl = part.url?.ifBlank { "https://example.org" } ?: "https://example.org"
    var address by remember(part.sessionId, part.url) { mutableStateOf(initialUrl) }
    val history = remember(part.sessionId) { mutableStateListOf(initialUrl) }
    var historyIndex by remember(part.sessionId) { mutableIntStateOf(0) }
    var chromeCollapsed by remember(part.sessionId) { mutableStateOf(false) }

    val cleanHost = address
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore("/")
        .ifBlank { address }

    val resolvedHeaderTitle = when {
        userInControl -> "You're in control"
        !statusTitle.isNullOrBlank() -> statusTitle
        state == AgentBrowserState.Working -> "Working"
        state == AgentBrowserState.ActionRequired -> "Action required"
        state == AgentBrowserState.Starting -> "Starting browser..."
        else -> "Task completed"
    }
    val resolvedStatusPrefix = when (state) {
        AgentBrowserState.Working -> "Working"
        AgentBrowserState.ActionRequired -> "Action required"
        AgentBrowserState.Starting -> "Starting"
        AgentBrowserState.Completed -> "Completed"
    }

    fun navigateTo(rawUrl: String) {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return
        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        address = normalized
        if (historyIndex < history.lastIndex) {
            while (history.size > historyIndex + 1) {
                history.removeAt(history.lastIndex)
            }
        }
        if (history.lastOrNull() != normalized) {
            history.add(normalized)
            historyIndex = history.lastIndex
        }
        onNavigate?.invoke(normalized)
    }

    Column(
        modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("agent-mini-browser-pane"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 4.dp)
                .testTag("agent-browser-top-bar"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircleIconButton(
                onClick = onClose,
                palette = palette,
                size = 40.dp,
                modifier = Modifier.testTag("agent-mini-browser-close"),
            ) {
                AgentIcon(AgentIconId.Close, size = 16.dp, color = palette.text)
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                UiText(
                    value = resolvedHeaderTitle,
                    palette = palette,
                    size = 16,
                    weight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                UiText(
                    value = if (userInControl) cleanHost else "$resolvedStatusPrefix · $cleanHost",
                    palette = palette,
                    muted = true,
                    size = 12,
                    maxLines = 1,
                )
            }
            if (userInControl) {
                CircleIconButton(
                    onClick = {
                        userInControl = false
                        onTakeoverChanged?.invoke(false)
                    },
                    palette = palette,
                    size = 40.dp,
                    modifier = Modifier.testTag("agent-browser-top-done"),
                ) {
                    AgentIcon(AgentIconId.Check, size = 16.dp, color = palette.text)
                }
            } else {
                CircleIconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(address))
                        onShare?.invoke(address)
                    },
                    palette = palette,
                    size = 40.dp,
                    modifier = Modifier.testTag("agent-browser-top-share"),
                ) {
                    BrowserShareIcon(size = 16.dp, color = palette.text)
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, palette.border, RoundedCornerShape(18.dp))
                .background(Color.White, RoundedCornerShape(18.dp)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFDFE3E8))
                    .padding(start = 8.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 240.dp)
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                        .background(Color.White)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("agent-browser-active-tab"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(Color(0xFFE20074), RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        UiText(
                            value = cleanHost.take(1).uppercase().ifBlank { "W" },
                            palette = palette.copy(text = Color.White),
                            size = 10,
                            weight = FontWeight.Bold,
                        )
                    }
                    UiText(
                        value = part.title.ifBlank { cleanHost },
                        palette = palette.copy(text = Color(0xFF1F2328)),
                        size = 12,
                        weight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onClose)
                            .testTag("agent-browser-tab-close"),
                        contentAlignment = Alignment.Center,
                    ) {
                        AgentIcon(AgentIconId.Close, size = 10.dp, color = Color(0xFF444746))
                    }
                }
                Box(
                    Modifier
                        .padding(bottom = 4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { navigateTo("https://www.google.com") }
                        .testTag("agent-browser-tab-new"),
                    contentAlignment = Alignment.Center,
                ) {
                    AgentIcon(AgentIconId.Plus, size = 13.dp, color = Color(0xFF444746))
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .padding(bottom = 4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { chromeCollapsed = !chromeCollapsed }
                        .testTag("agent-browser-tab-collapse"),
                    contentAlignment = Alignment.Center,
                ) {
                    AgentIcon(
                        id = AgentIconId.ChevronDown,
                        size = 13.dp,
                        color = Color(0xFF444746),
                    )
                }
            }

            if (!chromeCollapsed) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val canGoBack = historyIndex > 0
                    val canGoForward = historyIndex < history.lastIndex
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(enabled = canGoBack) {
                                if (historyIndex > 0) {
                                    historyIndex--
                                    address = history[historyIndex]
                                    onNavigate?.invoke(address)
                                }
                            }
                            .testTag("agent-browser-nav-back"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BrowserNavArrowIcon(
                            forward = false,
                            size = 15.dp,
                            color = if (canGoBack) Color(0xFF1F2328) else Color(0xFF9AA0A6),
                        )
                    }
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(enabled = canGoForward) {
                                if (historyIndex < history.lastIndex) {
                                    historyIndex++
                                    address = history[historyIndex]
                                    onNavigate?.invoke(address)
                                }
                            }
                            .testTag("agent-browser-nav-forward"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BrowserNavArrowIcon(
                            forward = true,
                            size = 15.dp,
                            color = if (canGoForward) Color(0xFF1F2328) else Color(0xFF9AA0A6),
                        )
                    }
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { onNavigate?.invoke(address) }
                            .testTag("agent-browser-nav-reload"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BrowserReloadIcon(size = 15.dp, color = Color(0xFF1F2328))
                    }
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFFF1F3F4), RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BrowserTuneIcon(size = 14.dp, color = Color(0xFF444746))
                        BasicTextField(
                            value = address,
                            onValueChange = { address = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color(0xFF1F2328), fontSize = 12.sp),
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier
                                .weight(1f)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                        navigateTo(address)
                                        true
                                    } else false
                                }
                                .testTag("agent-mini-browser-address"),
                        )
                    }
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable {
                                userInControl = !userInControl
                                onTakeoverChanged?.invoke(userInControl)
                            }
                            .testTag("agent-browser-nav-menu"),
                        contentAlignment = Alignment.Center,
                    ) {
                        BrowserMenuIcon(size = 15.dp, color = Color(0xFF444746))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE5E7EB)))
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(palette.codeBackground)
                    .testTag("agent-mini-browser-page"),
            ) {
                page(Modifier.fillMaxSize())
                if (userInControl) {
                    AgentBrowserFloatingToolbar(
                        activeTool = activeTool,
                        onSelectTool = { tool ->
                            activeTool = tool
                            when (tool) {
                                AgentBrowserControlTool.Copy -> clipboard.setText(AnnotatedString(address))
                                AgentBrowserControlTool.Paste -> {
                                    val clipText = clipboard.getText()?.text?.trim().orEmpty()
                                    if (clipText.isNotEmpty()) {
                                        navigateTo(clipText)
                                    }
                                }
                                AgentBrowserControlTool.Pointer -> Unit
                            }
                        },
                        palette = palette,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 4.dp)
                .testTag("agent-browser-bottom-bar"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentPillButton(
                label = "Stop task",
                onClick = { onStopTask?.invoke() ?: onClose() },
                modifier = Modifier.weight(1f).height(46.dp).testTag("agent-browser-stop-task"),
                variant = UiButtonVariant.Secondary,
                palette = palette,
            )
            AgentPillButton(
                label = if (userInControl) "Done" else "Take control",
                onClick = {
                    userInControl = !userInControl
                    onTakeoverChanged?.invoke(userInControl)
                },
                modifier = Modifier.weight(1f).height(46.dp).testTag("agent-mini-browser-takeover"),
                variant = UiButtonVariant.Primary,
                palette = palette,
            )
        }
    }
}

/**
 * Dedicated Mobile Diff Review Sheet variant (`AgentMobileDiffReviewSheet`).
 * Replaces the 48% desktop side-pane on mobile viewports (`< 640.dp`) with a full-screen
 * mobile review sheet with a top drag handle and pill header.
 */
@Composable
fun AgentMobileDiffReviewSheet(
    diff: ConversationPart.Diff,
    patchText: String,
    onSubmit: (ChatDiffReviewResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(10.dp)
            .testTag("agent-diff-review-pane"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .cossSurfaceElevation(palette = palette, radius = 22.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .background(palette.border, CircleShape)
                    .align(Alignment.CenterHorizontally),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    UiText(diff.title, palette, size = 15, weight = FontWeight.SemiBold)
                    if (diff.summary.isNotBlank()) UiText(diff.summary, palette, muted = true, size = 12)
                }
                AgentPillButton(
                    label = "Close",
                    onClick = onClose,
                    variant = UiButtonVariant.Secondary,
                    palette = palette,
                    modifier = Modifier.testTag("agent-diff-review-close"),
                )
            }
            AgentDiffReview(
                patch = patchText,
                onSubmit = onSubmit,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                title = diff.title,
                palette = palette,
            )
        }
    }
}

/**
 * Box 5 — Compact floating browser alert / PiP banner (`"Browser · Action required"` + `"Stop"` pill).
 */
@Composable
fun AgentBrowserAlertBanner(
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Browser",
    subtitle: String = "Action required",
    actionLabel: String = "Stop",
    onOpen: (() -> Unit)? = null,
    palette: AgentUiPalette = AgentUiTheme.palette,
    thumbnail: (@Composable () -> Unit)? = null,
) {
    val sizing = AgentUiTheme.sizing
    Row(
        modifier
            .widthIn(max = sizing.cardMaxWidth)
            .fillMaxWidth()
            .cossSurfaceElevation(palette = palette, radius = AgentUiBaseTokens.RadiusWindow)
            .then(if (onOpen != null) Modifier.clickable { onOpen() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("agent-browser-alert-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(width = 46.dp, height = 34.dp)
                .clip(RoundedCornerShape(AgentUiBaseTokens.RadiusControl))
                .background(Color.White, RoundedCornerShape(AgentUiBaseTokens.RadiusControl))
                .border(1.dp, palette.border, RoundedCornerShape(AgentUiBaseTokens.RadiusControl)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                thumbnail()
            } else {
                Column(
                    Modifier.fillMaxSize().padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(Modifier.width(14.dp).height(3.dp).background(palette.accent, CircleShape))
                    Box(Modifier.fillMaxWidth(0.85f).height(3.dp).background(Color(0xFFD0D5DD), CircleShape))
                    Box(Modifier.fillMaxWidth(0.65f).height(3.dp).background(Color(0xFFE4E7EC), CircleShape))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            UiText(title, palette, size = 13, weight = FontWeight.SemiBold)
            UiText(subtitle, palette, muted = true, size = 12)
        }
        AgentPillButton(
            label = actionLabel,
            onClick = onAction,
            variant = UiButtonVariant.Secondary,
            palette = palette,
            modifier = Modifier.testTag("agent-browser-alert-banner-action"),
        )
    }
}

/**
 * Box 6B Overlay — Circular floating jump-to-bottom button (`↓`).
 */
@Composable
fun AgentJumpToBottomButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val sizing = AgentUiTheme.sizing
    Box(
        modifier
            .size(if (sizing.isMobile) 40.dp else 36.dp)
            .cossButtonElevation(
                palette = palette,
                variant = UiButtonVariant.Primary,
                radius = AgentUiBaseTokens.RadiusPill,
            )
            .clickable(onClick = onClick)
            .testTag("agent-jump-bottom-circle"),
        contentAlignment = Alignment.Center,
    ) {
        AgentIcon(AgentIconId.ArrowDown, size = 16.dp, color = palette.accentText)
    }
}

/**
 * Box 7 — Floating agent avatar with overlapping live status pill
 * (`"Muse · Needs approval"`, `"Muse · Signing in"`, `"Muse · Is working"`).
 */
@Composable
fun AgentFloatingHeaderPill(
    agentName: String,
    status: AgentLiveStatusKind,
    modifier: Modifier = Modifier,
    statusText: String = when (status) {
        AgentLiveStatusKind.NeedsApproval -> "Needs approval"
        AgentLiveStatusKind.SigningIn -> "Signing in"
        AgentLiveStatusKind.Working -> "Is working"
        AgentLiveStatusKind.Idle -> "Ready"
    },
    onClick: (() -> Unit)? = null,
    palette: AgentUiPalette = AgentUiTheme.palette,
    avatar: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag("agent-floating-header-pill"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(palette.subtle, CircleShape)
                .border(2.dp, palette.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) {
                avatar()
            } else {
                UiText(
                    value = agentName.take(1).uppercase(),
                    palette = palette.copy(text = palette.accent),
                    size = 24,
                    weight = FontWeight.Bold,
                )
            }
        }
        Column(
            Modifier
                .offset(y = (-12).dp)
                .cossSurfaceElevation(palette = palette, radius = 18.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            UiText(agentName, palette, size = 14, weight = FontWeight.SemiBold)
            Row(
                Modifier.testTag("agent-floating-header-status"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (status == AgentLiveStatusKind.SigningIn) {
                    BrowserKeyIcon(size = 11.dp, color = palette.accent)
                }
                val statusColor = when (status) {
                    AgentLiveStatusKind.NeedsApproval -> palette.accent
                    else -> palette.muted
                }
                UiText(
                    value = statusText,
                    palette = palette.copy(text = statusColor),
                    size = 12,
                    weight = if (status == AgentLiveStatusKind.NeedsApproval) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Box 8 — Inline `"Add password"` timeline prompt card (`"Add password · For x.com"` + `"Continue"`).
 */
@Composable
fun AgentPasswordPromptCard(
    site: String,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Add password",
    subtitle: String = "For $site",
    actionLabel: String = "Continue",
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val sizing = AgentUiTheme.sizing
    Column(
        modifier
            .widthIn(max = if (sizing.isMobile) sizing.cardMaxWidth else 320.dp)
            .fillMaxWidth()
            .cossSurfaceElevation(palette = palette, radius = AgentUiBaseTokens.RadiusWindow)
            .padding(14.dp)
            .testTag("agent-password-prompt-card"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(palette.subtle, CircleShape)
                    .border(1.dp, palette.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                BrowserKeyIcon(size = 15.dp, color = palette.text)
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                UiText(title, palette, size = 13, weight = FontWeight.SemiBold)
                UiText(subtitle, palette, muted = true, size = 12)
            }
        }
        AgentPillButton(
            label = actionLabel,
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().testTag("agent-password-prompt-continue"),
            variant = UiButtonVariant.Secondary,
            palette = palette,
        )
    }
}

/**
 * Box 9 — `"Add password"` modal / bottom sheet with username, password + show/hide toggle,
 * `"Save to secure credentials store"` checkbox, `"Enter"` primary pill, and `"Cancel"`.
 */
@Composable
fun AgentPasswordSheet(
    site: String,
    onSubmit: (AgentCredentialSubmission) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialUsername: String = "",
    initialPassword: String = "",
    initialSaveToStore: Boolean = true,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    var username by remember(site) { mutableStateOf(initialUsername) }
    var password by remember(site) { mutableStateOf(initialPassword) }
    var passwordVisible by remember(site) { mutableStateOf(false) }
    var saveToStore by remember(site) { mutableStateOf(initialSaveToStore) }
    val canSubmit = username.isNotBlank() && password.isNotBlank()
    val sizing = AgentUiTheme.sizing

    Column(
        modifier
            .widthIn(max = if (sizing.isMobile) sizing.cardMaxWidth else 420.dp)
            .fillMaxWidth()
            .cossSurfaceElevation(palette = palette, radius = 22.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .testTag("agent-password-sheet"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .background(palette.border, CircleShape),
        )
        Box(
            Modifier
                .size(44.dp)
                .background(palette.text, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BrowserKeyIcon(size = 20.dp, color = palette.background)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            UiText("Add password", palette, size = 15, weight = FontWeight.SemiBold)
            UiText("For $site", palette, muted = true, size = 12)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .cossInputElevation(palette = palette, radius = AgentUiBaseTokens.RadiusCard),
        ) {
            BasicTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 14.sp),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("agent-password-username"),
                decorationBox = { inner ->
                    Box {
                        if (username.isEmpty()) UiText("Email or username", palette, muted = true, size = 14)
                        inner()
                    }
                },
            )
            UiDivider(palette)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    textStyle = TextStyle(color = palette.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(palette.accent),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("agent-password-input"),
                    decorationBox = { inner ->
                        Box {
                            if (password.isEmpty()) UiText("Password", palette, muted = true, size = 14)
                            inner()
                        }
                    },
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { passwordVisible = !passwordVisible }
                        .testTag("agent-password-toggle-visibility"),
                    contentAlignment = Alignment.Center,
                ) {
                    BrowserEyeIcon(visible = passwordVisible, size = 16.dp, color = palette.muted)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AgentUiBaseTokens.RadiusChip))
                .clickable { saveToStore = !saveToStore }
                .padding(vertical = 6.dp)
                .testTag("agent-password-save-checkbox"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(if (saveToStore) palette.accent else Color.Transparent, CircleShape)
                    .border(1.dp, if (saveToStore) palette.accent else palette.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (saveToStore) {
                    AgentIcon(AgentIconId.Check, size = 11.dp, color = palette.accentText)
                }
            }
            UiText("Save to secure credentials store", palette, size = 12)
        }
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AgentPillButton(
                label = "Enter",
                onClick = {
                    if (canSubmit) {
                        onSubmit(AgentCredentialSubmission(site, username.trim(), password, saveToStore))
                    }
                },
                enabled = canSubmit,
                variant = UiButtonVariant.Primary,
                palette = palette,
                modifier = Modifier.fillMaxWidth().testTag("agent-password-submit"),
            )
            UiButton(
                label = "Cancel",
                onClick = onCancel,
                palette = palette,
                variant = UiButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth().testTag("agent-password-cancel"),
            )
        }
    }
}

/**
 * Box 10 — Site sign-in / browser permission card
 * (`"Allow Muse to log in to x.com using Browser?"` with `"Allow"`, `"Always allow this site"`, `"Deny"`).
 */
@Composable
fun AgentBrowserPermissionCard(
    agentName: String,
    site: String,
    onDecide: (AgentBrowserPermissionDecision) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Allow $agentName to log in to $site using Browser?",
    description: String = "The agent wants to sign in to $site using your saved login details.",
    detailsText: String? = "Saved credentials for $site will be filled directly into the sign-in form without exposing raw passwords in the conversation.",
    disclaimer: String = "Supervise your agent carefully. You are responsible for agent actions while browsing.",
    onSeeDetails: (() -> Unit)? = null,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    var detailsExpanded by remember(agentName, site) { mutableStateOf(false) }
    val sizing = AgentUiTheme.sizing

    Column(
        modifier
            .widthIn(max = sizing.cardMaxWidth)
            .fillMaxWidth()
            .cossSurfaceElevation(palette = palette, radius = 20.dp)
            .padding(18.dp)
            .testTag("agent-browser-permission-card"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(palette.text, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                BrowserGlobeIcon(size = 22.dp, color = palette.background)
            }
            UiText(
                value = title,
                palette = palette,
                modifier = Modifier.weight(1f),
                size = 15,
                weight = FontWeight.SemiBold,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UiText(description, palette, size = 14)
            if (!detailsText.isNullOrBlank() || onSeeDetails != null) {
                UiText(
                    value = if (detailsExpanded) "Hide details" else "See details",
                    palette = palette.copy(text = palette.accent),
                    modifier = Modifier
                        .clickable {
                            detailsExpanded = !detailsExpanded
                            onSeeDetails?.invoke()
                        }
                        .padding(vertical = 4.dp)
                        .testTag("agent-browser-permission-details-toggle"),
                    size = 13,
                    weight = FontWeight.Medium,
                )
            }
            AnimatedVisibility(
                visible = detailsExpanded && !detailsText.isNullOrBlank(),
                enter = fadeIn(tween(150)) + expandVertically(tween(180)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(palette.subtle, RoundedCornerShape(AgentUiBaseTokens.RadiusControl))
                        .padding(10.dp)
                        .testTag("agent-browser-permission-details-body"),
                ) {
                    UiText(detailsText.orEmpty(), palette, muted = true, size = 12)
                }
            }
        }
        UiDivider(palette)
        UiText(disclaimer, palette, muted = true, size = 12)
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AgentPillButton(
                label = "Allow",
                onClick = { onDecide(AgentBrowserPermissionDecision.AllowOnce) },
                variant = UiButtonVariant.Primary,
                palette = palette,
                modifier = Modifier.fillMaxWidth().testTag("agent-browser-permission-allow"),
            )
            AgentPillButton(
                label = "Always allow this site",
                onClick = { onDecide(AgentBrowserPermissionDecision.AlwaysAllowSite) },
                variant = UiButtonVariant.Secondary,
                palette = palette,
                modifier = Modifier.fillMaxWidth().testTag("agent-browser-permission-always"),
            )
            AgentPillButton(
                label = "Deny",
                onClick = { onDecide(AgentBrowserPermissionDecision.Deny) },
                variant = UiButtonVariant.DestructiveOutline,
                palette = palette,
                modifier = Modifier.fillMaxWidth().testTag("agent-browser-permission-deny"),
            )
        }
    }
}

@Composable
private fun CircleIconButton(
    onClick: () -> Unit,
    palette: AgentUiPalette,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(size)
            .cossButtonElevation(
                palette = palette,
                variant = UiButtonVariant.Secondary,
                radius = AgentUiBaseTokens.RadiusPill,
                pressed = pressed,
                hovered = hovered,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun BrowserCompletedCheckCircleIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = 1.7.dp.toPx()
        val r = (this.size.minDimension - stroke) / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(color = color, radius = r, center = center, style = Stroke(width = stroke))
        val w = this.size.width
        val h = this.size.height
        val checkPath = Path().apply {
            moveTo(w * 0.30f, h * 0.52f)
            lineTo(w * 0.44f, h * 0.66f)
            lineTo(w * 0.71f, h * 0.36f)
        }
        drawPath(
            path = checkPath,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
internal fun BrowserNavArrowIcon(forward: Boolean, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = 1.6.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawLine(
            color = color,
            start = Offset(w * 0.20f, h * 0.50f),
            end = Offset(w * 0.80f, h * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        val headX = if (forward) w * 0.80f else w * 0.20f
        val wingX = if (forward) w * 0.52f else w * 0.48f
        drawLine(
            color = color,
            start = Offset(wingX, h * 0.24f),
            end = Offset(headX, h * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(wingX, h * 0.76f),
            end = Offset(headX, h * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun BrowserReloadIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = 1.6.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawArc(
            color = color,
            startAngle = 35f,
            sweepAngle = 285f,
            useCenter = false,
            topLeft = Offset(w * 0.18f, h * 0.18f),
            size = Size(w * 0.64f, h * 0.64f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(w * 0.82f, h * 0.18f),
            end = Offset(w * 0.82f, h * 0.42f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.58f, h * 0.42f),
            end = Offset(w * 0.82f, h * 0.42f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun BrowserTuneIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = 1.4.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawLine(
            color = color,
            start = Offset(w * 0.15f, h * 0.34f),
            end = Offset(w * 0.85f, h * 0.34f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = color, radius = w * 0.12f, center = Offset(w * 0.36f, h * 0.34f))
        drawLine(
            color = color,
            start = Offset(w * 0.15f, h * 0.68f),
            end = Offset(w * 0.85f, h * 0.68f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = color, radius = w * 0.12f, center = Offset(w * 0.64f, h * 0.68f))
    }
}

@Composable
internal fun BrowserMenuIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = 1.6.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        listOf(0.28f, 0.50f, 0.72f).forEach { yFrac ->
            drawLine(
                color = color,
                start = Offset(w * 0.18f, h * yFrac),
                end = Offset(w * 0.82f, h * yFrac),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
internal fun BrowserGlobeIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = 1.6.dp.toPx()
        val r = (this.size.minDimension - stroke) / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(color = color, radius = r, center = center, style = Stroke(width = stroke))
        drawOval(
            color = color,
            topLeft = Offset(center.x - r * 0.48f, center.y - r),
            size = Size(r * 0.96f, r * 2f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = Offset(center.x - r, center.y),
            end = Offset(center.x + r, center.y),
            strokeWidth = stroke,
        )
    }
}

@Composable
internal fun BrowserWarningTriangleIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = 1.7.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.90f, h * 0.86f)
            lineTo(w * 0.10f, h * 0.86f)
            close()
        }
        drawPath(path = path, color = color, style = Stroke(width = stroke, join = StrokeJoin.Round))
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.39f),
            end = Offset(w * 0.5f, h * 0.62f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = color, radius = stroke * 0.65f, center = Offset(w * 0.5f, h * 0.74f))
    }
}

@Composable
internal fun BrowserKeyIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = 1.6.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val ringCenter = Offset(w * 0.34f, h * 0.5f)
        val ringRadius = w * 0.20f
        drawCircle(color = color, radius = ringRadius, center = ringCenter, style = Stroke(width = stroke))
        drawLine(
            color = color,
            start = Offset(ringCenter.x + ringRadius, h * 0.5f),
            end = Offset(w * 0.88f, h * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.72f, h * 0.5f),
            end = Offset(w * 0.72f, h * 0.68f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.85f, h * 0.5f),
            end = Offset(w * 0.85f, h * 0.68f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun BrowserEyeIcon(visible: Boolean, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = 1.5.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawOval(
            color = color,
            topLeft = Offset(w * 0.12f, h * 0.26f),
            size = Size(w * 0.76f, h * 0.48f),
            style = Stroke(width = stroke),
        )
        drawCircle(color = color, radius = w * 0.11f, center = Offset(w * 0.5f, h * 0.5f))
        if (!visible) {
            drawLine(
                color = color,
                start = Offset(w * 0.18f, h * 0.82f),
                end = Offset(w * 0.82f, h * 0.18f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun BrowserShareIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = 1.5.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawLine(
            color = color,
            start = Offset(w * 0.5f, h * 0.62f),
            end = Offset(w * 0.5f, h * 0.16f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.32f, h * 0.34f),
            end = Offset(w * 0.5f, h * 0.16f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.68f, h * 0.34f),
            end = Offset(w * 0.5f, h * 0.16f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.20f, h * 0.46f),
            size = Size(w * 0.60f, h * 0.40f),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(width = stroke),
        )
    }
}

@Composable
private fun BrowserPointerHandIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = 1.5.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.42f, h * 0.16f)
            lineTo(w * 0.42f, h * 0.56f)
            lineTo(w * 0.26f, h * 0.48f)
            lineTo(w * 0.40f, h * 0.84f)
            lineTo(w * 0.74f, h * 0.84f)
            lineTo(w * 0.78f, h * 0.44f)
            lineTo(w * 0.54f, h * 0.44f)
            lineTo(w * 0.54f, h * 0.16f)
            close()
        }
        drawPath(path = path, color = color, style = Stroke(width = stroke, join = StrokeJoin.Round))
    }
}

@Composable
private fun BrowserClipboardIcon(size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = 1.5.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.22f, h * 0.22f),
            size = Size(w * 0.56f, h * 0.64f),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(width = stroke),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.36f, h * 0.12f),
            size = Size(w * 0.28f, h * 0.14f),
            cornerRadius = CornerRadius(1.5.dp.toPx()),
            style = Stroke(width = stroke),
        )
    }
}
