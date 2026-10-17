package dev.ide.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import dev.ide.ui.IdeUiState
import dev.ide.ui.ext.ToolWindowAnchor
import dev.ide.ui.platform.PlatformPredictiveBackHandler
import dev.ide.ui.platform.isMobilePlatform
import dev.ide.ui.platform.rememberIdeHaptics
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import dev.ide.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The right-edge zone where a leftward swipe grabs the drawer open. */
private val EdgeGrabWidth = 24.dp

/** Fling speed (px/s, expressed in dp) past which a release commits to open/close regardless of position. */
private val FlingCommit = 320.dp

/** Resting height fraction when the Agent Chat window shrinks to Half-Screen Multitasking Split mode. */
private const val AgentHalfScreenFraction = 0.52f

/**
 * The phone right-edge tool-window drawer: an overlay that tracks the finger continuously, mirroring
 * [PushDrawer] (which is left-only). It is fully plugin-derived — it hosts the RIGHT-anchored panels
 * ([pluginPanels]; the AI chat is the first such plugin), so it self-gates: when no plugin contributes a
 * RIGHT tool window it renders nothing, not even the gesture strip.
 *
 * **Custom Predictive Back & Multitasking Split**:
 * When the Agent view is open at full screen, swiping Back smoothly shrinks the Agent chat window to
 * **Half-Screen Split Mode ([AgentHalfScreenFraction])** at the bottom of the screen so the user can see
 * their code behind/above it AND watch what the agent is typing in real time. A second Back gesture from
 * Half-Screen mode dismisses the panel completely.
 */
@Composable
fun RightToolOverlay(state: IdeUiState) {
    val panels = pluginPanels(ToolWindowAnchor.RIGHT, state.backend, state.active?.path)
    if (panels.isEmpty()) return
    val primaryId = panels.first().id
    val panelState = rememberSaveableStateHolder()
    val headerSlots = rememberToolWindowHeaderSlots()
    val haptics = rememberIdeHaptics()

    // Live predictive-back gesture progress (0f..1f)
    var backGestureProgress by remember { mutableFloatStateOf(0f) }
    var thresholdCrossed by remember { mutableStateOf(false) }

    PlatformPredictiveBackHandler(
        enabled = isMobilePlatform && state.selectedRightPanel != null,
        onProgress = { progress ->
            backGestureProgress = progress
            if (progress > 0.22f && !thresholdCrossed) {
                thresholdCrossed = true
                haptics.predictiveBackThreshold()
            } else if (progress < 0.15f) {
                thresholdCrossed = false
            }
        },
        onCancel = {
            backGestureProgress = 0f
            thresholdCrossed = false
        },
        onBack = {
            backGestureProgress = 0f
            thresholdCrossed = false
            if (!state.rightPanelHalfScreen) {
                // First Back from Full-Screen Agent: smoothly lock into Half-Screen Multitasking Split
                // so the user can see the code editor behind it while still watching the agent type!
                haptics.predictiveBackThreshold()
                state.rightPanelHalfScreen = true
            } else {
                // Second Back from Half-Screen Split: dismiss the Agent overlay completely.
                haptics.tap()
                state.selectedRightPanel = null
                state.rightPanelHalfScreen = false
            }
        },
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val panelWidth = if (maxWidth < 480.dp) maxWidth else 420.dp
        val openPx = with(density) { panelWidth.toPx() }
        val fullHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val flingPx = with(density) { FlingCommit.toPx() }
        val touchSlop = LocalViewConfiguration.current.touchSlop
        val scope = rememberCoroutineScope()

        // 0 = fully closed (panel off-screen right), openPx = fully open (panel flush at the right edge).
        val shown = remember { Animatable(0f) }
        shown.updateBounds(0f, openPx)
        val visible by remember { derivedStateOf { shown.value > 0.5f || state.selectedRightPanel != null } }

        // Smoothly animated base height fraction: 1.0f (Full) <-> 0.52f (Half-Screen Split)
        val baseHeightFraction by animateFloatAsState(
            targetValue = if (state.rightPanelHalfScreen) AgentHalfScreenFraction else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "agentHeightFraction",
        )

        // Combine base height with live predictive-back gesture progress:
        // - If in Full-Screen mode, predictive back smoothly interpolates height from 1.0 -> 0.52
        // - If already in Half-Screen mode, predictive back smoothly shrinks from 0.52 -> 0.25 and slides out
        val effectiveHeightFraction = if (!state.rightPanelHalfScreen) {
            1f - (1f - AgentHalfScreenFraction) * backGestureProgress
        } else {
            (baseHeightFraction * (1f - 0.35f * backGestureProgress)).coerceIn(0.28f, 1f)
        }

        val splitMorphProgress = ((1f - effectiveHeightFraction) / (1f - AgentHalfScreenFraction)).coerceIn(0f, 1f)

        fun dragBy(delta: Float) {
            scope.launch { shown.snapTo((shown.value - delta).coerceIn(0f, openPx)) }
        }

        suspend fun settle(velocityX: Float) {
            val target = when {
                velocityX < -flingPx -> openPx
                velocityX > flingPx -> 0f
                shown.value >= openPx / 2f -> openPx
                else -> 0f
            }
            shown.animateTo(target, tween(Motion.BASE, easing = Motion.quiet), initialVelocity = -velocityX)
            if (target >= openPx / 2f) {
                state.selectedRightPanel = state.selectedRightPanel ?: primaryId
            } else {
                state.selectedRightPanel = null
                state.rightPanelHalfScreen = false
            }
        }

        LaunchedEffect(state.selectedRightPanel, openPx) {
            val target = if (state.selectedRightPanel != null) openPx else 0f
            if (shown.value != target) shown.animateTo(target, tween(Motion.BASE, easing = Motion.quiet))
        }

        if (isMobilePlatform) {
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(EdgeGrabWidth)
                    .pointerInput(openPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (shown.value > 0.5f) return@awaitEachGesture
                            val ed = state.active?.session
                            if (ed != null && ed.hScrollOffsetPx < ed.hScrollMaxPx - 0.5f) return@awaitEachGesture
                            var totalX = 0f
                            var totalY = 0f
                            var claimed = false
                            val tracker = VelocityTracker()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (claimed) {
                                        change.consume()
                                        scope.launch { settle(tracker.calculateVelocity().x) }
                                    }
                                    break
                                }
                                val delta = change.positionChange()
                                totalX += delta.x
                                totalY += delta.y
                                if (!claimed) {
                                    if (abs(totalY) > touchSlop && abs(totalY) > abs(totalX)) break
                                    if (-totalX > touchSlop && -totalX > abs(totalY)) claimed = true
                                }
                                if (claimed) {
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    dragBy(delta.x)
                                    change.consume()
                                }
                            }
                        }
                    },
            )
        }

        if (visible) {
            // In Full-Screen mode, dim the background editor; as the Agent shrinks toward Half-Screen Split,
            // fade the scrim to 0 so the user can clearly see and interact with the code editor in the top half!
            val scrimOpacity = (shown.value / openPx).coerceIn(0f, 1f) * (1f - splitMorphProgress)
            if (scrimOpacity > 0.02f) {
                Box(
                    Modifier.fillMaxSize()
                        .graphicsLayer { alpha = scrimOpacity }
                        .background(Color.Black.copy(alpha = 0.32f))
                        .draggable(
                            rememberDraggableState { dragBy(it) },
                            Orientation.Horizontal,
                            enabled = isMobilePlatform,
                            onDragStopped = { velocity -> scope.launch { settle(velocity) } },
                        ),
                )
            }

            val selectedId = state.selectedRightPanel
            val effectiveId = selectedId ?: primaryId
            val topCorner = lerp(0.dp, Ca.radius.sheet, splitMorphProgress)
            val panelShape = RoundedCornerShape(topStart = topCorner, topEnd = topCorner)
            val closeSlideOffset = if (state.rightPanelHalfScreen) (backGestureProgress * openPx * 0.45f).roundToInt() else 0

            // Vertical drag on the top handle lets the user manually pull the Agent down to Half-Screen or up to Full
            val verticalDragState = rememberDraggableState { dy ->
                if (dy > 18f && !state.rightPanelHalfScreen) {
                    haptics.predictiveBackThreshold()
                    state.rightPanelHalfScreen = true
                } else if (dy < -18f && state.rightPanelHalfScreen) {
                    haptics.tap()
                    state.rightPanelHalfScreen = false
                }
            }

            Box(
                Modifier.align(Alignment.BottomEnd)
                    .width(panelWidth)
                    .height(with(density) { (fullHeightPx * effectiveHeightFraction).toDp() })
                    .offset { IntOffset((openPx - shown.value).roundToInt() + closeSlideOffset, 0) }
                    .shadow(if (splitMorphProgress > 0.05f) 20.dp else 0.dp, panelShape, clip = false)
                    .clip(panelShape)
                    .then(
                        if (splitMorphProgress > 0.05f) {
                            Modifier.border(1.dp, Ide.colors.glassEdgeTop, panelShape)
                        } else {
                            Modifier
                        }
                    ),
            ) {
                ToolWindowSurface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        // Top multitasking handle + Half-Screen Split toggle bar
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .draggable(verticalDragState, Orientation.Vertical)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    haptics.predictiveBackThreshold()
                                    state.rightPanelHalfScreen = !state.rightPanelHalfScreen
                                }
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                if (state.rightPanelHalfScreen) "Agent Peek · Code Live Above" else "Swipe Back for ½ Split",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Box(
                                Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .background(
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                                        RoundedCornerShape(999.dp),
                                    )
                            )
                            Text(
                                if (state.rightPanelHalfScreen) "Expand ⤢" else "½ Split ⤡",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        ToolWindowSwitcherHeader(
                            panels = panels,
                            selectedId = effectiveId,
                            onSelect = { state.selectRightPanel(it) },
                            slots = headerSlots,
                            onHide = {
                                state.selectedRightPanel = null
                                state.rightPanelHalfScreen = false
                            },
                        )
                        AnimatedContent(
                            targetState = effectiveId,
                            transitionSpec = { fadeIn(tween(Motion.BASE)) togetherWith fadeOut(tween(Motion.FAST)) },
                            label = "rightPanelSwitch",
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) { id ->
                            PanelContent(
                                panels.firstOrNull { it.id == id } ?: panels.first(),
                                panelState,
                                Modifier.fillMaxSize(),
                                key = id,
                                headerSlot = headerSlots.of(id),
                            )
                        }
                    }
                }
            }
        }
    }
}
