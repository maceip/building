package dev.ide.android.reliability.repair

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import dev.ide.ui.backend.IdeBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths

val LocalIncidentRepairProject = staticCompositionLocalOf<AndroidIncidentRepairProject?> { null }
val LocalIncidentRepairError = staticCompositionLocalOf<String?> { null }

@Composable
fun IncidentRepairScope(backend: IdeBackend, projectEpoch: Int, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val root = remember(backend, projectEpoch) {
        try {
            backend.project.rootPath.takeIf { it.isNotBlank() }
        } catch (failure: IllegalStateException) {
            if (failure.message != "No project is open") throw failure
            null
        }
    }
    var project by remember(backend, root) { mutableStateOf<AndroidIncidentRepairProject?>(null) }
    var error by remember(backend, root) { mutableStateOf<String?>(null) }
    LaunchedEffect(backend, root) {
        if (root != null) {
            var opened: AndroidIncidentRepairProject? = null
            try {
                withContext(Dispatchers.IO) {
                    opened = AndroidIncidentRepairHost.get(context).project(Paths.get(root), backend.agent, scope)
                }
                project = opened
            } catch (cancelled: CancellationException) {
                opened?.close()
                throw cancelled
            } catch (_: Exception) {
                opened?.close()
                error = "Incident repair could not initialize for this project. No repair was started."
            }
        }
    }
    DisposableEffect(project) {
        val opened = project
        onDispose { opened?.close() }
    }
    CompositionLocalProvider(LocalIncidentRepairProject provides project, LocalIncidentRepairError provides error) {
        content()
    }
}
