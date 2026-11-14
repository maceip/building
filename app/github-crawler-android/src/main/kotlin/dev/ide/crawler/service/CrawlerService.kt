package dev.ide.crawler.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.ide.crawler.CrawlerRuntime
import dev.ide.crawler.MainActivity
import dev.ide.crawler.crawler.CrawlerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The background crawler service.
 *
 * Runs the slow crawl loop as a foreground service so the OS does not kill
 * it while the phone is idle - an indexer that stops every time the screen
 * locks would never build anything.
 *
 * The loop itself is deliberately lazy: one cycle every
 * [CrawlerConfig.TRENDING_INTERVAL_SECONDS], and each cycle spends at most
 * [CrawlerConfig.MAX_CALLS_PER_CYCLE] API calls at a
 * [CrawlerConfig.API_MIN_INTERVAL_MS] gap. That keeps the phone inside the
 * GitHub rate limits even when it shares a token with the macOS daemon.
 */
class CrawlerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification("starting…")

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // A one-shot cycle runs a single pass and then stops, so the user can
        // see the crawler work without leaving the service running.
        if (intent?.action == ACTION_ONE_CYCLE) {
            if (loopJob?.isActive != true) {
                CrawlerRuntime.onStarted(this)
                loopJob = scope.launch {
                    try {
                        val (engine, _) = CrawlerRuntime.ensureStack(this@CrawlerService)
                        val result = engine.runCycle()
                        CrawlerRuntime.onCycle(result)
                        CrawlerRuntime.log(
                            "one-shot cycle done: trending=${result.trending} expanded=${result.expanded} " +
                                "related=${result.related} api=${result.apiCalls}"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "one-shot cycle failed", e)
                        CrawlerRuntime.log("one-shot cycle failed: ${e.message}")
                    }
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }

        // Restart the service if the OS kills it, unless the user stopped it.
        if (loopJob?.isActive != true) {
            CrawlerRuntime.onStarted(this)
            loopJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        CrawlerRuntime.onStopped()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    private suspend fun runLoop() {
        CrawlerRuntime.log("crawler service started")

        // Give the user a moment to finish pasting a token.
        if (CrawlerRuntime.currentToken(this).isNullOrBlank()) {
            CrawlerRuntime.log("no GitHub token yet - waiting for one to be saved")
        }

        while (scope.isActive) {
            val (engine, _) = CrawlerRuntime.ensureStack(this)
            try {
                val result = engine.runCycle()
                CrawlerRuntime.onCycle(result)
                CrawlerRuntime.log(
                    "cycle done: trending=${result.trending} expanded=${result.expanded} " +
                        "related=${result.related} api=${result.apiCalls}"
                )
            } catch (e: Exception) {
                Log.w(TAG, "cycle failed", e)
                CrawlerRuntime.log("cycle failed: ${e.message}")
            }

            updateNotification(CrawlerRuntime.status.value.lastCycleSummary)

            // Sleep in slices so a stop request is honoured promptly.
            var waited = 0L
            val total = CrawlerConfig.TRENDING_INTERVAL_SECONDS * 1000L
            while (waited < total && scope.isActive) {
                delay(5_000L)
                waited += 5_000L
            }
        }
    }

    // ------------------------------------------------------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(dev.ide.crawler.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(dev.ide.crawler.R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(dev.ide.crawler.R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(dev.ide.crawler.R.drawable.ic_crawler)
            .setOngoing(true)
            .setContentIntent(pending)
        return builder.build()
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "CrawlerService"
        private const val CHANNEL_ID = "crawler-status"
        private const val NOTIFICATION_ID = 4711
        const val ACTION_STOP = "dev.ide.crawler.action.STOP"
        const val ACTION_ONE_CYCLE = "dev.ide.crawler.action.ONE_CYCLE"

        /** Start the background crawler. */
        fun start(context: Context) {
            try {
                val intent = Intent(context, CrawlerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // The system refuses a foreground-service start from the
                // background (a boot broadcast, for example). That must
                // degrade to "not running" - never to a crash.
                Log.w(TAG, "the system refused to start the crawler service: ${e.message}")
                CrawlerRuntime.log("the system refused to start the crawler: ${e.message}")
            }
        }

        /** Run a single crawl cycle and stop afterwards. */
        fun startOneCycle(context: Context) {
            try {
                val intent = Intent(context, CrawlerService::class.java).setAction(ACTION_ONE_CYCLE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "the system refused the one-shot cycle: ${e.message}")
                CrawlerRuntime.log("the system refused to run the cycle: ${e.message}")
            }
        }

        /** Stop the background crawler. */
        fun stop(context: Context) {
            try {
                context.startService(Intent(context, CrawlerService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "could not deliver the stop request: ${e.message}")
            }
        }
    }
}
