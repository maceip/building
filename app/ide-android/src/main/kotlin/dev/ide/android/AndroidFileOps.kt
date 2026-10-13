package dev.ide.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import dev.ide.core.project.CaprojFormat
import dev.ide.ui.backend.IdeBackend
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * The Android host's file/SAF/FileProvider plumbing, split out of [MainActivity]: content-URI import/copy,
 * Share and "Open with", "Save As" export, APK install, and "Open in Files". Pure Android glue over an
 * [activity]. The Compose layer owns the SAF launchers (they must be `remember`ed in composition) and the
 * pending-request state; it calls these to do the byte-level work once a URI comes back.
 */
internal class AndroidFileOps(private val activity: ComponentActivity) {

    /** Hand the APK at [path] to the system package installer (the OS install-confirmation UI). Only an APK
     *  the IDE's build wrote is accepted; see [dev.ide.ui.backend.FileActions.isBuiltApk]. */
    fun promptInstall(path: String) = runCatching {
        require(dev.ide.ui.backend.FileActions.isBuiltApk(path)) { "not a build output: $path" }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(path))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }.getOrElse {
        Toast.makeText(activity, "Couldn't open the installer for ${File(path).name}", Toast.LENGTH_SHORT).show()
    }

    /**
     * Copy a source file (at most 64 MiB) under [targetDir]. All import/copy entry points are blocking:
     * call on Dispatchers.IO. Invalid/unreadable/oversized input returns null; cancellation propagates.
     */
    fun importUri(uri: Uri, targetDir: String, backend: IdeBackend): String? = AndroidImportLimits.orNull {
        val metadata = importMetadata(uri)
        val name = AndroidImportLimits.fileName(metadata.name ?: "imported-${UUID.randomUUID()}")
        // The backend accepts bytes and refuses existing files. Validate containment before handing it a name.
        AndroidImportLimits.child(File(targetDir), File(targetDir), name)
        val budget = AndroidImportLimits.ByteBudget(AndroidImportLimits.MAX_SOURCE_BYTES)
        budget.checkFileSize(metadata.size, AndroidImportLimits.MAX_SOURCE_BYTES)
        val bytes = ByteArrayOutputStream().use { output ->
            val input = activity.contentResolver.openInputStream(uri)
                ?: throw AndroidImportException("The selected file could not be opened.")
            input.use {
                AndroidImportLimits.copy(it, output, budget, AndroidImportLimits.MAX_SOURCE_BYTES, metadata.size)
            }
            output.toByteArray()
        }
        AndroidImportLimits.checkInterrupted()
        backend.files.createFileBytes(targetDir, name, bytes)
    }

    /** Copy an inbound (`Open with`) file into a fresh private cache directory. Never interpret [name] as a path. */
    fun copyInboundToCache(uri: Uri, name: String): String? = AndroidImportLimits.orNull {
        val safeName = AndroidImportLimits.fileName(name)
        copyFileToFreshCache(uri, safeName, importMetadata(uri).size, "import")
    }

    /**
     * Materialize a SAF folder for the engine, bounded to 512 MiB, 20,000 entries and 64 levels. Exclude
     * regenerable directories but preserve .platform workspace data. Never return a partially read tree.
     * Call off the main thread. On refusal, clean up only this invocation's fresh staging directory.
     */
    fun copyTreeToCache(treeUri: Uri): String? = AndroidImportLimits.orNull {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val budget = AndroidImportLimits.TreeBudget(rootDocId)
        AndroidImportLimits.inFreshDirectory(activity.cacheDir, "gradle-import") { dest ->
            copyDocTree(treeUri, rootDocId, dest, dest, 0, budget)
            dest.absolutePath
        }
    }

    private fun copyDocTree(
        treeUri: Uri,
        parentDocId: String,
        root: File,
        destDir: File,
        depth: Int,
        budget: AndroidImportLimits.TreeBudget,
    ) {
        AndroidImportLimits.checkInterrupted()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val resolver = activity.contentResolver
        val cursor = resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        ) ?: throw AndroidImportException("The selected folder could not be read completely.")
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val childNames = HashSet<String>()
            while (c.moveToNext()) {
                val childId = c.getString(idIdx)
                    ?: throw AndroidImportException("The selected folder contains an invalid document.")
                budget.visit(childId, depth + 1)
                val childName = AndroidImportLimits.fileName(c.getString(nameIdx))
                if (!childNames.add(childName)) throw AndroidImportException("The selected folder contains duplicate file names.")
                val mime = c.getString(mimeIdx)
                if (mime.isNullOrBlank()) throw AndroidImportException("The selected folder contains an unreadable item.")
                val child = AndroidImportLimits.child(root, destDir, childName)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (AndroidImportLimits.skipDirectory(childName)) continue
                    if (!child.mkdir()) throw AndroidImportException("An import folder could not be created.")
                    copyDocTree(treeUri, childId, root, child, depth + 1, budget)
                } else {
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
                    budget.bytes.checkFileSize(size)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    val input = resolver.openInputStream(docUri)
                        ?: throw AndroidImportException("The selected file could not be opened.")
                    input.use {
                        // CREATE_NEW also refuses duplicate/case-aliased names instead of clobbering a file.
                        Files.newOutputStream(child.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                            AndroidImportLimits.copy(it, output, budget.bytes, declaredBytes = size)
                        }
                    }
                }
            }
            // Cloud providers can return an incomplete cursor while fetching; do not report it as success.
            if (c.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                c.extras.containsKey(DocumentsContract.EXTRA_ERROR)
            ) throw AndroidImportException("The selected folder could not be read completely. Try again when it is available offline.")
        }
    }

    /**
     * Copy a picked file (at most 512 MiB) on an I/O thread. Every pick is a new immutable snapshot: even
     * the same URI can change, and a previous copy may be in publication preflight. Deduplicate by content
     * at the consumer, not by an overwritable URI-hash path. Returns null on refusal/unreadable input.
     */
    fun copyUriToCache(uri: Uri): String? = AndroidImportLimits.orNull {
        val metadata = importMetadata(uri)
        val name = AndroidImportLimits.fileName(metadata.name ?: "picked-${UUID.randomUUID()}")
        copyFileToFreshCache(uri, name, metadata.size, "picked")
    }

    private fun copyFileToFreshCache(uri: Uri, name: String, size: Long?, prefix: String): String {
        val budget = AndroidImportLimits.ByteBudget(AndroidImportLimits.MAX_FILE_BYTES)
        budget.checkFileSize(size)
        return AndroidImportLimits.inFreshDirectory(activity.cacheDir, prefix) { root ->
            val dest = AndroidImportLimits.child(root, root, name)
            val input = activity.contentResolver.openInputStream(uri)
                ?: throw AndroidImportException("The selected file could not be opened.")
            input.use {
                Files.newOutputStream(dest.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    AndroidImportLimits.copy(it, output, budget, declaredBytes = size)
                }
            }
            dest.absolutePath
        }
    }

    private data class ImportMetadata(val name: String?, val size: Long?)

    private fun importMetadata(uri: Uri): ImportMetadata {
        AndroidImportLimits.checkInterrupted()
        if (uri.scheme == "file") {
            val file = File(uri.path ?: throw AndroidImportException("The selected file could not be opened."))
            return ImportMetadata(uri.lastPathSegment, file.length())
        }
        // A name/size is optional for non-document providers (e.g. clipboard images). Never use size to
        // allocate memory or trust a missing/small value; the stream is always counted independently.
        return activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) return@use ImportMetadata(null, null)
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                ImportMetadata(
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else null,
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null,
                )
            } ?: ImportMetadata(null, null)
    }

    /** A fresh file for the camera to write a photo into, and the FileProvider URI the camera app writes through
     *  (the camera runs in another process, so it needs a grantable content:// URI, not a path). */
    fun newCameraTarget(): Pair<File, Uri> {
        val dir = File(activity.cacheDir, "camera").apply { mkdirs() }
        // One photo at a time is all the chat needs; clearing old ones keeps the cache from filling with them.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        return file to FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
    }

    /** The first image on the clipboard as a content URI, or null. Reading the clip grants this app temporary
     *  read access to its URIs. */
    fun clipboardImageUri(): Uri? = runCatching {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return@runCatching null
        if (!clip.description.hasMimeType("image/*")) return@runCatching null
        (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }
    }.getOrNull()

    /** Whether the clipboard holds an image, from its description alone (no read of the clip itself). */
    fun clipboardHasImage(): Boolean = runCatching {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClipDescription?.hasMimeType("image/*") == true
    }.getOrDefault(false)

    /** Share [path] to another app via a FileProvider content:// URI (no FileUriExposedException). */
    fun shareFile(path: String) = runCatching {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", File(path))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(path)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Share ${File(path).name}"))
    }.getOrElse { Toast.makeText(activity, "Can't share this file", Toast.LENGTH_SHORT).show() }

    /** Copy [src]'s bytes into the user-chosen document [uri] (the "Save As"/export destination). */
    fun exportTo(uri: Uri, src: String) = runCatching {
        activity.contentResolver.openOutputStream(uri)
            ?.use { out -> File(src).inputStream().use { it.copyTo(out) } }
        Toast.makeText(activity, "Exported ${File(src).name}", Toast.LENGTH_SHORT).show()
    }.getOrElse { Toast.makeText(activity, "Couldn't export file", Toast.LENGTH_SHORT).show() }

    /**
     * Open the system Files app on CodeAssist's storage (served by `ProjectsDocumentsProvider`) so the user
     * can browse/manage there. Best-effort across OEM file managers: first tries to deep-link to [path]'s own
     * directory (a document-URI VIEW — DocumentsUI honors it; our doc ids are absolute paths), then the
     * provider root, then a generic Files launch, and finally explains where to look.
     */
    fun openInFiles(path: String?) {
        val auth = "${activity.packageName}.documents"
        val dirId = path?.let { p -> File(p).let { if (it.isDirectory) it else it.parentFile }?.absolutePath }
        val dirUri = dirId?.let { runCatching { DocumentsContract.buildDocumentUri(auth, it) }.getOrNull() }
        if (dirUri != null) {
            val viewDir = Intent(Intent.ACTION_VIEW)
                .setDataAndType(dirUri, "vnd.android.document/directory")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (runCatching { activity.startActivity(viewDir); true }.getOrDefault(false)) return
        }
        val rootUri = DocumentsContract.buildRootUri(auth, AndroidIde.externalHome(activity).absolutePath)
        val viewRoot = Intent(Intent.ACTION_VIEW).setDataAndType(rootUri, "vnd.android.document/root")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (runCatching { activity.startActivity(viewRoot); true }.getOrDefault(false)) return
        val browse = Intent("android.provider.action.BROWSE", rootUri)
        runCatching { activity.startActivity(browse) }.getOrElse {
            Toast.makeText(activity, "Open the Files app → building to browse your projects", Toast.LENGTH_LONG).show()
        }
    }

    /** Open [url] in the device browser (the Beta "Submit suggestions" action). */
    fun openInBrowser(url: String) = runCatching {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.getOrElse { Toast.makeText(activity, "No app to open links", Toast.LENGTH_SHORT).show() }

    fun queryDisplayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return uri.lastPathSegment
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** A best-effort content MIME type from the file extension (drives the share-sheet target list). */
    private fun mimeFor(path: String): String = when {
        path.endsWith(".apk") -> "application/vnd.android.package-archive"
        path.endsWith(".${CaprojFormat.EXTENSION}") -> CaprojFormat.MIME
        path.endsWith(".zip") || path.endsWith(".jar") -> "application/zip"
        path.endsWith(".txt") || path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".xml") -> "text/plain"
        else -> "application/octet-stream"
    }
}
