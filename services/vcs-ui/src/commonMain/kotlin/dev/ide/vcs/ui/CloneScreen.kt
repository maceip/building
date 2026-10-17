package dev.ide.vcs.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiForgeRepo
import dev.ide.ui.backend.UiProjectFolderKind
import dev.ide.ui.backend.VcsService
import dev.ide.ui.components.Chip
import dev.ide.ui.components.ExpressiveScaffold
import dev.ide.ui.components.PrimaryButton
import dev.ide.ui.components.cossElevation
import dev.ide.ui.components.pressScale
import dev.ide.ui.ext.ScreenContext
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.itemsKeyed
import dev.ide.ui.platform.HapticCategory
import dev.ide.ui.platform.rememberIdeHaptics
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Ide
import dev.ide.vcs.ui.generated.resources.Res
import dev.ide.vcs.ui.generated.resources.vcs_accounts
import dev.ide.vcs.ui.generated.resources.vcs_cancel
import dev.ide.vcs.ui.generated.resources.vcs_clone_auth_signed_in
import dev.ide.vcs.ui.generated.resources.vcs_clone_auth_signed_out
import dev.ide.vcs.ui.generated.resources.vcs_clone_auth_title
import dev.ide.vcs.ui.generated.resources.vcs_clone_none
import dev.ide.vcs.ui.generated.resources.vcs_clone_search
import dev.ide.vcs.ui.generated.resources.vcs_clone_title
import dev.ide.vcs.ui.generated.resources.vcs_clone_unrecognized_body
import dev.ide.vcs.ui.generated.resources.vcs_clone_unrecognized_open
import dev.ide.vcs.ui.generated.resources.vcs_clone_unrecognized_title
import dev.ide.vcs.ui.generated.resources.vcs_clone_yours
import dev.ide.vcs.ui.generated.resources.vcs_fork
import dev.ide.vcs.ui.generated.resources.vcs_private
import dev.ide.vcs.ui.generated.resources.vcs_sign_in_github
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Curated companion repository recommendation shown while a Git clone is streaming so the developer
 * can discover and queue complementary projects without waiting on a blank progress screen.
 */
private data class CompanionRepoRecommendation(
    val fullName: String,
    val name: String,
    val cloneUrl: String,
    val description: String,
    val reason: String,
    val language: String,
    val stars: String,
    val tags: List<String>,
)

private val COMPANION_RECOMMENDATIONS = listOf(
    // Android / Kotlin companions (< 4 MB, fast shallow clone)
    CompanionRepoRecommendation(
        fullName = "cortinico/kotlin-android-template",
        name = "kotlin-android-template",
        cloneUrl = "https://github.com/cortinico/kotlin-android-template.git",
        description = "Clean, modern Android + Kotlin + Gradle KTS project template with Detekt & CI.",
        reason = "Recommended Android companion · Clean modular Gradle KTS baseline",
        language = "Kotlin",
        stars = "1.9k",
        tags = listOf("Android", "Kotlin", "Gradle KTS"),
    ),
    CompanionRepoRecommendation(
        fullName = "skydoves/Sandwich",
        name = "Sandwich",
        cloneUrl = "https://github.com/skydoves/Sandwich.git",
        description = "Adaptable and lightweight sealed API response library for Kotlin and Android.",
        reason = "Pairs well with Compose UI · Type-safe network response handling",
        language = "Kotlin",
        stars = "1.8k",
        tags = listOf("Kotlin", "Android", "Networking"),
    ),
    // Swift & iOS companions (< 1 MB, fast shallow clone)
    CompanionRepoRecommendation(
        fullName = "AfrazCodes/SwiftUI-Calculator",
        name = "SwiftUI-Calculator",
        cloneUrl = "https://github.com/AfrazCodes/SwiftUI-Calculator.git",
        description = "Interactive iOS Calculator app in SwiftUI — compiles & renders live via Cupertino Bridge.",
        reason = "SwiftUI GUI companion · Zero-source-change Cupertino Bridge execution",
        language = "Swift",
        stars = "58",
        tags = listOf("SwiftUI", "Cupertino GUI", "iOS"),
    ),
    CompanionRepoRecommendation(
        fullName = "ricardomongza99/Calculator",
        name = "Calculator",
        cloneUrl = "https://github.com/ricardomongza99/Calculator.git",
        description = "Full iOS Calculator in SwiftUI with ObservableObject ViewModel and custom ButtonStyle.",
        reason = "SwiftUI MVVM companion · Live interactive Cupertino Bridge window",
        language = "Swift",
        stars = "42",
        tags = listOf("SwiftUI", "MVVM", "iOS"),
    ),
    CompanionRepoRecommendation(
        fullName = "johnsundell/Ink",
        name = "Ink",
        cloneUrl = "https://github.com/johnsundell/Ink.git",
        description = "Fast, zero-dependency Markdown parser and CLI written in pure Swift.",
        reason = "SwiftPM companion · Compiles natively with Swift & xtool",
        language = "Swift",
        stars = "2.5k",
        tags = listOf("Swift", "SwiftPM", "iOS"),
    ),
    // Python companions (< 1.5 MB, fast shallow clone)
    CompanionRepoRecommendation(
        fullName = "karpathy/micrograd",
        name = "micrograd",
        cloneUrl = "https://github.com/karpathy/micrograd.git",
        description = "Tiny scalar-valued autograd engine and neural network library in pure Python.",
        reason = "Python AI companion · Zero-dependency autograd & MLP engine",
        language = "Python",
        stars = "17.9k",
        tags = listOf("Python", "Autograd", "AI"),
    ),
    CompanionRepoRecommendation(
        fullName = "karpathy/nanoGPT",
        name = "nanoGPT",
        cloneUrl = "https://github.com/karpathy/nanoGPT.git",
        description = "The simplest, fastest pure-Python repository for training and running GPT models.",
        reason = "Python LLM companion · Clean standalone transformer pipeline",
        language = "Python",
        stars = "63.8k",
        tags = listOf("Python", "LLM", "GPT"),
    ),
    // Node.js companions (< 800 KB, fast shallow clone)
    CompanionRepoRecommendation(
        fullName = "jameshibbard/nodegui-password-generator",
        name = "nodegui-password-generator",
        cloneUrl = "https://github.com/jameshibbard/nodegui-password-generator.git",
        description = "NodeGUI desktop Password Generator app with QMainWindow, QCheckBox, QLineEdit & QPushButton.",
        reason = "NodeGUI Window companion · Live interactive GUI Bridge window",
        language = "JavaScript",
        stars = "18",
        tags = listOf("Node.js", "NodeGUI", "Desktop GUI"),
    ),
    CompanionRepoRecommendation(
        fullName = "lukeed/polka",
        name = "polka",
        cloneUrl = "https://github.com/lukeed/polka.git",
        description = "Micro web server and HTTP router for Node.js with middleware support.",
        reason = "Node.js companion · Ultra-fast local HTTP & REST router",
        language = "JavaScript",
        stars = "5.6k",
        tags = listOf("Node.js", "HTTP", "ESM"),
    ),
)

/**
 * In-app Git Clone & Multitasking Workspace Puller.
 *
 * Eliminates empty URL paste boxes and dead-end screens:
 * - Emits the unique **Git Clone "Rat-a-tat"** tactile haptics signature ([IdeHaptics.cloneStart],
 *   periodic [IdeHaptics.cloneRatatatPulse] while objects stream, and [IdeHaptics.cloneComplete]).
 * - While a clone is running, presents **Complementary & Related Projects to Pull In** so the user can
 *   queue additional companion repositories with 1 tap while feeling the clone progress through haptics.
 */
@Composable
internal fun CloneScreen(ctx: ScreenContext) {
    val vcs = ctx.backend.vcs
    val accounts by vcs.accounts.collectAsState()
    val activity by vcs.activity.collectAsState()
    val scope = rememberCoroutineScope()
    val feedback = rememberVcsFeedback()
    val haptics = rememberIdeHaptics()

    val initialUrl = (ctx.argument as? String)?.trim().orEmpty()
    var activeCloneUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var activeCloneName by remember(initialUrl) {
        mutableStateOf(
            if (initialUrl.isNotBlank()) initialUrl.substringAfterLast('/').removeSuffix(".git") else ""
        )
    }
    var autoCloneTriggered by remember(initialUrl) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf(emptyList<UiForgeRepo>()) }
    var loading by remember { mutableStateOf(false) }
    var unrecognized by remember { mutableStateOf<ClonedFolder?>(null) }
    var authFailed by remember { mutableStateOf(false) }

    // Repositories queued by the user while a primary clone is running
    val queuedClones = remember { mutableStateListOf<CompanionRepoRecommendation>() }
    val completedCompanions = remember { mutableStateListOf<String>() }

    // Periodic "Rat-a-tat" tactile pulse while a clone is actively running so the user can multitask
    // and feel the clone's heartbeat + completion without staring at a spinner.
    // Interval and category gating come directly from the persisted HapticsConfig.
    val pulseIntervalMs = haptics.config.clonePulseIntervalMs.coerceIn(500, 5000).toLong()
    val cloneHapticsActive = haptics.config.isEnabledFor(HapticCategory.GIT_CLONE)
    LaunchedEffect(activity.busy, pulseIntervalMs, cloneHapticsActive) {
        if (!activity.busy || !cloneHapticsActive) return@LaunchedEffect
        while (activity.busy) {
            delay(pulseIntervalMs)
            if (activity.busy) {
                haptics.cloneRatatatPulse()
            }
        }
    }

    LaunchedEffect(query, accounts) {
        if (accounts.isEmpty()) {
            repos = emptyList()
            return@LaunchedEffect
        }
        if (query.isNotBlank()) delay(SEARCH_DEBOUNCE_MS)
        loading = true
        repos = vcs.forgeRepositories(query)
        loading = false
    }

    fun executeClone(targetUrl: String, targetName: String, openWhenDone: Boolean = true) {
        if (activity.busy) return
        val cleanUrl = targetUrl.trim()
        if (cleanUrl.isBlank()) return
        val cleanName = targetName.trim().ifBlank { cleanUrl.substringAfterLast('/').removeSuffix(".git") }
        activeCloneUrl = cleanUrl
        activeCloneName = cleanName
        haptics.cloneStart()
        scope.launch {
            unrecognized = null
            authFailed = false
            val result = vcs.cloneRepository(cleanUrl, cleanName)
            feedback.show(result)
            authFailed = !result.ok && result.authRequired
            val path = result.path
            if (!result.ok || path == null) {
                haptics.buildFailed()
                return@launch
            }
            haptics.cloneComplete()
            completedCompanions.add(cleanUrl)

            // If the user queued companion repos while multitasking, pull them in before opening!
            while (queuedClones.isNotEmpty()) {
                val next = queuedClones.removeAt(0)
                activeCloneUrl = next.cloneUrl
                activeCloneName = next.name
                haptics.cloneStart()
                val nextRes = vcs.cloneRepository(next.cloneUrl, next.name)
                if (nextRes.ok) {
                    haptics.cloneComplete()
                    completedCompanions.add(next.cloneUrl)
                }
            }

            if (openWhenDone) {
                if (result.projectKind == UiProjectFolderKind.UNKNOWN) {
                    unrecognized = ClonedFolder(cleanName, path)
                } else {
                    ctx.backend.projects.openProject(path)
                }
            }
        }
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank() && !autoCloneTriggered) {
            autoCloneTriggered = true
            executeClone(initialUrl, activeCloneName, openWhenDone = true)
        }
    }

    val companionList = remember(activeCloneName, activeCloneUrl) {
        val hint = "$activeCloneUrl $activeCloneName".lowercase()
        val preferredLanguage = when {
            hint.contains("swift") || hint.contains("ink") || hint.contains("defaults") || hint.contains("publish") || hint.contains("dealer") -> "Swift"
            hint.contains("python") || hint.contains("gpt") || hint.contains("micrograd") || hint.contains("click") -> "Python"
            hint.contains("node") || hint.contains("polka") || hint.contains("kleur") || hint.contains("mitt") || hint.contains("commander") || hint.contains("/ms") -> "JavaScript"
            else -> "Kotlin"
        }
        COMPANION_RECOMMENDATIONS
            .filter { !it.name.equals(activeCloneName, ignoreCase = true) }
            .sortedByDescending { it.language.equals(preferredLanguage, ignoreCase = true) }
    }

    ExpressiveScaffold(
        title = stringResource(Res.string.vcs_clone_title),
        onBack = ctx::back,
        large = false,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Live Clone Status Card + Tactile Rat-a-tat Telemetry
            if (activeCloneUrl.isNotBlank() || activity.busy) {
                item("activeCloneCard") {
                    ActiveCloneTelemetryCard(
                        repoName = activeCloneName.ifBlank { activeCloneUrl },
                        repoUrl = activeCloneUrl,
                        busy = activity.busy,
                        hapticsEnabled = cloneHapticsActive,
                        taskLabel = localizedText(activity.taskText, activity.task),
                        fraction = activity.fraction,
                        queuedCount = queuedClones.size,
                    )
                }
            }

            item("feedback") {
                FeedbackStrip(feedback)
            }

            if (authFailed) {
                item("authFailed") {
                    CloneAuthNotice(
                        signedIn = accounts.isNotEmpty(),
                        onDismiss = { authFailed = false },
                        onSignIn = { ctx.openScreen(VcsService.SCREEN_ACCOUNTS) },
                    )
                }
            }

            unrecognized?.let { cloned ->
                item("unrecognized") {
                    UnrecognizedCloneNotice(
                        name = cloned.name,
                        onDismiss = { unrecognized = null },
                        onOpen = {
                            unrecognized = null
                            scope.launch { ctx.backend.projects.openProject(cloned.path) }
                        },
                    )
                }
            }

            // 2. Multitasking Companion Repositories ("Consider Pulling In As Well")
            item("companionHeader") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (activity.busy) "While Cloning · Consider Pulling In" else "Recommended Repositories to Pull In",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Chip(
                            text = "${companionList.size} curated",
                            fill = MaterialTheme.colorScheme.primaryContainer,
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Text(
                        if (activity.busy) {
                            "Queue complementary libraries & reference projects now — tactile rat-a-tat haptics will pulse as each clone completes."
                        } else {
                            "1-tap clone directly into your workspace without leaving the app."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(companionList, key = { it.fullName }) { companion ->
                val isQueued = queuedClones.any { it.cloneUrl == companion.cloneUrl }
                val isCompleted = completedCompanions.contains(companion.cloneUrl)
                val isCurrentlyCloning = activity.busy && activeCloneUrl == companion.cloneUrl
                CompanionRepoCard(
                    repo = companion,
                    isQueued = isQueued,
                    isCompleted = isCompleted,
                    isCurrentlyCloning = isCurrentlyCloning,
                    cloneBusy = activity.busy,
                    onAction = {
                        when {
                            isCompleted || isCurrentlyCloning -> Unit
                            isQueued -> {
                                haptics.tap()
                                queuedClones.removeAll { it.cloneUrl == companion.cloneUrl }
                            }
                            activity.busy -> {
                                haptics.cloneRatatatPulse()
                                queuedClones.add(companion)
                            }
                            else -> {
                                executeClone(companion.cloneUrl, companion.name, openWhenDone = true)
                            }
                        }
                    },
                )
            }

            // 3. Signed-in Account's Own Repositories (if connected)
            if (accounts.isNotEmpty()) {
                item("yourReposHeader") {
                    Spacer(Modifier.height(4.dp))
                    SignInAgainCard(accounts) { ctx.openScreen(VcsService.SCREEN_ACCOUNTS) }
                    SectionHeader(stringResource(Res.string.vcs_clone_yours), repos.size)
                    VcsField(
                        query,
                        { query = it },
                        stringResource(Res.string.vcs_clone_search),
                        leading = CaIcons.search,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (repos.isEmpty() && !loading) {
                    item("noAccountRepos") {
                        Text(
                            stringResource(Res.string.vcs_clone_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    itemsKeyed(repos, key = { it.fullName }) { repo ->
                        RepoRow(repo) {
                            executeClone(repo.cloneUrl, repo.name, openWhenDone = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveCloneTelemetryCard(
    repoName: String,
    repoUrl: String,
    busy: Boolean,
    hapticsEnabled: Boolean,
    taskLabel: String,
    fraction: Float,
    queuedCount: Int,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.lg)
    val interaction = remember { MutableInteractionSource() }
    val pulseTransition = rememberInfiniteTransition(label = "ratatatPulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
        label = "pulseDot",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .cossElevation(
                interaction = interaction,
                shape = shape,
                restElevation = 6.dp,
                topHighlight = Ide.colors.glassEdgeTop,
            )
            .background(scheme.surfaceContainerHigh, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            if (busy) scheme.primary.copy(alpha = pulseAlpha) else Ide.colors.run,
                            CircleShape,
                        )
                )
                Text(
                    repoName,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Chip(
                text = when {
                    busy && hapticsEnabled -> "Rat-a-tat Haptics Active"
                    busy -> "Cloning…"
                    else -> "Clone Ready"
                },
                fill = if (busy) scheme.primaryContainer else scheme.secondaryContainer,
                textColor = if (busy) scheme.onPrimaryContainer else scheme.onSecondaryContainer,
            )
        }

        Text(
            repoUrl,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (busy) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    taskLabel.ifBlank { "Receiving pack objects & indexing tree…" },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (queuedCount > 0) {
                    Text(
                        "+$queuedCount queued next",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (fraction >= 0f) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(999.dp)),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(999.dp)),
                )
            }
        }
    }
}

@Composable
private fun CompanionRepoCard(
    repo: CompanionRepoRecommendation,
    isQueued: Boolean,
    isCompleted: Boolean,
    isCurrentlyCloning: Boolean,
    cloneBusy: Boolean,
    onAction: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Ca.radius.md)
    val interaction = remember { MutableInteractionSource() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .pressScale(interaction)
            .cossElevation(
                interaction = interaction,
                shape = shape,
                restElevation = 4.dp,
                topHighlight = Ide.colors.glassEdgeTop,
            )
            .background(scheme.surfaceContainerLow, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onAction)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    repo.fullName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    repo.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val buttonLabel = when {
                isCompleted -> "Cloned ✓"
                isCurrentlyCloning -> "Cloning…"
                isQueued -> "Queued ✓"
                cloneBusy -> "Queue Next"
                else -> "Pull In"
            }
            PrimaryButton(
                text = buttonLabel,
                onClick = onAction,
                icon = if (isCompleted || isQueued) null else CaIcons.cloudDownload,
            )
        }

        Text(
            repo.description,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(repo.language, fill = scheme.secondaryContainer, textColor = scheme.onSecondaryContainer)
            Chip("★ ${repo.stars}")
            repo.tags.take(2).forEach { tag ->
                Chip(tag)
            }
        }
    }
}

/** A clone that finished, waiting on the user because nothing recognized what it holds. */
private data class ClonedFolder(val name: String, val path: String)

/**
 * Said in place, on the clone screen, the moment a clone turns out to hold no project the IDE understands.
 * The files are already saved and listed, so the only question left is whether to open them now, and the
 * editor repeats the limitation in its own banner once it does.
 */
@Composable
private fun UnrecognizedCloneNotice(name: String, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(Ide.colors.warning.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CaIcons.warning, null, Modifier.size(16.dp), tint = Ide.colors.warning)
            Text(
                stringResource(Res.string.vcs_clone_unrecognized_title),
                style = MaterialTheme.typography.bodyMedium,
                color = Ide.colors.warning,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            stringResource(Res.string.vcs_clone_unrecognized_body, name),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onDismiss) { Text(stringResource(Res.string.vcs_cancel), style = MaterialTheme.typography.labelLarge) }
            PrimaryButton(stringResource(Res.string.vcs_clone_unrecognized_open), onOpen, icon = CaIcons.folder)
        }
    }
}

/**
 * Said in place when a clone is refused for want of credentials. Git sign-in is its own account, kept apart
 * from the Projects Store sign-in, and a user signed in to one reasonably expects the other to follow, so the
 * notice says which one a clone uses and offers the way to add it.
 */
@Composable
private fun CloneAuthNotice(signedIn: Boolean, onDismiss: () -> Unit, onSignIn: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(scheme.errorContainer.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CaIcons.account, null, Modifier.size(16.dp), tint = scheme.error)
            Text(
                stringResource(Res.string.vcs_clone_auth_title),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            stringResource(if (signedIn) Res.string.vcs_clone_auth_signed_in else Res.string.vcs_clone_auth_signed_out),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onDismiss) { Text(stringResource(Res.string.vcs_cancel), style = MaterialTheme.typography.labelLarge) }
            PrimaryButton(
                stringResource(if (signedIn) Res.string.vcs_accounts else Res.string.vcs_sign_in_github),
                onSignIn,
                icon = CaIcons.account,
            )
        }
    }
}

@Composable
private fun RepoRow(repo: UiForgeRepo, onPick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onPick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CaIcons.folder, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                repo.fullName,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (repo.description.isNotBlank()) {
                Text(
                    repo.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (repo.private) Chip(stringResource(Res.string.vcs_private))
        else if (repo.fork) Chip(stringResource(Res.string.vcs_fork))
    }
}

private const val SEARCH_DEBOUNCE_MS = 250L
