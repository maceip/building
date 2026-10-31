package fragments.agentui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import fragments.chrome.AgentUiBaseTokens
import fragments.components.UiButtonVariant
import fragments.components.UiDivider
import fragments.components.UiText
import fragments.components.cossButtonElevation
import fragments.components.cossSurfaceElevation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Geometric character silhouettes for agent avatars (gumdrop blob vs. tilted hexagon). */
enum class AgentCharacterShape {
    Gumdrop,
    Hexagon,
}

/** Live presence badge state for 1-on-1 and group agent avatars. */
enum class AgentPresenceStatus {
    Online,
    Working,
    Away,
    Offline,
}

/** Visual specification for an agent's geometric character avatar. */
@Immutable
data class AgentAvatarSpec(
    val id: String,
    val name: String,
    val color: Color,
    val shape: AgentCharacterShape = AgentCharacterShape.Gumdrop,
    val presence: AgentPresenceStatus? = null,
)

/** Model for a single or multi-agent conversation row in [AgentSessionList]. */
@Immutable
data class AgentSessionSummary(
    val id: String,
    val title: String,
    val preview: String,
    val timestamp: String,
    val participants: List<AgentAvatarSpec>,
    val unread: Boolean = false,
)

/** Capability/status row model in [AgentProfileSheet]. */
enum class AgentProfileSectionIcon {
    Coding,
    Routines,
    Library,
    Computer,
}

@Immutable
data class AgentProfileSectionItem(
    val id: String,
    val label: String,
    val statusValue: String = "",
    val icon: AgentProfileSectionIcon,
)

/**
 * Renders the expressive geometric character avatar (Gumdrop blob or tilted Hexagon with
 * two vertical eye slits) and an optional bottom-right presence dot with a cutout border.
 */
@Composable
fun AgentCharacterAvatar(
    spec: AgentAvatarSpec,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    cutoutColor: Color = Color.Transparent,
    showPresence: Boolean = spec.presence != null,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    Box(
        modifier
            .size(size)
            .testTag("agent-character-avatar-${spec.id}"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(w, h) * 0.44f

            fun buildShapePath(radiusScale: Float = 1f): Path {
                val sr = r * radiusScale
                return Path().apply {
                    if (spec.shape == AgentCharacterShape.Hexagon) {
                        for (i in 0 until 6) {
                            val angleDeg = 60f * i - 18f
                            val rad = angleDeg.toDouble() * (PI / 180.0)
                            val px = cx + (sr * cos(rad)).toFloat()
                            val py = cy + (sr * sin(rad)).toFloat()
                            if (i == 0) moveTo(px, py) else lineTo(px, py)
                        }
                        close()
                    } else {
                        // Rounded gumdrop / pear silhouette
                        moveTo(cx - sr * 0.15f, cy - sr * 0.95f)
                        cubicTo(
                            cx + sr * 0.55f, cy - sr * 0.95f,
                            cx + sr * 1.05f, cy - sr * 0.10f,
                            cx + sr * 0.88f, cy + sr * 0.58f,
                        )
                        cubicTo(
                            cx + sr * 0.72f, cy + sr * 0.98f,
                            cx - sr * 0.68f, cy + sr * 0.98f,
                            cx - sr * 0.88f, cy + sr * 0.58f,
                        )
                        cubicTo(
                            cx - sr * 1.05f, cy - sr * 0.05f,
                            cx - sr * 0.65f, cy - sr * 0.95f,
                            cx - sr * 0.15f, cy - sr * 0.95f,
                        )
                        close()
                    }
                }
            }

            if (cutoutColor != Color.Transparent) {
                drawPath(
                    path = buildShapePath(1.14f),
                    color = cutoutColor,
                    style = Fill,
                )
            }
            drawPath(
                path = buildShapePath(1.0f),
                color = spec.color,
                style = Fill,
            )

            // Two expressive tilted eye slits
            val eyeColor = Color(0xFF17181A)
            val eyeWidth = (w * 0.095f).coerceAtLeast(1.5f)
            val eyeHeight = (h * 0.22f).coerceAtLeast(3f)
            rotate(degrees = -14f, pivot = Offset(cx, cy)) {
                drawRoundRect(
                    color = eyeColor,
                    topLeft = Offset(cx - w * 0.16f, cy - h * 0.14f),
                    size = Size(eyeWidth, eyeHeight),
                    cornerRadius = CornerRadius(eyeWidth / 2f, eyeWidth / 2f),
                )
                drawRoundRect(
                    color = eyeColor,
                    topLeft = Offset(cx + w * 0.05f, cy - h * 0.14f),
                    size = Size(eyeWidth, eyeHeight),
                    cornerRadius = CornerRadius(eyeWidth / 2f, eyeWidth / 2f),
                )
            }
        }

        val presence = spec.presence
        if (showPresence && presence != null && presence != AgentPresenceStatus.Offline) {
            val dotSize = (size.value * 0.36f).coerceIn(8f, 14f).dp
            val dotColor = when (presence) {
                AgentPresenceStatus.Online -> palette.success
                AgentPresenceStatus.Working -> palette.accent
                AgentPresenceStatus.Away -> palette.warning
                AgentPresenceStatus.Offline -> palette.muted
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(dotSize)
                    .background(dotColor, CircleShape)
                    .border(1.5.dp, if (cutoutColor != Color.Transparent) cutoutColor else palette.surface, CircleShape)
                    .testTag("agent-avatar-presence-${spec.id}"),
            )
        }
    }
}

/**
 * Horizontal overlapping stack of agent character avatars used in multi-agent session headers
 * (e.g. `tokenmaxxing` with 4 overlapping avatars).
 */
@Composable
fun AgentAvatarStack(
    avatars: List<AgentAvatarSpec>,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 22.dp,
    overlap: Dp = 7.dp,
    maxVisible: Int = 5,
    cutoutColor: Color = AgentUiTheme.palette.surface,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val visible = avatars.take(maxVisible)
    val step = (avatarSize - overlap).coerceAtLeast(8.dp)
    val totalWidth = if (visible.isEmpty()) 0.dp else avatarSize + step * (visible.size - 1)
    Box(
        modifier
            .width(totalWidth)
            .height(avatarSize)
            .testTag("agent-avatar-stack"),
        contentAlignment = Alignment.CenterStart,
    ) {
        visible.forEachIndexed { index, spec ->
            AgentCharacterAvatar(
                spec = spec,
                size = avatarSize,
                cutoutColor = cutoutColor,
                showPresence = false,
                palette = palette,
                modifier = Modifier
                    .offset(x = step * index)
                    .zIndex(index.toFloat()),
            )
        }
    }
}

/**
 * Triangular 3-agent cluster avatar (`AgentClusterAvatar`) used in multi-agent session list rows
 * (e.g. group `"X"` with top pink gumdrop, bottom-left teal hexagon, bottom-right tan hexagon).
 */
@Composable
fun AgentClusterAvatar(
    avatars: List<AgentAvatarSpec>,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    if (avatars.size <= 1) {
        val single = avatars.firstOrNull() ?: AgentAvatarSpec("default", "Agent", palette.accent)
        AgentCharacterAvatar(
            spec = single,
            size = size,
            cutoutColor = palette.background,
            palette = palette,
            modifier = modifier.testTag("agent-cluster-avatar"),
        )
        return
    }
    val subSize = (size.value * 0.56f).dp
    Box(
        modifier
            .size(size)
            .testTag("agent-cluster-avatar"),
    ) {
        val first = avatars[0]
        val second = avatars[1]
        val third = avatars.getOrNull(2)
        if (third == null) {
            AgentCharacterAvatar(
                spec = first,
                size = subSize,
                cutoutColor = palette.background,
                showPresence = false,
                palette = palette,
                modifier = Modifier.align(Alignment.TopStart).zIndex(1f),
            )
            AgentCharacterAvatar(
                spec = second,
                size = subSize,
                cutoutColor = palette.background,
                showPresence = false,
                palette = palette,
                modifier = Modifier.align(Alignment.BottomEnd).zIndex(2f),
            )
        } else {
            AgentCharacterAvatar(
                spec = first,
                size = subSize,
                cutoutColor = palette.background,
                showPresence = false,
                palette = palette,
                modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
            )
            AgentCharacterAvatar(
                spec = second,
                size = subSize,
                cutoutColor = palette.background,
                showPresence = false,
                palette = palette,
                modifier = Modifier.align(Alignment.BottomStart).zIndex(2f),
            )
            AgentCharacterAvatar(
                spec = third,
                size = subSize,
                cutoutColor = palette.background,
                showPresence = false,
                palette = palette,
                modifier = Modifier.align(Alignment.BottomEnd).zIndex(3f),
            )
        }
    }
}

/**
 * Dashed Box 1 — One-on-one agent chat header pill with online presence dot
 * (e.g. `[ < ]  [ (pink gumdrop + green dot) Adept ]  ...  [ ••• ]`).
 */
@Composable
fun AgentOneOnOneHeaderPill(
    agent: AgentAvatarSpec,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val sizing = AgentUiTheme.sizing
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .height(if (sizing.isMobile) 42.dp else 38.dp)
            .cossButtonElevation(
                palette = palette,
                variant = UiButtonVariant.Secondary,
                radius = AgentUiBaseTokens.RadiusPill,
                pressed = pressed,
                hovered = hovered,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag("agent-one-on-one-header-pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentCharacterAvatar(
            spec = if (agent.presence == null) agent.copy(presence = AgentPresenceStatus.Online) else agent,
            size = 22.dp,
            cutoutColor = palette.subtle,
            showPresence = true,
            palette = palette,
        )
        UiText(
            value = agent.name,
            palette = palette,
            size = 15,
            weight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Dashed Box 2 — Multi-agent chat session title pill with overlapping avatar stack
 * (e.g. `[ < ]  [ (4 stacked avatars) tokenmaxxing ]  ...  [ ••• ]`).
 */
@Composable
fun AgentGroupSessionHeaderPill(
    title: String,
    participants: List<AgentAvatarSpec>,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val sizing = AgentUiTheme.sizing
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .height(if (sizing.isMobile) 42.dp else 38.dp)
            .cossButtonElevation(
                palette = palette,
                variant = UiButtonVariant.Secondary,
                radius = AgentUiBaseTokens.RadiusPill,
                pressed = pressed,
                hovered = hovered,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag("agent-group-session-header-pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        AgentAvatarStack(
            avatars = participants,
            avatarSize = 22.dp,
            overlap = 7.dp,
            cutoutColor = palette.subtle,
            palette = palette,
        )
        UiText(
            value = title,
            palette = palette,
            size = 15,
            weight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Complete top navigation header bar wrapping either [AgentOneOnOneHeaderPill] (1 participant)
 * or [AgentGroupSessionHeaderPill] (multiple participants) alongside the circular `<` Back and
 * `•••` More buttons shown in Dashed Boxes 1 & 2.
 */
@Composable
fun AgentChatSessionTopBar(
    title: String,
    participants: List<AgentAvatarSpec>,
    onBack: () -> Unit = {},
    onTitleClick: () -> Unit = {},
    onMore: () -> Unit = {},
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("agent-chat-session-top-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeaderCircleButton(
                onClick = onBack,
                palette = palette,
                modifier = Modifier.testTag("agent-session-nav-back"),
            ) {
                AgentIcon(AgentIconId.ChevronLeft, size = 16.dp, color = palette.text)
            }
            if (participants.size == 1) {
                AgentOneOnOneHeaderPill(
                    agent = participants.first(),
                    onClick = onTitleClick,
                    palette = palette,
                )
            } else {
                AgentGroupSessionHeaderPill(
                    title = title,
                    participants = participants,
                    onClick = onTitleClick,
                    palette = palette,
                )
            }
        }
        HeaderCircleButton(
            onClick = onMore,
            palette = palette,
            modifier = Modifier.testTag("agent-session-nav-more"),
        ) {
            AgentIcon(AgentIconId.Dots, size = 16.dp, color = palette.text)
        }
    }
}

/**
 * Dashed Box 3 — Multi-agent & 1-on-1 Session List (`Muse` single avatar row and `X` 3-avatar cluster row).
 */
@Composable
fun AgentSessionList(
    sessions: List<AgentSessionSummary>,
    onSelectSession: (AgentSessionSummary) -> Unit,
    modifier: Modifier = Modifier,
    selectedSessionId: String? = null,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val sizing = AgentUiTheme.sizing
    Column(
        modifier
            .widthIn(max = sizing.cardMaxWidth)
            .fillMaxWidth()
            .cossSurfaceElevation(palette = palette, radius = AgentUiBaseTokens.RadiusWindow)
            .padding(vertical = 6.dp)
            .testTag("agent-session-list"),
    ) {
        sessions.forEachIndexed { index, session ->
            if (index > 0) {
                UiDivider(palette, Modifier.padding(start = 68.dp, end = 14.dp))
            }
            AgentSessionRow(
                session = session,
                selected = session.id == selectedSessionId,
                onClick = { onSelectSession(session) },
                palette = palette,
            )
        }
    }
}

@Composable
fun AgentSessionRow(
    session: AgentSessionSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val sizing = AgentUiTheme.sizing
    val interaction = remember(session.id) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = when {
            pressed -> palette.border.copy(alpha = 0.45f)
            selected || hovered -> palette.subtle.copy(alpha = 0.75f)
            else -> Color.Transparent
        },
        animationSpec = tween(110),
        label = "session row bg",
    )
    Row(
        modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (sizing.isMobile) 13.dp else 11.dp)
            .testTag("agent-session-row-${session.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (session.participants.size <= 1) {
            val spec = session.participants.firstOrNull()
                ?: AgentAvatarSpec(session.id, session.title, palette.accent)
            AgentCharacterAvatar(
                spec = spec,
                size = 42.dp,
                cutoutColor = palette.surface,
                palette = palette,
            )
        } else {
            AgentClusterAvatar(
                avatars = session.participants,
                size = 42.dp,
                palette = palette,
            )
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiText(
                    value = session.title,
                    palette = palette,
                    size = 15,
                    weight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                UiText(
                    value = session.timestamp,
                    palette = palette,
                    muted = true,
                    size = 12,
                    maxLines = 1,
                )
            }
            UiText(
                value = session.preview,
                palette = palette,
                muted = !session.unread,
                size = 13,
                weight = if (session.unread) FontWeight.Medium else FontWeight.Normal,
                maxLines = if (sizing.isMobile) 2 else 1,
            )
        }
    }
}

/**
 * Dashed Box 4 — Agent Profile Card / Sheet (`Adept` with character avatar, `"Edit"` pill,
 * `Coding` / `Routines` / `Library` / `Computer` rows, and `Notifications` toggle switch).
 */
@Composable
fun AgentProfileSheet(
    agent: AgentAvatarSpec,
    notificationsEnabled: Boolean,
    onNotificationsChanged: (Boolean) -> Unit,
    onEdit: () -> Unit = {},
    onSelectSection: (AgentProfileSectionItem) -> Unit = {},
    modifier: Modifier = Modifier,
    sections: List<AgentProfileSectionItem> = listOf(
        AgentProfileSectionItem("coding", "Coding", "0 working", AgentProfileSectionIcon.Coding),
        AgentProfileSectionItem("routines", "Routines", "0 on", AgentProfileSectionIcon.Routines),
        AgentProfileSectionItem("library", "Library", "", AgentProfileSectionIcon.Library),
        AgentProfileSectionItem("computer", "Computer", "Idle", AgentProfileSectionIcon.Computer),
    ),
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val sizing = AgentUiTheme.sizing
    Column(
        modifier
            .widthIn(max = if (sizing.isMobile) sizing.cardMaxWidth else 360.dp)
            .fillMaxWidth()
            .cossSurfaceElevation(palette = palette, radius = 22.dp)
            .padding(16.dp)
            .testTag("agent-profile-sheet"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AgentCharacterAvatar(
            spec = agent,
            size = if (sizing.isMobile) 64.dp else 56.dp,
            showPresence = false,
            palette = palette,
        )
        UiText(
            value = agent.name,
            palette = palette,
            size = 16,
            weight = FontWeight.SemiBold,
        )
        Box(
            Modifier
                .height(if (sizing.isMobile) 36.dp else 28.dp)
                .cossButtonElevation(
                    palette = palette,
                    variant = UiButtonVariant.Secondary,
                    radius = AgentUiBaseTokens.RadiusPill,
                )
                .clickable(onClick = onEdit)
                .padding(horizontal = if (sizing.isMobile) 18.dp else 14.dp)
                .testTag("agent-profile-edit"),
            contentAlignment = Alignment.Center,
        ) {
            UiText("Edit", palette, size = 12, weight = FontWeight.Medium)
        }

        // Grouped capability rows (Coding, Routines, Library, Computer)
        Column(
            Modifier
                .fillMaxWidth()
                .cossSurfaceElevation(
                    palette = palette,
                    radius = AgentUiBaseTokens.RadiusWindow,
                    surfaceColor = palette.subtle,
                ),
        ) {
            sections.forEachIndexed { index, item ->
                if (index > 0) {
                    UiDivider(palette, Modifier.padding(start = 38.dp))
                }
                val interaction = remember(item.id) { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                val pressed by interaction.collectIsPressedAsState()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                pressed -> palette.border.copy(alpha = 0.45f)
                                hovered -> palette.surface.copy(alpha = 0.55f)
                                else -> Color.Transparent
                            },
                        )
                        .clickable(interactionSource = interaction, indication = null) { onSelectSection(item) }
                        .padding(horizontal = 12.dp, vertical = if (sizing.isMobile) 13.dp else 11.dp)
                        .testTag("agent-profile-section-${item.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProfileSectionVectorIcon(icon = item.icon, size = 15.dp, color = palette.muted)
                    UiText(
                        value = item.label,
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        size = 13,
                        weight = FontWeight.Medium,
                    )
                    if (item.statusValue.isNotBlank()) {
                        UiText(item.statusValue, palette, muted = true, size = 13)
                    }
                    UiText("›", palette, muted = true, size = 14, weight = FontWeight.Medium)
                }
            }
        }

        // Notifications toggle card
        Row(
            Modifier
                .fillMaxWidth()
                .cossSurfaceElevation(
                    palette = palette,
                    radius = AgentUiBaseTokens.RadiusWindow,
                    surfaceColor = palette.subtle,
                )
                .clickable { onNotificationsChanged(!notificationsEnabled) }
                .padding(horizontal = 14.dp, vertical = if (sizing.isMobile) 12.dp else 10.dp)
                .testTag("agent-profile-notifications-row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            UiText("Notifications", palette, size = 13, weight = FontWeight.Medium)
            AgentSwitchToggle(
                checked = notificationsEnabled,
                onCheckedChange = onNotificationsChanged,
                activeColor = agent.color,
                palette = palette,
                modifier = Modifier.testTag("agent-profile-notifications-toggle"),
            )
        }
    }
}

@Composable
fun AgentSwitchToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = AgentUiTheme.palette.accent,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) activeColor else palette.border,
        animationSpec = tween(140),
        label = "switch track color",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 18.dp else 2.dp,
        animationSpec = tween(140),
        label = "switch thumb offset",
    )
    Box(
        modifier
            .width(42.dp)
            .height(24.dp)
            .clip(CircleShape)
            .background(trackColor, CircleShape)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
private fun HeaderCircleButton(
    onClick: () -> Unit,
    palette: AgentUiPalette,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sizing = AgentUiTheme.sizing
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(if (sizing.isMobile) 42.dp else 38.dp)
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
private fun ProfileSectionVectorIcon(
    icon: AgentProfileSectionIcon,
    size: Dp,
    color: Color,
) {
    when (icon) {
        AgentProfileSectionIcon.Coding -> Canvas(Modifier.size(size)) {
            val stroke = 1.4.dp.toPx()
            val w = this.size.width
            val h = this.size.height
            drawLine(color, Offset(w * 0.38f, h * 0.26f), Offset(w * 0.14f, h * 0.50f), stroke, StrokeCap.Round)
            drawLine(color, Offset(w * 0.14f, h * 0.50f), Offset(w * 0.38f, h * 0.74f), stroke, StrokeCap.Round)
            drawLine(color, Offset(w * 0.62f, h * 0.26f), Offset(w * 0.86f, h * 0.50f), stroke, StrokeCap.Round)
            drawLine(color, Offset(w * 0.86f, h * 0.50f), Offset(w * 0.62f, h * 0.74f), stroke, StrokeCap.Round)
        }
        AgentProfileSectionIcon.Routines -> AgentIcon(AgentIconId.Clock, size = size, color = color)
        AgentProfileSectionIcon.Library -> Canvas(Modifier.size(size)) {
            val stroke = 1.4.dp.toPx()
            val w = this.size.width
            val h = this.size.height
            val path = Path().apply {
                moveTo(w * 0.14f, h * 0.26f)
                lineTo(w * 0.42f, h * 0.26f)
                lineTo(w * 0.52f, h * 0.36f)
                lineTo(w * 0.86f, h * 0.36f)
                lineTo(w * 0.86f, h * 0.78f)
                lineTo(w * 0.14f, h * 0.78f)
                close()
            }
            drawPath(path, color, style = Stroke(width = stroke, join = StrokeJoin.Round))
        }
        AgentProfileSectionIcon.Computer -> AgentIcon(AgentIconId.Monitor, size = size, color = color)
    }
}
