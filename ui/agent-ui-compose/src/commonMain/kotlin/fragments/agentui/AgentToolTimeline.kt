package fragments.agentui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fragments.components.UiText
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
private fun currentTimelineMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** Signoff-style call phases; these preserve denied and interrupted outcomes. */
enum class ChatTimelinePhase { Streaming, Running, AwaitingApproval, Success, Error, Denied }

enum class ChatTimelineSubagentStatus { Running, Suspended, Done, Error }

/** Millisecond epoch timestamps from the agent runtime, rather than display-only elapsed text. */
@Immutable data class ChatToolTiming(
    val startedAtMillis: Long,
    val runningAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
)

@Immutable data class ChatToolTimelineCall(
    val id: String,
    val name: String,
    val phase: ChatTimelinePhase,
    val input: String? = null,
    val output: String? = null,
    val error: String? = null,
    val summary: String = "",
    val timing: ChatToolTiming? = null,
    /** Runs started by this call. Their calls appear beneath it, even when its details are closed. */
    val subagents: List<ChatTimelineSubagent> = emptyList(),
    /** Stable host renderer key. The visible name may change without losing its renderer. */
    val toolKind: String = name,
    /** Structured values passed to a host renderer and retained in the journal. */
    val fields: Map<String, String> = emptyMap(),
)

@Immutable data class ChatTimelineSubagent(
    val id: String,
    val name: String,
    val status: ChatTimelineSubagentStatus,
    val calls: List<ChatToolTimelineCall> = emptyList(),
    val error: String? = null,
)

private fun allTimelineCalls(calls: List<ChatToolTimelineCall>): List<ChatToolTimelineCall> = buildList {
    fun visit(call: ChatToolTimelineCall) {
        add(call)
        call.subagents.forEach { subagent -> subagent.calls.forEach(::visit) }
    }
    calls.forEach(::visit)
}

private fun allTimelineSubagents(calls: List<ChatToolTimelineCall>): List<ChatTimelineSubagent> = buildList {
    fun visit(call: ChatToolTimelineCall) {
        call.subagents.forEach { subagent ->
            add(subagent)
            subagent.calls.forEach(::visit)
        }
    }
    calls.forEach(::visit)
}

private enum class TimelineStatusMotion { None, Pulse, Spin, Ping }

@Composable
private fun TimelineStatusNode(
    icon: AgentIconId,
    color: Color,
    motion: TimelineStatusMotion,
    motionEnabled: Boolean,
) {
    val progress = if (motionEnabled && motion != TimelineStatusMotion.None) {
        val transition = rememberInfiniteTransition(label = "timeline status")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = when (motion) {
                        TimelineStatusMotion.Spin -> 900
                        TimelineStatusMotion.Ping -> 1_600
                        else -> 2_000
                    },
                    easing = if (motion == TimelineStatusMotion.Spin) LinearEasing
                        else if (motion == TimelineStatusMotion.Ping) CubicBezierEasing(0f, 0f, 0.2f, 1f)
                        else LinearEasing,
                ),
                repeatMode = if (motion == TimelineStatusMotion.Pulse) RepeatMode.Reverse else RepeatMode.Restart,
            ),
            label = "timeline status progress",
        )
        value
    } else 0f
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (motionEnabled && motion == TimelineStatusMotion.Ping) {
            Box(Modifier.matchParentSize().graphicsLayer {
                scaleX = 1f + 1.2f * progress
                scaleY = scaleX
                alpha = (0.7f * (1f - progress / 0.8f)).coerceAtLeast(0f)
            }.border(1.dp, color, CircleShape))
        }
        Box(Modifier.matchParentSize().border(1.dp, color, CircleShape), contentAlignment = Alignment.Center) {
            val iconModifier = when (motion) {
                TimelineStatusMotion.Spin -> Modifier.rotate(if (motionEnabled) progress * 360f else 0f)
                TimelineStatusMotion.Pulse -> Modifier.alpha(if (motionEnabled) 1f - 0.7f * progress else 1f)
                else -> Modifier
            }
            AgentIcon(icon, iconModifier, size = 13.dp, color = color)
        }
    }
}

/** Ordered call timeline. The compact row is the disclosure; details stay with that call. */
@Composable
fun AgentToolTimeline(
    calls: List<ChatToolTimelineCall>,
    modifier: Modifier = Modifier,
    expandedIds: Set<String>? = null,
    defaultExpandedIds: Set<String> = emptySet(),
    onExpandedChange: ((Set<String>) -> Unit)? = null,
    onApproval: ((toolId: String, approved: Boolean) -> Unit)? = null,
    active: Boolean = true,
    expandErrors: Boolean = false,
    waterfall: Boolean = true,
    announce: Boolean = true,
    clockMillis: Long? = null,
    palette: AgentUiPalette = AgentUiTheme.palette,
    rendererRegistry: ToolRendererRegistry? = null,
) {
    val allCalls = remember(calls) { allTimelineCalls(calls) }
    val allSubagents = remember(calls) { allTimelineSubagents(calls) }
    val initialCallIds = remember { allCalls.mapTo(mutableSetOf()) { it.id } }
    val seenCallIds = remember { mutableStateMapOf<String, Boolean>() }
    val motionEnabled = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor != 0f
    require(allCalls.map { it.id }.distinct().size == allCalls.size) { "Timeline call ids must be unique" }
    require(allSubagents.map { it.id }.distinct().size == allSubagents.size) { "Timeline subagent ids must be unique" }
    if (calls.isEmpty()) return
    var localExpanded by remember { mutableStateOf(defaultExpandedIds) }
    var collapsedErrors by remember { mutableStateOf(emptySet<String>()) }
    var openedSubagents by remember { mutableStateOf(emptySet<String>()) }
    var closedSubagents by remember { mutableStateOf(emptySet<String>()) }
    val expanded = expandedIds ?: localExpanded
    val isExpanded: (ChatToolTimelineCall) -> Boolean = { call ->
        call.id in expanded || expandErrors && call.phase == ChatTimelinePhase.Error && call.id !in collapsedErrors
    }
    val toggle: (String) -> Unit = { id ->
        val call = allCalls.first { it.id == id }
        val next = if (isExpanded(call)) {
            if (call.phase == ChatTimelinePhase.Error) collapsedErrors = collapsedErrors + id
            expanded - id
        } else {
            collapsedErrors = collapsedErrors - id
            expanded + id
        }
        if (expandedIds == null) localExpanded = next
        onExpandedChange?.invoke(next)
    }
    val isSubagentOpen: (ChatTimelineSubagent) -> Boolean = { subagent ->
        subagent.id in openedSubagents || subagent.id !in closedSubagents &&
            allTimelineCalls(subagent.calls).any { it.phase == ChatTimelinePhase.AwaitingApproval }
    }
    val toggleSubagent: (String) -> Unit = { id ->
        val subagent = allSubagents.first { it.id == id }
        if (isSubagentOpen(subagent)) {
            openedSubagents = openedSubagents - id
            closedSubagents = closedSubagents + id
        } else {
            closedSubagents = closedSubagents - id
            openedSubagents = openedSubagents + id
        }
    }
    val hasUnsettled = remember(allCalls) { allCalls.any {
        it.phase == ChatTimelinePhase.Running || it.phase == ChatTimelinePhase.Streaming
    } }
    val hasTimedUnsettled = remember(allCalls) { allCalls.any {
        (it.phase == ChatTimelinePhase.Running || it.phase == ChatTimelinePhase.Streaming) &&
            it.timing?.endedAtMillis == null && it.timing != null
    } }
    val running = active && hasUnsettled
    var liveNow by remember { mutableLongStateOf(currentTimelineMillis()) }
    var wasActive by remember { mutableStateOf(active) }
    var stoppedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(running, clockMillis) {
        if (clockMillis != null || !running) return@LaunchedEffect
        while (true) { liveNow = currentTimelineMillis(); delay(100) }
    }
    val now = clockMillis ?: liveNow
    LaunchedEffect(active) {
        if (wasActive && !active) stoppedAt = now
        wasActive = active
    }
    val displayNow = if (active) now else clockMillis ?: stoppedAt
    val spanStart = remember(allCalls) { allCalls.mapNotNull { it.timing?.startedAtMillis }.minOrNull() }
    val settledEnd = remember(allCalls) { allCalls.mapNotNull { it.timing?.endedAtMillis }.maxOrNull() }
    val spanEnd = if (hasTimedUnsettled && displayNow != null) maxOf(settledEnd ?: displayNow, displayNow)
        else settledEnd
    val seenPhases = remember { mutableStateMapOf<String, ChatTimelinePhase>() }
    val pendingAnnouncements = remember { mutableStateMapOf<String, Pair<Long, String>>() }
    var announcement by remember { mutableStateOf("") }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(allCalls.map { it.id to it.phase }) {
        if (initialized && announce) allCalls.forEach { call ->
            val prior = seenPhases[call.id]
            if (call.phase in listOf(ChatTimelinePhase.Success, ChatTimelinePhase.Error, ChatTimelinePhase.Denied) &&
                prior != call.phase && (prior == null || prior !in listOf(
                    ChatTimelinePhase.Success, ChatTimelinePhase.Error, ChatTimelinePhase.Denied))) {
                val sentence = when (call.phase) {
                    ChatTimelinePhase.Success -> "${call.name} finished${call.timing?.endedAtMillis?.let { end ->
                        " in ${formatDuration((end - (call.timing.runningAtMillis ?: call.timing.startedAtMillis)).coerceAtLeast(0))}"
                    }.orEmpty()}"
                    ChatTimelinePhase.Error -> "${call.name} failed${call.error?.let { ": $it" }.orEmpty()}"
                    ChatTimelinePhase.Denied -> "${call.name} denied"
                    else -> ""
                }
                pendingAnnouncements[call.id] = (call.timing?.endedAtMillis ?: clockMillis ?: currentTimelineMillis()) to sentence
            }
        }
        allCalls.forEach { seenPhases[it.id] = it.phase }
        initialized = true
    }
    LaunchedEffect(pendingAnnouncements.keys.toList()) {
        if (pendingAnnouncements.isEmpty()) return@LaunchedEffect
        delay(300)
        announcement = pendingAnnouncements.values.sortedBy { it.first }
            .joinToString(" ") { (_, sentence) -> sentence.trimEnd('.') + "." }
        pendingAnnouncements.clear()
    }
    LaunchedEffect(announce) {
        if (!announce) { pendingAnnouncements.clear(); announcement = "" }
    }
    val focusIds = remember(allCalls, allSubagents) {
        allCalls.map { "call:${it.id}" } + allSubagents.map { "subagent:${it.id}" }
    }
    val focus = remember(focusIds) { focusIds.associateWith { FocusRequester() } }
    val visibleFocusIds = buildList {
        fun visit(call: ChatToolTimelineCall) {
            add("call:${call.id}")
            call.subagents.forEach { subagent ->
                if (subagent.calls.isNotEmpty()) {
                    add("subagent:${subagent.id}")
                    if (isSubagentOpen(subagent)) subagent.calls.forEach(::visit)
                }
            }
        }
        calls.forEach(::visit)
    }
    val topIndexByFocusId = buildMap {
        fun visit(call: ChatToolTimelineCall, top: Int) {
            put("call:${call.id}", top)
            call.subagents.forEach { subagent ->
                put("subagent:${subagent.id}", top)
                subagent.calls.forEach { visit(it, top) }
            }
        }
        calls.forEachIndexed { index, call -> visit(call, index) }
    }
    val listState = rememberLazyListState()
    var requestedFocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(requestedFocus) {
        val id = requestedFocus ?: return@LaunchedEffect
        if (calls.size > 150) {
            listState.scrollToItem(topIndexByFocusId[id] ?: 0)
            kotlinx.coroutines.yield()
        }
        focus[id]?.requestFocus()
        requestedFocus = null
    }
    val navigate: (String, Key) -> Boolean = { from, key ->
        val index = visibleFocusIds.indexOf(from)
        if (index < 0) false else {
            val target = when (key) {
                Key.DirectionDown -> (index + 1) % visibleFocusIds.size
                Key.DirectionUp -> (index - 1 + visibleFocusIds.size) % visibleFocusIds.size
                Key.MoveHome -> 0
                Key.MoveEnd -> visibleFocusIds.lastIndex
                else -> -1
            }
            if (target < 0) false else { requestedFocus = visibleFocusIds[target]; true }
        }
    }
    val row: @Composable (Int, ChatToolTimelineCall) -> Unit = { index, call ->
        TimelineCallRow(call, index, calls.size, isExpanded(call), toggle,
            focus.getValue("call:${call.id}"), navigate, isExpanded,
            isSubagentOpen, toggleSubagent, focus, onApproval,
            active = active, now = displayNow, spanStart = spanStart, spanEnd = spanEnd,
            waterfall = waterfall, palette = palette, rendererRegistry = rendererRegistry,
            initialCallIds = initialCallIds, seenCallIds = seenCallIds, motionEnabled = motionEnabled)
    }
    if (calls.size > 150) {
        LazyColumn(modifier.fillMaxWidth().height(440.dp).testTag("agent-tool-timeline"),
            state = listState) {
            itemsIndexed(calls, key = { _, call -> call.id }) { index, call -> row(index, call) }
        }
    } else {
        Column(modifier.fillMaxWidth().testTag("agent-tool-timeline")) {
            calls.forEachIndexed { index, call -> androidx.compose.runtime.key(call.id) { row(index, call) } }
        }
    }
    if (announce) AgentLiveRegion(announcement)
}

@Composable
private fun TimelineCallRow(
    call: ChatToolTimelineCall,
    index: Int,
    count: Int,
    expanded: Boolean,
    toggle: (String) -> Unit,
    focus: FocusRequester,
    navigate: (String, Key) -> Boolean,
    isExpanded: (ChatToolTimelineCall) -> Boolean,
    isSubagentOpen: (ChatTimelineSubagent) -> Boolean,
    toggleSubagent: (String) -> Unit,
    focusById: Map<String, FocusRequester>,
    onApproval: ((String, Boolean) -> Unit)?,
    active: Boolean,
    now: Long?,
    spanStart: Long?,
    spanEnd: Long?,
    waterfall: Boolean,
    palette: AgentUiPalette,
    rendererRegistry: ToolRendererRegistry?,
    initialCallIds: Set<String>,
    seenCallIds: SnapshotStateMap<String, Boolean>,
    motionEnabled: Boolean,
) {
    val enter = motionEnabled && call.id !in initialCallIds && seenCallIds[call.id] != true
    val entry = remember(call.id) { Animatable(if (enter) 0f else 1f) }
    var entryFinished by remember(call.id) { mutableStateOf(!enter) }
    LaunchedEffect(call.id, motionEnabled) {
        seenCallIds[call.id] = true
        if (enter) entry.animateTo(1f, tween(320, easing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)))
        else entry.snapTo(1f)
        entryFinished = true
    }
    val stopped = !active && (call.phase == ChatTimelinePhase.Running || call.phase == ChatTimelinePhase.Streaming)
    val phaseText = if (stopped) "Stopped" else when (call.phase) {
        ChatTimelinePhase.Streaming -> "Streaming"
        ChatTimelinePhase.Running -> "Running"
        ChatTimelinePhase.AwaitingApproval -> "Needs approval"
        ChatTimelinePhase.Success -> "Completed"
        ChatTimelinePhase.Error -> "Failed"
        ChatTimelinePhase.Denied -> "Denied"
    }
    val icon = when {
        stopped -> AgentIconId.Pause
        call.phase == ChatTimelinePhase.Success -> AgentIconId.Check
        call.phase == ChatTimelinePhase.Error -> AgentIconId.Close
        call.phase == ChatTimelinePhase.Denied -> AgentIconId.Ban
        call.phase == ChatTimelinePhase.AwaitingApproval -> AgentIconId.Alert
        call.phase == ChatTimelinePhase.Running -> AgentIconId.Spinner
        else -> AgentIconId.Dot
    }
    val accent = when (call.phase) {
        ChatTimelinePhase.Error, ChatTimelinePhase.AwaitingApproval -> palette.danger
        ChatTimelinePhase.Running, ChatTimelinePhase.Streaming -> palette.accent
        else -> palette.muted
    }
    val timing = call.timing
    val end = timing?.endedAtMillis ?: if (call.phase == ChatTimelinePhase.Running ||
        call.phase == ChatTimelinePhase.Streaming) now else null
    val duration = if (timing != null && end != null && call.phase != ChatTimelinePhase.AwaitingApproval)
        (end - (timing.runningAtMillis ?: timing.startedAtMillis)).coerceAtLeast(0) else null
    val connectorHeight by animateDpAsState(if (expanded) 60.dp else 18.dp,
        tween(if (motionEnabled) 220 else 0), label = "timeline connector")
    val chevronRotation by animateFloatAsState(if (expanded) 90f else 0f,
        tween(if (motionEnabled) 200 else 0), label = "timeline chevron")
    val entryModifier = if (entryFinished) Modifier else Modifier.graphicsLayer {
        alpha = entry.value
        translationY = (1f - entry.value) * 4.dp.toPx()
    }
    Row(Modifier.fillMaxWidth().then(entryModifier).padding(bottom = if (index == count - 1) 0.dp else 6.dp)) {
        Column(Modifier.width(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TimelineStatusNode(icon, accent, when {
                stopped -> TimelineStatusMotion.None
                call.phase == ChatTimelinePhase.Streaming -> TimelineStatusMotion.Pulse
                call.phase == ChatTimelinePhase.Running -> TimelineStatusMotion.Spin
                call.phase == ChatTimelinePhase.AwaitingApproval -> TimelineStatusMotion.Ping
                else -> TimelineStatusMotion.None
            }, motionEnabled)
            if (index != count - 1) Box(Modifier.width(1.dp).height(connectorHeight)
                .padding(top = 2.dp)) { Canvas(Modifier.matchParentSize()) { drawRect(palette.border) } }
        }
        val sizing = AgentUiTheme.sizing
        Spacer(Modifier.width(if (sizing.isMobile) 8.dp else 10.dp))
        Column(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        navigate("call:${call.id}", event.key)
                    }
                    .clickable(role = Role.Button) { toggle(call.id) }
                    .semantics {
                        role = Role.Button
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                        contentDescription = "${call.name}, $phaseText${if (call.summary.isBlank()) "" else ", ${call.summary}"}"
                    }
                    .focusable()
                    .testTag("agent-timeline-call-${call.id}")
                    .padding(vertical = if (sizing.isMobile) 9.dp else 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UiText(call.name, palette, Modifier.weight(1f), size = 13, maxLines = if (sizing.isMobile) 2 else 1)
                    UiText(phaseText, palette, muted = true, size = 12, maxLines = 1)
                    if (!sizing.isMobile && waterfall && timing != null && spanStart != null && spanEnd != null && spanEnd > spanStart) {
                        TimelineWaterfall(timing, end, spanStart, spanEnd, accent, palette)
                    }
                    if (duration != null || !sizing.isMobile) {
                        UiText(
                            duration?.let(::formatDuration) ?: "",
                            palette,
                            if (sizing.isMobile) Modifier else Modifier.width(42.dp),
                            muted = true,
                            size = 11,
                            mono = true,
                            maxLines = 1,
                        )
                    }
                    AgentIcon(
                        AgentIconId.Chevron,
                        modifier = Modifier.rotate(chevronRotation),
                        size = 14.dp,
                        color = palette.muted,
                    )
                }
                if (sizing.isMobile && waterfall && timing != null && spanStart != null && spanEnd != null && spanEnd > spanStart) {
                    TimelineWaterfall(
                        timing = timing,
                        end = end,
                        spanStart = spanStart,
                        spanEnd = spanEnd,
                        color = accent,
                        palette = palette,
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                }
            }
            if (call.phase == ChatTimelinePhase.Error && !expanded && !call.error.isNullOrBlank())
                UiText(call.error, palette, muted = true, size = 12)
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(if (motionEnabled) 220 else 0)) +
                    expandVertically(tween(if (motionEnabled) 220 else 0)),
                exit = fadeOut(tween(if (motionEnabled) 180 else 0)) +
                    shrinkVertically(tween(if (motionEnabled) 180 else 0)),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    val renderer = rendererRegistry?.renderer(call.toolKind)
                    if (renderer != null) {
                        val fields = buildMap {
                            putAll(call.fields)
                            call.input?.let { if (!containsKey("input")) put("input", it) }
                            call.output?.let { if (!containsKey("output")) put("output", it) }
                            put("timelinePhase", call.phase.name)
                        }
                        val status = when (call.phase) {
                            ChatTimelinePhase.Streaming, ChatTimelinePhase.Running -> ChatToolStatus.Running
                            ChatTimelinePhase.AwaitingApproval -> ChatToolStatus.NeedsApproval
                            ChatTimelinePhase.Success -> ChatToolStatus.Completed
                            ChatTimelinePhase.Error, ChatTimelinePhase.Denied -> ChatToolStatus.Error
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            renderer.Render(ChatTool.Generic(call.id, call.name, call.output.orEmpty(),
                                status = status, kind = call.toolKind, fields = fields))
                        }
                    } else {
                        if (call.input != null) AgentJsonView(call.input, label = "Input", collapseAfter = 8, palette = palette)
                        if (call.output != null) AgentJsonView(call.output, label = "Output", collapseAfter = 8, palette = palette)
                        call.fields.entries.sortedBy { it.key }.forEach { (key, value) ->
                            UiText("$key: $value", palette, muted = true, size = 12)
                        }
                    }
                    if (!call.error.isNullOrBlank()) UiText(call.error, palette, size = 12)
                }
            }
            if (call.phase == ChatTimelinePhase.AwaitingApproval && onApproval != null)
                AgentApprovalCard(call.name, input = call.input.orEmpty(),
                    onDecide = { onApproval(call.id, it.decision.startsWith("allow")) }, palette = palette)
            call.subagents.forEach { subagent ->
                androidx.compose.runtime.key(subagent.id) {
                    TimelineSubagentRow(subagent, isSubagentOpen(subagent), toggleSubagent,
                        navigate, isExpanded, toggle, isSubagentOpen, focusById,
                        onApproval, active, now, spanStart, spanEnd, waterfall, palette, rendererRegistry,
                        initialCallIds, seenCallIds, motionEnabled)
                }
            }
        }
    }
}

@Composable
private fun TimelineSubagentRow(
    subagent: ChatTimelineSubagent,
    expanded: Boolean,
    toggleSubagent: (String) -> Unit,
    navigate: (String, Key) -> Boolean,
    isExpanded: (ChatToolTimelineCall) -> Boolean,
    toggleCall: (String) -> Unit,
    isSubagentOpen: (ChatTimelineSubagent) -> Boolean,
    focusById: Map<String, FocusRequester>,
    onApproval: ((String, Boolean) -> Unit)?,
    active: Boolean,
    now: Long?,
    spanStart: Long?,
    spanEnd: Long?,
    waterfall: Boolean,
    palette: AgentUiPalette,
    rendererRegistry: ToolRendererRegistry?,
    initialCallIds: Set<String>,
    seenCallIds: SnapshotStateMap<String, Boolean>,
    motionEnabled: Boolean,
) {
    val waiting = allTimelineCalls(subagent.calls).any { it.phase == ChatTimelinePhase.AwaitingApproval }
    val status = when {
        waiting -> "Needs approval"
        subagent.status == ChatTimelineSubagentStatus.Done -> "Completed"
        subagent.status == ChatTimelineSubagentStatus.Error -> "Failed"
        subagent.status == ChatTimelineSubagentStatus.Suspended -> "Paused"
        !active -> "Stopped"
        else -> "Running"
    }
    val icon = when (status) {
        "Needs approval" -> AgentIconId.Alert
        "Completed" -> AgentIconId.Check
        "Failed" -> AgentIconId.Close
        "Paused", "Stopped" -> AgentIconId.Pause
        else -> AgentIconId.Spinner
    }
    val accent = when (status) {
        "Needs approval", "Failed" -> palette.danger
        "Running" -> palette.accent
        else -> palette.muted
    }
    val sizing = AgentUiTheme.sizing
    val chevronRotation by animateFloatAsState(if (expanded) 90f else 0f,
        tween(if (motionEnabled) 200 else 0), label = "subagent chevron")
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        TimelineStatusNode(icon, accent, when (status) {
            "Needs approval" -> TimelineStatusMotion.Ping
            "Running" -> TimelineStatusMotion.Spin
            else -> TimelineStatusMotion.None
        }, motionEnabled)
        Spacer(Modifier.width(if (sizing.isMobile) 8.dp else 10.dp))
        Column(Modifier.weight(1f)) {
            val rowModifier = if (subagent.calls.isEmpty()) Modifier.fillMaxWidth() else
                Modifier.fillMaxWidth().focusRequester(focusById.getValue("subagent:${subagent.id}"))
                    .onPreviewKeyEvent { event ->
                        event.type == KeyEventType.KeyDown && navigate("subagent:${subagent.id}", event.key)
                    }
                    .clickable(role = Role.Button) { toggleSubagent(subagent.id) }
                    .semantics {
                        role = Role.Button
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                        contentDescription = "${subagent.name}, ${subagent.calls.size} ${if (subagent.calls.size == 1) "call" else "calls"}, $status"
                    }
                    .focusable().testTag("agent-timeline-subagent-${subagent.id}")
            Row(rowModifier.padding(vertical = if (sizing.isMobile) 9.dp else 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UiText(subagent.name, palette, Modifier.weight(1f), size = 13, maxLines = 1)
                UiText("${subagent.calls.size} ${if (subagent.calls.size == 1) "call" else "calls"}", palette, muted = true, size = 12, maxLines = 1)
                UiText(status, palette, muted = true, size = 12, maxLines = 1)
                if (subagent.calls.isNotEmpty()) AgentIcon(AgentIconId.Chevron,
                    modifier = Modifier.rotate(chevronRotation), size = 14.dp, color = palette.muted)
            }
            if (subagent.status == ChatTimelineSubagentStatus.Error && !subagent.error.isNullOrBlank())
                UiText(subagent.error, palette, muted = true, size = 12)
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(if (motionEnabled) 220 else 0)) +
                    expandVertically(tween(if (motionEnabled) 220 else 0)),
                exit = fadeOut(tween(if (motionEnabled) 180 else 0)) +
                    shrinkVertically(tween(if (motionEnabled) 180 else 0)),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    subagent.calls.forEachIndexed { index, call ->
                        androidx.compose.runtime.key(call.id) {
                            TimelineCallRow(call, index, subagent.calls.size, isExpanded(call), toggleCall,
                                focusById.getValue("call:${call.id}"), navigate, isExpanded,
                                isSubagentOpen, toggleSubagent, focusById, onApproval, active, now,
                                spanStart, spanEnd, waterfall, palette, rendererRegistry,
                                initialCallIds, seenCallIds, motionEnabled)
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String = if (ms < 1_000) "${ms}ms"
    else {
        val tenths = (ms + 50L) / 100L
        "${tenths / 10}.${tenths % 10}s"
    }

@Composable
private fun TimelineWaterfall(
    timing: ChatToolTiming,
    end: Long?,
    spanStart: Long,
    spanEnd: Long,
    color: Color,
    palette: AgentUiPalette,
    modifier: Modifier = Modifier.width(64.dp).height(4.dp),
) {
    Canvas(modifier.testTag("agent-timeline-waterfall")) {
        drawRoundRect(palette.subtle, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        if (end != null) {
            val span = (spanEnd - spanStart).toFloat()
            val left = ((timing.startedAtMillis - spanStart) / span).coerceIn(0f, 1f) * size.width
            val right = ((end - spanStart) / span).coerceIn(0f, 1f) * size.width
            drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
                size = androidx.compose.ui.geometry.Size((right - left).coerceAtLeast(size.width * .02f), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        }
    }
}
