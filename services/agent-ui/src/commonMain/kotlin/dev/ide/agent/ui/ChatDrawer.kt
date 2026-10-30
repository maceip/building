package dev.ide.agent.ui


import dev.ide.ui.components.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.ide.ui.itemsKeyed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAgentCommand
import dev.ide.ui.backend.UiAgentMention
import dev.ide.ui.backend.UiAgentAttachment
import dev.ide.ui.backend.UiAgentAttachmentKind
import dev.ide.ui.backend.UiAgentTodoStatus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.ide.ui.backend.UiAgentConfig
import dev.ide.ui.backend.UiAgentMessage
import dev.ide.ui.backend.UiAgentModel
import dev.ide.ui.backend.UiAgentPermissionMode
import dev.ide.ui.backend.UiAgentRole
import dev.ide.ui.backend.UiAgentSegment
import dev.ide.ui.backend.UiAgentToolCall
import dev.ide.ui.backend.UiAgentToolStatus
import dev.ide.ui.backend.UiAgentUsage
import dev.ide.agent.ui.generated.resources.Res
import dev.ide.agent.ui.generated.resources.chat_add_key
import dev.ide.agent.ui.generated.resources.chat_attach
import dev.ide.agent.ui.generated.resources.chat_attach_file
import dev.ide.agent.ui.generated.resources.chat_attach_image
import dev.ide.agent.ui.generated.resources.chat_attach_photo
import dev.ide.agent.ui.generated.resources.chat_paste_image
import dev.ide.agent.ui.generated.resources.chat_history
import dev.ide.agent.ui.generated.resources.chat_files_changed
import dev.ide.agent.ui.generated.resources.chat_jump_latest
import dev.ide.agent.ui.generated.resources.chat_more
import dev.ide.agent.ui.generated.resources.chat_thought
import dev.ide.agent.ui.generated.resources.chat_tool_count
import dev.ide.agent.ui.generated.resources.chat_image_failed
import dev.ide.agent.ui.generated.resources.chat_undo
import dev.ide.agent.ui.generated.resources.chat_undone
import dev.ide.agent.ui.generated.resources.chat_use_model
import dev.ide.agent.ui.generated.resources.chat_copied
import dev.ide.agent.ui.generated.resources.chat_copy
import dev.ide.agent.ui.generated.resources.chat_usage_cached
import dev.ide.agent.ui.generated.resources.chat_usage_input
import dev.ide.agent.ui.generated.resources.chat_usage_output
import dev.ide.agent.ui.generated.resources.chat_manage_keys
import dev.ide.agent.ui.generated.resources.chat_empty_body
import dev.ide.agent.ui.generated.resources.chat_empty_title
import dev.ide.agent.ui.generated.resources.chat_mode_ask
import dev.ide.agent.ui.generated.resources.chat_mode_auto
import dev.ide.agent.ui.generated.resources.chat_mode_plan
import dev.ide.agent.ui.generated.resources.chat_need_key
import dev.ide.agent.ui.generated.resources.chat_new
import dev.ide.agent.ui.generated.resources.chat_placeholder
import dev.ide.agent.ui.generated.resources.chat_retry
import dev.ide.agent.ui.generated.resources.chat_send
import dev.ide.agent.ui.generated.resources.chat_stop
import dev.ide.agent.ui.generated.resources.chat_thinking
import dev.ide.agent.ui.generated.resources.chat_title
import dev.ide.ui.components.CodeSample
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.markdown.Markdown
import dev.ide.ui.markdown.defaultHeadingStyle
import dev.ide.ui.screens.AgentStatusDot
import dev.ide.ui.screens.AgentChip
import dev.ide.ui.screens.AgentIconCircle
import dev.ide.ui.screens.AgentPrimaryButton
import androidx.compose.foundation.layout.BoxWithConstraints
import dev.ide.ui.theme.AgentUi
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.CaMotion
import dev.ide.ui.theme.Ide
import fragments.agentui.AgentCapabilities
import fragments.agentui.AgentChatPanel
import fragments.agentui.AgentId
import fragments.agentui.AgentMarkdownRenderer
import fragments.agentui.AgentParticipant
import fragments.agentui.AgentRun
import fragments.agentui.ChatActionPurpose
import fragments.agentui.ChatQuickAction
import fragments.agentui.ChatSlashAction
import fragments.agentui.ChatTimelinePhase
import fragments.agentui.ChatToolTimelineCall
import fragments.agentui.CommandResult
import fragments.agentui.ConversationAttachment
import fragments.agentui.ConversationCommand
import fragments.agentui.ConversationEvent
import fragments.agentui.ConversationHandle
import fragments.agentui.ConversationId
import fragments.agentui.ConversationPart
import fragments.agentui.ConversationSnapshot
import fragments.agentui.ConversationTurn
import fragments.agentui.File
import fragments.agentui.MessagePartRegistry
import fragments.agentui.RunId
import fragments.agentui.RunState
import fragments.agentui.TurnId
import fragments.agentui.rememberIdeAgentUiPalette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The AI agent chat drawer, powered by `io.fragments:agent-ui-compose` ([AgentChatPanel]).
 *
 * Renders user/assistant turns, streaming reasoning, tool timelines, interactive file diffs, a rich
 * composer, and two persistent action rows in every full-screen and half-screen chat window:
 * 1. **Where you want to go** ([ChatActionPurpose.Navigation]): e.g., `"Open <Most Recent Project>"`,
 *    `"All projects"`, `"Build & Run output"`, `"Agent providers"`.
 * 2. **What you want done** ([ChatActionPurpose.Task]): e.g., `"Rebuild project"`,
 *    `"Submit to the Google Play Store for inclusion"`, `"Fix build & diagnostics"`.
 */
@Composable
fun ChatDrawer(
    backend: IdeBackend,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    fileActions: FileActions = FileActions.None,
    activeFilePath: String? = null,
    openScreen: (String) -> Unit = {},
) {
    val chat by backend.agent.chatState.collectAsState()
    val models by backend.agent.models.collectAsState()
    var cfg by remember { mutableStateOf(backend.agent.config()) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var showProviders by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val imageFailed = stringResource(Res.string.chat_image_failed)
    val placeholderText = stringResource(if (cfg.configured) Res.string.chat_placeholder else Res.string.chat_need_key)
    val palette = rememberIdeAgentUiPalette()

    // Fetch the provider's live model list when the drawer opens or the provider changes.
    LaunchedEffect(cfg.selectedProvider) { backend.agent.refreshModels() }

    fun submitText(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        input = TextFieldValue("")
        if (text.startsWith("/")) {
            val name = text.removePrefix("/").substringBefore(' ').lowercase()
            val args = text.substringAfter(' ', "").trim()
            when (name) {
                "resume", "history" -> { showHistory = true; return }
                "model" -> { modelMenuOpen = true; return }
            }
            if (backend.agent.runCommand(name, args)) {
                cfg = backend.agent.config()
                return
            }
        }
        backend.agent.send(text)
    }

    /** Reads, scales and queues the image at [path]; every image source (picker, camera, clipboard) ends here. */
    fun attachImageAt(path: String?) {
        if (path == null) return
        scope.launch {
            val bytes = backend.projects.imageBytes(path)
            val attachment = bytes?.let { prepareImageAttachment(path.substringAfterLast('/'), it) }
            if (attachment != null) backend.agent.attach(attachment) else notice = imageFailed
        }
    }

    val imageSources = ImageSources(
        pick = if (fileActions.canPickFile) ({ fileActions.pickFile(IMAGE_EXTENSIONS, ::attachImageAt) }) else null,
        camera = if (fileActions.canTakePhoto) ({ fileActions.takePhoto(::attachImageAt) }) else null,
        paste = if (fileActions.canPasteImage) ({ fileActions.pasteImage(::attachImageAt) }) else null,
        hasClipboardImage = fileActions::hasClipboardImage,
    )

    val headerModels = models.ifEmpty { cfg.providers.firstOrNull { it.id == cfg.selectedProvider }?.models ?: emptyList() }
    val activeProvider = cfg.providers.firstOrNull { it.id == cfg.selectedProvider }
    val currentModelId = cfg.model.ifBlank { activeProvider?.defaultModel ?: "" }
    val activeModelName = headerModels.firstOrNull { it.id == currentModelId }?.displayName
        ?: currentModelId.ifBlank { activeProvider?.displayName ?: "AI Agent" }

    val handle = remember(backend) {
        IdeAgentConversationHandle(
            conversationId = ConversationId("ide-agent-chat"),
            onDraftChange = { next -> input = TextFieldValue(next, TextRange(next.length)) },
            onSend = { text -> submitText(text) },
            onStop = { backend.agent.stop() },
            onRemoveAttachment = { id -> id.toIntOrNull()?.let { backend.agent.detach(it) } },
            onAddFiles = { files ->
                files.forEach { file ->
                    backend.agent.attach(UiAgentAttachment(UiAgentAttachmentKind.FILE, file.name, path = file.path))
                }
            },
        )
    }
    handle.sync(
        chat = chat,
        configured = cfg.configured,
        canAttach = imageSources.any || activeFilePath != null,
        agentName = activeModelName,
        draft = input.text,
    )

    val rendererRegistry = remember(backend, chat.busy) {
        MessagePartRegistry().apply {
            register("ide.error") { part ->
                val text = part.fields["text"].orEmpty()
                val canRetry = part.fields["canRetry"] == "true"
                val suggestedModel = part.fields["suggestedModel"]
                ErrorMessage(
                    text = text,
                    onRetry = if (canRetry) ({ backend.agent.retry() }) else null,
                    suggestedModel = suggestedModel,
                    onUseModel = { model ->
                        backend.agent.switchModelAndRetry(model)
                        cfg = backend.agent.config()
                    },
                )
            }
            register("ide.file.changes") { part ->
                val msgId = part.fields["messageId"]?.toLongOrNull()
                val stepIdx = part.fields["stepIdx"]?.toIntOrNull()
                val msg = chat.messages.firstOrNull { it.id == msgId }
                val step = msg?.let { stepsOf(it).getOrNull(stepIdx ?: -1) } as? Step.Tools
                val changes = step?.calls?.flatMap { it.changes }.orEmpty()
                if (changes.isNotEmpty() && msg != null) {
                    DisableSelection {
                        FileChangesList(
                            changes = changes,
                            onUndo = if (!chat.busy && msg.canUndo) ({ backend.agent.undoTurn(msg.id) }) else null,
                        )
                    }
                }
            }
            register("ide.user.meta") { part ->
                val msgId = part.fields["messageId"]?.toLongOrNull()
                val canUndo = part.fields["canUndo"] == "true"
                val undone = part.fields["undone"] == "true"
                val text = part.fields["text"].orEmpty()
                DisableSelection {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (undone) {
                            Text(
                                stringResource(Res.string.chat_undone),
                                style = MaterialTheme.typography.labelSmall,
                                color = AgentUi.chrome.textFaint,
                            )
                        }
                        if (canUndo && msgId != null) {
                            TextButton(
                                onClick = { backend.agent.undoTurn(msgId) },
                                colors = ButtonDefaults.textButtonColors(contentColor = AgentUi.chrome.danger),
                            ) {
                                Icon(CaIcons.undo, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(Res.string.chat_undo), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (text.isNotBlank()) CopyButton(text)
                    }
                }
            }
            register("ide.assistant.footer") { part ->
                val msgId = part.fields["messageId"]?.toLongOrNull()
                val msg = chat.messages.firstOrNull { it.id == msgId }
                if (msg != null && !msg.streaming && (msg.text.isNotBlank() || msg.usage != null)) {
                    DisableSelection {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (msg.text.isNotBlank()) CopyButton(msg.text)
                            msg.usage?.let { UsageFooter(it) }
                        }
                    }
                }
            }
            register("ide.wait") { part ->
                val untilMs = part.fields["untilMs"]?.toLongOrNull()
                val reason = part.fields["reason"].orEmpty()
                if (untilMs != null) WaitRow(untilMs, reason)
            }
            register("ide.todos") {
                if (chat.todos.isNotEmpty()) TodoCard(chat.todos)
            }
        }
    }

    val allProjects = remember(backend.project.rootPath) {
        backend.projects.projects().sortedByDescending { it.lastOpened }
    }
    val activeProjectName = backend.project.name.trim()
    val mostRecentProject = allProjects.firstOrNull { it.rootPath != backend.project.rootPath }
        ?: allProjects.firstOrNull()

    val navigationActions = remember(allProjects, activeProjectName, backend) {
        buildList {
            if (mostRecentProject != null) {
                add(
                    ChatQuickAction(
                        id = "open-recent-project",
                        label = "Open ${mostRecentProject.name}",
                        purpose = ChatActionPurpose.Navigation,
                        primary = true,
                        onClick = {
                            scope.launch {
                                backend.projects.openProject(mostRecentProject.rootPath)
                            }
                            openScreen("ide.editor")
                        },
                    ),
                )
            } else if (activeProjectName.isNotEmpty()) {
                add(
                    ChatQuickAction(
                        id = "open-active-project",
                        label = "Open $activeProjectName",
                        purpose = ChatActionPurpose.Navigation,
                        primary = true,
                        onClick = { openScreen("ide.editor") },
                    ),
                )
            }
            add(
                ChatQuickAction(
                    id = "go-all-projects",
                    label = "All projects",
                    purpose = ChatActionPurpose.Navigation,
                    primary = mostRecentProject == null && activeProjectName.isEmpty(),
                    onClick = { openScreen("home.projects") },
                ),
            )
            add(
                ChatQuickAction(
                    id = "go-run-output",
                    label = "Build & Run output",
                    purpose = ChatActionPurpose.Navigation,
                    onClick = { openScreen("ide.run") },
                ),
            )
            add(
                ChatQuickAction(
                    id = "go-agent-providers",
                    label = "Agent providers",
                    purpose = ChatActionPurpose.Navigation,
                    onClick = { showProviders = true },
                ),
            )
        }
    }

    val taskActions = remember(backend, activeFilePath) {
        buildList {
            add(
                ChatQuickAction(
                    id = "rebuild-project",
                    label = "Rebuild project",
                    purpose = ChatActionPurpose.Task,
                    primary = true,
                    onClick = {
                        if (backend.build.supported()) {
                            backend.build.runBuild()
                        } else {
                            backend.agent.send("Rebuild the project and verify all targets compile cleanly.")
                        }
                    },
                ),
            )
            add(
                ChatQuickAction(
                    id = "submit-google-play-store",
                    label = "Submit to the Google Play Store for inclusion",
                    purpose = ChatActionPurpose.Task,
                    onClick = { openScreen("store.submit") },
                ),
            )
            add(
                ChatQuickAction(
                    id = "fix-build-diagnostics",
                    label = "Fix build & diagnostics",
                    purpose = ChatActionPurpose.Task,
                    onClick = {
                        backend.agent.send("Inspect current build errors and diagnostics in this project and fix them.")
                    },
                ),
            )
        }
    }

    val slashActions = remember(cfg) {
        backend.agent.commands().map { cmd ->
            ChatSlashAction(id = cmd.name, label = "/${cmd.name}", description = cmd.description)
        } + listOf(
            ChatSlashAction(id = "history", label = "/history", description = "Open saved conversations"),
            ChatSlashAction(id = "model", label = "/model", description = "Switch active AI model"),
        )
    }

    BoxWithConstraints(modifier.fillMaxSize().background(AgentUi.chrome.bg)) {
        val isCompactWindow = maxHeight < 500.dp
        Column(Modifier.fillMaxSize()) {
            val onPickModel: (String, String) -> Unit = { providerId, modelId ->
                backend.agent.selectProvider(providerId)
                backend.agent.setModel(modelId)
                cfg = backend.agent.config()
            }
            val onPickEffort: (String) -> Unit = { backend.agent.setEffort(it); cfg = backend.agent.config() }
            val onCycleMode = {
                backend.agent.setPermissionMode(nextMode(cfg.mode))
                cfg = backend.agent.config()
            }
            val hosted = ToolWindowHeaderActions(
                leading = {
                    ModelPicker(
                        cfg = cfg,
                        models = headerModels,
                        open = modelMenuOpen,
                        onOpen = { modelMenuOpen = it },
                        onPickModel = onPickModel,
                        onManageProviders = { showProviders = true },
                    )
                },
                actions = {
                    EffortChip(cfg.effort, onPickEffort)
                    ModeChip(cfg.mode, onCycleMode)
                    ChatMenu(
                        onNew = { backend.agent.newSession() },
                        onHistory = { showHistory = true },
                        onManage = { showProviders = true },
                    )
                },
            )
            if (!hosted) {
                ChatChrome {
                    ChatHeader(
                        cfg = cfg,
                        models = headerModels,
                        modelMenuOpen = modelMenuOpen,
                        onModelMenu = { modelMenuOpen = it },
                        onPickModel = onPickModel,
                        onPickEffort = onPickEffort,
                        onManage = { showProviders = true },
                        onHistory = { showHistory = true },
                        onCycleMode = onCycleMode,
                        onNew = { backend.agent.newSession() },
                        onClose = onClose,
                    )
                }
            }
            notice?.let { message ->
                LaunchedEffect(message) { delay(3000); notice = null }
                Text(
                    message,
                    color = AgentUi.chrome.danger,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
            Suggestions(
                backend = backend,
                input = input,
                onPick = { picked ->
                    input = picked
                    handle.setDraft(IdeAgentConversationHandle.AGENT_ID, picked.text)
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AgentMarkdownRenderer(renderer = { md -> AssistantMarkdown(md) }) {
                    AgentChatPanel(
                        handle = handle,
                        modifier = Modifier.fillMaxSize(),
                        palette = palette,
                        rendererRegistry = rendererRegistry,
                        slashActions = slashActions,
                        onSlashAction = { action, _ ->
                            when (action.id) {
                                "resume", "history" -> showHistory = true
                                "model" -> modelMenuOpen = true
                                else -> {
                                    if (backend.agent.runCommand(action.id, "")) {
                                        cfg = backend.agent.config()
                                    } else {
                                        val next = "${action.label} "
                                        input = TextFieldValue(next, TextRange(next.length))
                                    }
                                }
                            }
                        },
                        navigationActions = navigationActions,
                        taskActions = taskActions,
                        navigationActionsTitle = "Where you want to go",
                        taskActionsTitle = "What you want done",
                        compactActions = isCompactWindow,
                        showHeader = false,
                        placeholder = placeholderText,
                        onRequestAttach = if (imageSources.any || activeFilePath != null) ({ attachMenuOpen = true }) else null,
                        emptyState = {
                            EmptyState(configured = cfg.configured, onManage = { showProviders = true })
                        },
                    )
                }
                if (attachMenuOpen) {
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 72.dp)) {
                        CaDropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                            imageSources.pick?.let { pick ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.chat_attach_image), style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = { Icon(CaIcons.image, null, Modifier.size(16.dp)) },
                                    onClick = { attachMenuOpen = false; pick() },
                                )
                            }
                            imageSources.camera?.let { camera ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.chat_attach_photo), style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = { Icon(CaIcons.eye, null, Modifier.size(16.dp)) },
                                    onClick = { attachMenuOpen = false; camera() },
                                )
                            }
                            val paste = imageSources.paste
                            if (paste != null && remember(attachMenuOpen) { imageSources.hasClipboardImage() }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.chat_paste_image), style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = { Icon(CaIcons.copy, null, Modifier.size(16.dp)) },
                                    onClick = { attachMenuOpen = false; paste() },
                                )
                            }
                            if (activeFilePath != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.chat_attach_file), style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = { Icon(CaIcons.file, null, Modifier.size(16.dp)) },
                                    onClick = {
                                        attachMenuOpen = false
                                        backend.agent.attach(
                                            UiAgentAttachment(
                                                UiAgentAttachmentKind.FILE,
                                                activeFilePath.substringAfterLast('/'),
                                                path = activeFilePath,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (showProviders) {
            AgentProvidersSheet(backend) {
                showProviders = false
                cfg = backend.agent.config()
            }
        }
        if (showHistory) {
            AgentHistorySheet(backend) {
                showHistory = false
                cfg = backend.agent.config()
            }
        }
    }
}

/**
 * Bridges [IdeBackend.agent] (`UiAgentChatState`) into `io.fragments:agent-ui-compose`'s [ConversationHandle].
 */
private class IdeAgentConversationHandle(
    override val conversationId: ConversationId,
    private val onDraftChange: (String) -> Unit,
    private val onSend: (String) -> Unit,
    private val onStop: () -> Unit,
    private val onRemoveAttachment: (String) -> Unit,
    private val onAddFiles: (List<File>) -> Unit,
) : ConversationHandle {
    private val state = MutableStateFlow(ConversationSnapshot(conversationId = conversationId))
    override val snapshots: StateFlow<ConversationSnapshot> = state
    override val events: Flow<ConversationEvent> = emptyFlow()

    override fun history(): List<ConversationEvent> = emptyList()

    override fun setDraft(target: AgentId, value: String) {
        onDraftChange(value)
        state.value = state.value.copy(
            drafts = state.value.drafts + (target to value),
            revision = state.value.revision + 1,
        )
    }

    override fun addAttachments(target: AgentId, files: List<File>) {
        onAddFiles(files)
    }

    override fun removeAttachment(target: AgentId, attachmentId: String) {
        onRemoveAttachment(attachmentId)
    }

    override suspend fun dispatch(command: ConversationCommand): CommandResult {
        return when (command) {
            is ConversationCommand.Send -> {
                onDraftChange("")
                onSend(command.text)
                CommandResult.Accepted("send-${command.text.hashCode()}")
            }
            is ConversationCommand.Interrupt -> {
                onStop()
                CommandResult.Accepted("stop")
            }
            else -> CommandResult.Accepted("noop")
        }
    }

    fun sync(
        chat: dev.ide.ui.backend.UiAgentChatState,
        configured: Boolean,
        canAttach: Boolean,
        agentName: String,
        draft: String,
    ) {
        val participant = AgentParticipant(
            id = AGENT_ID,
            name = agentName,
            capabilities = AgentCapabilities(
                send = configured,
                steer = false,
                interrupt = true,
                approve = true,
                answerQuestion = true,
                attachments = canAttach,
            ),
        )
        val lastId = chat.messages.lastOrNull()?.id
        val showTodos = chat.todos.isNotEmpty() && (chat.busy || chat.todos.any { it.status != UiAgentTodoStatus.DONE })
        val turns = chat.messages.mapIndexed { msgIndex, msg ->
            val runId = RunId("run-${msg.id}")
            if (msg.role == UiAgentRole.USER) {
                ConversationTurn(
                    id = TurnId("turn-${msg.id}"),
                    authorId = "user",
                    agentId = AGENT_ID,
                    runId = runId,
                    parts = buildList {
                        if (msg.text.isNotBlank()) {
                            add(ConversationPart.Text("user-text-${msg.id}", msg.text))
                        }
                        msg.attachments.forEachIndexed { idx, att ->
                            add(ConversationPart.Attachment("user-att-${msg.id}-$idx", att.name, 0L))
                        }
                        add(
                            ConversationPart.Custom(
                                id = "user-meta-${msg.id}",
                                kind = "ide.user.meta",
                                fields = mapOf(
                                    "messageId" to msg.id.toString(),
                                    "canUndo" to (!chat.busy && msg.canUndo).toString(),
                                    "undone" to msg.undone.toString(),
                                    "text" to msg.text,
                                ),
                            ),
                        )
                    },
                )
            } else if (msg.isError) {
                ConversationTurn(
                    id = TurnId("turn-${msg.id}"),
                    authorId = "assistant",
                    agentId = AGENT_ID,
                    runId = runId,
                    parts = buildList {
                        add(
                            ConversationPart.Custom(
                                id = "error-${msg.id}",
                                kind = "ide.error",
                                fields = buildMap {
                                    put("messageId", msg.id.toString())
                                    put("text", msg.text)
                                    put("canRetry", (msg.id == lastId && msg.canRetry).toString())
                                    msg.suggestedModel?.takeIf { msg.id == lastId }?.let { put("suggestedModel", it) }
                                },
                            ),
                        )
                        if (showTodos && msgIndex == chat.messages.lastIndex) {
                            add(ConversationPart.Custom("todos-${msg.id}", "ide.todos", emptyMap()))
                        }
                    },
                )
            } else {
                val steps = stepsOf(msg)
                val parts = buildList {
                    steps.forEachIndexed { stepIdx, step ->
                        val live = msg.streaming && stepIdx == steps.lastIndex
                        when (step) {
                            is Step.Thinking -> if (step.text.isNotBlank()) {
                                add(ConversationPart.Reasoning("think-${msg.id}-$stepIdx", step.text))
                            }
                            is Step.Tools -> if (step.calls.isNotEmpty()) {
                                add(
                                    ConversationPart.ToolTimeline(
                                        id = "tools-${msg.id}-$stepIdx",
                                        calls = step.calls.map { call ->
                                            ChatToolTimelineCall(
                                                id = call.id,
                                                name = call.title,
                                                phase = when (call.status) {
                                                    UiAgentToolStatus.RUNNING -> ChatTimelinePhase.Running
                                                    UiAgentToolStatus.OK -> ChatTimelinePhase.Success
                                                    UiAgentToolStatus.ERROR -> ChatTimelinePhase.Error
                                                    UiAgentToolStatus.DENIED -> ChatTimelinePhase.Denied
                                                },
                                                output = call.detail.takeIf { it.isNotBlank() },
                                                summary = call.detail,
                                            )
                                        },
                                        active = live || step.calls.any { it.status == UiAgentToolStatus.RUNNING },
                                    ),
                                )
                                if (step.calls.any { it.changes.isNotEmpty() }) {
                                    add(
                                        ConversationPart.Custom(
                                            id = "changes-${msg.id}-$stepIdx",
                                            kind = "ide.file.changes",
                                            fields = mapOf(
                                                "messageId" to msg.id.toString(),
                                                "stepIdx" to stepIdx.toString(),
                                            ),
                                        ),
                                    )
                                }
                            }
                            is Step.Text -> if (step.text.isNotBlank()) {
                                add(ConversationPart.Text("text-${msg.id}-$stepIdx", step.text, streaming = live))
                            }
                        }
                    }
                    msg.waitUntilMs?.let { untilMs ->
                        add(
                            ConversationPart.Custom(
                                id = "wait-${msg.id}",
                                kind = "ide.wait",
                                fields = mapOf(
                                    "untilMs" to untilMs.toString(),
                                    "reason" to (msg.waitReason ?: ""),
                                ),
                            ),
                        )
                    }
                    val lastStep = steps.lastOrNull()
                    val idle = lastStep == null ||
                        (lastStep is Step.Tools && lastStep.calls.none { it.status == UiAgentToolStatus.RUNNING })
                    if (msg.streaming && msg.waitUntilMs == null && idle) {
                        add(ConversationPart.Reasoning("think-idle-${msg.id}", "Thinking…"))
                    }
                    if (!msg.streaming && (msg.text.isNotBlank() || msg.usage != null)) {
                        add(
                            ConversationPart.Custom(
                                id = "footer-${msg.id}",
                                kind = "ide.assistant.footer",
                                fields = mapOf("messageId" to msg.id.toString()),
                            ),
                        )
                    }
                    if (showTodos && msgIndex == chat.messages.lastIndex) {
                        add(ConversationPart.Custom("todos-${msg.id}", "ide.todos", emptyMap()))
                    }
                }
                ConversationTurn(
                    id = TurnId("turn-${msg.id}"),
                    authorId = "assistant",
                    agentId = AGENT_ID,
                    runId = runId,
                    parts = parts,
                )
            }
        }
        val activeRunId = RunId("run-${lastId ?: 0L}")
        val runs = if (chat.busy) {
            listOf(AgentRun(activeRunId, AGENT_ID, RunState.Working))
        } else {
            listOf(AgentRun(activeRunId, AGENT_ID, RunState.Complete))
        }
        val pendingAttachments = chat.pendingAttachments.mapIndexed { idx, att ->
            ConversationAttachment(id = idx.toString(), file = File(att.path ?: att.name))
        }
        state.value = ConversationSnapshot(
            conversationId = conversationId,
            participants = listOf(participant),
            turns = turns,
            runs = runs,
            drafts = mapOf(AGENT_ID to draft),
            attachments = mapOf(AGENT_ID to pendingAttachments),
            revision = state.value.revision + 1,
        )
    }

    companion object {
        val AGENT_ID = AgentId("ide-agent")
    }
}


private val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "gif")

/** The ways this host can supply an image; a null source is one the platform lacks, and is not offered. */
private class ImageSources(
    val pick: (() -> Unit)?,
    val camera: (() -> Unit)?,
    val paste: (() -> Unit)?,
    val hasClipboardImage: () -> Boolean,
) {
    val any: Boolean get() = pick != null || camera != null || paste != null
}

/**
 * What the composer can complete at the caret: a slash command while the message is just `/name`, or a project
 * file while the word under the caret starts with `@`. Picking one rewrites that word.
 */
@Composable
private fun Suggestions(backend: IdeBackend, input: TextFieldValue, onPick: (TextFieldValue) -> Unit) {
    val text = input.text
    val caret = input.selection.end.coerceIn(0, text.length)
    val beforeCaret = text.substring(0, caret)
    val commandQuery = if (text.startsWith("/") && ' ' !in text) text.removePrefix("/").lowercase() else null
    val mentionMatch = remember(beforeCaret) { MENTION_AT_CARET.find(beforeCaret) }
    val mentionQuery = mentionMatch?.groupValues?.get(1)

    var mentions by remember { mutableStateOf<List<UiAgentMention>>(emptyList()) }
    LaunchedEffect(mentionQuery) {
        mentions = if (mentionQuery == null) emptyList() else {
            delay(120)
            backend.agent.mentionCandidates(mentionQuery)
        }
    }
    val commands: List<UiAgentCommand> = remember(commandQuery) {
        if (commandQuery == null) emptyList() else backend.agent.commands().filter { it.name.startsWith(commandQuery) }
    }
    if (commands.isEmpty() && (mentionQuery == null || mentions.isEmpty())) return

    val chrome = AgentUi.chrome
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp)
            .clip(RoundedCornerShape(AgentUi.radius.card))
            .background(chrome.surface)
            .border(1.dp, chrome.hairlineStrong, RoundedCornerShape(AgentUi.radius.card))
            .heightIn(max = 220.dp).verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        if (commands.isNotEmpty()) {
            commands.forEach { command ->
                SuggestionRow(
                    title = "/" + command.name,
                    detail = command.description,
                    onClick = {
                        val value = "/" + command.name + " "
                        onPick(TextFieldValue(value, TextRange(value.length)))
                    },
                )
            }
        } else {
            val start = mentionMatch!!.range.first
            mentions.forEach { mention ->
                SuggestionRow(
                    title = mention.label,
                    detail = mention.detail,
                    onClick = {
                        val replaced = text.substring(0, start) + "@" + mention.insert + " "
                        val after = text.substring(caret)
                        onPick(TextFieldValue(replaced + after, TextRange(replaced.length)))
                    },
                )
            }
        }
    }
}

/** `@partial` ending at the caret, not glued to a preceding word (so an e-mail address is not a mention). */
private val MENTION_AT_CARET = Regex("""(?:^|(?<=\s))@([\w./-]*)$""")

@Composable
private fun SuggestionRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = AgentUi.chrome.text,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = AgentUi.chrome.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChatHeader(
    cfg: UiAgentConfig,
    models: List<UiAgentModel>,
    modelMenuOpen: Boolean,
    onModelMenu: (Boolean) -> Unit,
    onPickModel: (String, String) -> Unit,
    onPickEffort: (String) -> Unit,
    onManage: () -> Unit,
    onHistory: () -> Unit,
    onCycleMode: () -> Unit,
    onNew: () -> Unit,
    onClose: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SparkleBadge(size = 24)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.chat_title),
                color = AgentUi.chrome.text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ModelPicker(
                cfg = cfg,
                models = models,
                open = modelMenuOpen,
                onOpen = onModelMenu,
                onPickModel = onPickModel,
                onManageProviders = onManage,
            )
        }
        EffortChip(cfg.effort, onPickEffort)
        ModeChip(cfg.mode, onCycleMode)
        ChatMenu(onNew = onNew, onHistory = onHistory, onManage = onManage)
        if (onClose != null) HideToolWindowButton(onClose)
    }
}

private val EFFORT_OPTIONS = listOf(
    "default" to "Auto",
    "minimal" to "Min",
    "low" to "Low",
    "medium" to "Med",
    "high" to "High",
    "xhigh" to "X-High",
    "max" to "Max",
    "none" to "None",
)

@Composable
private fun EffortChip(effort: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chrome = AgentUi.chrome
    val normalized = effort.ifBlank { "default" }
    val shortLabel = EFFORT_OPTIONS.firstOrNull { it.first == normalized }?.second ?: normalized
    val active = normalized != "default" && normalized != "none"
    Box {
        AgentChip(
            label = "Think: $shortLabel",
            selected = active,
            onClick = { open = true },
        )
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EFFORT_OPTIONS.forEach { (id, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (id == "default") "Default (Auto)" else label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (id == normalized) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (id == normalized) chrome.accent else chrome.text,
                        )
                    },
                    onClick = { onPick(id); open = false },
                )
            }
        }
    }
}

/**
 * The permission mode chip. It cycles on tap, and is tinted by how much it lets the agent do: a session left on
 * auto-accept applies edits without asking, which should be visible in the header rather than something you have
 * to open settings to discover. The dense house chip keeps a compact header intact where an M3 chip's own padding
 * would crowd out the title.
 */
@Composable
private fun ModeChip(mode: UiAgentPermissionMode, onCycleMode: () -> Unit) {
    val chrome = AgentUi.chrome
    // Tinted by how much the mode lets the agent do: auto-accept is the loudest (lime wash), plan-only is
    // calm violet, ask-each is a quiet ghost pill.
    val modeFill = when (mode) {
        UiAgentPermissionMode.AUTO_ACCEPT -> chrome.accentSoft
        UiAgentPermissionMode.PLAN_ONLY -> chrome.surfaceAlt
        UiAgentPermissionMode.ASK_EACH -> Color.Transparent
    }
    val modeText = when (mode) {
        UiAgentPermissionMode.AUTO_ACCEPT -> chrome.accent
        UiAgentPermissionMode.PLAN_ONLY -> chrome.violet
        UiAgentPermissionMode.ASK_EACH -> chrome.textMuted
    }
    val shape = RoundedCornerShape(AgentUi.radius.chip)
    Box(
        Modifier.height(36.dp)
            .clip(shape)
            .background(modeFill)
            .then(
                if (mode == UiAgentPermissionMode.ASK_EACH) Modifier.border(1.dp, chrome.hairline, shape)
                else Modifier
            )
            .clickable(onClick = onCycleMode)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(modeLabel(mode), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = modeText, maxLines = 1)
    }
}

/** The header's occasional actions, in one menu so the title and the model name keep room to read. */
@Composable
private fun ChatMenu(onNew: () -> Unit, onHistory: () -> Unit, onManage: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButtonCa(CaIcons.ellipsis, stringResource(Res.string.chat_more), { menuOpen = true }, iconSize = 16, boxSize = 36)
        CaDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            HeaderMenuItem(CaIcons.plus, stringResource(Res.string.chat_new)) { menuOpen = false; onNew() }
            HeaderMenuItem(CaIcons.clock, stringResource(Res.string.chat_history)) { menuOpen = false; onHistory() }
            HeaderMenuItem(CaIcons.key, stringResource(Res.string.chat_manage_keys)) { menuOpen = false; onManage() }
        }
    }
}

@Composable
private fun HeaderMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = AgentUi.chrome.text) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp), tint = AgentUi.chrome.textFaint) },
        onClick = onClick,
    )
}

@Composable
private fun ModelPicker(
    cfg: UiAgentConfig,
    models: List<UiAgentModel>,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    onPickModel: (String, String) -> Unit,
    onManageProviders: (() -> Unit)? = null,
) {
    val activeProvider = cfg.providers.firstOrNull { it.id == cfg.selectedProvider }
    val current = cfg.model.ifBlank { activeProvider?.defaultModel ?: "" }
    val modelName = models.firstOrNull { it.id == current }?.displayName
        ?: current.ifBlank { activeProvider?.displayName ?: "" }
    val label = if (activeProvider != null && !modelName.equals(activeProvider.displayName, ignoreCase = true)) {
        "${activeProvider.displayName} · $modelName"
    } else {
        modelName
    }

    // Configured providers: have an API key or gateway URL/model, or fall back to selected provider
    val configured = cfg.providers.filter { p ->
        p.apiKey.isNotBlank() || (p.id == "gateway" && (cfg.gatewayBaseUrl.isNotBlank() || cfg.gatewayModel.isNotBlank()))
    }.ifEmpty {
        listOfNotNull(activeProvider ?: cfg.providers.firstOrNull())
    }

    Box {
        Row(
            Modifier.clip(RoundedCornerShape(Ca.radius.pill)).clickable { onOpen(true) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, color = AgentUi.chrome.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val chevron by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
            Icon(CaIcons.chevronDown, null, Modifier.size(12.dp).rotate(chevron), tint = AgentUi.chrome.textFaint)
        }
        CaDropdownMenu(expanded = open, onDismissRequest = { onOpen(false) }) {
            configured.forEachIndexed { idx, p ->
                val isCurrentProvider = p.id == cfg.selectedProvider
                val providerModels = if (isCurrentProvider && models.isNotEmpty()) models else p.models
                if (configured.size > 1) {
                    if (idx > 0) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(AgentUi.chrome.hairlineStrong))
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                p.displayName.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = AgentUi.chrome.accent,
                            )
                        },
                        enabled = false,
                        onClick = {},
                    )
                }
                if (providerModels.isEmpty()) {
                    val fallbackId = if (p.id == "gateway") cfg.gatewayModel.ifBlank { "custom" } else p.defaultModel
                    val fallbackName = if (p.id == "gateway") cfg.gatewayModel.ifBlank { "Custom gateway" } else p.displayName
                    val isSelected = isCurrentProvider && (current.isBlank() || current == fallbackId)
                    DropdownMenuItem(
                        text = {
                            Text(
                                fallbackName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) AgentUi.chrome.accent else AgentUi.chrome.text,
                            )
                        },
                        trailingIcon = if (isSelected) {
                            { Icon(CaIcons.check, null, Modifier.size(14.dp), tint = AgentUi.chrome.accent) }
                        } else null,
                        onClick = { onPickModel(p.id, fallbackId); onOpen(false) },
                    )
                } else {
                    providerModels.forEach { model ->
                        val isSelected = isCurrentProvider && model.id == current
                        DropdownMenuItem(
                            text = {
                                Text(
                                    model.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) AgentUi.chrome.accent else AgentUi.chrome.text,
                                )
                            },
                            trailingIcon = if (isSelected) {
                                { Icon(CaIcons.check, null, Modifier.size(14.dp), tint = AgentUi.chrome.accent) }
                            } else null,
                            onClick = { onPickModel(p.id, model.id); onOpen(false) },
                        )
                    }
                }
            }
            if (onManageProviders != null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(AgentUi.chrome.hairlineStrong))
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.chat_manage_keys), style = MaterialTheme.typography.labelMedium, color = AgentUi.chrome.accent) },
                    leadingIcon = { Icon(CaIcons.key, null, Modifier.size(14.dp), tint = AgentUi.chrome.accent) },
                    onClick = { onOpen(false); onManageProviders() },
                )
            }
        }
    }
}

/**
 * The conversation. It follows a streaming reply only while the reader is at the bottom: scrolling up to read stops
 * the follow, a jump-to-latest button brings it back, and a message the user sends always returns to the bottom.
 * Following keeps the END of the newest message in view. Scrolling to that message's index instead put its top at
 * the top of the screen, which for a tall tool-heavy turn held the view on its first tool call while the answer
 * streamed in below the fold.
 */
@Composable
internal fun Transcript(
    messages: List<UiAgentMessage>,
    busy: Boolean,
    onRetry: () -> Unit,
    onUndo: (Long) -> Unit,
    onUseModel: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    var following by remember { mutableStateOf(true) }
    // Set by a scroll the reader made. Only the scrollable's own gestures, wheel and fling pass through nested
    // scroll, so the programmatic scrolls below never set it.
    var readerScrolled by remember { mutableStateOf(false) }
    var seen by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // The zero-height anchor after the last message. Scrolling to it lands on the very end of the content, because
    // the list will not leave empty space below its last item.
    val end = messages.size
    val readerScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                readerScrolled = true
                // Moving back through the conversation stops the follow at once, so a reply streaming in meanwhile
                // cannot pull the view back down.
                if (consumed.y > 0f) following = false
                return Offset.Zero
            }
        }
    }
    // Where a reader's scroll settles decides whether to follow again: at the bottom it does. A reader's scroll is
    // measured as it is applied, so the list already knows whether it can go further.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && readerScrolled) {
                readerScrolled = false
                following = !listState.canScrollForward
            }
        }
    }
    // A message the user sends returns to the end, wherever the reader was.
    LaunchedEffect(messages.size) {
        if (messages.size < seen) seen = 0
        if (messages.drop(seen).any { it.role == UiAgentRole.USER }) following = true
        seen = messages.size
    }
    // Following is driven by the measured layout, not by message changes: whenever the list can scroll further while
    // following, it goes to the end. That catches every way the content grows (a streamed token, a tool row, a
    // folding run, an expanding diff), and it scrolls against the layout that already holds the new content. A
    // scroll issued from a message change ran before that layout and was clamped to the old, shorter content.
    val currentEnd by rememberUpdatedState(messages.size)
    LaunchedEffect(listState) {
        snapshotFlow { following && listState.canScrollForward && !listState.isScrollInProgress }
            .collectLatest { behind ->
                // Not during a scroll: that is the reader's drag or fling, or the jump button's animation, and an
                // instant scroll here would cancel it. Retried a few frames while a layout is still settling.
                var tries = 0
                while (behind && tries++ < 3 && following && listState.canScrollForward && !listState.isScrollInProgress) {
                    runCatching { listState.scrollToItem(currentEnd) }
                    withFrameNanos { }
                }
            }
    }
    val last = messages.lastOrNull()
    val lastId = last?.id
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().nestedScroll(readerScroll),
            // The bottom padding is short by the spacing the end anchor adds after the last message.
            contentPadding = PaddingValues(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 2.dp),
            // Chat-app feel: content anchors to the bottom when short, newest message at the bottom.
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            itemsKeyed(messages, key = { it.id }) { msg ->
                // Only the most recent failure offers a retry (it resumes the latest turn).
                val latest = msg.id == lastId
                val retry = if (latest && msg.isError && msg.canRetry) onRetry else null
                val useModel = if (latest && msg.isError) msg.suggestedModel else null
                MessageItem(
                    msg, retry,
                    onUndo = if (!busy && msg.canUndo) ({ onUndo(msg.id) }) else null,
                    suggestedModel = useModel,
                    onUseModel = onUseModel,
                )
            }
            item(key = TRANSCRIPT_END) { Spacer(Modifier.fillMaxWidth().height(1.dp)) }
        }
        AnimatedVisibility(
            visible = !following && listState.canScrollForward,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    following = true
                    scope.launch { runCatching { listState.animateScrollToItem(end) } }
                },
                shape = CircleShape,
                containerColor = AgentUi.chrome.accent,
                contentColor = AgentUi.chrome.accentInk,
            ) {
                Icon(CaIcons.chevronDown, stringResource(Res.string.chat_jump_latest), Modifier.size(18.dp))
            }
        }
    }
}

/** The key of the transcript's end anchor; message keys are their Long ids, so a String cannot collide. */
private const val TRANSCRIPT_END = "transcript-end"

@Composable
private fun MessageItem(
    msg: UiAgentMessage,
    onRetry: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
    suggestedModel: String? = null,
    onUseModel: (String) -> Unit = {},
) {
    // One selection scope per message, so a drag (or a long-press and its handles) can take any span of it, across
    // its paragraphs and code blocks. Controls inside opt out with DisableSelection.
    SelectionContainer(Modifier.fillMaxWidth().entranceSlideUp()) {
        MessageContent(msg, onRetry, onUndo, suggestedModel, onUseModel)
    }
}

@Composable
private fun MessageContent(
    msg: UiAgentMessage,
    onRetry: (() -> Unit)?,
    onUndo: (() -> Unit)?,
    suggestedModel: String?,
    onUseModel: (String) -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        when {
            msg.isError -> ErrorMessage(msg.text, onRetry, suggestedModel, onUseModel)
            msg.role == UiAgentRole.USER -> Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    // Agent-first user bubble: surfaceAlt, 18/18/6/18 rounding, ~82% width capped for wide panels.
                    Modifier.fillMaxWidth(0.82f).widthIn(max = 320.dp)
                        .background(AgentUi.chrome.surfaceAlt, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        msg.text,
                        color = AgentUi.chrome.text,
                        style = chatBodyStyle(),
                    )
                }
                AttachmentChips(msg.attachments, modifier = Modifier.fillMaxWidth(0.82f).widthIn(max = 320.dp))
                DisableSelection {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (msg.undone) {
                            Text(
                                stringResource(Res.string.chat_undone),
                                style = MaterialTheme.typography.labelSmall,
                                color = AgentUi.chrome.textFaint,
                            )
                        }
                        if (onUndo != null) {
                            TextButton(
                                onClick = onUndo,
                                colors = ButtonDefaults.textButtonColors(contentColor = AgentUi.chrome.danger),
                            ) {
                                Icon(CaIcons.undo, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(Res.string.chat_undo), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        CopyButton(msg.text)
                    }
                }
            }
            else -> AssistantMessage(msg, onUndo)
        }
    }
}

@Composable
private fun ErrorMessage(
    text: String,
    onRetry: (() -> Unit)?,
    suggestedModel: String? = null,
    onUseModel: (String) -> Unit = {},
) {
    // An opaque surfaceAlt card with danger ink: stays legible in every Agent skin without compositing alpha.
    val chrome = AgentUi.chrome
    Column(
        Modifier.fillMaxWidth()
            .background(chrome.surfaceAlt, RoundedCornerShape(AgentUi.radius.card))
            .border(1.dp, chrome.danger.copy(alpha = 0.35f), RoundedCornerShape(AgentUi.radius.card))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(CaIcons.warning, null, Modifier.size(16.dp), tint = chrome.danger)
            Text(text, color = chrome.text, style = chatBodyStyle())
        }
        DisableSelection {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CopyButton(text, tint = chrome.textFaint)
                // The one-tap fix for a model the account cannot use: switch and re-run, rather than leaving the user
                // to find the model picker and guess which model would work.
                if (suggestedModel != null) {
                    TextButton(
                        onClick = { onUseModel(suggestedModel) },
                        colors = ButtonDefaults.textButtonColors(contentColor = chrome.accent),
                    ) {
                        Icon(CaIcons.sparkle, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.chat_use_model, suggestedModel), style = MaterialTheme.typography.labelLarge)
                    }
                } else if (onRetry != null) {
                    TextButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColors(contentColor = chrome.accent),
                    ) {
                        Icon(CaIcons.refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.chat_retry), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** One step as the transcript draws it: a [UiAgentSegment] with its tool ids resolved to their calls. */
private sealed interface Step {
    data class Text(val text: String) : Step
    data class Thinking(val text: String) : Step
    data class Tools(val calls: List<UiAgentToolCall>) : Step
}

/**
 * The message's steps in the order they happened. A message with no recorded steps (saved by an older version, or
 * plain text such as an error) falls back to reasoning, then tools, then text.
 */
private fun stepsOf(msg: UiAgentMessage): List<Step> {
    if (msg.segments.isEmpty()) {
        return buildList {
            if (msg.thinking.isNotBlank()) add(Step.Thinking(msg.thinking))
            if (msg.toolCalls.isNotEmpty()) add(Step.Tools(msg.toolCalls))
            if (msg.text.isNotBlank()) add(Step.Text(msg.text))
        }
    }
    val byId = msg.toolCalls.associateBy { it.id }
    return msg.segments.map { seg ->
        when (seg) {
            is UiAgentSegment.Text -> Step.Text(seg.text)
            is UiAgentSegment.Thinking -> Step.Thinking(seg.text)
            is UiAgentSegment.Tools -> Step.Tools(seg.ids.mapNotNull(byId::get))
        }
    }
}

@Composable
private fun AssistantMessage(msg: UiAgentMessage, onUndo: (() -> Unit)? = null) {
    val steps = remember(msg.segments, msg.toolCalls, msg.text, msg.thinking) { stepsOf(msg) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { i, step ->
            // The newest step of a running turn is the live one: its reasoning stays open and its tool run stays
            // expanded until the turn moves past it.
            val live = msg.streaming && i == steps.lastIndex
            key(i) {
                when (step) {
                    is Step.Thinking -> if (step.text.isNotBlank()) ThinkingStep(step.text, live)
                    is Step.Tools -> if (step.calls.isNotEmpty()) DisableSelection { ToolRun(step.calls, live, onUndo) }
                    is Step.Text -> if (step.text.isNotBlank()) AssistantMarkdown(step.text)
                }
            }
        }
        msg.waitUntilMs?.let { WaitRow(it, msg.waitReason) }
        val lastStep = steps.lastOrNull()
        // A blinking caret while the answer is still streaming in.
        if (msg.streaming && lastStep is Step.Text && lastStep.text.isNotBlank()) TypingCaret()
        // Something live at the end of a running turn that has nothing streaming yet: before the first token, and
        // between a finished batch of tools and the model's next step, which otherwise looks stalled.
        val idle = lastStep == null ||
            (lastStep is Step.Tools && lastStep.calls.none { it.status == UiAgentToolStatus.RUNNING })
        if (msg.streaming && msg.waitUntilMs == null && idle) ThinkingStep(thinking = "", live = true)
        // Copy the finished answer + secondary usage summary on a single row.
        DisableSelection {
            if (!msg.streaming && (msg.text.isNotBlank() || msg.usage != null)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (msg.text.isNotBlank()) CopyButton(msg.text)
                    msg.usage?.let { UsageFooter(it) }
                }
            }
        }
    }
}

/**
 * What the finished turn cost, as a quiet footer. The cached figure is the point of it: a loop that is caching
 * properly shows it climbing turn over turn while the billed input stays small, so a run where it never appears
 * is the visible symptom of a prompt prefix that changed and threw the cache away.
 */
@Composable
private fun UsageFooter(usage: UiAgentUsage) {
    val parts = buildList {
        if (usage.input > 0) add(compactTokens(usage.input) + " " + stringResource(Res.string.chat_usage_input))
        if (usage.output > 0) add(compactTokens(usage.output) + " " + stringResource(Res.string.chat_usage_output))
        if (usage.cacheRead > 0) {
            add(compactTokens(usage.cacheRead) + " " + stringResource(Res.string.chat_usage_cached))
        }
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        color = AgentUi.chrome.textFaint,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Token counts as "940" / "8.4k" / "1.2M" — enough to read a trend, short enough for a one-line footer. */
private fun compactTokens(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 1_000_000 -> "${count / 1000}.${(count % 1000) / 100}k"
    else -> "${count / 1_000_000}.${(count % 1_000_000) / 100_000}M"
}

/**
 * A run of consecutive tool calls. One call is a single compact row. Several get a summary header and stay open
 * while the run is live or still running, then fold to that header once the turn moves past them, so a tool-heavy
 * turn reads as its prose with a line per batch of work. A tap on the header overrides the automatic choice.
 */
@Composable
private fun ToolRun(calls: List<UiAgentToolCall>, live: Boolean, onUndo: (() -> Unit)? = null) {
    if (calls.size == 1) {
        ToolCallRow(calls[0], onUndo)
        return
    }
    var choice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val expanded = choice ?: (live || calls.any { it.status == UiAgentToolStatus.RUNNING })
    val rail = AgentUi.chrome.hairlineStrong
    Column(Modifier.fillMaxWidth().entranceSlideUp()) {
        ToolRunHeader(calls, expanded) { choice = !expanded }
        if (expanded) {
            // A hairline rail ties the rows to their header, the way a nested list is indented under its parent.
            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp)
                    .drawBehind { drawRect(rail, size = Size(1.dp.toPx(), size.height)) }
                    .padding(start = 10.dp),
            ) {
                calls.forEach { call -> key(call.id) { ToolCallRow(call, onUndo) } }
            }
        }
    }
}

@Composable
private fun ToolRunHeader(calls: List<UiAgentToolCall>, expanded: Boolean, onToggle: () -> Unit) {
    val chrome = AgentUi.chrome
    val files = remember(calls) { calls.flatMap { it.changes }.map { it.path }.distinct().size }
    val summary = buildString {
        append(pluralStringResource(Res.plurals.chat_tool_count, calls.size, calls.size))
        if (files > 0) append(" · ").append(pluralStringResource(Res.plurals.chat_files_changed, files, files))
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle)
            .padding(horizontal = 2.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToolStatusIcon(aggregateStatus(calls))
        Text(summary, style = MaterialTheme.typography.bodySmall, color = chrome.text, fontWeight = FontWeight.Medium, maxLines = 1)
        // Folded, the header still says what the run did last.
        Text(
            if (expanded) "" else calls.last().title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, color = chrome.textMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "toolChevron")
        Icon(CaIcons.chevronDown, null, Modifier.size(14.dp).rotate(rotation), tint = chrome.textFaint)
    }
}

/** The status shown on a collapsed tool group: running wins, then error, then denied, else all-ok. */
private fun aggregateStatus(calls: List<UiAgentToolCall>): UiAgentToolStatus = when {
    calls.any { it.status == UiAgentToolStatus.RUNNING } -> UiAgentToolStatus.RUNNING
    calls.any { it.status == UiAgentToolStatus.ERROR } -> UiAgentToolStatus.ERROR
    calls.any { it.status == UiAgentToolStatus.DENIED } -> UiAgentToolStatus.DENIED
    else -> UiAgentToolStatus.OK
}

/** One-tap copy of a message's text, flipping to a check for ~1.5s as confirmation. */
@Composable
private fun CopyButton(text: String, tint: androidx.compose.ui.graphics.Color = AgentUi.chrome.textFaint) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    IconButtonCa(
        if (copied) CaIcons.check else CaIcons.copy,
        stringResource(if (copied) Res.string.chat_copied else Res.string.chat_copy),
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        iconSize = 14,
        boxSize = 26,
        tint = if (copied) Ide.colors.success else tint,
    )
}

@Composable
private fun TypingCaret() {
    Box(
        Modifier.size(width = 7.dp, height = 14.dp)
            .background(pulseColor(true), RoundedCornerShape(2.dp)),
    )
}

/**
 * The model's reasoning. Open while it streams, so there is something to watch. Once the turn moves on it folds to one
 * "Thought" line that expands on tap: a finished turn's reasoning is rarely worth its screen space.
 */
@Composable
private fun ThinkingStep(thinking: String, live: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    val expanded = live || open
    val chrome = AgentUi.chrome
    val canToggle = !live && thinking.isNotBlank()
    Column(
        Modifier.fillMaxWidth().entranceSlideUp().clip(RoundedCornerShape(14.dp))
            .background(if (expanded) chrome.surface else Color.Transparent)
            .then(if (expanded) Modifier.border(1.dp, chrome.hairline, RoundedCornerShape(14.dp)) else Modifier)
            .then(if (canToggle) Modifier.clickable { open = !open } else Modifier)
            .padding(horizontal = if (expanded) 12.dp else 2.dp, vertical = if (expanded) 10.dp else 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AgentStatusDot(active = true, size = 7.dp, color = pulseColor(live))
            Text(
                stringResource(if (live) Res.string.chat_thinking else Res.string.chat_thought),
                style = MaterialTheme.typography.labelSmall, color = chrome.textMuted,
            )
            if (canToggle) {
                val rotation by animateFloatAsState(if (open) 0f else -90f, label = "thoughtChevron")
                Icon(CaIcons.chevronDown, null, Modifier.size(12.dp).rotate(rotation), tint = chrome.textFaint)
            }
        }
        if (expanded && thinking.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(thinking, style = MaterialTheme.typography.bodySmall, color = chrome.textFaint)
        }
    }
}

/**
 * One tool call as a single line: status, the call (its verb set apart from its target), and its result. A long
 * result is cut to the line; a tap shows it whole. Files the call changed list under it, each expanding to its diff.
 */
@Composable
private fun ToolCallRow(call: UiAgentToolCall, onUndo: (() -> Unit)? = null) {
    var open by rememberSaveable(call.id) { mutableStateOf(false) }
    val chrome = AgentUi.chrome
    val canOpen = call.detail.isNotBlank()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .then(if (canOpen) Modifier.clickable { open = !open } else Modifier)
                .padding(horizontal = 2.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolStatusIcon(call.status)
            Text(
                toolTitle(call.title, chrome.textFaint),
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall, color = chrome.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (canOpen && !open) {
                Text(
                    call.detail,
                    Modifier.widthIn(max = 160.dp),
                    style = MaterialTheme.typography.bodySmall, color = chrome.textFaint,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (open) {
            Text(
                call.detail,
                Modifier.padding(start = 24.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = chrome.textFaint,
            )
        }
        if (call.changes.isNotEmpty()) Box(Modifier.padding(start = 22.dp, top = 2.dp)) { FileChangesList(call.changes, onUndo = onUndo) }
    }
}

/** A call's title with its first word, the tool's verb, in a quieter weight than what it acted on. */
private fun toolTitle(title: String, verbColor: Color): AnnotatedString = buildAnnotatedString {
    val split = title.indexOf(' ')
    if (split <= 0) {
        append(title)
        return@buildAnnotatedString
    }
    withStyle(SpanStyle(color = verbColor, fontWeight = FontWeight.Medium)) { append(title.substring(0, split)) }
    append(title.substring(split))
}

@Composable
private fun ToolStatusIcon(status: UiAgentToolStatus) {
    // Rounded-square status dots (6-8px): lime for running/ok, coral for error, amber for denied.
    val chrome = AgentUi.chrome
    Crossfade(targetState = status, label = "toolStatus") { s ->
        when (s) {
            UiAgentToolStatus.RUNNING -> AgentStatusDot(active = true, size = 8.dp, color = pulseColor(true))
            UiAgentToolStatus.OK -> AgentStatusDot(active = true, size = 7.dp, color = chrome.accent)
            UiAgentToolStatus.ERROR -> AgentStatusDot(active = true, size = 7.dp, color = chrome.danger)
            UiAgentToolStatus.DENIED -> AgentStatusDot(active = true, size = 7.dp, color = chrome.warn)
        }
    }
}

/**
 * The style for message text. A message is in whatever language its writer used (an English provider error, a
 * model's reply, the user's prompt), not the UI's, so each paragraph takes its direction from its own first
 * strong character, as Android's TextView does. Compose otherwise lays it out in the UI's direction, which put an
 * English sentence's final period on the far left under the Arabic UI.
 */
@Composable
private fun chatBodyStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontSize = 15.sp,
    lineHeight = 22.sp,
    textDirection = TextDirection.Content,
)

@Composable
private fun AssistantMarkdown(text: String) {
    // The shared Markdown renderer (headings, lists, quotes, emphasis, links), tuned to the chat's compact
    // footnote style; fenced code uses the syntax-highlighted CodeSample card.
    Markdown(
        text,
        paragraphStyle = chatBodyStyle(),
        headingStyle = { defaultHeadingStyle(it).copy(textDirection = TextDirection.Content) },
        color = AgentUi.chrome.text,
        spacing = 8.dp,
        codeBlock = { code, lang -> CodeSample(code, lang) },
    )
}

@Composable
private fun EmptyState(configured: Boolean, onManage: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.entrancePop()) { SparkleBadge(size = 44, animated = true) }
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(Res.string.chat_empty_title),
            modifier = Modifier.entranceSlideUp(90),
            color = AgentUi.chrome.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (configured) stringResource(Res.string.chat_empty_body) else stringResource(Res.string.chat_need_key),
            modifier = Modifier.entranceSlideUp(150),
            color = AgentUi.chrome.textMuted, style = MaterialTheme.typography.bodyMedium,
        )
        if (!configured) {
            Spacer(Modifier.height(16.dp))
            // Lime CTA with dark ink (AgentPrimaryButton); leading key icon kept for parity with the old button.
            Row(
                modifier = Modifier.entranceSlideUp(210),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AgentPrimaryButton(
                    stringResource(Res.string.chat_add_key),
                    onManage,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    value: TextFieldValue,
    configured: Boolean,
    busy: Boolean,
    images: ImageSources,
    activeFilePath: String?,
    onAttachFile: (String) -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val chrome = AgentUi.chrome
    // Agent-first pill composer: one 22px-radius rail holding attach, free text and a 40px lime send.
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(chrome.surface)
                .border(1.dp, chrome.hairlineStrong, RoundedCornerShape(22.dp))
                .drawBehind {
                    drawLine(
                        color = chrome.sheen,
                        start = Offset(12f, 1f),
                        end = Offset(size.width - 12f, 1f),
                        strokeWidth = 1f,
                    )
                }
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (images.any || activeFilePath != null) {
                var attachOpen by remember { mutableStateOf(false) }
                Box {
                    AgentIconCircle(
                        CaIcons.plus, stringResource(Res.string.chat_attach), { attachOpen = true },
                        size = 40.dp,
                    )
                    CaDropdownMenu(expanded = attachOpen, onDismissRequest = { attachOpen = false }) {
                        images.pick?.let { pick ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.chat_attach_image), style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = { Icon(CaIcons.image, null, Modifier.size(16.dp)) },
                                onClick = { attachOpen = false; pick() },
                            )
                        }
                        images.camera?.let { camera ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.chat_attach_photo), style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = { Icon(CaIcons.eye, null, Modifier.size(16.dp)) },
                                onClick = { attachOpen = false; camera() },
                            )
                        }
                        // Offered only while there is an image to paste, checked as the menu opens.
                        val paste = images.paste
                        if (paste != null && remember(attachOpen) { images.hasClipboardImage() }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.chat_paste_image), style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = { Icon(CaIcons.copy, null, Modifier.size(16.dp)) },
                                onClick = { attachOpen = false; paste() },
                            )
                        }
                        if (activeFilePath != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.chat_attach_file), style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = { Icon(CaIcons.file, null, Modifier.size(16.dp)) },
                                onClick = { attachOpen = false; onAttachFile(activeFilePath) },
                            )
                        }
                    }
                }
            }
            // Focus lights the lime cursor and a stronger hairline; the pill itself never reflows.
            val fieldInteraction = remember { MutableInteractionSource() }
            val focused by fieldInteraction.collectIsFocusedAsState()
            val borderColor by animateColorAsState(
                if (focused) chrome.accentSoftLine else chrome.hairlineStrong,
                animationSpec = CaMotion.defaultEffects(),
                label = "composerBorder",
            )
            Box(
                Modifier.weight(1f).padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) {
                    Text(
                        stringResource(if (configured) Res.string.chat_placeholder else Res.string.chat_need_key),
                        color = chrome.textMuted, fontSize = 15.sp,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = configured && !busy,
                    textStyle = TextStyle(color = chrome.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(chrome.accent),
                    maxLines = 5,
                    interactionSource = fieldInteraction,
                    modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                        when {
                            event.type != KeyEventType.KeyDown -> false
                            event.key == Key.Enter && !event.isShiftPressed -> { onSend(); true }
                            // Paste claims Ctrl/Cmd+V only when the clipboard holds an image; text paste is untouched.
                            event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed) &&
                                images.paste != null && images.hasClipboardImage() -> { images.paste.invoke(); true }
                            else -> false
                        }
                    },
                )
                // Focus ring drawn over the pill's inner edge.
                if (focused) {
                    Box(
                        Modifier.matchParentSize()
                            .border(1.dp, borderColor, RoundedCornerShape(22.dp)),
                    )
                }
            }
            // 40px lime send. While a turn is running it becomes the stop control.
            val canSend = configured && value.text.isNotBlank() && !busy
            AgentIconCircle(
                icon = if (busy) CaIcons.stop else CaIcons.arrowRight,
                contentDescription = stringResource(if (busy) Res.string.chat_stop else Res.string.chat_send),
                onClick = { if (busy) onStop() else onSend() },
                size = 40.dp,
                accent = busy || canSend,
            )
        }
    }
}

/**
 * The agent's mark: a tonal container rather than the gradient slab it used to be. Material You supplies the
 * hue, so on Android 12+ it is the wallpaper's own primary — the chat reads as part of the system, not as a
 * separately-branded panel bolted into the IDE.
 */
@Composable
private fun SparkleBadge(size: Int, animated: Boolean = false) {
    val breathe = rememberInfiniteTransition(label = "sparkle")
    val pulse by breathe.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1900), RepeatMode.Reverse),
        label = "sparkleScale",
    )
    val scale = if (animated) pulse else 1f
    val chrome = AgentUi.chrome
    Box(
        Modifier.size(size.dp)
            .background(chrome.accent, RoundedCornerShape((size / 2.6f).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            CaIcons.sparkle, null, Modifier.size((size * 0.58f).dp).scale(scale),
            tint = chrome.accentInk,
        )
    }
}

/**
 * The header and composer sit on a tonal container so the transcript is the only thing on the base surface —
 * the same separation the editor chrome uses, and what makes a long scroll read as content moving under
 * fixed furniture rather than one flat sheet.
 */
@Composable
private fun ChatChrome(content: @Composable () -> Unit) {
    Surface(color = AgentUi.chrome.surface) { content() }
}

/**
 * The "still working" pulse, as a colour animation between two solid theme roles rather than a fading alpha.
 * A pulsing alpha dims the glyph toward whatever sits behind it; lerping between two opaque colours keeps the
 * same sense of motion with contrast that holds at every point in the cycle.
 */
@Composable
private fun pulseColor(active: Boolean): Color {
    val chrome = AgentUi.chrome
    if (!active) return chrome.accent
    val transition = rememberInfiniteTransition(label = "pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    return lerp(chrome.textFaint, chrome.accent, progress)
}

@Composable
private fun modeLabel(mode: UiAgentPermissionMode): String = when (mode) {
    UiAgentPermissionMode.ASK_EACH -> stringResource(Res.string.chat_mode_ask)
    UiAgentPermissionMode.AUTO_ACCEPT -> stringResource(Res.string.chat_mode_auto)
    UiAgentPermissionMode.PLAN_ONLY -> stringResource(Res.string.chat_mode_plan)
}

private fun nextMode(mode: UiAgentPermissionMode): UiAgentPermissionMode = when (mode) {
    UiAgentPermissionMode.ASK_EACH -> UiAgentPermissionMode.AUTO_ACCEPT
    UiAgentPermissionMode.AUTO_ACCEPT -> UiAgentPermissionMode.PLAN_ONLY
    UiAgentPermissionMode.PLAN_ONLY -> UiAgentPermissionMode.ASK_EACH
}

