package dev.ide.crawler

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import dev.ide.crawler.crawler.TokenStore
import dev.ide.crawler.service.CrawlerService

/**
 * The status screen.
 *
 * Shows how big the local index is, how much of the GitHub quota is left,
 * lets the user paste a token, and starts or stops the background crawler.
 * It is a plain View-based screen - the app has no AndroidX or Compose
 * dependency, which keeps the APK small for something that runs in the
 * background for weeks.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var toggleButton: Button
    private lateinit var tokenInput: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val tokens by lazy { TokenStore(this) }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        toggleButton = findViewById(R.id.toggleButton)
        tokenInput = findViewById(R.id.tokenInput)

        findViewById<Button>(R.id.toggleButton).setOnClickListener { onToggle() }
        findViewById<Button>(R.id.cycleButton).setOnClickListener { onRunCycle() }
        findViewById<Button>(R.id.saveTokenButton).setOnClickListener { onSaveToken() }
        findViewById<Button>(R.id.clearTokenButton).setOnClickListener { onClearToken() }

        // The crawler only reads the token file when a cycle runs.
        CrawlerRuntime.ensureStack(this)
        CrawlerRuntime.onTokenChanged(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    // ------------------------------------------------------------------
    private fun onToggle() {
        val running = CrawlerRuntime.status.value.running
        if (running) {
            CrawlerService.stop(this)
            CrawlerRuntime.log("stop requested")
        } else {
            if (tokens.read().isNullOrBlank()) {
                CrawlerRuntime.log("paste a GitHub personal access token first")
                tokenInput.requestFocus()
                return
            }
            requestNotificationPermissionIfNeeded()
            CrawlerService.start(this)
        }
        render()
    }

    /**
     * On Android 13+ the foreground-service notification is only shown in the
     * shade when POST_NOTIFICATIONS is granted. The service runs either way,
     * but a hidden notification makes it look like the crawler stopped.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        val granted = checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(permission), REQUEST_POST_NOTIFICATIONS)
        }
    }

    private fun onRunCycle() {
        if (tokens.read().isNullOrBlank()) {
            CrawlerRuntime.log("paste a GitHub personal access token first")
            tokenInput.requestFocus()
            return
        }
        CrawlerRuntime.log("one-shot cycle requested")
        val serviceIntent = android.content.Intent(this, CrawlerService::class.java)
        serviceIntent.action = CrawlerService.ACTION_ONE_CYCLE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun onSaveToken() {
        val value = tokenInput.text.toString()
        if (value.isBlank()) {
            CrawlerRuntime.log("no token to save")
            return
        }
        if (tokens.write(value)) {
            tokenInput.setText("")
            CrawlerRuntime.onTokenChanged(this)
            CrawlerRuntime.log("token saved")
        } else {
            CrawlerRuntime.log("could not save the token")
        }
        render()
    }

    private fun onClearToken() {
        tokens.clear()
        tokenInput.setText("")
        CrawlerRuntime.onTokenChanged(this)
        CrawlerRuntime.log("token cleared")
        render()
    }

    // ------------------------------------------------------------------
    private fun render() {
        val state = CrawlerRuntime.status.value
        val stats = state.stats
        val status = buildString {
            append("state      : ").append(if (state.running) "running" else "stopped").append('\n')
            append("token      : ").append(state.tokenDescription).append('\n')
            append("repos      : ").append(stats.repos).append('\n')
            append("developers : ").append(stats.developers).append('\n')
            append("edges      : ").append(stats.edges).append('\n')
            append("snapshots  : ").append(stats.snapshots).append('\n')
            append("api calls  : ").append(stats.apiCallsLastHour).append(" in the last hour\n")
            state.coreRemaining?.let { append("core quota : ").append(it).append(" left\n") }
            state.graphqlRemaining?.let { append("gql quota  : ").append(it).append(" left\n") }
            append("last cycle : ").append(state.lastCycleSummary)
        }
        statusView.text = status
        logView.text = state.logLines.joinToString("\n")
        toggleButton.text = getString(
            if (state.running) R.string.stop_crawling else R.string.start_crawling
        )
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1_000L
        private const val REQUEST_POST_NOTIFICATIONS = 1001
    }
}
