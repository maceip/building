package dev.ide.ui.screens

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.ide.ui.editor.preview.decodeImageBytes
import dev.ide.ui.platform.touchInput
import dev.ide.ui.platform.PlatformBackHandler
import dev.ide.ui.generated.resources.home_account
import dev.ide.ui.generated.resources.home_search_projects
import dev.ide.ui.generated.resources.home_no_match
import dev.ide.ui.generated.resources.home_sort
import dev.ide.ui.generated.resources.home_sort_recent
import dev.ide.ui.generated.resources.home_sort_name
import dev.ide.ui.generated.resources.home_clear_search
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.ProjectInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.ide.ui.backend.UiProjectIcon
import dev.ide.ui.editor.preview.ProjectIconRaster
import dev.ide.ui.itemsIndexedKeyed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.ide.ui.components.CountFilterChip
import dev.ide.ui.components.dashedBorder
import dev.ide.ui.components.Eyebrow
import dev.ide.ui.components.MonoChip
import dev.ide.ui.components.RareFolder
import dev.ide.ui.components.SquareToneButton
import dev.ide.ui.components.SupportingOnContainer
import dev.ide.ui.components.TonalTile
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.components.WatermarkGlyph
import dev.ide.ui.components.pressScale
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.home_account_title
import dev.ide.ui.generated.resources.home_backup
import dev.ide.ui.generated.resources.home_clone_repository
import dev.ide.ui.generated.resources.home_delete
import dev.ide.ui.generated.resources.home_dismiss
import dev.ide.ui.generated.resources.home_feedback
import dev.ide.ui.generated.resources.home_import_project
import dev.ide.ui.generated.resources.home_legacy_recovery
import dev.ide.ui.generated.resources.home_open_in_files
import dev.ide.ui.generated.resources.home_settings_tools
import dev.ide.ui.generated.resources.home_share
import dev.ide.ui.generated.resources.home_sponsor
import dev.ide.ui.generated.resources.home_star_github
import dev.ide.ui.generated.resources.join_the_community
import dev.ide.ui.generated.resources.join_the_community_content
import dev.ide.ui.generated.resources.project_opened_days
import dev.ide.ui.generated.resources.project_opened_hours
import dev.ide.ui.generated.resources.project_age_just_now
import dev.ide.ui.generated.resources.project_age_minutes
import dev.ide.ui.generated.resources.project_age_hours
import dev.ide.ui.generated.resources.project_age_days
import dev.ide.ui.generated.resources.project_age_weeks
import dev.ide.ui.generated.resources.project_opened_just_now
import dev.ide.ui.generated.resources.project_opened_minutes
import dev.ide.ui.generated.resources.project_opened_weeks
import dev.ide.ui.generated.resources.home_empty_list
import dev.ide.ui.generated.resources.home_good_afternoon
import dev.ide.ui.generated.resources.home_good_evening
import dev.ide.ui.generated.resources.home_good_morning
import dev.ide.ui.generated.resources.home_new_project
import dev.ide.ui.generated.resources.home_open
import dev.ide.ui.generated.resources.home_open_named
import dev.ide.ui.generated.resources.home_resume_title
import dev.ide.ui.generated.resources.home_seg_projects
import dev.ide.ui.generated.resources.home_seg_saved
import dev.ide.ui.generated.resources.home_seg_updates
import dev.ide.ui.generated.resources.home_your_projects
import dev.ide.ui.generated.resources.import_gradle_subtitle
import dev.ide.ui.generated.resources.import_gradle_title
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.platform.localHourOfDay
import dev.ide.ui.platform.nowMillis
import dev.ide.ui.theme.CaShapes
import dev.ide.ui.theme.Symbol
import dev.ide.ui.theme.cardShape
import dev.ide.ui.theme.tileShape
import dev.ide.ui.theme.tonalPair
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import dev.ide.ui.generated.resources.home_cancel
import dev.ide.ui.generated.resources.home_delete_confirm_action
import dev.ide.ui.generated.resources.home_delete_confirm_body
import dev.ide.ui.generated.resources.home_delete_confirm_title
import dev.ide.ui.generated.resources.home_more_actions
import org.jetbrains.compose.resources.stringResource

/** Which list the Home screen's segment chips are showing. */
enum class HomeSegment { Projects, Updates, Saved }

/**
 * The home screen's **Projects** tab: the project manager.
 *
 * Replaces the older project picker. The layout is the Material 3 Expressive design: a time-of-day
 * eyebrow over a display-weight title, a 44 dp account tile, the asymmetric New-project / Clone pair,
 * segment chips carrying counts, the project cards, and a Resume card at the foot.
 *
 * Two segments are structurally present but empty until their backends land: **Updates** needs installed
 * items compared against the remote catalog's versions, and **Saved** needs store favorites. They render
 * the same dashed empty state a user with no projects sees, rather than being hidden — the chips carry
 * their real counts, so a zero is a fact rather than a missing feature.
 *
 * Everything the old picker reached that this design has no slot for (Settings & Tools, backup, storage
 * location, the community links) moved into the account sheet behind the avatar tile; import and clone
 * moved into the New-project sheet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProjectsHomeScreen(
    projects: List<ProjectInfo>,
    onOpen: (ProjectInfo) -> Unit,
    onNewProject: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteProject: ((ProjectInfo) -> Unit)? = null,
    onImportProject: (() -> Unit)? = null,
    onCloneRepository: (() -> Unit)? = null,
    onExportProject: ((ProjectInfo) -> Unit)? = null,
    onBackup: (() -> Unit)? = null,
    onOpenHub: (() -> Unit)? = null,
    onSubmitSuggestions: (() -> Unit)? = null,
    onJoinDiscord: (() -> Unit)? = null,
    onSponsor: (() -> Unit)? = null,
    onStarOnGitHub: (() -> Unit)? = null,
    storagePath: String? = null,
    onOpenInFiles: (() -> Unit)? = null,
    showLegacyRecovery: Boolean = false,
    onDismissLegacyRecovery: () -> Unit = {},
    /** Re-read the project list from disk. Null hides the pull-to-refresh gesture entirely. */
    onRefresh: (() -> Unit)? = null,
    /**
     * The notification bell for the header. Passed in rather than built here so this screen keeps taking
     * plain data and stays renderable in a snapshot test with no backend.
     */
    bell: (@Composable () -> Unit)? = null,
    /**
     * The project's launcher icon, when the host can resolve one. Null keeps the glyph tiles, which is
     * what a snapshot test with no backend renders.
     */
    loadIcon: (suspend (ProjectInfo) -> UiProjectIcon?)? = null,
    /**
     * Reads an image file the icon references, by absolute path.
     *
     * Needed alongside [loadIcon] rather than folded into it: an adaptive icon's foreground is very often
     * a `<bitmap>`, and without this those layers render as a placeholder instead of the artwork.
     */
    loadIconImage: (suspend (String) -> ByteArray?)? = null,
    /**
     * Set by the wide home's sidebar to open the account sheet here (it lives on this tab); the screen
     * opens it and calls [onAccountRequestHandled] so the request is consumed once.
     */
    accountRequested: Boolean = false,
    onAccountRequestHandled: () -> Unit = {},
    backend: dev.ide.ui.backend.IdeBackend? = null,
    onCloneAndOpen: ((htmlUrl: String, repoName: String) -> Unit)? = null,
    /**
     * Opens the redesigned Agent settings destination. Null hides the agent icon on the phone home.
     * The host routes this to `openPluginScreen("agent.settings")`.
     */
    onOpenAgentSettings: (() -> Unit)? = null,
    /** Clone a Git repo from the New-project sheet (host opens the VCS clone screen). */
    onCloneGit: (() -> Unit)? = null,
    /** Import a `.caproj` package from the New-project sheet. */
    onImportPackage: (() -> Unit)? = null,
) {
    var segment by remember { mutableStateOf(HomeSegment.Projects) }
    // Deleting wipes the project from disk and nothing else in this flow asks first.
    var pendingDelete by remember { mutableStateOf<ProjectInfo?>(null) }
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }
    // Phone agent-first Home: the prompt hero is primary; "All projects" reveals the existing list.
    var showAllProjects by remember { mutableStateOf(false) }
    var agentPrompt by remember { mutableStateOf("") }
    var newProjectPrompt by remember { mutableStateOf("") }
    var selectedStack by remember { mutableIntStateOf(0) }
    val now = remember(projects) { nowMillis() }
    val ordered = remember(projects) { projects.sortedByDescending { it.lastOpened } }
    // Rendered icons by project root. Held for the screen rather than per card: a card is recomposed every
    // time it scrolls back into view, and resolving an icon means parsing a manifest and a drawable.
    val icons = remember { ProjectIconCache() }
    val mostRecent = ordered.firstOrNull { it.lastOpened > 0L }

    // Pull-to-refresh re-reads the project list. The read is synchronous, so the spinner is held up for a
    // short beat afterwards purely so the gesture registers as having done something.
    val refreshScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val refreshState = rememberPullToRefreshState()

    LaunchedEffect(accountRequested) {
        if (accountRequested) {
            sheet = HomeSheet.Account
            onAccountRequestHandled()
        }
    }

    PlatformBackHandler(enabled = sheet != null) { sheet = null }
    PlatformBackHandler(enabled = pendingDelete != null) { pendingDelete = null }
    // Back from the classic list returns to the agent-first Home rather than leaving the tab.
    PlatformBackHandler(enabled = showAllProjects) { showAllProjects = false }

    BoxWithConstraints(modifier.fillMaxSize()) {
    if (maxWidth >= ProjectsWideBreakpoint) {
        WideProjectsHome(
            projects = ordered,
            now = now,
            icons = icons,
            loadIcon = loadIcon,
            loadIconImage = loadIconImage,
            onOpen = onOpen,
            onNewProject = { sheet = HomeSheet.NewProject },
            onImportProject = onImportProject,
            onCloneRepository = onCloneRepository,
            onExportProject = onExportProject,
            onRequestDelete = onDeleteProject?.let { { project: ProjectInfo -> pendingDelete = project } },
            showLegacyRecovery = showLegacyRecovery,
            onDismissLegacyRecovery = onDismissLegacyRecovery,
            bell = bell,
            onOpenHub = onOpenHub,
            onOpenAccount = { sheet = HomeSheet.Account },
            onJoinDiscord = onJoinDiscord,
            backend = backend,
            onCloneAndOpen = onCloneAndOpen,
        )
    } else if (!showAllProjects) {
        // Agent-first Home (phone width): prompt hero + Continue, with the classic project list one tap
        // away via "All projects". New / Start opens the redesigned bottom sheet, which hands off to the
        // existing create-project gallery.
        val agentCfg = remember(backend) { backend?.agent?.config() }
        val activeProvider = remember(agentCfg) {
            agentCfg?.providers?.firstOrNull { it.id == agentCfg.selectedProvider }
        }
        val activeProviderLabel = remember(agentCfg, activeProvider) {
            when {
                agentCfg == null || !agentCfg.configured -> "Configure AI"
                activeProvider != null -> {
                    val modelId = agentCfg.model.ifBlank { activeProvider.defaultModel }
                    if (modelId.isNotBlank()) "${activeProvider.displayName} · $modelId" else activeProvider.displayName
                }
                else -> "Configure AI"
            }
        }
        val visibleProjects = remember(ordered) { ordered.take(5) }
        val continueItems = visibleProjects.mapIndexed { idx, p ->
            val stackLabel = when {
                p.isSwift -> "Swift & iOS"
                p.isPython -> "Python"
                p.isNode -> "Node.js"
                p.isAndroid -> "Android"
                p.compatibility -> "Gradle"
                p.unrecognized -> "Files"
                else -> "building"
            }
            val modulesLabel = if (p.moduleCount == 1) "1 module" else "${p.moduleCount} modules"
            val avatarImg = projectIconImage(p, icons, loadIcon, loadIconImage, backend)
            AgentContinueItem(
                id = p.rootPath,
                title = p.name,
                meta = (listOf(stackLabel, modulesLabel) + p.tags).joinToString(" · "),
                time = "",
                letter = p.name.firstOrNull()?.uppercase() ?: "?",
                needsAttention = false,
                stackLabel = stackLabel,
                stackColor = projectStackColor(
                    isAndroid = p.isAndroid,
                    isNode = p.isNode,
                    isPython = p.isPython,
                    isSwift = p.isSwift,
                    compatibility = p.compatibility,
                ),
                modulesLabel = modulesLabel,
                tags = p.tags,
                avatar = avatarImg,
                shimmerSubtitle = idx == 0,
            )
        }
        AgentHomeContent(
            appName = "building",
            prompt = agentPrompt,
            onPromptChange = { agentPrompt = it },
            onStart = {
                if (agentPrompt.isNotBlank()) {
                    // Carry the description into the New-project sheet so Start building keeps it.
                    newProjectPrompt = agentPrompt
                }
                sheet = HomeSheet.NewProject
            },
            onOpenAgentSettings = onOpenAgentSettings ?: {},
            onOpenAccount = { sheet = HomeSheet.Account },
            onNewProject = { sheet = HomeSheet.NewProject },
            onImportOrClone = {
                when {
                    onCloneGit != null -> onCloneGit()
                    onImportProject != null -> onImportProject()
                }
            },
            onDeviceLabel = activeProviderLabel,
            providerConfigured = agentCfg?.configured == true,
            continueItems = continueItems,
            allProjectsLabel = "All projects",
            onOpenItem = { item -> ordered.firstOrNull { it.rootPath == item.id }?.let(onOpen) },
            onSeeAllProjects = { showAllProjects = true },
            modifier = Modifier.fillMaxSize(),
        )
    } else
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
      PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            if (onRefresh != null) {
                refreshScope.launch {
                    isRefreshing = true
                    // A refresh is also how an icon changed in the app-icon studio gets picked up here.
                    icons.clear()
                    onRefresh()
                    delay(600)
                    isRefreshing = false
                }
            }
        },
        state = refreshState,
        indicator = {
            // The design's own indicator: a filled primaryContainer disc with a sync glyph, rather than
            // the default arrow-in-a-circle, so the gesture matches the rest of the screen's tonal language.
            PullToRefreshDefaults.Indicator(
                state = refreshState,
                isRefreshing = isRefreshing,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
      ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            item("header") {
                HomeHeader(
                    onOpenAccount = { sheet = HomeSheet.Account },
                    onOpenHub = onOpenHub,
                    bell = bell,
                )
            }
            if (onImportProject != null) {
                item("actions") {
                    HomeActions(
                        onImportProject = onImportProject,
                    )
                }
            }
            item("segments") {
                SegmentRow(
                    segment = segment,
                    projectCount = ordered.size,
                    // Both are 0 until the store backend lands; see the KDoc above.
                    updateCount = 0,
                    savedCount = 0,
                    onSelect = { segment = it },
                )
            }

            if (segment == HomeSegment.Projects && ordered.isNotEmpty()) {
                itemsIndexedKeyed(ordered, key = { _, p -> p.rootPath }) { i, project ->
                    LocalProjectCard(
                        project = project,
                        index = i,
                        now = now,
                        icon = projectIconImage(project, icons, loadIcon, loadIconImage, backend),
                        onOpen = { onOpen(project) },
                        onSecondary = onExportProject?.let { export -> { export(project) } }
                            ?: onDeleteProject?.let { del -> { del(project) } },
                        secondaryIsDelete = onExportProject == null && onDeleteProject != null,
                        // Only when export took the visible slot; otherwise delete IS the secondary button.
                        onDelete = if (onExportProject != null && onDeleteProject != null) {
                            { pendingDelete = project }
                        } else {
                            null
                        },
                    )
                }
            } else {
                item("empty") { EmptyList() }
            }
        }
      }
    }
    }

    when (sheet) {
        HomeSheet.NewProject -> NewProjectSheet(
            prompt = newProjectPrompt,
            onPromptChange = { newProjectPrompt = it },
            onStartBuilding = {
                sheet = null
                // Existing create-project gallery owns the template picker; the prompt rides along in the
                // sheet state so the user can copy it into the agent once a project is open.
                onNewProject()
            },
            onDismiss = { sheet = null },
            selectedStack = selectedStack,
            onSelectStack = { selectedStack = it },
            onCloneGit = onCloneGit?.let { { sheet = null; it() } },
            onOpenFolder = onImportProject?.let { { sheet = null; it() } },
            onImportPackage = onImportPackage?.let { { sheet = null; it() } }
                ?: onImportProject?.let { { sheet = null; it() } },
        )
        HomeSheet.Account -> AccountSheet(
            onDismiss = { sheet = null },
            storagePath = storagePath,
            onOpenInFiles = onOpenInFiles?.let { { sheet = null; it() } },
            onBackup = onBackup?.let { { sheet = null; it() } },
            onSubmitSuggestions = onSubmitSuggestions?.let { { sheet = null; it() } },
            onJoinDiscord = onJoinDiscord?.let { { sheet = null; it() } },
            onSponsor = onSponsor?.let { { sheet = null; it() } },
            onStarOnGitHub = onStarOnGitHub?.let { { sheet = null; it() } },
        )
        null -> Unit
    }

    // Deleting is irreversible and removes files from disk, so it asks. The project's name is in the
    // question rather than a generic "this project", because the wrong one is the mistake worth preventing.
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(Res.string.home_delete_confirm_title, target.name)) },
            text = { Text(stringResource(Res.string.home_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteProject?.invoke(target)
                    },
                ) {
                    Text(
                        stringResource(Res.string.home_delete_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(Res.string.home_cancel))
                }
            },
        )
    }
}

private enum class HomeSheet { Account, NewProject }

@Composable
private fun HomeHeader(
    onOpenAccount: () -> Unit,
    onOpenHub: (() -> Unit)? = null,
    /** The notification bell, supplied by the host so this screen needs no backend of its own. */
    bell: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(Res.string.home_your_projects),
            style = MaterialTheme.typography.titleLarge,
            color = c.onSurface,
            modifier = Modifier.weight(1f),
        )
        bell?.invoke()
        if (onOpenHub != null) {
            SquareToneButton(
                glyph = CaSymbols.settings,
                contentDescription = stringResource(Res.string.home_settings_tools),
                onClick = onOpenHub,
                shape = CircleShape,
                size = 40.dp,
            )
        }
        SquareToneButton(
            glyph = CaSymbols.accountCircle,
            contentDescription = stringResource(Res.string.home_account),
            onClick = onOpenAccount,
            shape = CircleShape,
            size = 40.dp,
        )
    }
}

/**
 * Primary project creation actions: New Project alongside direct 1-tap Clone (Git / GitHub) and Import
 * affordances so users never have to hunt inside a hidden overflow menu to clone from GitHub.
 */
@Composable
private fun HomeActions(
    onImportProject: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeaderButton(
            glyph = CaSymbols.folderOpen,
            label = stringResource(Res.string.home_import_project),
            primary = false,
            modifier = Modifier.weight(1f),
            onClick = onImportProject,
        )
    }
}

@Composable
private fun SegmentRow(
    segment: HomeSegment,
    projectCount: Int,
    updateCount: Int,
    savedCount: Int,
    onSelect: (HomeSegment) -> Unit,
) {
    // When Updates and Saved are empty and the user is on Projects, skip the redundant single-chip filter bar.
    if (updateCount == 0 && savedCount == 0 && segment == HomeSegment.Projects) {
        Spacer(Modifier.height(6.dp))
        return
    }
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("projects") {
            CountFilterChip(
                label = stringResource(Res.string.home_seg_projects),
                count = projectCount,
                selected = segment == HomeSegment.Projects,
                onClick = { onSelect(HomeSegment.Projects) },
            )
        }
        if (updateCount > 0 || segment == HomeSegment.Updates) {
            item("updates") {
                CountFilterChip(
                    label = stringResource(Res.string.home_seg_updates),
                    count = updateCount,
                    selected = segment == HomeSegment.Updates,
                    onClick = { onSelect(HomeSegment.Updates) },
                )
            }
        }
        if (savedCount > 0 || segment == HomeSegment.Saved) {
            item("saved") {
                CountFilterChip(
                    label = stringResource(Res.string.home_seg_saved),
                    count = savedCount,
                    selected = segment == HomeSegment.Saved,
                    onClick = { onSelect(HomeSegment.Saved) },
                )
            }
        }
    }
}

/**
 * The home list's rendered project icons, keyed by project root.
 *
 * Kept for the whole screen rather than per card, because a card is recomposed every time it scrolls back
 * into view and resolving an icon means reading a manifest and parsing a drawable.
 *
 * [generation] is why this is a class and not a bare map. Emptying the cache has to reach the cards that
 * are *currently composed*, and those cards only re-resolve if something they key on changes — so the
 * counter is part of the same operation as the clear, and a caller cannot do one without the other. When
 * [clear] only emptied a map, a refresh blanked every icon on screen and each one came back alone, later,
 * when a scroll had taken its card out of composition and brought it back.
 */
@Stable
internal class ProjectIconCache {
    private val byRoot = mutableStateMapOf<String, ImageBitmap?>()

    /** Bumped by [clear]; a card's loader keys on it, so a cleared cache re-resolves what is on screen. */
    var generation by mutableIntStateOf(0)
        private set

    /** The rendered icon for [rootPath]: null both while it is being resolved and when it has none. */
    operator fun get(rootPath: String): ImageBitmap? = byRoot[rootPath]

    /** Whether [rootPath] has an answer already — including the answer "this project has no icon". */
    fun isResolved(rootPath: String): Boolean = byRoot.containsKey(rootPath)

    /** Record [rootPath]'s icon, [image] being null for a project that has none. */
    fun put(rootPath: String, image: ImageBitmap?) {
        byRoot[rootPath] = image
    }

    /** Drop every rendered icon and open a new generation, so composed cards resolve theirs again. */
    fun clear() {
        byRoot.clear()
        generation++
    }
}

/**
 * [project]'s launcher icon, rendered once and remembered in [cache].
 *
 * Null until it has been resolved, and null forever for a project that has no icon to show, which is the
 * same answer the card needs: draw the glyph tile. The work is off the main thread because resolving one
 * means reading a manifest and parsing a drawable, and this runs for every row in the list.
 */
@Composable
private fun projectIconImage(
    project: ProjectInfo,
    cache: ProjectIconCache,
    loadIcon: (suspend (ProjectInfo) -> UiProjectIcon?)?,
    loadImage: (suspend (String) -> ByteArray?)?,
    backend: dev.ide.ui.backend.IdeBackend? = null,
): ImageBitmap? {
    if (loadIcon == null && (project.ownerAvatarUrl == null || backend == null)) return null
    // Keyed on the cache's generation as well as the project: a refresh empties the cache, and this is what
    // makes a card that never left the screen ask for its icon again instead of sitting blank.
    LaunchedEffect(project.rootPath, project.ownerAvatarUrl, cache.generation, loadIcon, backend) {
        if (cache.isResolved(project.rootPath)) return@LaunchedEffect
        // Recorded even when it is null, so a project with no icon is asked about once rather than on
        // every scroll.
        cache.put(
            project.rootPath,
            runCatching {
                val avatarUrl = project.ownerAvatarUrl
                if (avatarUrl != null && backend != null) {
                    val file = runCatching { backend.store.avatarFile(avatarUrl) }.getOrNull()
                    val bytes = file?.let { runCatching { backend.projects.imageBytes(it) }.getOrNull() }
                    val decoded = bytes?.let { withContext(Dispatchers.Default) { decodeImageBytes(it) } }
                    if (decoded != null) return@runCatching decoded
                }
                val resolved = loadIcon?.invoke(project) ?: return@runCatching null
                withContext(Dispatchers.Default) {
                    ProjectIconRaster.toImage(resolved, TILE_ICON_PIXELS) { path -> loadImage?.invoke(path) }
                }
            }.getOrNull(),
        )
    }
    return cache[project.rootPath]
}

/** Comfortably above a 46 dp tile on a 3x screen, and cheap to render at. */
private const val TILE_ICON_PIXELS = 192

/**
 * One local project row — flatter, uniform card shape, compact vertical padding so more projects fit on screen.
 */
@Composable
private fun LocalProjectCard(
    project: ProjectInfo,
    index: Int,
    now: Long,
    /** The project's own launcher icon, or null to draw the glyph tile it has always drawn. */
    icon: ImageBitmap? = null,
    onOpen: () -> Unit,
    onSecondary: (() -> Unit)?,
    secondaryIsDelete: Boolean,
    /**
     * Deleting the project. Its own control rather than sharing the secondary slot: the card had one slot
     * and export always filled it, so delete was unreachable — there was no way to remove a project from
     * the app at all.
     */
    onDelete: (() -> Unit)? = null,
) {
    var menuOpen by remember(project.rootPath) { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    val pair = tonalPair(index)
    val glyph = symbolForProject(project)
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onOpen,
        shape = cardShape(index),
        color = c.surfaceContainerLow,
        contentColor = c.onSurface,
        border = BorderStroke(1.dp, c.outlineVariant),
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp)
            .pressScale(interaction),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Box(
                    Modifier.size(38.dp).clip(tileShape(index)).background(pair.container),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            } else {
                TonalTile(glyph, pair, tileShape(index), size = 38.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val stackName = kindLabel(project) ?: when {
                    project.isSwift -> "Swift & iOS"
                    project.isPython -> "Python"
                    project.isNode -> "Node.js"
                    project.isAndroid -> "Android"
                    else -> "building"
                }
                val stackColor = projectStackColor(
                    isAndroid = project.isAndroid,
                    isNode = project.isNode,
                    isPython = project.isPython,
                    isSwift = project.isSwift,
                    compatibility = project.compatibility,
                )
                val shimmer = index == 0
                val stackBrush = if (shimmer) rememberTextShimmerBrush(stackColor) else null
                val metaBrush = if (shimmer) rememberTextShimmerBrush(c.onSurfaceVariant) else null
                val tailParts = buildList {
                    add(moduleLabel(project.moduleCount))
                    addAll(project.tags.filter { it.isNotBlank() })
                    relativeAge(project.lastOpened, now)?.let { add(it) }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 1.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                if (stackBrush != null) SpanStyle(brush = stackBrush, fontWeight = FontWeight.Medium)
                                else SpanStyle(color = stackColor, fontWeight = FontWeight.Medium),
                            ) {
                                append(stackName)
                            }
                            if (tailParts.isNotEmpty()) {
                                withStyle(
                                    if (metaBrush != null) SpanStyle(brush = metaBrush)
                                    else SpanStyle(color = c.onSurfaceVariant),
                                ) {
                                    append(" · ")
                                    append(tailParts.joinToString(" · "))
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            // Everything that is not "open this project" lives behind one control. Tapping the row opens.
            if (onDelete != null || onSecondary != null) {
                Box {
                    IconButtonCa(
                        CaIcons.ellipsis,
                        stringResource(Res.string.home_more_actions),
                        onClick = { menuOpen = true },
                        iconSize = 18,
                        boxSize = 36,
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onSecondary != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (secondaryIsDelete) Res.string.home_delete else Res.string.home_share,
                                        ),
                                        color = if (secondaryIsDelete) c.error else c.onSurface,
                                    )
                                },
                                onClick = { menuOpen = false; onSecondary() },
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.home_delete), color = c.error) },
                                onClick = { menuOpen = false; onDelete() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The import route, back on the surface.
 *
 * The design has no slot for it, but it is the only way into an existing **CodeAssist folder or Gradle
 * build**, and burying that a sheet deep made the second-most-common way to start invisible. Styled as a
 * tonal row so it reads as a secondary action beside the New-project button, not a third project card.
 */
@Composable
private fun ImportCard(onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 26.dp, bottomEnd = 12.dp, bottomStart = 26.dp),
        color = c.surfaceContainerLow,
        contentColor = c.onSurface,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp)
            .pressScale(interaction),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(44.dp).background(c.surfaceContainerHighest, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(CaSymbols.folderOpen, contentDescription = null, size = 22.dp, tint = c.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.import_gradle_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.onSurface,
                )
                Text(
                    stringResource(Res.string.import_gradle_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
            }
            Symbol(CaSymbols.chevronRight, contentDescription = null, size = 20.dp, tint = c.onSurfaceVariant)
        }
    }
}

/** The dashed empty state: shown for an empty Projects list and for the two not-yet-backed segments. */
@Composable
private fun EmptyList() {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var tappedOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp)
            .dashedBorder(c.outlineVariant, cornerRadius = 12.dp)
            .clickable(interaction, indication = null) { tappedOpen = !tappedOpen }
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RareFolder(
            expanded = hovered || tappedOpen,
            modifier = Modifier.size(96.dp, 88.dp),
        )
        Text(
            stringResource(Res.string.home_empty_list),
            style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * "Pick up where you left off".
 *
 * Names the most recently opened project rather than a file: which file was last open is persisted per
 * project and only readable once a project is loaded, so the Home screen genuinely does not know it yet.
 */
@Composable
private fun ResumeCard(project: ProjectInfo, now: Long, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = c.tertiaryContainer,
        contentColor = c.onTertiaryContainer,
        border = BorderStroke(1.dp, c.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Symbol(CaSymbols.bolt, contentDescription = null, size = 22.dp, tint = c.onTertiaryContainer)
                Text(
                    stringResource(Res.string.home_resume_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.onTertiaryContainer,
                )
            }
            SupportingOnContainer(
                text = listOfNotNull(
                    project.name,
                    moduleLabel(project.moduleCount),
                    relativeOpened(project.lastOpened, now),
                ).joinToString(" · "),
                onContainer = c.onTertiaryContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            // Inverted against the card: the container's on-colour becomes the button's background. It is
            // the only fully saturated element here, so it reads as the single next action.
            Surface(
                onClick = onOpen,
                shape = CircleShape,
                color = c.onTertiaryContainer,
                contentColor = c.tertiaryContainer,
                modifier = Modifier.padding(top = 14.dp).height(40.dp),
            ) {
                Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(Res.string.home_open_named, project.name),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---- sheets ----

/**
 * The redesigned "Start something new" bottom sheet. Hosts [NewProjectSheetContent] inside a modal sheet so
 * both iOS and Android pick up the same New / Start flow; Start building hands off to the existing
 * create-project gallery via [onStartBuilding].
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NewProjectSheet(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onStartBuilding: () -> Unit,
    onDismiss: () -> Unit,
    selectedStack: Int = 0,
    onSelectStack: (Int) -> Unit = {},
    onCloneGit: (() -> Unit)? = null,
    onOpenFolder: (() -> Unit)? = null,
    onImportPackage: (() -> Unit)? = null,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = CaShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        NewProjectSheetContent(
            prompt = prompt,
            onPromptChange = onPromptChange,
            onStartBuilding = onStartBuilding,
            onClose = onDismiss,
            selectedStack = selectedStack,
            onSelectStack = onSelectStack,
            onCloneGit = onCloneGit ?: {},
            onOpenFolder = onOpenFolder ?: {},
            onImportPackage = onImportPackage ?: {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AccountSheet(
    onDismiss: () -> Unit,
    storagePath: String?,
    onOpenInFiles: (() -> Unit)?,
    onBackup: (() -> Unit)?,
    onSubmitSuggestions: (() -> Unit)?,
    onJoinDiscord: (() -> Unit)?,
    onSponsor: (() -> Unit)?,
    onStarOnGitHub: (() -> Unit)?,
) {
    SheetScaffold(onDismiss, stringResource(Res.string.home_account_title), subtitle = storagePath) {
        if (onOpenInFiles != null) SheetAction(CaSymbols.folderOpen, stringResource(Res.string.home_open_in_files), onOpenInFiles)
        if (onBackup != null) SheetAction(CaSymbols.storage, stringResource(Res.string.home_backup), onBackup)
        if (onSubmitSuggestions != null) SheetAction(CaSymbols.rateReview, stringResource(Res.string.home_feedback), onSubmitSuggestions)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SheetScaffold(
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    /** The account sheet's subtitle is a filesystem path and reads as monospace; prose does not. */
    subtitleMono: Boolean = true,
    content: @Composable () -> Unit,
) {
    val state = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = CaShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = if (subtitleMono) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = dev.ide.ui.theme.Ca.type.codeFamily)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}

/** One row in the account sheet: a tonal disc holding the glyph, then the label. */
@Composable
private fun SheetAction(glyph: Char, label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = c.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(c.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(glyph, contentDescription = null, size = 20.dp, tint = c.onSurfaceVariant)
            }
            Text(label, style = MaterialTheme.typography.titleSmall, color = c.onSurface)
        }
    }
}

@Composable
private fun LegacyRecoveryNotice(onDismiss: () -> Unit) {
    // Intentionally no-op: legacy recovery banner removed permanently.
}

/** Discord's brand colour, used only for the community card's tile and edge. */
private val DiscordBlurple = Color(0xFF5865F2)

/** The invitation to the community Discord, kept in sight on the home rather than only in the account sheet. */
@Composable
private fun CommunityCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = c.surfaceContainerLow,
        contentColor = c.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DiscordBlurple.copy(alpha = 0.35f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(44.dp).background(DiscordBlurple, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(CaIcons.discord, contentDescription = null, modifier = Modifier.size(24.dp), tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.join_the_community), style = MaterialTheme.typography.titleMedium, color = c.onSurface)
                Text(
                    stringResource(Res.string.join_the_community_content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                )
            }
            Symbol(CaSymbols.chevronRight, contentDescription = null, size = 22.dp, tint = c.onSurfaceVariant)
        }
    }
}

// ---- helpers ----

@Composable
private fun greeting(): String = when (localHourOfDay()) {
    in 0..11 -> stringResource(Res.string.home_good_morning)
    in 12..17 -> stringResource(Res.string.home_good_afternoon)
    else -> stringResource(Res.string.home_good_evening)
}

private fun symbolForProject(project: ProjectInfo): Char = when {
    project.isSwift -> CaSymbols.phoneAndroid
    project.isPython -> CaSymbols.codeBlocks
    project.isNode -> CaSymbols.terminal
    project.isAndroid -> CaSymbols.phoneAndroid
    project.compatibility -> CaSymbols.construction
    project.unrecognized -> CaSymbols.folder
    project.moduleCount > 1 -> CaSymbols.hub
    else -> CaSymbols.folder
}

private fun kindLabel(project: ProjectInfo): String = when {
    project.isSwift -> "Swift & iOS"
    project.isPython -> "Python"
    project.isNode -> "Node.js"
    project.isAndroid -> "Android"
    project.compatibility -> "Gradle"
    // A cloned folder nothing recognized is listed so the user can reach it, but calling it a CodeAssist
    // project would be the same claim that made a clone confusing in the first place.
    project.unrecognized -> "Files"
    else -> "building"
}

private fun moduleLabel(count: Int): String = if (count == 1) "1 module" else "$count modules"

/** Drop the leading path components so the project folder — the identifying part — always survives. */
private fun shortenPath(path: String, maxSegments: Int = 2): String {
    val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
    if (parts.size <= maxSegments) return path
    return "…/" + parts.takeLast(maxSegments).joinToString("/")
}

/**
 * "Opened 2h ago", bucketed to the largest unit that still reads as a duration.
 *
 * Moved here from the project picker this screen replaces; the lesson-track and export screens do not use
 * it, so it stays private to Home.
 */
@Composable
private fun relativeAge(lastOpened: Long, now: Long): String? {
    if (lastOpened <= 0L) return null
    val diff = (now - lastOpened).coerceAtLeast(0L)
    val minutes = (diff / 60_000L).toInt()
    val hours = (diff / 3_600_000L).toInt()
    val days = (diff / 86_400_000L).toInt()
    val weeks = (diff / 604_800_000L).toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.project_age_just_now)
        hours < 1 -> pluralStringResource(Res.plurals.project_age_minutes, minutes, minutes)
        days < 1 -> pluralStringResource(Res.plurals.project_age_hours, hours, hours)
        weeks < 1 -> pluralStringResource(Res.plurals.project_age_days, days, days)
        else -> pluralStringResource(Res.plurals.project_age_weeks, weeks, weeks)
    }
}

@Composable
private fun relativeOpened(lastOpened: Long, now: Long): String? {
    if (lastOpened <= 0L) return null
    val diff = (now - lastOpened).coerceAtLeast(0L)
    val minutes = (diff / 60_000L).toInt()
    val hours = (diff / 3_600_000L).toInt()
    val days = (diff / 86_400_000L).toInt()
    val weeks = (diff / 604_800_000L).toInt()
    return when {
        minutes < 1 -> stringResource(Res.string.project_opened_just_now)
        hours < 1 -> pluralStringResource(Res.plurals.project_opened_minutes, minutes, minutes)
        days < 1 -> pluralStringResource(Res.plurals.project_opened_hours, hours, hours)
        weeks < 1 -> pluralStringResource(Res.plurals.project_opened_days, days, days)
        else -> pluralStringResource(Res.plurals.project_opened_weeks, weeks, weeks)
    }
}

/** From this width the Projects tab is the dense, searchable desktop list rather than the phone's cards. */
private val ProjectsWideBreakpoint = 600.dp

/** The wide list's maximum width: past this, rows only spread their columns further apart. */
private val ProjectsWideMaxWidth = 1120.dp

/** From this width the wide rows also show the kind and module count as a column of their own. */
private val ProjectsKindColumnBreakpoint = 760.dp

private enum class ProjectSort { Recent, Name }

/**
 * The Projects tab on a tablet or desktop window, after IntelliJ's welcome screen: the title with Import,
 * Clone and New project as buttons in the header, a search field and a sort over a dense list of two-line
 * rows (name over path), and the per-project actions on hover. There is no resume card: the list is sorted
 * by last opened, so its first row is already the project to continue. The inbox and account buttons stay
 * in the header only when no home sidebar is offering them ([LocalHomeSidebarShown]).
 */
@Composable
private fun WideProjectsHome(
    projects: List<ProjectInfo>,
    now: Long,
    icons: ProjectIconCache,
    loadIcon: (suspend (ProjectInfo) -> UiProjectIcon?)?,
    loadIconImage: (suspend (String) -> ByteArray?)?,
    onOpen: (ProjectInfo) -> Unit,
    onNewProject: () -> Unit,
    onImportProject: (() -> Unit)?,
    onCloneRepository: (() -> Unit)?,
    onExportProject: ((ProjectInfo) -> Unit)?,
    onRequestDelete: ((ProjectInfo) -> Unit)?,
    showLegacyRecovery: Boolean,
    onDismissLegacyRecovery: () -> Unit,
    bell: (@Composable () -> Unit)?,
    onOpenHub: (() -> Unit)?,
    onOpenAccount: () -> Unit,
    onJoinDiscord: (() -> Unit)?,
    backend: dev.ide.ui.backend.IdeBackend? = null,
    onCloneAndOpen: ((String, String) -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ProjectSort.Recent) }
    val shown = remember(projects, query, sort) {
        val q = query.trim()
        projects
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.rootPath.contains(q, ignoreCase = true) }
            .let { list -> if (sort == ProjectSort.Name) list.sortedBy { it.name.lowercase() } else list }
    }
    val sidebarShown = LocalHomeSidebarShown.current
    BoxWithConstraints(Modifier.fillMaxSize().background(c.background)) {
        val showKind = maxWidth >= ProjectsKindColumnBreakpoint
        Column(Modifier.widthIn(max = ProjectsWideMaxWidth).fillMaxSize().padding(horizontal = 28.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(Res.string.home_your_projects),
                    style = MaterialTheme.typography.titleLarge,
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onImportProject != null) {
                    HeaderButton(CaSymbols.folderOpen, stringResource(Res.string.home_import_project), primary = false, onClick = onImportProject)
                }
                if (onOpenHub != null) {
                    SquareToneButton(
                        glyph = CaSymbols.settings,
                        contentDescription = stringResource(Res.string.home_settings_tools),
                        onClick = onOpenHub,
                        shape = CircleShape,
                        size = 40.dp,
                    )
                }
                if (!sidebarShown) {
                    bell?.invoke()
                    SquareToneButton(
                        glyph = CaSymbols.accountCircle,
                        contentDescription = stringResource(Res.string.home_account),
                        onClick = onOpenAccount,
                        shape = CircleShape,
                        size = 40.dp,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProjectSearchField(query, { query = it }, Modifier.weight(1f, fill = false).widthIn(max = 380.dp))
                Spacer(Modifier.weight(0.0001f))
                SortChooser(sort) { sort = it }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (shown.isEmpty()) {
                    item("empty") {
                        if (projects.isEmpty()) {
                            EmptyList()
                        } else {
                            Text(
                                stringResource(Res.string.home_no_match, query.trim()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 24.dp),
                            )
                        }
                    }
                }
                itemsIndexedKeyed(shown, key = { _, p -> p.rootPath }) { i, project ->
                    DenseProjectRow(
                        project = project,
                        index = i,
                        now = now,
                        icon = projectIconImage(project, icons, loadIcon, loadIconImage),
                        showKind = showKind,
                        onOpen = { onOpen(project) },
                        onShare = onExportProject?.let { export -> { export(project) } },
                        onDelete = onRequestDelete?.let { request -> { request(project) } },
                    )
                }
            }
        }
    }
}

/** A compact header button: filled for the primary action, outlined for the others. */
@Composable
private fun HeaderButton(
    glyph: Char,
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val content: @Composable RowScope.() -> Unit = {
        Symbol(glyph, contentDescription = null, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
    }
    val padding = PaddingValues(start = 12.dp, end = 14.dp)
    val shape = RoundedCornerShape(8.dp)
    if (primary) {
        Button(onClick = onClick, shape = shape, contentPadding = padding, modifier = modifier.height(36.dp), content = content)
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = shape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            contentPadding = padding,
            modifier = modifier.height(36.dp),
            content = content,
        )
    }
}

/** The project list's filter: matches a project's name or its folder path, ignoring case. */
@Composable
private fun ProjectSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .height(36.dp)
            .clip(shape)
            .background(c.surfaceContainerLow)
            .border(1.dp, c.outlineVariant, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Symbol(CaSymbols.search, contentDescription = null, size = 16.dp, tint = c.onSurfaceVariant)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
            cursorBrush = SolidColor(c.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(stringResource(Res.string.home_search_projects), style = MaterialTheme.typography.bodyMedium, color = c.outline)
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButtonCa(CaIcons.close, stringResource(Res.string.home_clear_search), { onQuery("") }, iconSize = 14, boxSize = 24)
        }
    }
}

/** A rounded search field: the project list's filter, and the template gallery's. */
@Composable
internal fun SearchPill(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .height(36.dp)
            .clip(shape)
            .background(c.surfaceContainerLow)
            .border(1.dp, c.outlineVariant, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Symbol(CaSymbols.search, contentDescription = null, size = 16.dp, tint = c.onSurfaceVariant)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.onSurface),
            cursorBrush = SolidColor(c.primary),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = c.outline, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButtonCa(CaIcons.close, stringResource(Res.string.home_clear_search), { onQuery("") }, iconSize = 14, boxSize = 24)
        }
    }
}

/** Recent (the default, most recently opened first) or alphabetical. */
@Composable
private fun SortChooser(sort: ProjectSort, onSort: (ProjectSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label: @Composable (ProjectSort) -> String = {
        stringResource(if (it == ProjectSort.Recent) Res.string.home_sort_recent else Res.string.home_sort_name)
    }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Symbol(CaSymbols.sort, contentDescription = stringResource(Res.string.home_sort), size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(label(sort), style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ProjectSort.entries.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = { open = false; onSort(option) })
            }
        }
    }
}

/**
 * One project in the wide list: icon, name over horizontally scrolling colored stack + modules + tags,
 * the last-opened time, and the actions menu.
 */
@Composable
private fun DenseProjectRow(
    project: ProjectInfo,
    index: Int,
    now: Long,
    icon: ImageBitmap?,
    showKind: Boolean,
    onOpen: () -> Unit,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val c = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuOpen by remember(project.rootPath) { mutableStateOf(false) }
    val stackName = kindLabel(project) ?: when {
        project.isSwift -> "Swift & iOS"
        project.isPython -> "Python"
        project.isNode -> "Node.js"
        project.isAndroid -> "Android"
        else -> "building"
    }
    val stackColor = projectStackColor(
        isAndroid = project.isAndroid,
        isNode = project.isNode,
        isPython = project.isPython,
        isSwift = project.isSwift,
        compatibility = project.compatibility,
    )
    val shimmer = index == 0
    val stackBrush = if (shimmer) rememberTextShimmerBrush(stackColor) else null
    val metaBrush = if (shimmer) rememberTextShimmerBrush(c.onSurfaceVariant) else null
    val tailParts = buildList {
        add(moduleLabel(project.moduleCount))
        addAll(project.tags.filter { it.isNotBlank() })
        add(shortenPath(project.rootPath, maxSegments = 4))
    }
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hovered || menuOpen) c.surfaceContainerHigh else Color.Transparent)
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(Modifier.size(34.dp).clip(tileShape(index)).background(tonalPair(index).container)) {
                Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        } else {
            TonalTile(symbolForProject(project), tonalPair(index), tileShape(index), size = 34.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                project.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = c.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            if (stackBrush != null) SpanStyle(brush = stackBrush, fontWeight = FontWeight.Medium)
                            else SpanStyle(color = stackColor, fontWeight = FontWeight.Medium),
                        ) {
                            append(stackName)
                        }
                        if (tailParts.isNotEmpty()) {
                            withStyle(
                                if (metaBrush != null) SpanStyle(brush = metaBrush)
                                else SpanStyle(color = c.onSurfaceVariant),
                            ) {
                                append(" · ")
                                append(tailParts.joinToString(" · "))
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (showKind) {
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        if (stackBrush != null) SpanStyle(brush = stackBrush, fontWeight = FontWeight.Medium)
                        else SpanStyle(color = stackColor, fontWeight = FontWeight.Medium),
                    ) {
                        append(stackName)
                    }
                    withStyle(
                        if (metaBrush != null) SpanStyle(brush = metaBrush)
                        else SpanStyle(color = c.onSurfaceVariant),
                    ) {
                        append(" · ")
                        append(moduleLabel(project.moduleCount))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(170.dp),
            )
        }
        Text(
            relativeAge(project.lastOpened, now).orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = c.outline,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(110.dp),
        )
        if (onShare != null || onDelete != null) {
            Box(Modifier.alpha(if (hovered || menuOpen || touchInput) 1f else 0f)) {
                IconButtonCa(CaIcons.ellipsis, stringResource(Res.string.home_more_actions), { menuOpen = true }, iconSize = 18, boxSize = 36)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (onShare != null) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.home_share)) }, onClick = { menuOpen = false; onShare() })
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.home_delete), color = c.error) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.width(36.dp))
        }
    }
}
