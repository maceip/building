package dev.ide.android.gradle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import dev.ide.gradle.GradleUserProperties
import dev.ide.gradle.PrefixProbe
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Foreground service that owns the tooling JVM. It runs in the IDE process. The JVM is a child,
 * started as `/system/bin/linker64 $JAVA_HOME/bin/java -jar …` with `LD_PRELOAD` only in that
 * child's environment. Idle timeout destroys the JVM. The client reports a dead JVM as a failed
 * build, with the process's last stderr lines.
 */
class GradleBuildService : Service() {

    private val live = ConcurrentHashMap.newKeySet<Process>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var inForeground = false

    private val idleStop = Runnable {
        if (live.isEmpty()) {
            if (inForeground) {
                inForeground = false
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            }
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        enterForeground("Preparing Gradle")
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        mainHandler.removeCallbacks(idleStop)
        live.forEach { process -> runCatching { process.destroy() } }
        live.clear()
        super.onDestroy()
    }

    /**
     * argv is literally the system linker, the JDK's `java`, and `-jar`. The environment is the
     * one the host built for this child. It is not applied to the IDE process.
     */
    fun spawn(javaHome: Path, jar: Path, env: Map<String, String>): Process {
        mainHandler.removeCallbacks(idleStop)
        val linker = PrefixProbe.linkerFor(Build.SUPPORTED_ABIS.firstOrNull())
        val java = javaHome.resolve("bin/java").toString()
        val process = ProcessBuilder(linker, java, "-jar", jar.toString())
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .apply { environment().putAll(env) }
            .start()
        live.add(process)
        enterForeground("Gradle is running")
        thread(isDaemon = true, name = "gradle-jvm") {
            runCatching { process.waitFor() }
            live.remove(process)
            mainHandler.post {
                if (live.isEmpty()) {
                    enterForeground("Gradle is idle")
                    mainHandler.removeCallbacks(idleStop)
                    mainHandler.postDelayed(idleStop, GradleUserProperties.IDLE_TIMEOUT_MS.toLong())
                }
            }
        }
        return process
    }

    private fun enterForeground(text: String) {
        runCatching {
            val notif = buildNotification(text)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            inForeground = true
        }.onFailure { inForeground = false }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gradle", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while building is running a Gradle build"
                },
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Gradle")
            .setContentText(text)
            .setSmallIcon(dev.ide.android.R.drawable.ic_stat_building)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { open?.let { setContentIntent(it) } }
            .build()
    }

    inner class LocalBinder : Binder() {
        fun spawn(javaHome: Path, jar: Path, env: Map<String, String>): Process =
            this@GradleBuildService.spawn(javaHome, jar, env)
    }

    companion object {
        private const val CHANNEL_ID = "gradle"
        private const val NOTIF_ID = 4712

        fun acquire(context: Context, javaHome: Path, jar: Path, env: Map<String, String>): Process {
            val app = context.applicationContext
            val intent = Intent(app, GradleBuildService::class.java)
            app.startForegroundService(intent)
            val latch = CountDownLatch(1)
            val holder = AtomicReference<LocalBinder>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    holder.set(service as LocalBinder)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {}
            }
            check(app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                "Couldn't bind the Gradle build service."
            }
            try {
                check(latch.await(15, TimeUnit.SECONDS)) { "The Gradle build service did not start." }
                return holder.get().spawn(javaHome, jar, env)
            } finally {
                runCatching { app.unbindService(connection) }
            }
        }
    }
}
