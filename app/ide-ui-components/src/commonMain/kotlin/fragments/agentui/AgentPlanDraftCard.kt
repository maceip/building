package fragments.agentui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
private fun newPlanStepId(): String = Uuid.random().toString()

@Immutable
data class AgentPlanDraftStep(
    val id: String,
    val instruction: String = "",
    val agentId: AgentId = AgentId("assistant"),
    val requiresApproval: Boolean = true,
)

/** Host-controlled plan draft. Every edit is emitted to [onChange] for durable conversation storage. */
@Composable
fun AgentPlanDraftCard(
    steps: List<AgentPlanDraftStep>,
    agents: List<AgentParticipant>,
    submitted: Boolean = false,
    sending: Boolean = false,
    onChange: (List<AgentPlanDraftStep>) -> Unit,
    onSend: (List<AgentPlanDraftStep>) -> Unit,
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    Column(modifier.fillMaxWidth().background(palette.surface).testTag("agent-plan-draft"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PlanText("Plan draft", palette, Modifier.weight(1f), 15, FontWeight.SemiBold)
            PlanText("${steps.size} steps", palette.copy(text = palette.muted), size = 11)
        }
        steps.forEachIndexed { index, step ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
            PlanStepEditor(step, index, steps.size, agents, palette,
                onUpdate = { revised -> onChange(steps.toMutableList().also { it[index] = revised }) },
                onMove = { direction ->
                    val target = index + direction
                    if (target in steps.indices) onChange(steps.toMutableList().also {
                        val moving = it.removeAt(index)
                        it.add(target, moving)
                    })
                },
                onDelete = { onChange(steps.filterIndexed { at, _ -> at != index }) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentUiButton("Add step", { onChange(steps + AgentPlanDraftStep(newPlanStepId())) },
                palette = palette)
            if (sending) PlanText("Sending…", palette.copy(text = palette.muted), size = 12)
            else if (submitted) PlanText("Sent for review", palette.copy(text = palette.success), size = 12)
            else if (steps.any { it.instruction.isNotBlank() }) AgentUiButton(
                "Ask ${agents.firstOrNull { it.id == steps.firstOrNull()?.agentId }?.name ?: "agent"} to review", {
                onSend(steps.filter { it.instruction.isNotBlank() })
            }, primary = true, palette = palette)
        }
    }
}

@Composable
private fun PlanStepEditor(
    step: AgentPlanDraftStep,
    index: Int,
    count: Int,
    agents: List<AgentParticipant>,
    palette: AgentUiPalette,
    onUpdate: (AgentPlanDraftStep) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var roleMenuOpen by remember(step.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanText("${index + 1}.", palette.copy(text = palette.muted), size = 12)
            Box(Modifier.weight(1f)) {
                if (step.instruction.isEmpty()) PlanText("Describe this step", palette.copy(text = palette.muted),
                    Modifier.padding(vertical = 3.dp), size = 13)
                BasicTextField(step.instruction, { onUpdate(step.copy(instruction = it)) },
                    Modifier.fillMaxWidth().testTag("agent-plan-step-input-$index"),
                    textStyle = TextStyle(color = palette.text, fontSize = 13.sp),
                    cursorBrush = SolidColor(palette.accent), singleLine = false)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                val selected = agents.firstOrNull { it.id == step.agentId }?.name ?: step.agentId.value
                AgentUiButton("$selected ▾", { roleMenuOpen = true },
                    Modifier.testTag("agent-plan-role-button-$index"), palette = palette)
                if (roleMenuOpen) Popup(onDismissRequest = { roleMenuOpen = false }) {
                    Column(Modifier.background(palette.surface).padding(6.dp).testTag("agent-plan-role-menu")) {
                        agents.forEach { agent ->
                            PlanText(agent.name, palette,
                                Modifier.fillMaxWidth().clickable {
                                    onUpdate(step.copy(agentId = agent.id))
                                    roleMenuOpen = false
                                }.padding(8.dp).testTag("agent-plan-role-${agent.id.value}"), size = 12)
                        }
                    }
                }
            }
            AgentUiButton(if (step.requiresApproval) "Ask approval" else "Auto run", {
                onUpdate(step.copy(requiresApproval = !step.requiresApproval))
            }, Modifier.testTag("agent-plan-approval-$index"), palette = palette)
            if (index > 0) AgentUiButton("↑", { onMove(-1) },
                Modifier.semantics { contentDescription = "Move step up" }, palette = palette)
            if (index < count - 1) AgentUiButton("↓", { onMove(1) },
                Modifier.semantics { contentDescription = "Move step down" }, palette = palette)
            AgentUiButton("×", onDelete,
                Modifier.semantics { contentDescription = "Delete step" }, palette = palette)
        }
    }
}

@Composable
private fun PlanText(value: String, palette: AgentUiPalette, modifier: Modifier = Modifier,
    size: Int = 13, weight: FontWeight = FontWeight.Normal) =
    BasicText(value, modifier, style = TextStyle(color = palette.text, fontSize = size.sp, fontWeight = weight))
