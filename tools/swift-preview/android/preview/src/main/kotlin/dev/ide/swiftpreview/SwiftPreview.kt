package dev.ide.swiftpreview

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pureswift.swiftui.CallbackSink
import com.pureswift.swiftui.Render
import com.pureswift.swiftui.SwiftBridge
import com.pureswift.swiftui.ViewNode
import java.io.File
import java.util.concurrent.Executors
import kotlinx.serialization.json.*

/** Embeddable preview. The caller supplies an executable and its runtime library directory.
 * Disposing the pane kills the Swift child. This renderer currently permits one live pane.
 */
@Composable
fun SwiftPreview(executable: File, libraries: File, modifier: Modifier = Modifier) {
    var tree by remember(executable, libraries) { mutableStateOf<ViewNode?>(null) }
    var failure by remember(executable, libraries) { mutableStateOf<String?>(null) }
    DisposableEffect(executable, libraries) {
        val session = SwiftPreviewSession(executable, libraries,
            onTree = { tree = cupertino(it) }, onFailure = { failure = it })
        session.start()
        onDispose { session.close() }
    }
    Box(modifier) {
        val error = failure
        val root = tree
        when {
            error != null -> Text("Swift preview stopped: $error")
            root != null -> Render(root)
            else -> Text("Starting Swift…")
        }
    }
}

internal class SwiftPreviewSession(
    private val executable: File,
    private val libraries: File,
    private val onTree: (ViewNode) -> Unit,
    private val onFailure: (String) -> Unit,
) : CallbackSink, AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var process: Process? = null
    @Volatile private var closed = false
    private var previousSink: CallbackSink? = null
    private val errorTail = StringBuilder()

    fun start() {
        // SwiftBridge is global in the existing renderer. Refuse a second host
        // rather than sending one app's events into another app's process.
        if (SwiftBridge.sink is SwiftPreviewSession) {
            onFailure("Close the other Swift preview before opening this one.")
            close()
            return
        }
        previousSink = SwiftBridge.sink
        SwiftBridge.sink = this
        Thread({
            try {
                val child = ProcessBuilder(executable.absolutePath).apply {
                    environment()["LD_LIBRARY_PATH"] = libraries.absolutePath
                }.start()
                synchronized(this) {
                    if (closed) { child.destroy(); return@Thread }
                    process = child
                }
                Thread({
                    child.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line -> synchronized(errorTail) {
                            errorTail.append(line).append('\n')
                            if (errorTail.length > 4096) errorTail.delete(0, errorTail.length - 4096)
                        } }
                    }
                }, "swift-preview-stderr").apply { isDaemon = true; start() }
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        require(line.length <= 1_048_576) { "View tree exceeds the preview limit" }
                        val root = Json.decodeFromString<ViewNode>(line)
                        main.post { if (!closed) onTree(root) }
                    }
                }
                val exit = child.waitFor()
                val details = synchronized(errorTail) { errorTail.toString().takeLast(1500) }
                fail("Process exited ($exit). $details")
            } catch (error: Exception) {
                fail(error.message ?: error.javaClass.simpleName)
            }
        }, "swift-preview-reader").apply { isDaemon = true; start() }
    }

    private fun fail(message: String) {
        main.post { if (!closed) { onFailure(message); close() } }
    }

    private fun event(id: Long, kind: String, value: JsonElement? = null) {
        if (closed) return
        val record = buildJsonObject {
            // String ids survive JSON consumers without 53-bit truncation.
            put("id", id.toString()); put("kind", kind)
            if (value != null) put("value", value)
        }.toString() + "\n"
        writer.execute {
            try {
                val stream = process?.outputStream ?: error("Swift process is not ready")
                stream.write(record.toByteArray(Charsets.UTF_8)); stream.flush()
            } catch (error: Exception) { fail(error.message ?: "Callback failed") }
        }
    }

    override fun invokeVoid(id: Long) = event(id, "void")
    override fun invokeBool(id: Long, value: Boolean) = event(id, "bool", JsonPrimitive(value))
    override fun invokeDouble(id: Long, value: Double) = event(id, "double", JsonPrimitive(value))
    override fun invokeInt(id: Long, value: Int) = event(id, "int", JsonPrimitive(value))
    override fun invokeString(id: Long, value: String) = event(id, "string", JsonPrimitive(value))

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            process?.destroy()
        }
        writer.shutdownNow()
        if (SwiftBridge.sink === this) previousSink?.let { SwiftBridge.sink = it }
    }
}

/** Apply Cupertino styling to ordinary SwiftUI controls, retaining callback ids,
 * structural identity, labels, modifiers, and Swift-owned values.
 */
internal fun cupertino(node: ViewNode): ViewNode {
    val children = node.children.map(::cupertino)
    fun component(name: String, props: Map<String, JsonElement>, content: List<ViewNode> = children) =
        node.copy(type = "Composable", props = JsonObject(props + ("name" to JsonPrimitive(name))), children = content)
    fun slot(name: String, content: List<ViewNode>) = ViewNode("CupertinoSlot", "${node.id}/$name",
        props = buildJsonObject { put("name", name) }, children = content)
    return when (node.type) {
        "Text" -> component("CupertinoText", node.props)
        // These two common symbols are sufficient for the pinned upstream
        // counter. The square minus has no shared Cupertino vector, so use a
        // visible minus glyph. Keep other unsupported symbols diagnostic.
        "Image" -> when (node.props["systemName"]?.jsonPrimitive?.content) {
            "plus.square.fill" -> component("CupertinoIcon", buildJsonObject {
                put("imageVector", "CupertinoIcons.Filled.PlusSquare")
                put("contentDescription", "Increment")
            })
            "minus.square.fill" -> component("CupertinoText", buildJsonObject { put("text", "−") })
            else -> node.copy(children = children)
        }
        "Button" -> component("CupertinoButton", mapOf("onClick" to node.props.getValue("onTap")),
            listOf(slot("content", children)))
        "Toggle" -> node.copy(type = "HStack", props = buildJsonObject { put("spacing", 12) },
            children = children + ViewNode("Spacer", "${node.id}/space") +
                component("CupertinoSwitch", mapOf("checked" to node.props.getValue("isOn"),
                    "onCheckedChange" to node.props.getValue("onChange")), emptyList()).copy(
                    id = "${node.id}/switch", modifiers = emptyList()))
        "TextField" -> if (node.props["secure"] == JsonPrimitive(true)) node.copy(children = children) else
            component("CupertinoBorderedTextField", mapOf("value" to node.props.getValue("text"),
                "onValueChange" to node.props.getValue("onChange")), listOf(slot("placeholder", listOf(
                    ViewNode("Composable", "${node.id}/placeholder/text", props = buildJsonObject {
                        put("name", "CupertinoText"); put("text", node.props["placeholder"] ?: JsonPrimitive(""))
                    })))))
        else -> node.copy(children = children)
    }
}
