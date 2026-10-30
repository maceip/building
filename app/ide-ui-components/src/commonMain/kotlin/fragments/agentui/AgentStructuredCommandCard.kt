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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup

/** A host-defined slash action edited as a durable conversation part before it is sent. */
@Immutable
data class AgentStructuredCommandField(
    val id: String,
    val label: String,
    val value: String = "",
    val hint: String = "",
    val required: Boolean = true,
)

@Immutable
data class AgentStructuredCommandDraft(
    val command: String,
    val title: String,
    val agentId: AgentId,
    val fields: List<AgentStructuredCommandField>,
)

/**
 * A reusable editor for structured slash actions. The host owns persistence and the actual
 * effect of [onSend]; an accepted send is never presented as a completed agent action.
 */
@Composable
fun AgentStructuredCommandCard(
    draft: AgentStructuredCommandDraft,
    agents: List<AgentParticipant>,
    onChange: (AgentStructuredCommandDraft) -> Unit,
    onSend: (AgentStructuredCommandDraft) -> Unit,
    submitted: Boolean = false,
    sending: Boolean = false,
    modifier: Modifier = Modifier,
    palette: AgentUiPalette = AgentUiTheme.palette,
) {
    var agentMenuOpen by remember(draft.command) { mutableStateOf(false) }
    val targetName = agents.firstOrNull { it.id == draft.agentId }?.name ?: draft.agentId.value
    val canSend = draft.fields.isNotEmpty() && draft.fields.all { !it.required || it.value.isNotBlank() }
    Column(
        modifier.fillMaxWidth().background(palette.surface)
            .testTag("agent-structured-command-${draft.command}"),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StructuredText(draft.title, palette, Modifier.weight(1f), 15, FontWeight.SemiBold)
            StructuredText("/${draft.command}", palette.copy(text = palette.muted), size = 11)
        }
        draft.fields.forEachIndexed { index, field ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StructuredText(field.label, palette.copy(text = palette.muted), size = 11)
                Box(Modifier.fillMaxWidth()) {
                    if (field.value.isEmpty()) StructuredText(field.hint, palette.copy(text = palette.muted),
                        Modifier.padding(vertical = 2.dp), size = 13)
                    BasicTextField(field.value, { value ->
                        onChange(draft.copy(fields = draft.fields.toMutableList().also {
                            it[index] = field.copy(value = value)
                        }))
                    }, Modifier.fillMaxWidth().testTag("agent-structured-field-${field.id}"),
                        textStyle = TextStyle(color = palette.text, fontSize = 13.sp),
                        cursorBrush = SolidColor(palette.accent), singleLine = false)
                }
            }
        }
        Box {
            AgentUiButton("To: $targetName ▾", { agentMenuOpen = true },
                Modifier.testTag("agent-structured-target"), palette = palette)
            if (agentMenuOpen) Popup(onDismissRequest = { agentMenuOpen = false }) {
                Column(Modifier.background(palette.surface).padding(6.dp)
                    .testTag("agent-structured-target-menu")) {
                    agents.forEach { agent ->
                        StructuredText(agent.name, palette,
                            Modifier.fillMaxWidth().clickable {
                                onChange(draft.copy(agentId = agent.id))
                                agentMenuOpen = false
                            }.padding(8.dp).testTag("agent-structured-target-${agent.id.value}"), size = 12)
                    }
                }
            }
        }
        when {
            sending -> StructuredText("Sending…", palette.copy(text = palette.muted), size = 12)
            submitted -> StructuredText("Sent to $targetName", palette.copy(text = palette.success), size = 12)
            canSend -> AgentUiButton("Send request", { onSend(draft) },
                Modifier.testTag("agent-structured-send"), primary = true, palette = palette)
        }
    }
}

@Composable
private fun StructuredText(value: String, palette: AgentUiPalette, modifier: Modifier = Modifier,
    size: Int = 13, weight: FontWeight = FontWeight.Normal) =
    BasicText(value, modifier, style = TextStyle(color = palette.text, fontSize = size.sp, fontWeight = weight))
