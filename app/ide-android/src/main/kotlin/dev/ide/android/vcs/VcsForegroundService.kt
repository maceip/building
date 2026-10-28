package dev.ide.android.vcs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.ide.platform.log.Log
import dev.ide.ui.backend.UiVcsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps the IDE process in `PROCESS_STATE_FOREGROUND_SERVICE` with a partial CPU wake lock and low-latency
 * Wi-Fi lock whenever a Git network operation (`clone`, `fetch`, `pull`, `push`, `publish`) is in flight.
 *
 * Without an active foreground service in the main IDE process, switching focused Android apps mid-clone
 * moves the app UID to a cached background state within seconds, causing Android's `ConnectivityService` /
 * `CachedAppOptimizer` to destroy active TCP sockets with `ECONNABORTED` ("Software caused connection abort").
 *
 * Starting this service as soon as [UiVcsActivity.busy] flips to `true` (while the user is still in the
 * foreground tapping Clone) ensures Android 12–17 permits the foreground service promotion before the user
 * switches apps.
 */
class VcsForegroundService : Service() {

    private val log = Log.logger("dev.ide.android.vcs.VcsForegroundService")
    private val mainHandler = Handler(Looper.getMainLooper())
    private var inForeground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotificationUpdateMs = 0L

    private val delayedStop = Runnable {
        if (!latestBusy) {
            releaseLocks()
            if (inForeground) {
                inForeground = false
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            }
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
        enterForeground(latestTask.ifBlank { "Synchronizing Git repository…" }, latestFraction)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP && !latestBusy) {
            mainHandler.removeCallbacks(delayedStop)
            mainHandler.postDelayed(delayedStop, STOP_GRACE_MS)
            return START_NOT_STICKY
        }
        mainHandler.removeCallbacks(delayedStop)
        acquireLocks()
        val task = intent?.getStringExtra(EXTRA_TASK)?.takeIf { it.isNotBlank() }
            ?: latestTask.ifBlank { "Cloning repository…" }
        val fraction = intent?.getFloatExtra(EXTRA_FRACTION, latestFraction) ?: latestFraction
        if (!inForeground) {
            enterForeground(task, fraction)
        } else {
            val now = System.currentTimeMillis()
            if (now - lastNotificationUpdateMs >= NOTIFICATION_THROTTLE_MS) {
                lastNotificationUpdateMs = now
                updateNotification(task, fraction)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(delayedStop)
        releaseLocks()
        inForeground = false
        super.onDestroy()
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "building:vcs-network").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(MAX_LOCK_DURATION_MS)
            }
        }.onFailure { e ->
            log.warn("VcsForegroundService: wakeLock acquire failed: ${e.message}")
        }

        runCatching {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            if (wm != null && wifiLock == null) {
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockMode, "building:vcs-wifi").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }.onFailure { e ->
            log.warn("VcsForegroundService: wifiLock acquire failed: ${e.message}")
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun enterForeground(task: String, fraction: Float) {
        val notif = buildNotification(task, fraction)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        }.recoverCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        }.getOrElse { e ->
            log.warn("VcsForegroundService: startForeground failed: ${e.message}")
            false
        }
        inForeground = started
        lastNotificationUpdateMs = System.currentTimeMillis()
    }

    private fun updateNotification(task: String, fraction: Float) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, buildNotification(task, fraction))
        }
    }

    private fun buildNotification(task: String, fraction: Float): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Git Clone & Sync", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps Git repository clone and sync connections alive while multitasking"
                },
            )
        }
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val hasDeterminateProgress = fraction in 0f..1f
        val pct = if (hasDeterminateProgress) (fraction * 100f).roundToInt().coerceIn(0, 100) else -1
        val subtitle = if (pct >= 0) "$task ($pct%)" else task
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Git Clone & Sync")
            .setContentText(subtitle)
            .setSmallIcon(dev.ide.android.R.drawable.ic_stat_building)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (hasDeterminateProgress) {
                    setProgress(100, pct, false)
                } else {
                    setProgress(100, 0, true)
                }
                openIntent?.let { setContentIntent(it) }
            }
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "vcs_sync"
        private const val NOTIF_ID = 4719
        private const val ACTION_START_OR_UPDATE = "dev.ide.android.vcs.START_OR_UPDATE"
        private const val ACTION_STOP = "dev.ide.android.vcs.STOP"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_FRACTION = "fraction"
        private const val STOP_GRACE_MS = 1_500L
        private const val NOTIFICATION_THROTTLE_MS = 500L
        private const val MAX_LOCK_DURATION_MS = 15 * 60 * 1000L

        @Volatile
        private var latestBusy: Boolean = false

        @Volatile
        private var latestTask: String = ""

        @Volatile
        private var latestFraction: Float = -1f

        private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun bindToVcsActivity(context: Context, activityFlow: StateFlow<UiVcsActivity>) {
            val app = context.applicationContext
            watchScope.launch {
                var wasBusy = false
                var lastSentMs = 0L
                activityFlow.collect { act ->
                    latestBusy = act.busy
                    latestTask = act.task
                    latestFraction = act.fraction
                    if (act.busy) {
                        val now = System.currentTimeMillis()
                        if (!wasBusy || now - lastSentMs >= NOTIFICATION_THROTTLE_MS) {
                            wasBusy = true
                            lastSentMs = now
                            runCatching {
                                val intent = Intent(app, VcsForegroundService::class.java).apply {
                                    action = ACTION_START_OR_UPDATE
                                    putExtra(EXTRA_TASK, act.task.ifBlank { "Cloning repository…" })
                                    putExtra(EXTRA_FRACTION, act.fraction)
                                }
                                app.startForegroundService(intent)
                            }
                        }
                    } else if (wasBusy) {
                        wasBusy = false
                        runCatching {
                            val intent = Intent(app, VcsForegroundService::class.java).apply {
                                action = ACTION_STOP
                            }
                            app.startService(intent)
                        }
                    }
                }
            }
        }
    }
}
