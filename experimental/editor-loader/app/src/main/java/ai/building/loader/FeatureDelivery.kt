package ai.building.loader

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus

class FeatureDelivery(private val activity: Activity) {
    private val manager = SplitInstallManagerFactory.create(activity)
    var installed by mutableStateOf("node_runtime" in manager.installedModules); private set
    var status by mutableStateOf(if (installed) "Ready offline" else "Not downloaded"); private set
    var busy by mutableStateOf(false); private set
    var session = 0; private set
    private var cancelRequested = false
    private val listener = SplitInstallStateUpdatedListener { state ->
        if ("node_runtime" in state.moduleNames()) {
            session = state.sessionId()
            busy = state.status() in setOf(1, 2, 3, 4, 8, 9)
            status = when (state.status()) {
                SplitInstallSessionStatus.PENDING -> "Waiting for download"
                SplitInstallSessionStatus.DOWNLOADING -> "Downloading ${state.bytesDownloaded() / 1_000_000} / ${state.totalBytesToDownload() / 1_000_000} MB"
                SplitInstallSessionStatus.DOWNLOADED, SplitInstallSessionStatus.INSTALLING -> "Installing"
                SplitInstallSessionStatus.INSTALLED -> { installed = true; SplitCompat.install(activity); "Ready offline" }
                SplitInstallSessionStatus.CANCELED -> "Download canceled. You can retry."
                SplitInstallSessionStatus.CANCELING -> "Canceling"
                SplitInstallSessionStatus.FAILED -> "Download failed (${state.errorCode()}). You can retry."
                SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION -> {
                    manager.startConfirmationDialogForResult(state, activity, 601)
                    "Waiting for your download confirmation"
                }
                else -> "Not downloaded"
            }
        }
    }
    init {
        manager.registerListener(listener)
        manager.sessionStates.addOnSuccessListener { states -> states.forEach(listener::onStateUpdate) }
    }
    fun download() {
        if (busy || installed) return
        cancelRequested = false; session = 0
        busy = true; status = "Requesting download"
        manager.startInstall(SplitInstallRequest.newBuilder().addModule("node_runtime").build())
            .addOnSuccessListener { session = it; if (cancelRequested) cancel() }
            .addOnFailureListener { busy = false; status = "Download failed: ${it.message}. You can retry." }
    }
    fun cancel() {
        cancelRequested = true
        if (session > 0) manager.cancelInstall(session).addOnFailureListener { status = "Cancel failed: ${it.message}" }
    }
    fun close() = manager.unregisterListener(listener)
    fun runtime(context: Context): RuntimeFeature {
        check(installed) { "Download JavaScript first" }
        SplitCompat.install(context)
        return Class.forName("ai.building.loader.node.NodeRuntime", true, context.classLoader)
            .getDeclaredConstructor().newInstance() as RuntimeFeature
    }
}
