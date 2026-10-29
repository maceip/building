package dev.ide.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import dev.ide.android.MainActivity
import dev.ide.android.R
import dev.ide.core.discovery.BundledTrendingCatalog
import dev.ide.core.discovery.GitHubDiscoveryClient
import dev.ide.core.discovery.SmartAppImageGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Android Home-Screen AppWidget that displays the current Daily, Weekly, or Monthly
 * GitHub Trending repositories for supported on-device phone languages, including a
 * JIT-generated or bundled screenshot of the featured trending app.
 */
class TrendingReposAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidgetAsync(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        when (intent.action) {
            ACTION_CYCLE_PERIOD -> {
                val current = prefs.getString(KEY_PERIOD, "daily") ?: "daily"
                val next = when (current) {
                    "daily" -> "weekly"
                    "weekly" -> "monthly"
                    else -> "daily"
                }
                prefs.edit().putString(KEY_PERIOD, next).apply()
                refreshAllWidgets(context)
            }
            ACTION_CYCLE_LANGUAGE -> {
                val current = prefs.getString(KEY_LANGUAGE, "all") ?: "all"
                val idx = WIDGET_LANGUAGES.indexOfFirst { it.first == current }.coerceAtLeast(0)
                val next = WIDGET_LANGUAGES[(idx + 1) % WIDGET_LANGUAGES.size].first
                prefs.edit().putString(KEY_LANGUAGE, next).apply()
                refreshAllWidgets(context)
            }
        }
    }

    private fun refreshAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TrendingReposAppWidgetProvider::class.java))
        for (id in ids) {
            updateWidgetAsync(context, manager, id)
        }
    }

    private fun updateWidgetAsync(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        val pendingResult = goAsync()
        widgetScope.launch {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val period = prefs.getString(KEY_PERIOD, "daily") ?: "daily"
                val language = prefs.getString(KEY_LANGUAGE, "all") ?: "all"
                val languageLabel = WIDGET_LANGUAGES.firstOrNull { it.first == language }?.second ?: "All"
                val periodLabel = period.replaceFirstChar { it.uppercase() }

                val views = RemoteViews(context.packageName, R.layout.widget_trending_repos)
                views.setTextViewText(R.id.widget_lang_btn, languageLabel)
                views.setTextViewText(R.id.widget_period_btn, periodLabel)

                val cycleLangIntent = Intent(context, TrendingReposAppWidgetProvider::class.java).apply {
                    action = ACTION_CYCLE_LANGUAGE
                }
                views.setOnClickPendingIntent(
                    R.id.widget_lang_btn,
                    PendingIntent.getBroadcast(
                        context,
                        101,
                        cycleLangIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )

                val cyclePeriodIntent = Intent(context, TrendingReposAppWidgetProvider::class.java).apply {
                    action = ACTION_CYCLE_PERIOD
                }
                views.setOnClickPendingIntent(
                    R.id.widget_period_btn,
                    PendingIntent.getBroadcast(
                        context,
                        102,
                        cyclePeriodIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )

                val cacheDir = File(context.cacheDir, "github_discovery").toPath()
                val client = GitHubDiscoveryClient(cacheDir = cacheDir)
                val feed = client.getTrendingFeed(language = language, period = period, limit = 5, preferLive = false)
                val repos = feed.trendingHero
                val hero = repos.firstOrNull()

                if (hero != null) {
                    views.setTextViewText(R.id.widget_repo_1_title, "${hero.owner}/${hero.name}")
                    views.setTextViewText(R.id.widget_repo_1_desc, hero.description.ifBlank { hero.language })
                    val velocity = if (hero.starsInPeriod > 0) " (+${hero.starsInPeriod})" else ""
                    views.setTextViewText(
                        R.id.widget_repo_1_meta,
                        "★ ${hero.stars}$velocity · ${hero.language}",
                    )

                    val shotBytes = hero.localScreenshotPath?.let { path ->
                        runCatching { File(path).readBytes() }.getOrNull()
                    } ?: run {
                        val generated = client.resolveOrGenerateAppImage(
                            owner = hero.owner,
                            repoName = hero.name,
                            language = hero.language,
                            description = hero.description,
                            defaultBranch = hero.defaultBranch,
                            homepageUrl = hero.homepageUrl,
                            forceLocalBuild = false,
                        )
                        val filePath = generated?.first
                        if (!filePath.isNullOrBlank()) {
                            runCatching { File(filePath).readBytes() }.getOrNull()
                        } else {
                            null
                        }
                    } ?: SmartAppImageGenerator().synthesizeFromSourceCode(
                        owner = hero.owner,
                        repo = hero.name,
                        language = hero.language,
                        description = hero.description,
                        sourceFiles = emptyMap(),
                    ).bytes

                    val bitmap = BitmapFactory.decodeByteArray(shotBytes, 0, shotBytes.size)
                    if (bitmap != null) {
                        views.setImageViewBitmap(R.id.widget_hero_shot, bitmap)
                    }
                }

                val second = repos.getOrNull(1)
                views.setTextViewText(
                    R.id.widget_repo_2,
                    if (second != null) "2. ${second.owner}/${second.name}  ★ ${second.stars} · ${second.language}" else "2. —",
                )
                val third = repos.getOrNull(2)
                views.setTextViewText(
                    R.id.widget_repo_3,
                    if (third != null) "3. ${third.owner}/${third.name}  ★ ${third.stars} · ${third.language}" else "3. —",
                )

                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val launchPending = PendingIntent.getActivity(
                    context,
                    100,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.widget_hero_row, launchPending)
                views.setOnClickPendingIntent(R.id.widget_title, launchPending)

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (_: Throwable) {
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "trending_repos_widget_prefs"
        private const val KEY_PERIOD = "period"
        private const val KEY_LANGUAGE = "language"
        const val ACTION_CYCLE_PERIOD = "dev.ide.android.widget.ACTION_CYCLE_PERIOD"
        const val ACTION_CYCLE_LANGUAGE = "dev.ide.android.widget.ACTION_CYCLE_LANGUAGE"

        private val WIDGET_LANGUAGES = listOf(
            "all" to "All",
            "kotlin" to "Kotlin",
            "swift" to "Swift",
            "python" to "Python",
            "typescript" to "TypeScript",
            "javascript" to "JavaScript",
            "java" to "Java",
            "rust" to "Rust",
            "c++" to "C++",
        )

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
