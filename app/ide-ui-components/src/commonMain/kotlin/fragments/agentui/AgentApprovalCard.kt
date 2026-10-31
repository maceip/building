package fragments.agentui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import fragments.components.UiButton
import fragments.components.UiButtonVariant
import fragments.components.UiDivider
import fragments.components.UiInput
import fragments.components.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val approvalDecisions = setOf("allow-once", "allow-session", "allow-always", "deny-once", "deny-always")

/** A single, kit-owned approval surface. The callback is one-shot for each request. */
@Composable
fun AgentApprovalCard(
    toolName: String,
    status: ChatApprovalStatus? = null,
    onDecide: ((ChatApprovalChoice) -> Unit)? = null,
    description: String = "",
    input: String = "",
    risk: ChatRiskLevel? = null,
    palette: AgentUiPalette = AgentUiTheme.palette,
    decisions: List<String> = listOf("allow-once", "deny-once"),
    editable: Boolean = false,
    allowReason: Boolean = true,
    shortcuts: Boolean = true,
    /** Pattern suggestions for lasting decisions. Null starts with any arguments. */
    ruleArgs: Map<String, String>? = null,
    ruleScope: Boolean = true,
    /** Use this instead of onDecide when the decision requires asynchronous work. */
    onDecideAsync: (suspend (ChatApprovalChoice) -> Unit)? = null,
) {
    require((onDecide == null) != (onDecideAsync == null)) {
        "Provide exactly one approval decision handler"
    }
    require(decisions.isNotEmpty() && decisions.distinct().size == decisions.size &&
        decisions.all { it in approvalDecisions }) { "Approval decisions must be unique supported values" }
    val sizing = AgentUiTheme.sizing
    val shape = RoundedCornerShape(if (sizing.isMobile) 16.dp else palette.toolRadius)
    var localStatus by remember(toolName, input, status) { mutableStateOf(ChatApprovalStatus.Pending) }
    var decisionSent by remember(toolName, input, status) { mutableStateOf(false) }
    var armed by remember(toolName, input, status) { mutableStateOf<String?>(null) }
    var reasonOpen by remember(toolName, input, status) { mutableStateOf(false) }
    var reason by remember(toolName, input, status) { mutableStateOf(TextFieldValue()) }
    var reasonFocused by remember { mutableStateOf(false) }
    val reasonFocus = remember { FocusRequester() }
    var editing by remember(toolName, input, status) { mutableStateOf(false) }
    var editedInput by remember(toolName, input, status) { mutableStateOf(TextFieldValue(input)) }
    var editFocused by remember { mutableStateOf(false) }
    val editFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var scopeArgs by remember(toolName, input, status, ruleArgs) { mutableStateOf(ruleArgs.orEmpty()) }
    var anyArguments by remember(toolName, input, status, ruleArgs) { mutableStateOf(ruleArgs.isNullOrEmpty()) }
    var submitError by remember(toolName, input, status) { mutableStateOf(false) }
    val current = if (status == null || status == ChatApprovalStatus.Pending && decisionSent) localStatus else status
    val submitting = decisionSent && current == ChatApprovalStatus.Pending
    LaunchedEffect(reasonOpen, current) {
        if (reasonOpen && current == ChatApprovalStatus.Pending) reasonFocus.requestFocus()
    }
    LaunchedEffect(editing, current) {
        if (editing && current == ChatApprovalStatus.Pending) editFocus.requestFocus()
    }

    fun choose(decision: String) {
        if (current != ChatApprovalStatus.Pending || decisionSent || decision !in decisions) return
        if (risk == ChatRiskLevel.Critical && decision.startsWith("allow") && armed != decision) {
            armed = decision
            return
        }
        val allowed = decision.startsWith("allow")
        val choice = ChatApprovalChoice(
            decision,
            reason = if (allowed) null else reason.text.trim().ifBlank { null },
            input = if (allowed) editedInput.text else null,
            args = if (ruleScope && decision != "allow-once" && decision != "deny-once" && !anyArguments) {
                scopeArgs.toMap()
            } else {
                null
            },
        )
        decisionSent = true
        armed = null
        submitError = false
        if (onDecideAsync != null) {
            scope.launch {
                try {
                    onDecideAsync(choice)
                    localStatus = if (allowed) ChatApprovalStatus.Approved else ChatApprovalStatus.Denied
                } catch (cancelled: CancellationException) {
                    decisionSent = false
                    localStatus = ChatApprovalStatus.Pending
                    throw cancelled
                } catch (_: Throwable) {
                    decisionSent = false
                    localStatus = ChatApprovalStatus.Pending
                    submitError = true
                }
            }
        } else {
            localStatus = if (allowed) ChatApprovalStatus.Approved else ChatApprovalStatus.Denied
            try {
                onDecide?.invoke(choice)
            } catch (error: Throwable) {
                decisionSent = false
                localStatus = ChatApprovalStatus.Pending
                throw error
            }
        }
    }

    val borderColor = if (current == ChatApprovalStatus.Pending && risk in listOf(
            ChatRiskLevel.High,
            ChatRiskLevel.Critical,
        )
    ) {
        palette.danger.copy(alpha = .55f)
    } else {
        palette.border
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.surface, shape)
            .border(1.dp, borderColor, shape)
            .onPreviewKeyEvent { event ->
                if (!shortcuts || current != ChatApprovalStatus.Pending ||
                    reasonFocused || editFocused || event.type != KeyEventType.KeyDown
                ) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.Y -> {
                        if (risk != ChatRiskLevel.Critical && "allow-once" in decisions) {
                            choose("allow-once")
                            true
                        } else {
                            false
                        }
                    }
                    Key.N -> if ("deny-once" in decisions) {
                        choose("deny-once")
                        true
                    } else {
                        false
                    }
                    else -> false
                }
            }
            .semantics { contentDescription = "Approval for $toolName" }
            .testTag("agent-approval-card")
            .padding(if (current == ChatApprovalStatus.Pending) (if (sizing.isMobile) 16.dp else 14.dp) else 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (current != ChatApprovalStatus.Pending) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentIcon(
                    if (current == ChatApprovalStatus.Approved) AgentIconId.Check else AgentIconId.Ban,
                    size = 16.dp,
                    color = if (current == ChatApprovalStatus.Approved) palette.success else palette.danger,
                )
                UiText(
                    if (current == ChatApprovalStatus.Approved) "Approved" else "Denied",
                    palette,
                    weight = FontWeight.SemiBold,
                    size = 13,
                )
                UiText(toolName, palette, Modifier.weight(1f), muted = true, size = 13)
            }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(if (sizing.isMobile) 36.dp else 32.dp)
                    .background(palette.subtle, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AgentIcon(
                    AgentIconId.Shield,
                    size = 18.dp,
                    color = if (risk in listOf(ChatRiskLevel.High, ChatRiskLevel.Critical)) palette.danger else palette.muted,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                UiText(
                    "APPROVAL REQUIRED",
                    palette.copy(text = palette.danger),
                    size = 10,
                    weight = FontWeight.SemiBold,
                )
                UiText(toolName, palette, size = 15, weight = FontWeight.SemiBold)
                if (description.isNotBlank()) UiText(description, palette, muted = true, size = 13)
            }
            if (risk != null) {
                Box(
                    Modifier
                        .border(
                            1.dp,
                            if (risk in listOf(ChatRiskLevel.High, ChatRiskLevel.Critical)) {
                                palette.danger.copy(alpha = .5f)
                            } else {
                                palette.border
                            },
                            CircleShape,
                        )
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    UiText(
                        risk.name,
                        palette.copy(
                            text = if (risk in listOf(ChatRiskLevel.High, ChatRiskLevel.Critical)) {
                                palette.danger
                            } else {
                                palette.muted
                            },
                        ),
                        size = 11,
                        weight = FontWeight.SemiBold,
                    )
                }
            }
        }

        if (input.isNotBlank()) {
            if (editing) {
                UiInput(
                    editedInput,
                    { editedInput = it; armed = null },
                    "Arguments",
                    palette,
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp)
                        .focusRequester(editFocus)
                        .onFocusChanged { editFocused = it.isFocused }
                        .testTag("approval-arguments-editor"),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .clipToBounds()
                        .verticalScroll(rememberScrollState()),
                ) {
                    AgentCodeBlock(editedInput.text.lines(), palette = palette, language = "text")
                }
            }
        }
        if (editable && input.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UiButton(if (editing) "Done editing" else "Edit arguments", { editing = !editing }, palette)
                if (editedInput.text != input) {
                    UiButton(
                        "Undo edits",
                        {
                            editedInput = TextFieldValue(input)
                            editing = false
                        },
                        palette,
                    )
                }
            }
        }

        if (ruleScope && decisions.any { it != "allow-once" && it != "deny-once" }) {
            UiDivider(palette)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UiText("Remember this choice for $toolName", palette, muted = true, size = 12)
                if (scopeArgs.isEmpty()) {
                    UiText("Any arguments", palette, size = 12)
                } else {
                    UiButton(
                        if (anyArguments) "Any arguments" else "Match shown arguments",
                        { anyArguments = !anyArguments; armed = null },
                        palette,
                        enabled = !submitting,
                    )
                    if (!anyArguments) {
                        scopeArgs.entries.sortedBy { it.key }.forEach { (name, pattern) ->
                            if (sizing.isMobile) {
                                Column(
                                    Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    UiText("$name matches", palette, muted = true, size = 12)
                                    UiInput(
                                        pattern,
                                        { value -> scopeArgs = scopeArgs + (name to value); armed = null },
                                        "Pattern",
                                        palette,
                                        Modifier.fillMaxWidth().testTag("approval-rule-$name"),
                                        singleLine = true,
                                    )
                                }
                            } else {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    UiText("$name matches", palette, muted = true, size = 12)
                                    UiInput(
                                        pattern,
                                        { value -> scopeArgs = scopeArgs + (name to value); armed = null },
                                        "Pattern",
                                        palette,
                                        Modifier.weight(1f).testTag("approval-rule-$name"),
                                        singleLine = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (submitting) UiText("Submitting decision…", palette, muted = true, size = 12)
        if (submitError) UiText("Decision failed. Try again.", palette.copy(text = palette.danger), size = 12)

        if (reasonOpen) {
            UiText("Reason for denying", palette, muted = true, size = 12)
            UiInput(
                reason,
                { reason = it },
                "What should the agent do instead?",
                palette,
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .focusRequester(reasonFocus)
                    .onFocusChanged { reasonFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || reason.composition != null) false
                        else when (event.key) {
                            Key.Enter -> if (!event.isShiftPressed && "deny-once" in decisions) {
                                choose("deny-once")
                                true
                            } else false
                            Key.Escape -> {
                                reasonOpen = false
                                true
                            }
                            else -> false
                        }
                    }
                    .testTag("approval-reason"),
            )
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val useMobileStack = sizing.isMobile || maxWidth < 460.dp
            if (useMobileStack) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Put primary allow actions first in the thumb zone, followed by deny and feedback
                    decisions.filter { it.startsWith("allow") }.forEach { decision ->
                        ApprovalDecisionButton(
                            decision = decision,
                            armed = armed == decision,
                            reasonOpen = reasonOpen,
                            choose = ::choose,
                            palette = palette,
                            enabled = !submitting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    decisions.filter { it.startsWith("deny") }.forEach { decision ->
                        ApprovalDecisionButton(
                            decision = decision,
                            armed = false,
                            reasonOpen = reasonOpen,
                            choose = ::choose,
                            palette = palette,
                            enabled = !submitting,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (allowReason && !reasonOpen) {
                        UiButton(
                            "Deny with feedback",
                            { reasonOpen = true },
                            palette,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !submitting,
                        )
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (allowReason && !reasonOpen) {
                        UiButton("Deny with feedback", { reasonOpen = true }, palette, enabled = !submitting)
                        Spacer(Modifier.width(8.dp))
                    }
                    decisions.filter { it.startsWith("deny") }.forEach { decision ->
                        ApprovalDecisionButton(
                            decision = decision,
                            armed = false,
                            reasonOpen = reasonOpen,
                            choose = ::choose,
                            palette = palette,
                            enabled = !submitting,
                        )
                        Spacer(Modifier.width(7.dp))
                    }
                    decisions.filter { it.startsWith("allow") }.forEach { decision ->
                        ApprovalDecisionButton(
                            decision = decision,
                            armed = armed == decision,
                            reasonOpen = reasonOpen,
                            choose = ::choose,
                            palette = palette,
                            enabled = !submitting,
                        )
                        Spacer(Modifier.width(7.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalDecisionButton(
    decision: String,
    armed: Boolean,
    reasonOpen: Boolean,
    choose: (String) -> Unit,
    palette: AgentUiPalette,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val label = when (decision) {
        "allow-once" -> if (armed) "Confirm approve once" else "Approve once"
        "allow-session" -> if (armed) "Confirm session approval" else "Approve for session"
        "allow-always" -> if (armed) "Confirm always approve" else "Always approve"
        "deny-always" -> "Always deny"
        else -> if (reasonOpen) "Send denial" else "Deny"
    }
    val primary = decision == "allow-once" || armed
    val variant = when {
        armed -> UiButtonVariant.Destructive
        primary -> UiButtonVariant.Primary
        decision.startsWith("deny") -> UiButtonVariant.DestructiveOutline
        else -> UiButtonVariant.Default
    }
    UiButton(
        label = label,
        onClick = { choose(decision) },
        palette = if (armed) palette.copy(accent = palette.danger) else palette,
        modifier = modifier,
        primary = primary,
        enabled = enabled,
        variant = variant,
    )
}
