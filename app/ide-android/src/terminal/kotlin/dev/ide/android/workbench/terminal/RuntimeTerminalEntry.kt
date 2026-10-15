@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.ide.android.workbench.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.nio.file.Path

/** Main RuntimePanel only needs this entry; no regular run/controller or shared form modifications. */
@Composable
fun RuntimeTerminalEntry(workingDirectory: Path?, enabled: Boolean = true) {
    var consent by remember { mutableStateOf(false) }
    var launch by remember { mutableStateOf<TerminalLaunch?>(null) }
    OutlinedButton(onClick = { consent = true }, enabled = enabled, modifier = Modifier.testTag("runtime-terminal-open")) { Text("Terminal…") }
    if (consent) AlertDialog(
        onDismissRequest = { consent = false },
        title = { Text("Open interactive terminal?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Commands run as this app's Android UID with its file and network permissions. This is NOT an isolated sandbox. Run only commands and dependencies you trust.")
                Text("Working directory: ${workingDirectory ?: "Runtime home"}")
                Text("Writes are direct to this folder, not an internal runtime copy. External storage may not support npm symlinks/native tools. This terminal does not sync SAF folders.")
                Text("Uses a real PTY and VT terminal: Node REPL, shell jobs, ANSI colors, cursor keys and resize. npm install can download code and execute lifecycle scripts. Stop/Close terminates the session. Older devices without safe per-process signaling show a warning because separate job groups may remain.")
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { consent = false; launch = TerminalLaunch(workingDirectory, RuntimeTerminalSession.Program.NODE) }) { Text("Trust & Node REPL") }
                Button(onClick = { consent = false; launch = TerminalLaunch(workingDirectory, RuntimeTerminalSession.Program.SHELL) }) { Text("Trust & open shell") }
            }
        },
        dismissButton = { TextButton(onClick = { consent = false }) { Text("Cancel") } },
    )
    launch?.let { request -> TerminalDialog(request) { launch = null } }
}

/** Never coerce a clipboard URI/Intent: that can perform unbounded provider I/O on Main. */
internal fun terminalClipboardText(item: ClipData.Item?): String {
    val text = requireNotNull(item?.text) { "Only plain-text clipboard content can be pasted. Copy text instead of a file or link item." }
    require(text.length <= 16 * 1024) { "Paste is limited to 16 KiB at a time." }
    val plain = text.toString()
    require(plain.toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "Paste is limited to 16 KiB at a time." }
    return plain
}

private data class TerminalLaunch(val directory: Path?, val program: RuntimeTerminalSession.Program)

@Composable
private fun TerminalDialog(request: TerminalLaunch, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val session = remember(request) {
        RuntimeTerminalSession(context.applicationContext,
            outcomeObserverFactory = dev.ide.android.reliability.AndroidRuntimeReliability(context).terminalObserver)
    }
    val status by session.status.collectAsState()
    val frame by session.frame.collectAsState()
    var view by remember { mutableStateOf<RuntimeTerminalView?>(null) }
    var clipboardPaste by remember { mutableStateOf<String?>(null) }
    var clipboardError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(session) {
        session.start(request.directory, request.program)
        onDispose { session.stop() }
    }
    fun close() { session.stop(); onDismiss() }
    val running = status.phase == RuntimeTerminalSession.Phase.RUNNING
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("${status.title} · ${status.phase.name.lowercase()}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("runtime-terminal-status"))
                    status.exitCode?.let { Text("Exit code $it", style = MaterialTheme.typography.bodySmall) }
                    Text(status.directory.ifEmpty { request.directory?.toString() ?: "Runtime home" }, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = session::stop, enabled = status.phase in setOf(RuntimeTerminalSession.Phase.STARTING, RuntimeTerminalSession.Phase.RUNNING), modifier = Modifier.testTag("runtime-terminal-stop")) { Text("Stop") }
                        TextButton(onClick = { view?.showKeyboard() }, enabled = running) { Text("Keyboard") }
                        TextButton(onClick = {
                            clipboardError = null
                            try {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                                clipboardPaste = terminalClipboardText(item)
                            } catch (failure: Exception) {
                                clipboardError = failure.message ?: "Clipboard text is unavailable."
                            }
                        }, enabled = running) { Text("Paste…") }
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", view?.visibleText().orEmpty()))
                        }) { Text("Copy screen") }
                        TextButton(onClick = ::close, modifier = Modifier.testTag("runtime-terminal-close")) { Text("Close") }
                    }
                    status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    clipboardError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    status.cleanupWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                if (status.phase == RuntimeTerminalSession.Phase.STARTING) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(
                    factory = { RuntimeTerminalView(it).also { terminal -> terminal.session = session; view = terminal } },
                    update = { terminal -> frame; terminal.refresh() },
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("runtime-terminal-screen"),
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf("Ctrl-C" to "\u0003", "Ctrl-D" to "\u0004", "Ctrl-Z" to "\u001a", "Ctrl-L" to "\u000c").forEach { (label, sequence) ->
                        TextButton(onClick = { session.send(sequence) }, enabled = running) { Text(label) }
                    }
                    listOf("Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB, "Left" to KeyEvent.KEYCODE_DPAD_LEFT, "Up" to KeyEvent.KEYCODE_DPAD_UP, "Down" to KeyEvent.KEYCODE_DPAD_DOWN, "Right" to KeyEvent.KEYCODE_DPAD_RIGHT, "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END).forEach { (label, code) ->
                        TextButton(onClick = { view?.sendKey(code) }, enabled = running) { Text(label) }
                    }
                }
            }
        }
        clipboardPaste?.let { text ->
            AlertDialog(
                onDismissRequest = { clipboardPaste = null }, title = { Text("Paste into terminal?") },
                text = { Text("Clipboard text can contain newlines and execute commands. Paste only text you trust. ${text.length} characters; 16 KiB maximum.") },
                confirmButton = { TextButton(onClick = { clipboardPaste = null; session.paste(text) }, enabled = text.toByteArray(Charsets.UTF_8).size <= 16384 && running) { Text("Paste") } },
                dismissButton = { TextButton(onClick = { clipboardPaste = null }) { Text("Cancel") } },
            )
        }
    }
}
