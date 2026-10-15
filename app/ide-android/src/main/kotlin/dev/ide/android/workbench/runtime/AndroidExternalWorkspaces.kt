package dev.ide.android.workbench.runtime

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Android owns the grant and durable binding; npm/editor receive only the same real working directory. */
internal class AndroidExternalWorkspaces(context: Context) {
    private val app = context.applicationContext
    private val filesRoot = app.filesDir.canonicalFile.toPath()
    private val home = filesRoot.resolve("external-workspaces")
    private val store = ExternalWorkspaceStore(filesRoot.resolve("external-workspace-links"), AndroidExternalWorkspaceFiles::rename)

    /** Blocking: invoke with runInterruptible(Dispatchers.IO), never from composition/the main thread. */
    fun openTree(uri: Uri): Path = synchronized(lock) {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "Choose a document-provider folder." }
        val treeUri = uri.toString()
        val resolver = app.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try { resolver.takePersistableUriPermission(uri, flags) }
        catch (_: SecurityException) {
            throw ExternalWorkspaceException("The folder did not grant persistent read/write access. No editable link was created; choose a writable folder.")
        }
        val tree = SafExternalDocumentTree(resolver, uri)
        tree.requireAccess()
        val root = home.resolve(externalWorkspaceKey(treeUri)).resolve("project")
        val existing = store.read(root)
        if (existing != null) {
            require(existing.treeUri == treeUri && Files.isDirectory(root, NOFOLLOW_LINKS) && root.toRealPath() == root.toAbsolutePath().normalize()) {
                "The linked working copy is unavailable. Existing local edits will not be replaced by another import."
            }
            // Re-grant/reopen is NOT sync consent and must not overwrite either side.
            return@synchronized root
        }
        require(!Files.exists(root, NOFOLLOW_LINKS)) { "Unlinked working files already exist. They were kept; no original files were changed." }
        Files.createDirectories(root.parent)
        require(root.parent.toRealPath() == root.parent.toAbsolutePath().normalize()) { "The working-copy parent cannot contain symbolic links." }
        ExternalWorkspaceSync(store, tree, AndroidExternalWorkspaceFiles::publishNew, AndroidExternalWorkspaceFiles::rename).importTree(treeUri, root)
        root
    }

    fun binding(root: Path): ExternalWorkspaceBinding? = synchronized(lock) {
        val normalized = root.toAbsolutePath().normalize()
        if (!normalized.startsWith(home.toAbsolutePath().normalize())) return@synchronized null
        store.read(normalized)
    }

    fun sync(root: Path): ExternalSyncReport = synchronized(lock) {
        val binding = binding(root) ?: throw ExternalWorkspaceException("This workspace has no saved link to an original folder.")
        ExternalWorkspaceSync(store, SafExternalDocumentTree(app.contentResolver, Uri.parse(binding.treeUri)),
            AndroidExternalWorkspaceFiles::publishNew, AndroidExternalWorkspaceFiles::rename).synchronize(binding)
    }

    companion object { private val lock = Any() }
}

/** Android SELinux denies hard links; public NDK renameat2(RENAME_NOREPLACE) preserves no-clobber. */
internal object AndroidExternalWorkspaceFiles {
    private val nativeLoaded by lazy { System.loadLibrary("runtime-pty"); true }
    private external fun renameNoReplace(source: ByteArray, target: ByteArray): Int

    /** Consumes staged bytes only on success. Unsupported platforms fail closed, never overwrite/copy. */
    fun publishNew(target: Path, staged: Path) {
        if (android.os.Build.VERSION.SDK_INT < 30) throw ExternalWorkspaceException("Safe atomic original-folder pull requires Android 11 or newer. Original and working files were kept.")
        val source = staged.toString()
        val destination = target.toString()
        require('\u0000' !in source && '\u0000' !in destination)
        val errno = try {
            check(nativeLoaded)
            renameNoReplace(source.toByteArray(Charsets.UTF_8), destination.toByteArray(Charsets.UTF_8))
        } catch (failure: LinkageError) {
            throw ExternalWorkspaceException("Atomic file publication is unavailable. Original and working files were kept.").also { it.initCause(failure) }
        }
        if (errno == android.system.OsConstants.EEXIST) throw java.nio.file.FileAlreadyExistsException(destination)
        if (errno != 0) throw java.io.IOException("Atomic no-replace publication failed (errno $errno).")
    }

    /** Only private ledger files and unpredictable fresh recovery names use replacement-capable rename. */
    fun rename(source: Path, target: Path) {
        try { android.system.Os.rename(source.toString(), target.toString()) }
        catch (failure: android.system.ErrnoException) { throw java.io.IOException("Atomic private-file rename failed (errno ${failure.errno}).", failure) }
    }
}

/** All IDs are addressed through the selected tree URI; never convert content URIs into raw paths. */
internal class SafExternalDocumentTree(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val access: () -> Boolean = {
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission && it.isWritePermission }
    },
) : ExternalDocumentTree {
    override fun requireAccess() {
        if (!access()) throw SecurityException("Persistent folder read/write permission is missing.")
    }

    override fun root(): ExternalDocument = document(DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)))

    override fun children(parent: ExternalDocument): List<ExternalDocument> {
        requireAccess()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.id)
        val result = ArrayList<ExternalDocument>()
        val cursor = resolver.query(uri, columns, null, null, null)
            ?: throw ExternalWorkspaceException("The original folder could not be listed completely.")
        cursor.use {
            while (it.moveToNext()) {
                if (result.size >= dev.ide.android.AndroidImportLimits.MAX_TREE_ENTRIES) throw ExternalWorkspaceException("The original folder has too many entries.")
                dev.ide.android.AndroidImportLimits.checkInterrupted()
                result += decode(it)
            }
            complete(it)
        }
        return result
    }

    override fun read(document: ExternalDocument): InputStream {
        requireAccess()
        return resolver.openInputStream(uri(document)) ?: throw ExternalWorkspaceException("The provider did not open a source file. Sync was not completed.")
    }

    override fun create(parent: ExternalDocument, name: String, directory: Boolean): ExternalDocument {
        requireAccess()
        dev.ide.android.AndroidImportLimits.fileName(name)
        val created = DocumentsContract.createDocument(resolver, uri(parent), if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", name)
            ?: throw ExternalWorkspaceException("The provider could not create a staged document. Existing files were kept.")
        validateUri(created)
        return document(created)
    }

    override fun writeStaged(document: ExternalDocument): OutputStream {
        requireAccess()
        // Extra guard against ever truncating a normal project document through this adapter.
        require(document.name.startsWith(".building-stage-") && !document.directory)
        return resolver.openOutputStream(uri(document), "wt")
            ?: throw ExternalWorkspaceException("The provider did not open the staging file for writing. Existing files were kept.")
    }

    override fun rename(document: ExternalDocument, name: String): ExternalDocument {
        requireAccess()
        dev.ide.android.AndroidImportLimits.fileName(name)
        val renamed = DocumentsContract.renameDocument(resolver, uri(document), name)
            ?: throw ExternalWorkspaceException("The provider could not publish the file safely. Inspect retained recovery files before retrying.")
        validateUri(renamed)
        return document(renamed)
    }

    private fun document(uri: Uri): ExternalDocument {
        requireAccess()
        val cursor = resolver.query(uri, columns, null, null, null)
            ?: throw ExternalWorkspaceException("The provider returned no document metadata.")
        return cursor.use {
            if (!it.moveToFirst()) throw ExternalWorkspaceException("The provider document is unavailable.")
            val result = decode(it)
            if (it.moveToNext()) throw ExternalWorkspaceException("The provider returned ambiguous document metadata.")
            complete(it)
            result
        }
    }

    private fun decode(cursor: android.database.Cursor): ExternalDocument {
        fun text(column: String): String = cursor.getString(cursor.getColumnIndexOrThrow(column))
            ?: throw ExternalWorkspaceException("The provider returned invalid document metadata.")
        fun number(column: String): Long? {
            val index = cursor.getColumnIndex(column)
            return if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
        }
        val mime = text(DocumentsContract.Document.COLUMN_MIME_TYPE)
        if (mime.isBlank()) throw ExternalWorkspaceException("The provider returned an unreadable item.")
        return ExternalDocument(
            id = text(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            name = text(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            directory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            size = number(DocumentsContract.Document.COLUMN_SIZE),
            modifiedMillis = number(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            canRename = (number(DocumentsContract.Document.COLUMN_FLAGS) ?: 0).toInt() and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0,
        )
    }

    private fun complete(cursor: android.database.Cursor) {
        if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) || cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)) {
            throw ExternalWorkspaceException("The provider is still loading or reported an error. No incomplete folder listing is treated as a successful sync.")
        }
    }

    private fun uri(document: ExternalDocument): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, document.id)

    private fun validateUri(uri: Uri) {
        require(uri.scheme == "content" && uri.authority == treeUri.authority && DocumentsContract.isTreeUri(uri) && DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(treeUri)) {
            "The provider returned a document outside the granted tree."
        }
    }

    companion object {
        private val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
