package ai.building.loader

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.ide.ui.editor.core.EditorDocument
import org.json.JSONObject
import java.io.File

/** Reuses the product's immutable text model without importing its IDE dependency graph. */
class EditorState(private val context: Context) {
    private data class Revision(val document: EditorDocument, val selection: TextRange)
    private var document = EditorDocument.of("")
    private val past = ArrayDeque<Revision>()
    private val future = ArrayDeque<Revision>()
    var value by mutableStateOf(TextFieldValue("")); private set
    var uri by mutableStateOf<Uri?>(null); private set
    var name by mutableStateOf("untitled.js"); private set
    var dirty by mutableStateOf(false); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var savedText = ""; private set
    private val draft = AtomicFile(File(context.filesDir, "draft.json"))

    init {
        runCatching {
            val json = draft.openRead().bufferedReader().use { JSONObject(it.readText()) }
            document = EditorDocument.of(json.getString("text"))
            value = TextFieldValue(document.text, TextRange(json.optInt("selection").coerceIn(0, document.length)))
            uri = json.optString("uri").takeIf { it.isNotEmpty() }?.let(Uri::parse)
            name = json.getString("name")
            savedText = json.getString("savedText")
            dirty = document.text != savedText
        }
    }
    fun edit(next: TextFieldValue) {
        if (next.text != value.text) {
            past.addLast(Revision(document, value.selection))
            if (past.size > 100) past.removeFirst()
            future.clear()
            // Send only the changed span to the existing persistent rope.
            val old = value.text
            var start = 0
            while (start < minOf(old.length, next.text.length) && old[start] == next.text[start]) start++
            var suffix = 0
            while (suffix < minOf(old.length, next.text.length) - start &&
                old[old.lastIndex - suffix] == next.text[next.text.lastIndex - suffix]) suffix++
            document = document.replace(start, old.length - suffix, next.text.substring(start, next.text.length - suffix))
        }
        value = next
        update()
    }
    fun undo() = move(past, future)
    fun redo() = move(future, past)
    private fun move(from: ArrayDeque<Revision>, to: ArrayDeque<Revision>) {
        if (from.isEmpty()) return
        to.addLast(Revision(document, value.selection))
        val revision = from.removeLast()
        document = revision.document
        value = TextFieldValue(document.text, revision.selection)
        update()
    }
    fun find(query: String): Boolean {
        if (query.isEmpty()) return false
        val found = value.text.indexOf(query, value.selection.max).takeIf { it >= 0 }
            ?: value.text.indexOf(query).takeIf { it >= 0 } ?: return false
        value = value.copy(selection = TextRange(found, found + query.length))
        return true
    }
    fun open(target: Uri?, text: String, title: String) {
        uri = target; name = title; savedText = text
        document = EditorDocument.of(text); value = TextFieldValue(text)
        past.clear(); future.clear(); update(); persist()
    }
    fun saved(target: Uri, title: String, text: String) {
        uri = target; name = title; savedText = text; update(); persist()
    }
    private fun update() {
        canUndo = past.isNotEmpty(); canRedo = future.isNotEmpty(); dirty = value.text != savedText
    }
    fun persist() {
        val json = JSONObject().put("text", value.text).put("selection", value.selection.end)
            .put("uri", uri?.toString().orEmpty()).put("name", name).put("savedText", savedText)
        val stream = draft.startWrite()
        try { stream.write(json.toString().toByteArray()); draft.finishWrite(stream) }
        catch (error: Exception) { draft.failWrite(stream); throw error }
    }
}
