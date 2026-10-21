package dev.ide.agent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiAgentAttachment
import dev.ide.ui.backend.UiAgentAttachmentKind
import dev.ide.ui.backend.UiAgentFileChange
import dev.ide.ui.backend.UiAgentTodo
import dev.ide.ui.backend.UiAgentTodoStatus
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.editor.preview.decodeImageBytes
import dev.ide.ui.editor.preview.encodeImageJpeg
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.screens.AgentStatusDot
import dev.ide.ui.theme.AgentUi
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.delay
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_file_deleted
import dev.ide.agent.ui.generated.resources.chat_file_new
import dev.ide.agent.ui.generated.resources.chat_no_changes
import dev.ide.agent.ui.generated.resources.chat_plan
import dev.ide.agent.ui.generated.resources.chat_remove
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------------
// Diffs
// ---------------------------------------------------------------------------------------------------

/**
 * The files a tool call changed, each as a collapsible row (path + added/removed counts) that expands into its
 * diff. Collapsed by default in the transcript, where a turn can touch many files; [expandedByDefault] is for the
 * permission prompt, where the diff is the whole point.
 */
@Composable
internal fun FileChangesList(
    changes: List<UiAgentFileChange>,
    expandedByDefault: Boolean = false,
    maxDiffHeight: Int = 320,
    onUndo: (() -> Unit)? = null,
) {
    var reviewAll by rememberSaveable { mutableStateOf(false) }
    val chrome = AgentUi.chrome
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        changes.forEach { change -> FileChangeRow(change, expandedByDefault, maxDiffHeight, forceExpand = reviewAll) }
        if (onUndo != null) {
            // Diff summary footer row: Review opens every hunk; Undo reverts the turn.
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(AgentUi.radius.tile))
                    .background(chrome.surface)
                    .border(1.dp, chrome.hairlineStrong, RoundedCornerShape(AgentUi.radius.tile))
                    .height(42.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().clickable { reviewAll = true }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("Review", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = chrome.text)
                }
                Box(Modifier.width(1.dp).height(42.dp).background(chrome.hairlineStrong))
                Box(
                    Modifier.weight(1f).fillMaxWidth().clickable(onClick = onUndo),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Undo", style = MaterialTheme.typography.labelLarge, color = chrome.danger)
                }
            }
        }
    }
}

@Composable
private fun FileChangeRow(change: UiAgentFileChange, expandedByDefault: Boolean, maxDiffHeight: Int, forceExpand: Boolean = false) {
    val result = remember(change) { LineDiff.diff(change.before, change.after) }
    var expanded by rememberSaveable(change.path) { mutableStateOf(expandedByDefault) }
    val open = forceExpand || expanded
    val chrome = AgentUi.chrome
    // Diff summary card: surface fill, hairline, +lime / -coral counts.
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(AgentUi.radius.tile))
            .background(chrome.surface)
            .border(1.dp, chrome.hairline, RoundedCornerShape(AgentUi.radius.tile)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                CaIcons.chevronDown, null,
                Modifier.size(12.dp).rotate(if (open) 0f else -90f),
                tint = chrome.textFaint,
            )
            Text(
                change.path.substringAfterLast('/'),
                style = MaterialTheme.typography.labelMedium,
                color = chrome.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val note = when {
                change.before == null -> stringResource(Res.string.chat_file_new)
                change.after == null -> stringResource(Res.string.chat_file_deleted)
                else -> null
            }
            if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = chrome.textFaint)
            if (result.added > 0) Text("+${result.added}", style = MaterialTheme.typography.labelSmall, color = chrome.accent)
            if (result.removed > 0) Text("-${result.removed}", style = MaterialTheme.typography.labelSmall, color = chrome.danger)
        }
        if (open) DiffView(result, maxDiffHeight)
    }
}

/** The hunks of a [LineDiff.Result] in a monospace block, scrollable both ways inside a bounded height. */
@Composable
internal fun DiffView(result: LineDiff.Result, maxHeight: Int = 320) {
    val chrome = AgentUi.chrome
    // Opaque tints, blended against the panel's own surface, so the added/removed rows hold their contrast in
    // both themes (the panel never composites with alpha).
    val addedBg = lerp(chrome.bg, chrome.accent, 0.16f)
    val removedBg = lerp(chrome.bg, chrome.danger, 0.16f)
    val horizontal = rememberScrollState()
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight.dp).background(chrome.bg)
            .verticalScroll(rememberScrollState()).horizontalScroll(horizontal)
            .padding(vertical = 4.dp),
    ) {
        if (result.hunks.isEmpty()) {
            Text(stringResource(Res.string.chat_no_changes), style = Ca.type.codeSmall, color = chrome.textFaint, modifier = Modifier.padding(horizontal = 10.dp))
        }
        result.hunks.forEachIndexed { index, hunk ->
            if (index > 0) {
                Text("⋯", style = Ca.type.codeSmall, color = chrome.textFaint, modifier = Modifier.padding(horizontal = 10.dp))
            }
            hunk.lines.forEach { line ->
                val (marker, bg, fg) = when (line.kind) {
                    LineDiff.Kind.ADDED -> Triple("+", addedBg, chrome.text)
                    LineDiff.Kind.REMOVED -> Triple("-", removedBg, chrome.text)
                    LineDiff.Kind.CONTEXT -> Triple(" ", Color.Unspecified, chrome.textMuted)
                }
                val number = (line.newLine ?: line.oldLine)?.toString().orEmpty().padStart(4)
                Text(
                    "$number $marker ${line.text}",
                    style = Ca.type.codeSmall,
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.then(if (bg != Color.Unspecified) Modifier.background(bg) else Modifier)
                        .padding(horizontal = 10.dp, vertical = 1.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Attachments
// ---------------------------------------------------------------------------------------------------

/** The attachments on a message or queued in the composer, as chips; [onRemove] adds a remove control. */
@Composable
internal fun AttachmentChips(
    attachments: List<UiAgentAttachment>,
    onRemove: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEachIndexed { index, a -> AttachmentChip(a, onRemove?.let { { it(index) } }) }
    }
}

@Composable
private fun AttachmentChip(attachment: UiAgentAttachment, onRemove: (() -> Unit)?) {
    val chrome = AgentUi.chrome
    Row(
        Modifier.clip(RoundedCornerShape(AgentUi.radius.tile)).background(chrome.surfaceAlt)
            .padding(start = 6.dp, end = if (onRemove != null) 0.dp else 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val thumb = remember(attachment.base64) { attachment.base64?.let(::decodeBase64Image) }
        if (thumb != null) {
            Image(
                thumb, null,
                Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            val icon = when (attachment.kind) {
                UiAgentAttachmentKind.IMAGE -> CaIcons.image
                UiAgentAttachmentKind.FILE -> CaIcons.file
                UiAgentAttachmentKind.SELECTION -> CaIcons.code
            }
            Icon(icon, null, Modifier.size(14.dp), tint = chrome.textFaint)
        }
        Text(
            attachment.name,
            style = MaterialTheme.typography.labelMedium,
            color = chrome.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        if (onRemove != null) IconButtonCa(CaIcons.close, stringResource(Res.string.chat_remove), onRemove, iconSize = 12, boxSize = 24)
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeBase64Image(data: String): ImageBitmap? =
    runCatching { decodeImageBytes(Base64.Default.decode(data)) }.getOrNull()

/** The long edge an attached image is scaled down to: what the providers resize to anyway, so more is waste. */
private const val MAX_IMAGE_EDGE = 1568

/** Under this, an image already small enough is sent as picked rather than re-encoded. */
private const val MAX_PASSTHROUGH_BYTES = 1_000_000

/**
 * Turns picked image bytes into an attachment the model can read, or null when they are not a decodable image.
 * A large image is scaled so its long edge is [MAX_IMAGE_EDGE] and re-encoded as JPEG: a phone photo straight
 * from the camera is several megabytes and costs the same tokens as the scaled copy once the provider resizes
 * it, so sending it whole only spends upload time and quota.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun prepareImageAttachment(name: String, bytes: ByteArray): UiAgentAttachment? {
    val image = decodeImageBytes(bytes) ?: return null
    val edge = max(image.width, image.height)
    val type = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> null
    }
    val (data, mediaType) = if (edge <= MAX_IMAGE_EDGE && bytes.size <= MAX_PASSTHROUGH_BYTES && type != null) {
        bytes to type
    } else {
        val scale = minOf(1f, MAX_IMAGE_EDGE.toFloat() / edge)
        val w = (image.width * scale).roundToInt().coerceAtLeast(1)
        val h = (image.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = ImageBitmap(w, h)
        Canvas(scaled).drawImageRect(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(w, h),
            paint = Paint().apply { filterQuality = FilterQuality.High },
        )
        (encodeImageJpeg(scaled, 85) ?: return null) to "image/jpeg"
    }
    return UiAgentAttachment(
        UiAgentAttachmentKind.IMAGE,
        name = name,
        mediaType = mediaType,
        base64 = Base64.Default.encode(data),
    )
}

// ---------------------------------------------------------------------------------------------------
// Plan and waits
// ---------------------------------------------------------------------------------------------------

/** The agent's current plan, pinned above the composer while there is unfinished work in it. */
@Composable
internal fun TodoCard(todos: List<UiAgentTodo>) {
    if (todos.isEmpty()) return
    val chrome = AgentUi.chrome
    var expanded by rememberSaveable { mutableStateOf(true) }
    val done = todos.count { it.status == UiAgentTodoStatus.DONE }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp)
            .clip(RoundedCornerShape(AgentUi.radius.card))
            .background(chrome.surface)
            .border(1.dp, chrome.hairline, RoundedCornerShape(AgentUi.radius.card)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(CaIcons.chevronDown, null, Modifier.size(12.dp).rotate(if (expanded) 0f else -90f), tint = chrome.textFaint)
            Text(stringResource(Res.string.chat_plan), style = MaterialTheme.typography.labelLarge, color = chrome.text, modifier = Modifier.weight(1f))
            Text("$done/${todos.size}", style = MaterialTheme.typography.labelMedium, color = chrome.textFaint)
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                todos.forEach { todo ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val (dotActive, dotColor) = when (todo.status) {
                            UiAgentTodoStatus.DONE -> true to chrome.accent
                            UiAgentTodoStatus.IN_PROGRESS -> true to chrome.warn
                            UiAgentTodoStatus.PENDING -> false to chrome.textFaint
                        }
                        AgentStatusDot(
                            active = dotActive,
                            size = 7.dp,
                            color = dotColor,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                        Text(
                            todo.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (todo.status == UiAgentTodoStatus.DONE) chrome.textFaint else chrome.text,
                            fontWeight = if (todo.status == UiAgentTodoStatus.IN_PROGRESS) FontWeight.SemiBold else null,
                        )
                    }
                }
            }
        }
    }
}

/** A paused run: why, and a live countdown to when it resumes. Stop cancels it like any other turn. */
@Composable
internal fun WaitRow(untilMs: Long, reason: String) {
    var now by remember { mutableLongStateOf(currentTimeMs()) }
    LaunchedEffect(untilMs) {
        while (now < untilMs) {
            delay(250)
            now = currentTimeMs()
        }
    }
    val seconds = ((untilMs - now + 999) / 1000).coerceAtLeast(0)
    val chrome = AgentUi.chrome
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(AgentUi.radius.card))
            .background(chrome.surfaceAlt)
            .border(1.dp, chrome.hairline, RoundedCornerShape(AgentUi.radius.card))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(CaIcons.clock, null, Modifier.size(16.dp), tint = chrome.warn)
        Column(Modifier.weight(1f)) {
            Text(reason, style = MaterialTheme.typography.bodySmall, color = chrome.text)
        }
        Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "${seconds}s",
                style = MaterialTheme.typography.labelLarge,
                color = chrome.warn,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun currentTimeMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
