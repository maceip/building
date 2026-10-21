package dev.ide.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_close
import dev.ide.agent.ui.generated.resources.chat_delete
import dev.ide.agent.ui.generated.resources.chat_history
import dev.ide.agent.ui.generated.resources.chat_history_empty
import dev.ide.agent.ui.generated.resources.chat_history_messages
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.components.CenteredDialog
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.screens.AgentStatusDot
import dev.ide.ui.theme.AgentUi
import org.jetbrains.compose.resources.stringResource

/** The saved conversations for this project, newest first: tap one to resume it, or delete it. */
@Composable
internal fun AgentHistorySheet(backend: IdeBackend, onClose: () -> Unit) {
    var sessions by remember { mutableStateOf(backend.agent.sessions()) }
    val current = backend.agent.chatState.value.sessionId
    val chrome = AgentUi.chrome
    CenteredDialog(visible = true, onDismiss = onClose) {
        Column(
            Modifier.widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(AgentUi.radius.sheet))
                .background(chrome.surface)
                .border(1.dp, chrome.hairlineStrong, RoundedCornerShape(AgentUi.radius.sheet))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CaIcons.clock, null, Modifier.size(18.dp), tint = chrome.accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(Res.string.chat_history),
                    color = chrome.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButtonCa(CaIcons.close, stringResource(Res.string.chat_close), onClose, iconSize = 16, boxSize = 30)
            }
            if (sessions.isEmpty()) {
                Text(
                    stringResource(Res.string.chat_history_empty),
                    color = chrome.textMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sessions.forEach { session ->
                    val selected = session.id == current
                    val container = if (selected) chrome.surfaceAlt else chrome.bg
                    val onContainer = if (selected) chrome.text else chrome.textMuted
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(AgentUi.radius.card)).background(container)
                            .then(
                                if (selected) Modifier.border(1.dp, chrome.accentSoftLine, RoundedCornerShape(AgentUi.radius.card))
                                else Modifier.border(1.dp, chrome.hairline, RoundedCornerShape(AgentUi.radius.card))
                            )
                            .clickable {
                                backend.agent.resumeSession(session.id)
                                onClose()
                            }
                            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AgentStatusDot(
                            active = selected,
                            size = 7.dp,
                            color = if (selected) chrome.accent else chrome.textFaint,
                            modifier = Modifier.padding(end = 0.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                session.title,
                                color = onContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(Res.string.chat_history_messages, session.messageCount),
                                color = if (selected) chrome.textMuted else chrome.textFaint,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        IconButtonCa(
                            CaIcons.close,
                            stringResource(Res.string.chat_delete),
                            {
                                backend.agent.deleteSession(session.id)
                                sessions = backend.agent.sessions()
                            },
                            iconSize = 14,
                            boxSize = 30,
                            tint = onContainer,
                        )
                    }
                }
            }
        }
    }
}
