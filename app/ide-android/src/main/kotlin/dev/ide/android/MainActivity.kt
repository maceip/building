package dev.ide.android

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.ide.core.project.CaprojFormat
import dev.ide.core.IdeServicesBackend
import dev.ide.platform.log.Log
import io.github.oshai.kotlinlogging.KotlinLogging
import dev.ide.ui.CodeAssistApp
import dev.ide.ui.editor.preview.OffscreenPreviewRenderer
import dev.ide.ui.ext.AppKeys
import dev.ide.ui.ext.PreviewSnapshots
import dev.ide.android.preview.SurfaceComposer
import dev.ide.android.workbench.LocalWorkbenchRuntime
import dev.ide.android.workbench.WorkbenchUiPlugin
import dev.ide.android.workbench.runtime.AndroidRuntimeHost
import dev.ide.ui.ext.UiPluginHost
import dev.ide.workbench.runtime.RuntimeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android host. Bootstraps the real on-device engine ([AndroidIde]) off the main thread, then renders the
 * shared Compose IDE UI ([CodeAssistApp]) over the resulting [IdeBackend] — the same path the desktop runs.
 * The SAF/FileProvider plumbing lives in [AndroidFileOps]; the small host helpers (splash, "Save As" contract,
 * source-root search) in AndroidHostUi. This activity is just lifecycle + the Compose host + the SAF
 * launchers (which must be remembered in composition). A splash shows while the engine starts.
 */
class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.google.android.play.core.splitcompat.SplitCompat.installActivity(this)
    }

    private var session: AndroidIde.Session? = null
    private val workbenchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var workbenchRuntime: RuntimeController? = null

    /** A file handed in by another app ("Open with" / "Share to"), pending import once the engine is up. */
    private val inbound = mutableStateOf<Uri?>(null)

    /** Android file/SAF/FileProvider plumbing (byte-level import / share / export / install / reveal). */
    private val fileOps by lazy { AndroidFileOps(this) }

    /**
     * A store sign-in redirect that arrived before the engine finished booting.
     *
     * The browser can come back while `bootstrap` is still running (it runs off the main thread and takes
     * seconds on a cold start), and the account service does not exist until it finishes, so the link is
     * held here and delivered when the session appears.
     */
    private var pendingAuthRedirect: String? = null

    /**
     * A store item id from a `codeassist://store/...` link, pending the composition that can act on it.
     *
     * Held outside composition for the same reason [inbound] is: the link can arrive by `onNewIntent` on a
     * warm activity, or on the launch intent before anything is composed.
     */
    private val storeLink = mutableStateOf<String?>(null)
    private val autoRunProjects = mutableStateOf<String?>(null)
    private val klogger = KotlinLogging.logger("dev.ide.android.MainActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge with default (auto) bar styles; the system-bar ICON appearance is then driven reactively
        // by the app theme via `PlatformSystemBars` (light icons in dark mode, dark icons in light mode) — a fixed
        // `SystemBarStyle.dark` here forced light icons even in light mode.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidIde.ensureSnortConnected(applicationContext)
        Log.logger("dev.ide.android.MainActivity").traceSpan(
            operation = "android.activity.onCreate",
            initialAttributes = mapOf(
                "device.manufacturer" to (Build.MANUFACTURER ?: ""),
                "device.model" to (Build.MODEL ?: ""),
                "os.sdk_int" to Build.VERSION.SDK_INT.toString(),
            ),
        ) {
            klogger.atInfo {
                message = "MainActivity launched on physical Android device"
                payload = mapOf(
                    "device.manufacturer" to (Build.MANUFACTURER ?: ""),
                    "device.model" to (Build.MODEL ?: ""),
                    "os.release" to (Build.VERSION.RELEASE ?: ""),
                    "os.sdk_int" to Build.VERSION.SDK_INT,
                    "package" to packageName,
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
        val runtimeReliability = dev.ide.android.reliability.AndroidRuntimeReliability(applicationContext)
        workbenchRuntime = RuntimeController(AndroidRuntimeHost(applicationContext), workbenchScope, observer = runtimeReliability.runtimeObserver)
        UiPluginHost.register(WorkbenchUiPlugin)
        dev.ide.android.workbench.runtime.RuntimeBrowserBridge.get(applicationContext)
        // Before extractStream: a sign-in redirect is an ACTION_VIEW with a URI, which would otherwise be
        // taken for a file to import.
        if (!takeAuthRedirect(intent) && !takeStoreLink(intent) && !takeRuntimeCallback(intent)) inbound.value = extractStream(intent)
        intent?.getStringExtra("auto_run_projects")?.takeIf { it.isNotBlank() }?.let {
            autoRunProjects.value = it
            intent?.removeExtra("auto_run_projects")
        }

        setContent {
            var backend by remember { mutableStateOf<IdeBackend?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                runCatching { withContext(Dispatchers.IO) { AndroidIde.bootstrap(applicationContext) } }.onSuccess { s ->
                    session = s; backend = s.backend
                    AndroidFrameMetrics.register(this@MainActivity, s.backend)
                    // A redirect that landed mid-bootstrap now has somewhere to go.
                    deliverAuthRedirect()
                }.onFailure { e -> error = e.stackTraceToString() }
            }

            // POST_NOTIFICATIONS (Android 13+/API 33) is asked for at the FIRST build, not here at launch, so the
            // request lands in context — see BuildNotificationGate (:ide-ui). Nothing to request in this activity.
            val hostScope = rememberCoroutineScope()
            var pendingTarget by remember { mutableStateOf<String?>(null) }
            var pendingCallback by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
            val importLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    val b = backend
                    val target = pendingTarget
                    val callback = pendingCallback
                    pendingTarget = null; pendingCallback = null
                    hostScope.launch {
                        val created = runInterruptible(Dispatchers.IO) {
                            if (b != null && target != null) uris.mapNotNull { fileOps.importUri(it, target, b) } else emptyList()
                        }
                        callback?.invoke(created)
                    }
                }
            var pendingPick by remember { mutableStateOf<((String?) -> Unit)?>(null) }
            val pickLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    val callback = pendingPick
                    pendingPick = null
                    hostScope.launch {
                        val path = runInterruptible(Dispatchers.IO) { uri?.let { fileOps.copyUriToCache(it) } }
                        if (uri != null && path == null) {
                            Toast.makeText(this@MainActivity, "Couldn't import file: unreadable, invalid name, or too large", Toast.LENGTH_LONG).show()
                        }
                        callback?.invoke(path)
                    }
                }
            // Camera capture for the AI chat: the camera app writes into a FileProvider URI we hand it.
            var pendingPhoto by remember { mutableStateOf<Pair<File, (String?) -> Unit>?>(null) }
            val photoLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
                    val pending = pendingPhoto
                    pendingPhoto = null
                    val (file, cb) = pending ?: return@rememberLauncherForActivityResult
                    cb(if (saved && file.length() > 0) file.absolutePath else null)
                }
            // SAF providers are not POSIX filesystems; keep a linked private working copy with explicit sync.
            var pendingPickDir by remember { mutableStateOf<((String?) -> Unit)?>(null) }
            val pickDirLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    val cb = pendingPickDir
                    pendingPickDir = null
                    if (uri == null) { cb?.invoke(null); return@rememberLauncherForActivityResult }
                    Toast.makeText(this@MainActivity, "Opening linked working copy…", Toast.LENGTH_SHORT).show()
                    hostScope.launch {
                        try {
                            val path = runInterruptible(Dispatchers.IO) { fileOps.openLinkedTree(uri) }
                            cb?.invoke(path)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Toast.makeText(this@MainActivity, failure.message ?: "Couldn't open the linked folder", Toast.LENGTH_LONG).show()
                            cb?.invoke(null)
                        }
                    }
                }
            // "Save As" export: the user picks a destination (Files/Drive/Downloads); we copy the bytes there.
            var pendingExport by remember { mutableStateOf<String?>(null) }
            val exportLauncher =
                rememberLauncherForActivityResult(ExportDocumentContract()) { uri ->
                    val src = pendingExport
                    pendingExport = null
                    if (uri != null && src != null) fileOps.exportTo(uri, src)
                }
            val fileActions = remember {
                object : FileActions {
                    override val canImport: Boolean = true
                    override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) {
                        pendingTarget = targetDir
                        pendingCallback = onImported
                        try {
                            importLauncher.launch(arrayOf("text/*", "application/json", "application/xml", "*/*"))
                        } catch (e: ActivityNotFoundException) {
                            pendingTarget = null; pendingCallback = null
                            Toast.makeText(this@MainActivity, "No file manager available to import from", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override val canPickFile: Boolean = true
                    override fun pickFile(extensions: List<String>, onPicked: (String?) -> Unit) {
                        pendingPick = onPicked
                        // Custom extensions (e.g. .caproj) have no registered MIME, so fall back to */* and let
                        // the caller validate the picked file; known extensions narrow the SAF picker.
                        val mimes = extensions.mapNotNull { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                        try {
                            pickLauncher.launch(if (mimes.isEmpty()) arrayOf("*/*") else mimes.toTypedArray())
                        } catch (e: ActivityNotFoundException) {
                            // Some ROMs ship no SAF/documents provider; don't crash — signal cancel + tell the user.
                            pendingPick = null
                            onPicked(null)
                            Toast.makeText(this@MainActivity, "No file manager available to pick a file", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override val canTakePhoto: Boolean =
                        packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                    override fun takePhoto(onTaken: (String?) -> Unit) {
                        val (file, uri) = runCatching { fileOps.newCameraTarget() }.getOrElse { onTaken(null); return }
                        pendingPhoto = file to onTaken
                        try {
                            photoLauncher.launch(uri)
                        } catch (e: ActivityNotFoundException) {
                            pendingPhoto = null
                            onTaken(null)
                            Toast.makeText(this@MainActivity, "No camera app available", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override val canPasteImage: Boolean = true
                    override fun hasClipboardImage(): Boolean = fileOps.clipboardHasImage()
                    override fun pasteImage(onPasted: (String?) -> Unit) {
                        val uri = fileOps.clipboardImageUri()
                        hostScope.launch {
                            onPasted(runInterruptible(Dispatchers.IO) { uri?.let { fileOps.copyUriToCache(it) } })
                        }
                    }

                    override val canPickDirectory: Boolean = true
                    override fun pickDirectory(onPicked: (String?) -> Unit) {
                        pendingPickDir = onPicked
                        try {
                            pickDirLauncher.launch(null)
                        } catch (e: ActivityNotFoundException) {
                            pendingPickDir = null
                            onPicked(null)
                            Toast.makeText(this@MainActivity, "No file manager available to pick a folder", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override val canShare: Boolean = true
                    override fun share(path: String) = fileOps.shareFile(path)

                    override val canExport: Boolean = true
                    override fun exportFile(path: String) {
                        pendingExport = path
                        try {
                            exportLauncher.launch(File(path).name)
                        } catch (e: ActivityNotFoundException) {
                            pendingExport = null
                            Toast.makeText(this@MainActivity, "No file manager available to export to", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override val canOpenUrl: Boolean = true
                    override fun openUrl(url: String) = fileOps.openInBrowser(url)

                    override val canReveal: Boolean = true
                    override fun reveal(path: String) = fileOps.openInFiles(path)

                    override val canInstallApk: Boolean = true
                    override fun installApk(path: String) = fileOps.promptInstall(path)
                }
            }

            // A `.caproj` package handed in via "Open with" opens the import preview (see the branch below); any
            // other inbound file is copied into the open project's first source root as before.
            var importPackagePath by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(backend, inbound.value) {
                val b = backend
                val uri = inbound.value
                if (b != null && uri != null) {
                    val name = fileOps.queryDisplayName(uri) ?: ""
                    if (name.endsWith(".${CaprojFormat.EXTENSION}", ignoreCase = true)) {
                        val path = runInterruptible(Dispatchers.IO) { fileOps.copyInboundToCache(uri, name) }
                        if (path != null) importPackagePath = path
                        else Toast.makeText(this@MainActivity, "Couldn't open the project package", Toast.LENGTH_SHORT).show()
                    } else {
                        val target = firstSourceRoot(b.files.fileTree())
                        val path = runInterruptible(Dispatchers.IO) { if (target != null) fileOps.importUri(uri, target, b) else null }
                        Toast.makeText(
                            this@MainActivity,
                            if (path != null) "Imported ${File(path).name}" else "Couldn't import file",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    inbound.value = null
                }
            }

            val b = backend
            LaunchedEffect(b, autoRunProjects.value) {
                val ideBackend = b as? IdeServicesBackend ?: return@LaunchedEffect
                val spec = autoRunProjects.value ?: return@LaunchedEffect
                autoRunProjects.value = null
                workbenchScope.launch(Dispatchers.IO) {
                    runStagedProjectsOnDevice(ideBackend, spec)
                }
            }
            // On-device Compose preview: render @Preview composables through the interpreter. The backend instance
            // is stable across project switches (it swaps services internally), so one host suffices.
            val previewHost = remember(b) { (b as? IdeServicesBackend)?.let { AndroidComposePreviewHost(it) } }
            // The same previews rendered off screen, for the AI agent to look at a file the user has not opened.
            DisposableEffect(b, previewHost) {
                val registration = b?.let {
                    PreviewSnapshots.registerHeadless(OffscreenPreviewRenderer(it, previewHost, SurfaceComposer(applicationContext)))
                }
                onDispose { registration?.dispose() }
            }
            when {
                b != null -> CompositionLocalProvider(
                    LocalWorkbenchRuntime provides workbenchRuntime,
                    dev.ide.ui.ext.LocalSdkManagerTools provides { toolsBackend ->
                        dev.ide.android.workbench.runtime.AndroidDeveloperTools(toolsBackend)
                    },
                    dev.ide.ui.screens.LocalRuntimeDownloads provides { dev.ide.android.workbench.runtime.RuntimeDownloadPanel() },
                ) {
                    val projectEpoch by b.projects.projectEpoch.collectAsState()
                    var lastProjectEpoch by remember(b) { mutableStateOf(projectEpoch) }
                    LaunchedEffect(projectEpoch) {
                        if (lastProjectEpoch != projectEpoch) workbenchRuntime?.stop()
                        lastProjectEpoch = projectEpoch
                    }
                    dev.ide.android.reliability.repair.IncidentRepairScope(b, projectEpoch) {
                        CodeAssistApp(
                            b,
                            fileActions = fileActions,
                            composePreviewHost = previewHost,
                            importPackagePath = importPackagePath,
                            openStoreItemId = storeLink.value,
                            onStoreItemIdHandled = { storeLink.value = null },
                        )
                    }
                }

                error != null -> Splash("Failed to start: $error")
                else -> Splash("Starting building…")
            }
        }
    }

    /**
     * An app shortcut pressed while nothing in the Compose content has focus. With a focused node the root
     * listener in [CodeAssistApp] already had it; this only sees a key the content left unconsumed.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        super.dispatchKeyEvent(event) || AppKeys.onKey(androidx.compose.ui.input.key.KeyEvent(event))

    /** The app's shortcuts in Android's Keyboard Shortcuts list (Meta+/). */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<android.view.KeyboardShortcutGroup>,
        menu: android.view.Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data.addAll(AppKeyboardShortcuts.groups())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("auto_run_projects")?.takeIf { it.isNotBlank() }?.let {
            autoRunProjects.value = it
            intent.removeExtra("auto_run_projects")
            return
        }
        if (takeAuthRedirect(intent)) return
        if (takeStoreLink(intent)) return
        if (takeRuntimeCallback(intent)) return
        extractStream(intent)?.let { inbound.value = it }
    }

    private suspend fun runStagedProjectsOnDevice(backend: IdeServicesBackend, spec: String) {
        runCatching { backend.diagnostics.setAnalyticsConsent(false) }
        val mgr = backend.manager ?: return
        val projectsRoot = mgr.projectsRoot
        java.nio.file.Files.createDirectories(projectsRoot)
        val stageTar = File("/data/local/tmp/trending-oct20-stage.tar.gz")
        if (stageTar.isFile && stageTar.canRead()) {
            runCatching {
                ProcessBuilder("tar", "-m", "-xzf", stageTar.absolutePath, "-C", projectsRoot.toString())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }
        }
        val dirs = if (spec.trim().equals("all", ignoreCase = true)) {
            java.nio.file.Files.newDirectoryStream(projectsRoot).use { stream ->
                stream.filter { java.nio.file.Files.isDirectory(it) }.sortedBy { it.fileName.toString().lowercase() }
            }
        } else {
            spec.split(',').mapNotNull { token ->
                val name = token.trim()
                if (name.isEmpty()) null else projectsRoot.resolve(name).takeIf { java.nio.file.Files.isDirectory(it) }
            }
        }
        android.util.Log.i("TrendingVerifier", "AUTO_RUN_START count=${dirs.size} spec=$spec")
        var succeededCount = 0
        for (dir in dirs) {
            val name = dir.fileName.toString()
            try {
                val kind = mgr.inspectFolder(dir)
                val adopted = mgr.adoptProjectInPlace(dir)
                val opened = backend.projects.openProject(dir.toString())
                delay(250)
                val tasks = backend.build.runTasks()
                val firstTask = tasks.firstOrNull { it.group == "run" } ?: tasks.firstOrNull()
                if (firstTask == null) {
                    android.util.Log.e("TrendingVerifier", "AUTO_RUN_RESULT repo=$name kind=$kind adopted=$adopted opened=$opened task=none status=Failed")
                    continue
                }
                backend.build.runTask(firstTask.id)
                var sawRunning = false
                val deadline = System.currentTimeMillis() + 60_000L
                while (System.currentTimeMillis() < deadline) {
                    val status = backend.build.buildState.value.status
                    if (status == dev.ide.ui.backend.RunStatus.Running) {
                        sawRunning = true
                    } else if (sawRunning && (status == dev.ide.ui.backend.RunStatus.Succeeded || status == dev.ide.ui.backend.RunStatus.Failed)) {
                        break
                    } else if (!sawRunning && status == dev.ide.ui.backend.RunStatus.Succeeded && backend.build.buildState.value.log.isNotEmpty()) {
                        break
                    }
                    delay(50)
                }
                val finalState = backend.build.buildState.value
                val console = backend.build.runConsole.value
                val previewLine = finalState.log.map { it.message }.firstOrNull { it.contains("Preview: http://127.0.0.1:") } ?: ""
                val previewUrl = previewLine.substringAfter("Preview: ", "").trim()
                if (finalState.status == dev.ide.ui.backend.RunStatus.Succeeded) succeededCount++
                android.util.Log.i(
                    "TrendingVerifier",
                    "AUTO_RUN_RESULT repo=$name kind=$kind adopted=$adopted opened=$opened task=${firstTask.id} status=${finalState.status} guiFrame=${console?.frame != null} preview=$previewUrl",
                )
                if (finalState.status != dev.ide.ui.backend.RunStatus.Succeeded) {
                    val tail = finalState.log.takeLast(8).joinToString(" | ") { it.message }
                    android.util.Log.e("TrendingVerifier", "AUTO_RUN_FAIL_LOG repo=$name tail=$tail")
                }
                delay(250)
            } catch (t: Throwable) {
                android.util.Log.e("TrendingVerifier", "AUTO_RUN_RESULT repo=$name status=Exception error=${t.message}", t)
            }
        }
        android.util.Log.i("TrendingVerifier", "AUTO_RUN_COMPLETE succeeded=$succeededCount total=${dirs.size}")
    }

    /**
     * Claim [intent] if it is a developer runtime project OAuth or browser callback.
     */
    private fun takeRuntimeCallback(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        if (uri.scheme?.lowercase(java.util.Locale.ROOT) != "codeassist") return false
        val host = uri.host?.lowercase(java.util.Locale.ROOT)
        if (host == "callback" || host == "runtime-callback") {
            val url = uri.toString()
            dev.ide.android.workbench.runtime.RuntimeBrowserBridge.recordCallback(url)
            val previewUrl = workbenchRuntime?.state?.value?.previewUrl
            if (previewUrl != null) {
                dev.ide.android.workbench.runtime.RuntimeBrowserBridge.forwardCallbackToPreview(previewUrl, uri)
            }
            intent.data = null
            return true
        }
        return false
    }

    /**
     * Claim [intent] if it is a store link, and return whether it was claimed.
     *
     * The URI is cleared off the intent for the same reason the sign-in redirect is: this activity also
     * handles `file` and `content` VIEW intents, and a `codeassist://store/...` left sitting in
     * `getIntent()` would be read as a file to import on the next configuration change.
     */
    private fun takeStoreLink(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val url = intent.data?.toString()
        if (!dev.ide.store.StoreLink.isStoreLink(url)) return false
        // A bare `codeassist://store` names no item, which the app reads as "the Store tab".
        storeLink.value = dev.ide.store.StoreLink.itemId(url) ?: ""
        intent.data = null
        return true
    }

    /**
     * Claim [intent] if it is a store sign-in redirect, and return whether it was claimed.
     *
     * The URI is cleared off the intent once claimed: the activity is also the handler for `file` and
     * `content` VIEW intents, and a stale `codeassist://auth-callback` sitting in `getIntent()` would be
     * read as a file to import on the next configuration change. The tokens are in the fragment, so the
     * link is also not something to keep around.
     */
    private fun takeAuthRedirect(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val url = intent.data?.toString()
        if (!dev.ide.store.StoreAuth.isAuthRedirect(url)) return false
        pendingAuthRedirect = url
        intent.data = null
        deliverAuthRedirect()
        return true
    }

    /**
     * Hand a held redirect to the engine.
     *
     * Through the store service rather than the account port directly, so the sign-in state has one owner:
     * the engine reports the outcome on its auth flow, which is what any screen is watching. Returns
     * immediately; the token exchange happens off this thread.
     */
    private fun deliverAuthRedirect() {
        val store = session?.backend?.store ?: return
        val url = pendingAuthRedirect ?: return
        pendingAuthRedirect = null
        store.completeSignIn(url)
    }

    override fun onResume() {
        super.onResume()
        workbenchScope.launch(Dispatchers.IO) {
            runCatching { dev.ide.android.reliability.AndroidRuntimeReliability(applicationContext).capture.replay() }
        }
    }

    override fun onDestroy() {
        workbenchRuntime?.close()
        workbenchRuntime = null
        workbenchScope.cancel()
        super.onDestroy()
        // Close the *active* engine (a project switch may have swapped it), not just the initial one.
        session?.backend?.close()
    }

    private fun extractStream(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        else -> null
    }
}
