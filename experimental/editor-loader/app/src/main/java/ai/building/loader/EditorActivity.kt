package ai.building.loader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.splitcompat.SplitCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorActivity : ComponentActivity() {
    private lateinit var editor: EditorState
    private lateinit var delivery: FeatureDelivery
    private var message by mutableStateOf("Editor works offline. Add JavaScript when you need it.")
    private var loading by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private var output by mutableStateOf("")
    private var settings by mutableStateOf(false)
    private var pendingAction by mutableStateOf<(() -> Unit)?>(null)
    private var enabled by mutableStateOf(true)
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri)
    }
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) save(uri)
    }
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        SplitCompat.installActivity(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor = EditorState(this)
        delivery = FeatureDelivery(this)
        enabled = getPreferences(MODE_PRIVATE).getBoolean("javascript", true)
        setContent { Editor() }
    }
    override fun onStop() {
        if (::editor.isInitialized) editor.persist()
        super.onStop()
    }
    override fun onDestroy() {
        if (::delivery.isInitialized) delivery.close()
        super.onDestroy()
    }
    private fun title(uri: Uri): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "document.txt"
    private fun retain(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }
    private fun load(uri: Uri) {
        loading = true
        lifecycleScope.launch {
            try {
                val (name, text) = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)!!.use { input ->
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (bytes.size() <= 1_000_000) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 1_000_001 - bytes.size()))
                            if (count < 0) break
                            bytes.write(buffer, 0, count)
                        }
                        bytes.toByteArray()
                    }
                    require(bytes.size <= 1_000_000) { "This prototype opens UTF-8 files up to 1 MB" }
                    val decoder = Charsets.UTF_8.newDecoder()
                    title(uri) to decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                }
                retain(uri); editor.open(uri, text, name); message = "Opened $name"
            } catch (error: Exception) { message = "Open failed: ${error.message}" }
            finally { loading = false }
        }
    }
    private fun save(uri: Uri) {
        val text = editor.value.text
        loading = true
        lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(text) }
                    title(uri)
                }
                retain(uri); editor.saved(uri, name, text); message = "Saved $name"
            } catch (error: Exception) { message = "Save failed: ${error.message}" }
            finally { loading = false }
        }
    }
    private fun replaceDocument(action: () -> Unit) {
        if (editor.dirty) pendingAction = action else action()
    }
    private fun runSource() {
        val source = editor.value.text
        running = true; output = "Starting JavaScript…"
        lifecycleScope.launch {
            output = try { withContext(Dispatchers.IO) { delivery.runtime(applicationContext).run(applicationContext, source) } }
            catch (error: Exception) { "Run failed: ${error.cause?.message ?: error.message}" }
            finally { running = false }
        }
    }

    @Composable
    private fun Editor() {
        var searching by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        val paper = Color(0xFFF7F6F2)
        val ink = Color(0xFF202A31)
        LaunchedEffect(editor.value.text, editor.uri) { delay(350); editor.persist() }
        MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF246354), background = paper,
            surface = paper, onSurface = ink)) {
            Column(Modifier.fillMaxSize().background(paper).safeDrawingPadding().imePadding()
                .semantics { testTagsAsResourceId = true }.padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.padding(vertical = 12.dp)) {
                        Text("building", fontSize = 23.sp)
                        Text("EDITOR / DELIVERY PROTOTYPE", fontSize = 10.sp, color = Color(0xFF66736F))
                    }
                    TextButton(onClick = { settings = true }, modifier = Modifier.testTag("settings")) { Text("Settings") }
                }
                HorizontalDivider()
                Text(editor.name + if (editor.dirty) " •" else "", modifier = Modifier.padding(top = 12.dp).testTag("filename"),
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { replaceDocument { editor.open(null, "", "untitled.js"); output = "" } }, enabled = !loading,
                        modifier = Modifier.testTag("new")) { Text("New") }
                    TextButton(onClick = { replaceDocument { openDocument.launch(arrayOf("text/*", "application/json", "application/javascript", "application/octet-stream")) } },
                        enabled = !loading, modifier = Modifier.testTag("open")) { Text("Open") }
                    TextButton(onClick = { editor.uri?.let(::save) ?: createDocument.launch(editor.name) }, enabled = !loading,
                        modifier = Modifier.testTag("save")) { Text("Save") }
                    TextButton(onClick = editor::undo, enabled = editor.canUndo && !loading, modifier = Modifier.testTag("undo")) { Text("Undo") }
                    TextButton(onClick = editor::redo, enabled = editor.canRedo && !loading, modifier = Modifier.testTag("redo")) { Text("Redo") }
                    TextButton(onClick = { searching = !searching }, modifier = Modifier.testTag("search")) { Text("Find") }
                }
                if (searching) Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(query, { query = it }, label = { Text("Find text") }, singleLine = true,
                        modifier = Modifier.weight(1f).testTag("query"))
                    TextButton(onClick = { message = if (editor.find(query)) "Match selected" else "No match" },
                        modifier = Modifier.testTag("find-next")) { Text("Next") }
                }
                BasicTextField(editor.value, editor::edit, enabled = !loading,
                    textStyle = TextStyle(color = ink, fontSize = 16.sp, lineHeight = 24.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f).fillMaxWidth().background(Color.White)
                        .padding(12.dp).verticalScroll(rememberScrollState()).testTag("editor"))
                if (output.isNotEmpty()) Text(output, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp).testTag("output"))
                Text(message, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp).testTag("message"))
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                        Text("JavaScript", fontSize = 13.sp)
                        Text(if (!enabled) "Disabled in settings" else delivery.status, fontSize = 11.sp,
                            modifier = Modifier.testTag("delivery-status"))
                    }
                    if (!delivery.installed) Button(onClick = if (delivery.busy) delivery::cancel else delivery::download,
                        modifier = Modifier.testTag(if (delivery.busy) "cancel" else "download")) {
                        Text(if (delivery.busy) "Cancel" else "Download")
                    } else Button(onClick = ::runSource, enabled = enabled && !running,
                        modifier = Modifier.testTag("run")) { Text(if (running) "Running…" else "Run") }
                }
            }
            if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Settings") },
                modifier = Modifier.semantics { testTagsAsResourceId = true },
                text = { Column {
                    Text("Editing is available offline. JavaScript is an optional download.")
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Enable JavaScript")
                        Switch(enabled, { enabled = it; getPreferences(MODE_PRIVATE).edit().putBoolean("javascript", it).apply() },
                            modifier = Modifier.testTag("runtime-enabled"))
                    }
                    Text("Turning it off keeps the downloaded files for later use.", fontSize = 12.sp)
                } }, confirmButton = { TextButton(onClick = { settings = false }, modifier = Modifier.testTag("settings-done")) { Text("Done") } })
            if (pendingAction != null) AlertDialog(onDismissRequest = { pendingAction = null },
                modifier = Modifier.semantics { testTagsAsResourceId = true },
                title = { Text("Unsaved changes") }, text = { Text("Save this document before opening another, or discard these changes.") },
                confirmButton = { TextButton(onClick = { val action = pendingAction; pendingAction = null; action?.invoke() },
                    modifier = Modifier.testTag("discard")) { Text("Discard changes") } },
                dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Keep editing") } })
        }
    }
}
