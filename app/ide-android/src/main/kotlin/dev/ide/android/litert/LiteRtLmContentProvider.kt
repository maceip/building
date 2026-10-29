package dev.ide.android.litert

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import dev.ide.android.AndroidIde
import dev.ide.core.agent.LiteRtIpcBridge
import java.io.FileNotFoundException
import java.nio.file.Path

/**
 * Android [ContentProvider] IPC bridge (`content://<applicationId>.litertlm/...`) for the
 * Self-Serve LiteRT-LM Corner.
 *
 * Allows guest Android apps built and installed on the phone to:
 * 1. Query installed `.litertlm` models via [query] (`content://<authority>/models`).
 * 2. Run on-device LLM inference over Binder via [call] (`method = "generate"`, `"status"`, `"list_models"`).
 * 3. Open read-only [ParcelFileDescriptor] handles to verified `.litertlm` artifacts via [openFile]
 *    (`content://<authority>/models/<modelId>`) for zero-copy `mmap` with the LiteRT / MediaPipe GenAI SDK
 *    without duplicating multi-hundred-MB model weights into each guest APK.
 */
class LiteRtLmContentProvider : ContentProvider() {

    private fun resolveStorageRoot(): Path {
        val ctx = context ?: throw IllegalStateException("LiteRtLmContentProvider context is not attached.")
        return AndroidIde.externalHome(ctx).toPath()
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: DEFAULT_COLUMNS
        val cursor = MatrixCursor(cols)
        val rows = runCatching { LiteRtIpcBridge.listInstalledModels(resolveStorageRoot()) }.getOrDefault(emptyList())
        val requestedId = uri.pathSegments.takeIf { it.size >= 2 && it[0] == "models" }?.get(1)
        for (row in rows) {
            if (requestedId != null && requestedId != row.id && !(requestedId == "active" && row.active)) {
                continue
            }
            val values = Array<Any?>(cols.size) { idx ->
                when (cols[idx]) {
                    "model_id" -> row.id
                    "display_name" -> row.displayName
                    "family" -> row.family
                    "parameters" -> row.parameterCount
                    "quantization" -> row.quantization
                    "size_bytes" -> row.sizeBytes
                    "sha256" -> row.sha256
                    "local_path" -> row.localFilePath
                    "active" -> if (row.active) 1 else 0
                    else -> null
                }
            }
            cursor.addRow(values)
        }
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val root = resolveStorageRoot()
        val out = Bundle()
        when (method) {
            "status" -> {
                val snap = LiteRtIpcBridge.statusSnapshot(root)
                out.putBoolean("ok", true)
                out.putString("soc", snap.hardware.socName)
                out.putString("delegate", snap.hardware.selectedDelegate.name)
                out.putString("active_model_id", snap.activeModelId)
                out.putBoolean("server_running", snap.server.running)
                out.putInt("server_port", snap.server.port)
                out.putString("openai_endpoint", snap.server.openAiEndpoint)
                out.putString("native_endpoint", snap.server.nativeEndpoint)
                out.putString("ipc_uri", snap.server.ipcUri)
                out.putInt("installed_count", snap.models.count { it.sizeOnDiskBytes > 0L })
            }
            "list_models" -> {
                val installed = LiteRtIpcBridge.listInstalledModels(root)
                out.putBoolean("ok", true)
                out.putStringArrayList("models", ArrayList(installed.map { it.id }))
                out.putString("active_model_id", installed.firstOrNull { it.active }?.id.orEmpty())
            }
            "generate" -> {
                val prompt = arg?.takeIf { it.isNotBlank() }
                    ?: extras?.getString("prompt").orEmpty()
                val system = extras?.getString("system")
                val modelId = extras?.getString("model")
                val delegateId = extras?.getString("delegate")
                val maxTokens = extras?.getInt("max_tokens", 512) ?: 512
                val result = LiteRtIpcBridge.generate(
                    storageRoot = root,
                    prompt = prompt,
                    system = system,
                    modelId = modelId,
                    delegateId = delegateId,
                    maxTokens = maxTokens,
                )
                out.putBoolean("ok", result.ok)
                out.putString("model", result.modelId)
                out.putString("delegate", result.delegateId)
                out.putString("text", result.text)
                out.putString("thinking", result.thinking)
                out.putInt("prompt_tokens", result.promptTokens)
                out.putInt("completion_tokens", result.completionTokens)
                out.putLong("latency_ms", result.latencyMs)
                if (!result.ok) {
                    out.putString("error", result.error)
                }
            }
            else -> {
                out.putBoolean("ok", false)
                out.putString("error", "Unsupported LiteRT-LM IPC method '$method'. Expected 'generate', 'status', or 'list_models'.")
            }
        }
        return out
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw SecurityException("LiteRtLmContentProvider only permits read-only ('r') access to verified .litertlm artifacts.")
        }
        val modelId = uri.lastPathSegment?.removeSuffix(".litertlm").orEmpty()
        val file = LiteRtIpcBridge.resolveVerifiedModelFile(resolveStorageRoot(), modelId)
            ?: throw FileNotFoundException("No verified .litertlm model artifact installed for '$modelId'.")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/x-litertlm"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("LiteRtLmContentProvider is read-only for external callers.")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("LiteRtLmContentProvider is read-only for external callers.")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("LiteRtLmContentProvider is read-only for external callers.")

    companion object {
        private val DEFAULT_COLUMNS = arrayOf(
            "model_id",
            "display_name",
            "family",
            "parameters",
            "quantization",
            "size_bytes",
            "sha256",
            "local_path",
            "active",
        )
    }
}
