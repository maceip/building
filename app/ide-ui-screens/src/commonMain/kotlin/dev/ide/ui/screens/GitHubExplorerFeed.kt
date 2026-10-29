package dev.ide.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.SUPPORTED_PHONE_LANGUAGES
import dev.ide.ui.backend.UiDiscoveredRepo
import dev.ide.ui.backend.UiDiscoveryFeed
import dev.ide.ui.backend.UiGitHubAuthState
import dev.ide.ui.backend.UiNorthStarBenchmark
import dev.ide.ui.backend.UiPeerProject
import dev.ide.ui.backend.UiScreenshotSourceTier
import dev.ide.ui.backend.UiTrendingPeriod
import dev.ide.ui.components.SquareToneButton
import dev.ide.ui.components.pressScale
import dev.ide.ui.editor.preview.decodeImageBytes
import dev.ide.ui.icons.CaSymbols
import dev.ide.ui.platform.rememberIdeHaptics
import dev.ide.ui.theme.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ExplorerCategory(
    val id: String,
    val label: String,
    val topic: String,
    val symbol: Char,
    val isLanguageFilter: Boolean = true
)

private val EXPLORER_CATEGORIES = listOf(
    ExplorerCategory("all", "All Languages", "all", CaSymbols.explore, isLanguageFilter = true),
    ExplorerCategory("kotlin", "Kotlin", "kotlin", CaSymbols.phoneAndroid, isLanguageFilter = true),
    ExplorerCategory("swift", "Swift & iOS", "swift", CaSymbols.phoneAndroid, isLanguageFilter = true),
    ExplorerCategory("python", "Python", "python", CaSymbols.codeBlocks, isLanguageFilter = true),
    ExplorerCategory("typescript", "TypeScript", "typescript", CaSymbols.codeBlocks, isLanguageFilter = true),
    ExplorerCategory("javascript", "JavaScript", "javascript", CaSymbols.terminal, isLanguageFilter = true),
    ExplorerCategory("java", "Java", "java", CaSymbols.deployedCode, isLanguageFilter = true),
    ExplorerCategory("rust", "Rust", "rust", CaSymbols.bolt, isLanguageFilter = true),
    ExplorerCategory("c++", "C++", "c++", CaSymbols.construction, isLanguageFilter = true),
    ExplorerCategory("agents", "AI Agents", "agents", CaSymbols.deployedCode, isLanguageFilter = false),
    ExplorerCategory("llm", "LLMs", "llm", CaSymbols.bolt, isLanguageFilter = false),
    ExplorerCategory("pentesting", "Pen Testing", "penetration-testing", CaSymbols.terminal, isLanguageFilter = false),
    ExplorerCategory("reverse", "Reverse Eng", "reverse-engineering", CaSymbols.construction, isLanguageFilter = false),
    ExplorerCategory("gaming", "Gaming", "gaming", CaSymbols.rocketLaunch, isLanguageFilter = false),
    ExplorerCategory("science", "Science", "science", CaSymbols.functions, isLanguageFilter = false)
)

private enum class PeerNetworkFilter(val id: String, val label: String) {
    ALL("all", "All Related"),
    SAME_AUTHOR("author", "By Author"),
    STARRED("starred", "★ Starred"),
    FIRST_HOP("hop1", "1st-Hop Following"),
    SECOND_HOP("hop2", "2nd-Hop Following"),
    SIMILAR("similar", "Related Stack")
}

/**
 * Compact, self-contained **Trending Repos Widget** that displays the current
 * `Daily`, `Weekly`, and `Monthly` GitHub trending lists for all popular phone-supported languages,
 * with Just-In-Time (JIT) best-effort app screenshot generation (local GUI/Compose preview build ->
 * repo/linked webpage screenshot -> dynamic source synthesis).
 */
@Composable
fun TrendingReposWidget(
    backend: IdeBackend,
    onCloneAndOpen: (htmlUrl: String, repoName: String) -> Unit,
    onOpenExplorer: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberIdeHaptics()
    val c = MaterialTheme.colorScheme
    val languages = remember(backend) { backend.discovery.supportedLanguages().ifEmpty { SUPPORTED_PHONE_LANGUAGES } }

    var selectedPeriod by remember { mutableStateOf(UiTrendingPeriod.DAILY) }
    var selectedLanguageId by remember { mutableStateOf(languages.first().id) }
    var feed by remember { mutableStateOf<UiDiscoveryFeed?>(null) }
    var loading by remember { mutableStateOf(true) }
    val jitScreenshots = remember { mutableStateMapOf<String, Pair<String, UiScreenshotSourceTier>>() }
    val buildingSlugs = remember { mutableStateMapOf<String, Boolean>() }

    fun loadWidgetFeed(langId: String, period: UiTrendingPeriod, forceRefresh: Boolean = false) {
        selectedLanguageId = langId
        selectedPeriod = period
        scope.launch {
            loading = true
            val result = runCatching {
                backend.discovery.getTrendingFeed(
                    language = langId,
                    period = period,
                    refresh = forceRefresh
                )
            }.getOrDefault(UiDiscoveryFeed())
            feed = result
            loading = false
        }
    }

    LaunchedEffect(backend) {
        loadWidgetFeed(selectedLanguageId, selectedPeriod, forceRefresh = false)
    }

    val repos = feed?.trendingHero.orEmpty()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("trending_repos_widget")
            .border(0.5.dp, c.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = c.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row + Period switcher (Daily / Weekly / Monthly)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Symbol(CaSymbols.rocketLaunch, contentDescription = null, size = 16.dp, tint = c.primary)
                    Column {
                        Text(
                            "Trending Repos",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = c.onSurface
                        )
                        Text(
                            if (feed?.isFromBuildBundle == true) "Bundled + Live JIT Previews" else "Live GitHub Crawler",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = c.onSurfaceVariant
                        )
                    }
                }

                // Segmented Period Selector (Daily | Weekly | Monthly)
                Row(
                    modifier = Modifier
                        .background(c.surfaceContainerHigh, RoundedCornerShape(8.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    UiTrendingPeriod.entries.forEach { period ->
                        val isSelected = selectedPeriod == period
                        Surface(
                            onClick = {
                                haptics.tap()
                                if (selectedPeriod != period) {
                                    loadWidgetFeed(selectedLanguageId, period, forceRefresh = false)
                                }
                            },
                            modifier = Modifier.testTag("widget_period_${period.sinceParam}"),
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) c.primary else Color.Transparent
                        ) {
                            Text(
                                period.label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (isSelected) c.onPrimary else c.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Supported Phone Languages Bar
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(languages, key = { it.id }) { lang ->
                    val isSelected = selectedLanguageId == lang.id
                    Surface(
                        onClick = {
                            haptics.tap()
                            if (selectedLanguageId != lang.id) {
                                loadWidgetFeed(lang.id, selectedPeriod, forceRefresh = false)
                            }
                        },
                        modifier = Modifier.testTag("widget_lang_${lang.id}"),
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) c.secondaryContainer else c.surfaceContainerHigh,
                        border = if (isSelected) BorderStroke(1.dp, c.primary) else null
                    ) {
                        Text(
                            lang.label,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (isSelected) c.onSecondaryContainer else c.onSurfaceVariant
                        )
                    }
                }
            }

            if (loading && repos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else if (repos.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repos.take(3).forEach { repo ->
                        val overrideShot = jitScreenshots[repo.slug]
                        val effectiveShotUrl = overrideShot?.first ?: repo.screenshotUrl
                        val effectiveTier = overrideShot?.second ?: repo.screenshotSourceTier
                        val isBuilding = buildingSlugs[repo.slug] == true

                        TrendingWidgetItemRow(
                            repo = repo,
                            screenshotUrl = effectiveShotUrl,
                            tier = effectiveTier,
                            isBuildingPreview = isBuilding,
                            backend = backend,
                            onBuildPreview = {
                                if (!isBuilding) {
                                    haptics.tap()
                                    buildingSlugs[repo.slug] = true
                                    scope.launch {
                                        val built = runCatching {
                                            backend.discovery.resolveOrGenerateAppImage(repo, forceLocalBuild = true)
                                        }.getOrNull()
                                        if (built != null) {
                                            jitScreenshots[repo.slug] = built
                                        }
                                        buildingSlugs[repo.slug] = false
                                    }
                                }
                            },
                            onAutoResolveNeeded = {
                                if (jitScreenshots[repo.slug] == null && buildingSlugs[repo.slug] != true) {
                                    buildingSlugs[repo.slug] = true
                                    scope.launch {
                                        val resolved = runCatching {
                                            backend.discovery.resolveOrGenerateAppImage(repo, forceLocalBuild = false)
                                        }.getOrNull()
                                        if (resolved != null) {
                                            jitScreenshots[repo.slug] = resolved
                                        }
                                        buildingSlugs[repo.slug] = false
                                    }
                                }
                            },
                            onClone = { onCloneAndOpen(repo.htmlUrl, repo.name) }
                        )
                    }
                }

                if (onOpenExplorer != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Surface(
                            onClick = { haptics.tap(); onOpenExplorer() },
                            modifier = Modifier.testTag("widget_open_full_explorer"),
                            shape = RoundedCornerShape(6.dp),
                            color = c.surfaceContainerHigh
                        ) {
                            Text(
                                "Explore Full Gallery & 2-Hop Author Graph →",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = c.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendingWidgetItemRow(
    repo: UiDiscoveredRepo,
    screenshotUrl: String?,
    tier: UiScreenshotSourceTier,
    isBuildingPreview: Boolean,
    backend: IdeBackend,
    onBuildPreview: () -> Unit,
    onAutoResolveNeeded: () -> Unit,
    onClone: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    val bitmap by produceState<ImageBitmap?>(null, repo.slug, screenshotUrl) {
        value = null
        if (!screenshotUrl.isNullOrBlank()) {
            val bytes = backend.discovery.loadScreenshotBytes(screenshotUrl)
            val decoded = bytes?.let { withContext(Dispatchers.Default) { decodeImageBytes(it) } }
            value = decoded
            if (decoded == null) {
                onAutoResolveNeeded()
            }
        } else {
            onAutoResolveNeeded()
        }
    }

    Surface(
        onClick = onClone,
        shape = RoundedCornerShape(10.dp),
        color = c.surfaceContainer,
        border = BorderStroke(0.5.dp, c.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // App Preview Thumbnail
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "${repo.name} preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (isBuildingPreview) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                } else {
                    Symbol(CaSymbols.codeBlocks, contentDescription = null, size = 18.dp, tint = c.primary)
                }
            }

            // Repo metadata
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "${repo.owner}/${repo.name}",
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = c.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (repo.starsInPeriod > 0) {
                        Text(
                            "+${repo.starsInPeriod} ★",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color(0xFF10B981),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Text(
                    repo.description.ifBlank { "${repo.language} trending project" },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "${repo.language.ifBlank { "App" }} • ${repo.stars} ★",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = c.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        tier.badgeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = c.primary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // On-Demand Local Build Preview button + Clone button
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End
            ) {
                Surface(
                    onClick = onBuildPreview,
                    modifier = Modifier.testTag("widget_build_preview_${repo.slug}"),
                    shape = RoundedCornerShape(5.dp),
                    color = c.surfaceContainerHigh
                ) {
                    Text(
                        if (isBuildingPreview) "Building..." else "Build Shot",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = c.primary
                    )
                }
                Surface(
                    onClick = onClone,
                    modifier = Modifier.testTag("widget_clone_${repo.slug}"),
                    shape = RoundedCornerShape(5.dp),
                    color = c.primary
                ) {
                    Text(
                        "Clone",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = c.onPrimary
                    )
                }
            }
        }
    }
}

/**
 * Modern, condensed GitHub Explorer feed featuring:
 * 1. Build-time bundled + live Daily / Weekly / Monthly GitHub Trending lists across all
 *    popular languages supported on this phone.
 * 2. Hero visual repository cards with 3-tier Just-In-Time (JIT) screenshot generation:
 *    - Preferred (Tier 1): Build/render GUI app (`SwiftUiPreviewInterpreter`) or Compose `@Preview` locally on demand.
 *    - Fallback 1 (Tier 2): Search the GitHub repository AND the GitHub linked webpage for a real screenshot.
 *    - Last-Ditch Effort (Tier 3): Dynamic visual synthesis from inspecting the source code.
 * 3. Deep 2-Hop Author Social Graph + Starred Repos + Related Apps Explorer:
 *    - Inspects `@author`, who `@author` follows (`@peer1`), who `@peer1` follows (`@peer2`),
 *      their starred repos, and ranks repositories related to the currently selected app.
 * 4. Architectural shape intelligence ("Shaped like this one, but with more stars").
 */
@Composable
fun GitHubExplorerFeed(
    backend: IdeBackend,
    onCloneAndOpen: (htmlUrl: String, repoName: String) -> Unit,
    modifier: Modifier = Modifier,
    initialFeed: UiDiscoveryFeed? = null
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberIdeHaptics()
    var feed by remember { mutableStateOf<UiDiscoveryFeed?>(initialFeed) }
    var loading by remember { mutableStateOf(feed == null) }
    var selectedCategory by remember { mutableStateOf(EXPLORER_CATEGORIES.first()) }
    var selectedPeriod by remember { mutableStateOf(initialFeed?.period ?: UiTrendingPeriod.DAILY) }
    var selectedHeroUrl by remember { mutableStateOf<String?>(initialFeed?.trendingHero?.firstOrNull()?.htmlUrl) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var selectedPeerFilter by remember { mutableStateOf(PeerNetworkFilter.ALL) }
    var dynamicPeerProjects by remember { mutableStateOf<List<UiPeerProject>>(initialFeed?.authorPeerPicks.orEmpty()) }
    var loadingPeers by remember { mutableStateOf(false) }

    val jitScreenshots = remember { mutableStateMapOf<String, Pair<String, UiScreenshotSourceTier>>() }
    val buildingPreviewSlugs = remember { mutableStateMapOf<String, Boolean>() }
    var readmeDialogTitle by remember { mutableStateOf<String?>(null) }
    var readmeDialogMarkdown by remember { mutableStateOf<String?>(null) }
    var loadingReadmeDialog by remember { mutableStateOf(false) }

    val authState by backend.discovery.authState.collectAsState()
    val heroListState = rememberLazyListState()
    val heroSnapFling = rememberSnapFlingBehavior(lazyListState = heroListState)

    fun loadCategoryAndPeriod(
        category: ExplorerCategory,
        period: UiTrendingPeriod,
        forceRefresh: Boolean = false
    ) {
        selectedCategory = category
        selectedPeriod = period
        selectedHeroUrl = null
        scope.launch {
            loading = true
            val nextFeed = runCatching {
                if (category.isLanguageFilter) {
                    val trending = backend.discovery.getTrendingFeed(
                        language = category.topic,
                        period = period,
                        refresh = forceRefresh
                    )
                    if (trending.trendingHero.isNotEmpty()) {
                        trending
                    } else {
                        backend.discovery.getFeed(
                            topic = if (category.topic == "all") "android" else category.topic,
                            language = if (category.topic == "all") "kotlin" else category.topic,
                            refresh = forceRefresh
                        )
                    }
                } else {
                    backend.discovery.getFeed(topic = category.topic, refresh = forceRefresh)
                }
            }.getOrDefault(UiDiscoveryFeed())
            feed = nextFeed
            dynamicPeerProjects = nextFeed.authorPeerPicks
            selectedHeroUrl = nextFeed.trendingHero.firstOrNull()?.htmlUrl
            heroListState.scrollToItem(0)
            loading = false
        }
    }

    LaunchedEffect(backend) {
        if (feed == null) {
            loadCategoryAndPeriod(selectedCategory, selectedPeriod, forceRefresh = false)
        } else if (selectedHeroUrl == null) {
            selectedHeroUrl = feed?.trendingHero?.firstOrNull()?.htmlUrl
        }
    }

    fun refresh() {
        loadCategoryAndPeriod(selectedCategory, selectedPeriod, forceRefresh = true)
    }

    val currentFeed = feed ?: UiDiscoveryFeed()

    // Keep the active hero repository synced with horizontal paging AND explicit card taps
    val pagedHeroIndex by remember {
        derivedStateOf {
            val layoutInfo = heroListState.layoutInfo
            val visible = layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) 0
            else {
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                visible.minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - viewportCenter) }?.index ?: 0
            }
        }
    }

    LaunchedEffect(pagedHeroIndex, currentFeed.trendingHero) {
        val pagedRepo = currentFeed.trendingHero.getOrNull(pagedHeroIndex)
        if (pagedRepo != null && heroListState.isScrollInProgress) {
            selectedHeroUrl = pagedRepo.htmlUrl
        }
    }

    val activeHeroRepo = remember(selectedHeroUrl, pagedHeroIndex, currentFeed.trendingHero) {
        currentFeed.trendingHero.firstOrNull { it.htmlUrl == selectedHeroUrl }
            ?: currentFeed.trendingHero.getOrNull(pagedHeroIndex)
            ?: currentFeed.trendingHero.firstOrNull()
    }

    // Whenever the currently selected app changes, crawl/load its author's 2-hop social network + starred + related repos
    LaunchedEffect(activeHeroRepo?.htmlUrl, backend) {
        val target = activeHeroRepo ?: return@LaunchedEffect
        loadingPeers = true
        val crawled = runCatching {
            backend.discovery.getRelatedAndAuthorNetwork(target, refresh = false)
        }.getOrDefault(emptyList())
        dynamicPeerProjects = if (crawled.isNotEmpty()) {
            crawled
        } else {
            currentFeed.authorPeerPicks
        }
        loadingPeers = false
    }

    val filteredPeers = remember(dynamicPeerProjects, selectedPeerFilter) {
        when (selectedPeerFilter) {
            PeerNetworkFilter.ALL -> dynamicPeerProjects
            PeerNetworkFilter.SAME_AUTHOR -> dynamicPeerProjects.filter { it.relationKind == "SAME_AUTHOR" }
            PeerNetworkFilter.STARRED -> dynamicPeerProjects.filter {
                it.relationKind in setOf("AUTHOR_STARRED", "PEER_STARRED", "SECOND_DEGREE_STARRED")
            }
            PeerNetworkFilter.FIRST_HOP -> dynamicPeerProjects.filter { it.relationKind == "FOLLOWED_PEER" }
            PeerNetworkFilter.SECOND_HOP -> dynamicPeerProjects.filter {
                it.relationKind in setOf("SECOND_DEGREE_PEER", "SECOND_DEGREE_STARRED")
            }
            PeerNetworkFilter.SIMILAR -> dynamicPeerProjects.filter { it.relationKind == "RELATED_ARCHITECTURE" }
        }.ifEmpty { dynamicPeerProjects }
    }

    val activeBenchmarks = remember(activeHeroRepo, currentFeed.northStarBenchmarks, currentFeed.trendingHero) {
        val target = activeHeroRepo
        if (target == null) {
            currentFeed.northStarBenchmarks.take(4)
        } else {
            val targetSlug = "${target.owner}/${target.name}"
            val directMatches = currentFeed.northStarBenchmarks.filter {
                it.targetRepoName.equals(targetSlug, ignoreCase = true) ||
                    it.targetRepoName.equals(target.name, ignoreCase = true)
            }
            if (directMatches.isNotEmpty()) {
                directMatches.take(4)
            } else {
                currentFeed.trendingHero
                    .filter { !it.htmlUrl.equals(target.htmlUrl, ignoreCase = true) }
                    .sortedByDescending { it.stars }
                    .take(3)
                    .map { candidate ->
                        val ratio = if (target.stars > 0 && candidate.stars > target.stars) {
                            "${(candidate.stars.toDouble() / target.stars.coerceAtLeast(1)).toInt().coerceAtLeast(2)}x more stars"
                        } else {
                            "${candidate.stars} ★"
                        }
                        UiNorthStarBenchmark(
                            targetRepoName = target.name,
                            northStarName = "${candidate.owner}/${candidate.name}",
                            starMultiplierText = ratio,
                            matchPercentage = 88,
                            whyRecommended = "Shaped like ${target.name}: shares the ${candidate.language.ifBlank { "same" }} architecture — ${candidate.description}",
                            northStarUrl = candidate.htmlUrl,
                        )
                    }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Symbol(CaSymbols.travelExplore, contentDescription = null, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "GitHub Explorer",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    "Daily/Weekly/Monthly trending, JIT local GUI previews & 2-hop author graphs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Bundled App README.md Button
                Surface(
                    onClick = {
                        haptics.tap()
                        readmeDialogTitle = "README.md · CodeAssist"
                        loadingReadmeDialog = true
                        scope.launch {
                            readmeDialogMarkdown = runCatching {
                                backend.discovery.getBundledAppReadmeMarkdown()
                            }.getOrDefault("# CodeAssist\n\nOn-device IDE & Smart GitHub Trending Service.")
                            loadingReadmeDialog = false
                        }
                    },
                    modifier = Modifier.testTag("explorer_app_readme_button"),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Symbol(
                            CaSymbols.codeBlocks,
                            contentDescription = null,
                            size = 13.dp,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "README.md",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Auth & Rate Limit Chip
                val authInteraction = remember { MutableInteractionSource() }
                Surface(
                    onClick = { showAuthDialog = true },
                    interactionSource = authInteraction,
                    modifier = Modifier.pressScale(authInteraction),
                    shape = RoundedCornerShape(8.dp),
                    color = if (authState.isAuthenticated) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Symbol(
                            if (authState.isAuthenticated) CaSymbols.checkCircle else CaSymbols.accountCircle,
                            contentDescription = null,
                            size = 14.dp,
                            tint = if (authState.isAuthenticated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (authState.isAuthenticated) "@${authState.username}" else "${authState.rateRemaining}/60",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = if (authState.isAuthenticated) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                SquareToneButton(
                    glyph = CaSymbols.sync,
                    contentDescription = "Refresh Feed",
                    onClick = { if (!loading) refresh() },
                    size = 34.dp,
                )
            }
        }

        // Trending Period Selector Bar (Daily / Weekly / Monthly) + Bundle Provenance Badge
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                UiTrendingPeriod.entries.forEach { period ->
                    val isSelected = selectedPeriod == period
                    Surface(
                        onClick = {
                            haptics.tap()
                            if (selectedPeriod != period) {
                                loadCategoryAndPeriod(selectedCategory, period, forceRefresh = false)
                            }
                        },
                        modifier = Modifier.testTag("trending_period_${period.sinceParam}"),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    ) {
                        Text(
                            period.label,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    if (currentFeed.isFromBuildBundle) "BUNDLED + JIT READY" else "LIVE CRAWL",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Supported Phone Languages & Domain Category Pills Row
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(EXPLORER_CATEGORIES, key = { it.id }) { category ->
                val selected = selectedCategory.id == category.id
                val interactionSource = remember { MutableInteractionSource() }
                Surface(
                    onClick = {
                        haptics.tap()
                        if (selectedCategory.id != category.id) {
                            loadCategoryAndPeriod(category, selectedPeriod, forceRefresh = false)
                        }
                    },
                    interactionSource = interactionSource,
                    modifier = Modifier
                        .pressScale(interactionSource)
                        .testTag("trending_lang_${category.id}"),
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = if (selected) null else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Symbol(
                            category.symbol,
                            contentDescription = null,
                            size = 14.dp,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            category.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (showAuthDialog) {
            GitHubAuthDialog(
                authState = authState,
                backend = backend,
                onDismiss = { showAuthDialog = false }
            )
        }

        if (readmeDialogTitle != null) {
            ReadmeMarkdownDialog(
                title = readmeDialogTitle ?: "README.md",
                markdown = readmeDialogMarkdown.orEmpty(),
                loading = loadingReadmeDialog,
                onDismiss = {
                    readmeDialogTitle = null
                    readmeDialogMarkdown = null
                    loadingReadmeDialog = false
                }
            )
        }

        if (loading && currentFeed.trendingHero.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            return
        }

        // 1. Hero Trending Visual Cards
        if (currentFeed.trendingHero.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "TRENDING VISUAL SHOWCASE · ${selectedPeriod.label.uppercase()} · ${selectedCategory.label.uppercase()}",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LazyRow(
                    state = heroListState,
                    flingBehavior = heroSnapFling,
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    itemsIndexed(currentFeed.trendingHero, key = { _, it -> it.htmlUrl }) { index, repo ->
                        val isSelected = activeHeroRepo?.htmlUrl == repo.htmlUrl
                        val jitOverride = jitScreenshots[repo.slug]
                        val effectiveRepo = if (jitOverride != null) {
                            repo.copy(
                                screenshotUrl = jitOverride.first,
                                screenshotSourceTier = jitOverride.second
                            )
                        } else {
                            repo
                        }
                        val isBuildingPreview = buildingPreviewSlugs[repo.slug] == true

                        HeroVisualRepoCard(
                            repo = effectiveRepo,
                            selected = isSelected,
                            isBuildingPreview = isBuildingPreview,
                            backend = backend,
                            onSelect = {
                                haptics.tap()
                                selectedHeroUrl = repo.htmlUrl
                                scope.launch { heroListState.animateScrollToItem(index) }
                            },
                            onBuildPreview = {
                                if (!isBuildingPreview) {
                                    haptics.tap()
                                    buildingPreviewSlugs[repo.slug] = true
                                    scope.launch {
                                        val built = runCatching {
                                            backend.discovery.resolveOrGenerateAppImage(repo, forceLocalBuild = true)
                                        }.getOrNull()
                                        if (built != null) {
                                            jitScreenshots[repo.slug] = built
                                        }
                                        buildingPreviewSlugs[repo.slug] = false
                                    }
                                }
                            },
                            onAutoResolveNeeded = {
                                if (jitScreenshots[repo.slug] == null && buildingPreviewSlugs[repo.slug] != true) {
                                    buildingPreviewSlugs[repo.slug] = true
                                    scope.launch {
                                        val resolved = runCatching {
                                            backend.discovery.resolveOrGenerateAppImage(repo, forceLocalBuild = false)
                                        }.getOrNull()
                                        if (resolved != null) {
                                            jitScreenshots[repo.slug] = resolved
                                        }
                                        buildingPreviewSlugs[repo.slug] = false
                                    }
                                }
                            },
                            onViewReadme = {
                                haptics.tap()
                                readmeDialogTitle = "README.md · ${repo.owner}/${repo.name}"
                                loadingReadmeDialog = true
                                scope.launch {
                                    readmeDialogMarkdown = runCatching {
                                        backend.discovery.fetchReadmeMarkdown(repo)
                                    }.getOrDefault("# ${repo.owner}/${repo.name}\n\n${repo.description}")
                                    loadingReadmeDialog = false
                                }
                            },
                            onClone = { onCloneAndOpen(repo.htmlUrl, repo.name) }
                        )
                    }
                }
            }
        }

        // 1B. Integrated README.md Preview Card for Active Repository
        if (activeHeroRepo != null) {
            ActiveRepoReadmeCard(
                repo = activeHeroRepo,
                backend = backend,
                onOpenFullReadme = { loadedMd ->
                    haptics.tap()
                    readmeDialogTitle = "README.md · ${activeHeroRepo.owner}/${activeHeroRepo.name}"
                    readmeDialogMarkdown = loadedMd
                    loadingReadmeDialog = false
                }
            )
        }

        // 2. "Shaped Like This, But..." (North Star Architectural Benchmarks)
        if (activeBenchmarks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val targetTitle = activeHeroRepo?.name?.uppercase() ?: "THIS REPO"
                Text(
                    "SHAPED LIKE $targetTitle, BUT WITH MORE STARS",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    activeBenchmarks.forEach { benchmark ->
                        val cloneFolder = benchmark.northStarName
                            .substringAfterLast('/')
                            .ifBlank { benchmark.targetRepoName.substringAfterLast('/') }
                        NorthStarBenchmarkCard(
                            benchmark = benchmark,
                            onClone = {
                                onCloneAndOpen(benchmark.northStarUrl, cloneFolder)
                            }
                        )
                    }
                }
            }
        }

        // 3. Deep 2-Hop Author Social Graph + Starred Repos + Related Apps
        if (dynamicPeerProjects.isNotEmpty() || loadingPeers) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val authorTitle = activeHeroRepo?.owner?.let { "@${it.uppercase()}'S 2-HOP NETWORK & RELATED" }
                    ?: "AUTHOR'S PEER CIRCLE"
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        authorTitle,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (loadingPeers) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    }
                }

                // Filter Chips for 2-Hop Social Graph & Starred Repos
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(PeerNetworkFilter.entries, key = { it.id }) { filter ->
                        val isSelected = selectedPeerFilter == filter
                        Surface(
                            onClick = {
                                haptics.tap()
                                selectedPeerFilter = filter
                            },
                            modifier = Modifier.testTag("peer_filter_${filter.id}"),
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            Text(
                                filter.label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredPeers, key = { it.htmlUrl }) { peer ->
                        AuthorPeerCard(
                            peer = peer,
                            onClone = { onCloneAndOpen(peer.htmlUrl, peer.name) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Visual Hero Card rendering screenshot preview banner, screenshot provenance tier badge,
 * star velocity in period, on-demand local GUI/Compose preview build button, and capability pills.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroVisualRepoCard(
    repo: UiDiscoveredRepo,
    selected: Boolean,
    isBuildingPreview: Boolean,
    backend: IdeBackend,
    onSelect: () -> Unit,
    onBuildPreview: () -> Unit,
    onAutoResolveNeeded: () -> Unit,
    onViewReadme: () -> Unit,
    onClone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = MaterialTheme.colorScheme
    val cardInteraction = remember { MutableInteractionSource() }

    val screenshotBitmap by produceState<ImageBitmap?>(null, repo.htmlUrl, repo.screenshotUrl) {
        value = null
        val url = repo.screenshotUrl
        if (!url.isNullOrBlank()) {
            val bytes = backend.discovery.loadScreenshotBytes(url)
            val decoded = bytes?.let { withContext(Dispatchers.Default) { decodeImageBytes(it) } }
            value = decoded
            if (decoded == null) {
                onAutoResolveNeeded()
            }
        } else {
            onAutoResolveNeeded()
        }
    }

    Surface(
        onClick = onSelect,
        interactionSource = cardInteraction,
        modifier = modifier
            .width(292.dp)
            .pressScale(cardInteraction)
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = if (selected) c.primary else c.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) c.surfaceContainer else c.surfaceContainerLow
    ) {
        Column {
            // Visual Preview Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(c.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                val bmp = screenshotBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = repo.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // Gradient scrim over the bottom for legibility
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.55f)
                                    )
                                )
                            )
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isBuildingPreview) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text(
                                "Building Local Preview...",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.primary
                            )
                        } else {
                            Symbol(CaSymbols.codeBlocks, contentDescription = null, size = 32.dp, tint = c.primary)
                            Text(
                                repo.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = c.onSurface
                            )
                        }
                    }
                }

                // Top Floating Badges (Total Stars + Velocity + Zero Cloud)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .align(Alignment.TopStart),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Black.copy(alpha = 0.70f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Symbol(CaSymbols.star, contentDescription = null, size = 12.dp, tint = Color(0xFFFFB800))
                                Text(
                                    "${repo.stars}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Color.White
                                )
                            }
                        }

                        if (repo.starsInPeriod > 0) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF065F46).copy(alpha = 0.88f)
                            ) {
                                Text(
                                    "+${repo.starsInPeriod} ★",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = Color(0xFFA7F3D0)
                                )
                            }
                        }
                    }

                    if (repo.isZeroCloud) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF065F46).copy(alpha = 0.85f)
                        ) {
                            Text(
                                "ZERO CLOUD",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = Color(0xFFA7F3D0)
                            )
                        }
                    }
                }

                // Bottom Floating Row: Screenshot Provenance Tier Badge + On-Demand Build Preview Trigger
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .align(Alignment.BottomStart),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = Color.Black.copy(alpha = 0.72f)
                    ) {
                        Text(
                            repo.screenshotSourceTier.badgeLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = Color(0xFF38BDF8)
                        )
                    }

                    Surface(
                        onClick = onBuildPreview,
                        modifier = Modifier.testTag("hero_build_preview_${repo.slug}"),
                        shape = RoundedCornerShape(5.dp),
                        color = Color.Black.copy(alpha = 0.78f),
                        border = BorderStroke(0.5.dp, Color(0xFF38BDF8).copy(alpha = 0.7f))
                    ) {
                        Text(
                            if (isBuildingPreview) "Building..." else "Build Preview",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = Color.White
                        )
                    }
                }
            }

            // Info Content
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "${repo.owner}/${repo.name}",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    repo.description.ifBlank {
                        "Modern ${repo.language.ifBlank { "open-source" }} repository ready to clone and run."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Capabilities FlowRow
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val langLower = repo.language.lowercase()
                    when {
                        repo.usesCompose -> CapabilityTag("Jetpack Compose", Color(0xFF4F46E5), Color(0xFFEEF2FF))
                        langLower == "swift" -> CapabilityTag("Swift & iOS", Color(0xFFEA580C), Color(0xFFFFF7ED))
                        langLower == "python" || langLower == "jupyter notebook" -> CapabilityTag("Python 3", Color(0xFF2563EB), Color(0xFFEFF6FF))
                        langLower == "javascript" || langLower == "typescript" -> CapabilityTag(repo.language, Color(0xFF16A34A), Color(0xFFF0FDF4))
                        repo.language.isNotBlank() -> CapabilityTag(repo.language, Color(0xFF4F46E5), Color(0xFFEEF2FF))
                    }
                    if (repo.isOfflineFirst) {
                        CapabilityTag("Offline First", Color(0xFF0D9488), Color(0xFFCCFBF1))
                    }
                    if (repo.usesNativeNdk) {
                        CapabilityTag("Native C/NDK", Color(0xFFD97706), Color(0xFFFEF3C7))
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Clone & Open + README.md Action Row
                val ctaInteraction = remember { MutableInteractionSource() }
                val haptics = rememberIdeHaptics()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onViewReadme,
                        modifier = Modifier.testTag("hero_readme_${repo.slug}"),
                        shape = RoundedCornerShape(8.dp),
                        color = c.surfaceContainerHigh,
                        border = BorderStroke(0.5.dp, c.outlineVariant.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Symbol(CaSymbols.codeBlocks, contentDescription = null, size = 13.dp, tint = c.primary)
                            Text(
                                "README.md",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = c.onSurface,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Surface(
                        onClick = { haptics.heavyTap(); onClone() },
                        interactionSource = ctaInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .pressScale(ctaInteraction),
                        shape = RoundedCornerShape(8.dp),
                        color = c.primary
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Symbol(CaSymbols.download, contentDescription = null, size = 14.dp, tint = c.onPrimary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Clone & Open",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = c.onPrimary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * North Star Comparison Card comparing structural genome.
 */
@Composable
private fun NorthStarBenchmarkCard(
    benchmark: UiNorthStarBenchmark,
    onClone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = MaterialTheme.colorScheme
    val cardInteraction = remember { MutableInteractionSource() }
    val haptics = rememberIdeHaptics()

    Surface(
        onClick = { haptics.heavyTap(); onClone() },
        interactionSource = cardInteraction,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(cardInteraction)
            .border(0.5.dp, c.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = c.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        benchmark.northStarName,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = c.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = c.secondaryContainer
                    ) {
                        Text(
                            benchmark.starMultiplierText,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = c.onSecondaryContainer,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Text(
                    benchmark.whyRecommended,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    "${benchmark.matchPercentage}% Architectural Genome Match",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    color = c.primary
                )
            }

            val cloneInteraction = remember { MutableInteractionSource() }
            Surface(
                onClick = { haptics.heavyTap(); onClone() },
                interactionSource = cloneInteraction,
                modifier = Modifier.pressScale(cloneInteraction),
                shape = RoundedCornerShape(8.dp),
                color = c.primary
            ) {
                Text(
                    "Clone",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = c.onPrimary
                )
            }
        }
    }
}

/**
 * Author's 2-Hop Peer & Starred Repository Card displaying social graph path and relevance score.
 */
@Composable
private fun AuthorPeerCard(
    peer: UiPeerProject,
    onClone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = MaterialTheme.colorScheme
    val cardInteraction = remember { MutableInteractionSource() }
    val haptics = rememberIdeHaptics()

    Surface(
        onClick = { haptics.heavyTap(); onClone() },
        interactionSource = cardInteraction,
        modifier = modifier
            .width(256.dp)
            .pressScale(cardInteraction)
            .border(0.5.dp, c.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = c.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    peer.socialPathText.ifBlank { peer.attributionText },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = c.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (peer.relevanceScore > 0) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = c.secondaryContainer
                    ) {
                        Text(
                            "${peer.relevanceScore}%",
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = c.onSecondaryContainer
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${peer.owner}/${peer.name}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Symbol(CaSymbols.star, contentDescription = null, size = 12.dp, tint = Color(0xFFFFB800))
                    Text(
                        "${peer.stars}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = c.onSurfaceVariant
                    )
                }
            }

            Text(
                peer.description.ifBlank { "Open source project discovered in author's 2-hop network." },
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                peer.relevanceReason.ifBlank { peer.attributionText },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                ),
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(2.dp))

            val peerInteraction = remember { MutableInteractionSource() }
            Surface(
                onClick = { haptics.heavyTap(); onClone() },
                interactionSource = peerInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(peerInteraction),
                shape = RoundedCornerShape(6.dp),
                color = c.primary
            ) {
                Text(
                    "Clone & Inspect",
                    modifier = Modifier.padding(vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = c.onPrimary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CapabilityTag(
    label: String,
    textColor: Color,
    bgColor: Color
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            ),
            color = textColor
        )
    }
}

@Composable
private fun GitHubAuthDialog(
    authState: UiGitHubAuthState,
    backend: IdeBackend,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var tokenInput by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Symbol(
                    if (authState.isAuthenticated) CaSymbols.verified else CaSymbols.accountCircle,
                    contentDescription = null,
                    size = 20.dp,
                    tint = colors.primary
                )
                Text(
                    if (authState.isAuthenticated) "GitHub Connected" else "Connect GitHub",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (authState.isAuthenticated) colors.primaryContainer.copy(alpha = 0.5f) else colors.surfaceContainerHigh,
                    border = BorderStroke(0.5.dp, colors.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "API RATE LIMIT",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = colors.primary
                            )
                            Text(
                                if (authState.isAuthenticated) "${authState.rateRemaining} / 5,000 req/hr" else "${authState.rateRemaining} / 60 req/hr",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (authState.isAuthenticated) colors.primary else colors.onSurfaceVariant
                            )
                        }
                        Text(
                            if (authState.isAuthenticated) {
                                "Connected as @${authState.username}. Your high-throughput rate limit powers full architectural twins, live visual previews, and author circle exploration."
                            } else {
                                "Anonymous requests are capped at 60/hr. Connect via Personal Access Token or OAuth to unlock 5,000 requests/hr and prevent rate limit exhaustion."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                if (authState.isAuthenticated) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                backend.discovery.clearToken()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Disconnect Token", color = colors.error)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Personal Access Token (classic or fine-grained)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = colors.onSurface
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, if (errorMessage != null) colors.error else colors.outlineVariant, RoundedCornerShape(8.dp))
                                .background(colors.surfaceContainerLow, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BasicTextField(
                                value = tokenInput,
                                onValueChange = {
                                    tokenInput = it
                                    errorMessage = null
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = colors.onSurface
                                ),
                                decorationBox = { innerTextField ->
                                    if (tokenInput.isEmpty()) {
                                        Text(
                                            "ghp_... or github_pat_...",
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            )

                            TextButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text
                                    if (!clip.isNullOrBlank()) {
                                        tokenInput = clip.trim()
                                        errorMessage = null
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("Paste", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        if (errorMessage != null) {
                            Text(
                                errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                uriHandler.openUri(backend.discovery.getOAuthAuthorizeUrl())
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Symbol(CaSymbols.link, contentDescription = null, size = 14.dp, tint = colors.primary)
                        Text(
                            "Generate token or authorize in browser",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = colors.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!authState.isAuthenticated) {
                TextButton(
                    onClick = {
                        val token = tokenInput.trim()
                        if (token.isEmpty()) {
                            errorMessage = "Please enter or paste a valid GitHub token"
                            return@TextButton
                        }
                        scope.launch {
                            submitting = true
                            val success = backend.discovery.submitToken(token)
                            submitting = false
                            if (success) {
                                onDismiss()
                            } else {
                                errorMessage = "Invalid token or unable to authenticate with GitHub"
                            }
                        }
                    },
                    enabled = !submitting
                ) {
                    if (submitting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Connect & Upgrade Limit")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (authState.isAuthenticated) "Done" else "Cancel")
            }
        }
    )
}

@Composable
private fun ActiveRepoReadmeCard(
    repo: UiDiscoveredRepo,
    backend: IdeBackend,
    onOpenFullReadme: (String) -> Unit
) {
    val c = MaterialTheme.colorScheme
    var expanded by remember(repo.htmlUrl) { mutableStateOf(false) }
    val readmeMarkdown by produceState<String?>(null, repo.htmlUrl) {
        value = null
        value = runCatching {
            backend.discovery.fetchReadmeMarkdown(repo)
        }.getOrDefault("# ${repo.owner}/${repo.name}\n\n${repo.description}")
    }

    val currentMd = readmeMarkdown
    val previewExcerpt = remember(currentMd, expanded) {
        if (currentMd.isNullOrBlank()) {
            ""
        } else if (expanded) {
            currentMd.take(4000)
        } else {
            currentMd.lineSequence().take(10).joinToString("\n").take(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .testTag("active_repo_readme_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "INTEGRATED README.MD · ${repo.owner.uppercase()}/${repo.name.uppercase()}",
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                ),
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("readme_card_toggle_expand"),
                    shape = RoundedCornerShape(6.dp),
                    color = c.surfaceContainerHigh
                ) {
                    Text(
                        if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = c.primary
                    )
                }

                if (!currentMd.isNullOrBlank()) {
                    Surface(
                        onClick = { onOpenFullReadme(currentMd) },
                        modifier = Modifier.testTag("readme_card_open_modal"),
                        shape = RoundedCornerShape(6.dp),
                        color = c.secondaryContainer
                    ) {
                        Text(
                            "Full README.md",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = c.onSecondaryContainer
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = c.surfaceContainerLow,
            border = BorderStroke(0.5.dp, c.outlineVariant.copy(alpha = 0.5f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                if (currentMd == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                        Text(
                            "Loading README.md for ${repo.owner}/${repo.name}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant
                        )
                    }
                } else {
                    ListingProse(
                        text = previewExcerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadmeMarkdownDialog(
    title: String,
    markdown: String,
    loading: Boolean,
    onDismiss: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("readme_markdown_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Symbol(CaSymbols.codeBlocks, contentDescription = null, size = 18.dp, tint = c.primary)
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
            ) {
                if (loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        ListingProse(
                            text = markdown.ifBlank { "# README.md\n\nNo README content available." },
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("readme_dialog_close")
            ) {
                Text("Close")
            }
        }
    )
}
