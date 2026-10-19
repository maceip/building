package dev.ide.swiftpreview

import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pureswift.bridge.BridgeExport
import com.pureswift.swiftandroid.AndroidBridgeHost
import com.pureswift.swiftui.*

/** One in-process Swift tree. Upstream JNI transports typed values and patches.
 * The library has one global callback registry, so only one live pane is allowed.
 */
@Composable
fun SwiftPreview(modifier: Modifier = Modifier) {
    val store = remember { TreeStore() }
    var failure by remember { mutableStateOf<String?>(null) }
    DisposableEffect(store) {
        val session = runCatching { SwiftPreviewSession(store) }
            .onFailure { failure = it.message ?: it.javaClass.simpleName }.getOrNull()
        onDispose { session?.close() }
    }
    Box(modifier) {
        if (failure != null) Text("Swift preview stopped: $failure")
        else store.root?.let { RenderCupertinoPreview(it) } ?: Text("Starting Swift…")
    }
}

class SwiftRuntime {
    external fun start(store: TreeStore)
    external fun stop()
    external fun isRunning(): Boolean

    companion object {
        init {
            // JNI_OnLoad must run before SwiftJava creates any Java objects.
            System.loadLibrary("SwiftJava")
            System.loadLibrary("BuildingPreview")
        }
    }
}

class SwiftPreviewSession(private val store: TreeStore) : AutoCloseable {
    private val runtime = SwiftRuntime()
    private val previousSink: CallbackSink
    private val sink = JextractCallbackSink()
    private var closed = false

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!runtime.isRunning()) { "Close the other Swift preview first" }
        previousSink = SwiftBridge.sink
        BridgeExport.bridgeSetHost(AndroidBridgeHost())
        SwiftBridge.sink = sink
        runtime.start(store)
        Log.i("BuildingSwiftPreview", "JNI preview started")
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        runtime.stop()
        store.update(null)
        if (SwiftBridge.sink === sink) SwiftBridge.sink = previousSink
        check(!runtime.isRunning())
        Log.i("BuildingSwiftPreview", "JNI preview disposed")
    }
}
