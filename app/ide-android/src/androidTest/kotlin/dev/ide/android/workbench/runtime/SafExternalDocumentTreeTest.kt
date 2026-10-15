package dev.ide.android.workbench.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import androidx.test.platform.app.InstrumentationRegistry
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.content.ContentResolver
import android.os.Build
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual Android ContentResolver/DocumentsContract adapter against a fixture provider, never user data. */
@Suppress("DEPRECATION")
class SafExternalDocumentTreeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun adapterWritesEditedAndNewBytesIntoGrantedTreeAndRetainsOldFile() {
        val f = fixture()
        f.provider.put("index.js", "old")
        val link = f.sync.importTree(f.uri.toString(), f.root)
        Files.write(f.root.resolve("index.js"), "edited".toByteArray())
        Files.write(f.root.resolve("new.js"), "new source".toByteArray())
        val result = f.sync.synchronize(link)
        assertTrue(result.toString(), result.complete)
        assertEquals(2, result.pushed)
        assertEquals("edited", f.provider.text("index.js"))
        assertEquals("new source", f.provider.text("new.js"))
        assertEquals("old", f.provider.backups().single().file.readText())
        assertTrue(f.provider.openedForWrite.all { it.startsWith(".building-stage-") })
    }

    @Test fun publicAndroidRenameIsAtomicNoClobberAndProviderPullUsesIt() {
        assumeTrue("Public renameat2 requires API 30 or newer", Build.VERSION.SDK_INT >= 30)
        val base = temporary.newFolder().canonicalFile.toPath()
        val source = base.resolve("source")
        val target = base.resolve("target")
        Files.write(source, "first".toByteArray())
        AndroidExternalWorkspaceFiles.publishNew(target, source)
        assertEquals("first", String(Files.readAllBytes(target)))
        assertFalse(Files.exists(source))
        val other = base.resolve("other")
        Files.write(other, "second".toByteArray())
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { AndroidExternalWorkspaceFiles.publishNew(target, other) }
        assertEquals("first", String(Files.readAllBytes(target)))
        assertEquals("second", String(Files.readAllBytes(other)))
        val f = fixture()
        f.provider.put("index.js", "old")
        val link = f.sync.importTree(f.uri.toString(), f.root)
        f.provider.put("index.js", "outside edit")
        f.provider.put("new.bin", "new binary")
        val result = f.sync.synchronize(link)
        assertTrue(result.toString(), result.complete)
        assertEquals(2, result.pulled)
        assertEquals("outside edit", String(Files.readAllBytes(f.root.resolve("index.js"))))
        assertEquals("new binary", String(Files.readAllBytes(f.root.resolve("new.bin"))))
    }

    @Test fun loadingOrErrorCursorAndRevokedPermissionAbortWithoutWrites() {
        val f = fixture()
        f.provider.put("index.js", "old")
        val link = f.sync.importTree(f.uri.toString(), f.root)
        Files.write(f.root.resolve("index.js"), "edited".toByteArray())
        f.provider.loading = true
        assertFalse(f.sync.synchronize(link).complete)
        assertEquals("old", f.provider.text("index.js"))
        f.provider.loading = false
        f.provider.error = true
        assertFalse(f.sync.synchronize(link).complete)
        f.provider.error = false
        f.accessGranted = false
        val revoked = f.sync.synchronize(link)
        assertFalse(revoked.complete)
        assertTrue(revoked.failures.single().reason.contains("permission"))
        assertTrue(f.provider.openedForWrite.isEmpty())
    }

    @Test fun nullOutputProviderFailureIsPartialNotExportSuccess() {
        val f = fixture()
        f.provider.put("index.js", "old")
        val link = f.sync.importTree(f.uri.toString(), f.root)
        Files.write(f.root.resolve("index.js"), "edited".toByteArray())
        f.provider.refuseWrites = true
        val result = f.sync.synchronize(link)
        assertFalse(result.complete)
        assertEquals(0, result.pushed)
        assertEquals("old", f.provider.text("index.js"))
        assertTrue(result.recoveryPaths.single().startsWith(".building-stage-"))
    }

    @Test fun adapterRejectsRepeatedProviderIdsAndTruncatedRead() {
        val f = fixture()
        f.provider.put("index.js", "original bytes")
        f.provider.duplicate = true
        assertThrows(Exception::class.java) { f.sync.importTree(f.uri.toString(), f.root) }
        assertFalse(Files.exists(f.root))
        f.provider.duplicate = false
        f.provider.declaredExtra = 5
        assertThrows(ExternalWorkspaceException::class.java) { f.sync.importTree(f.uri.toString(), f.root) }
        assertFalse(Files.exists(f.root))
    }

    private class Fixture(val uri: Uri, val provider: Provider, val root: java.nio.file.Path, val store: ExternalWorkspaceStore) {
        var accessGranted = true
        val resolver = ContentResolver.wrap(provider)
        val sync = ExternalWorkspaceSync(store, SafExternalDocumentTree(resolver, uri) { accessGranted },
            AndroidExternalWorkspaceFiles::publishNew, AndroidExternalWorkspaceFiles::rename)
    }

    private fun fixture(): Fixture {
        assumeTrue("ContentResolver.wrap fixture needs API 29 or newer", Build.VERSION.SDK_INT >= 29)
        val base = temporary.newFolder().canonicalFile
        val provider = Provider(File(base, "provider").apply { mkdirs() })
        provider.attachInfo(InstrumentationRegistry.getInstrumentation().targetContext, ProviderInfo().apply {
            authority = AUTHORITY
            exported = false
            grantUriPermissions = true
        })
        return Fixture(DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root"), provider, File(base, "working").toPath(), ExternalWorkspaceStore(File(base, "metadata").toPath(), AndroidExternalWorkspaceFiles::rename))
    }

    private class Provider(private val storage: File) : ContentProvider() {
        data class Item(val id: String, var name: String, val file: File, var version: Long = 1)
        private val items = linkedMapOf<String, Item>()
        private var next = 1
        var loading = false
        var error = false
        var duplicate = false
        var declaredExtra = 0
        var refuseWrites = false
        val openedForWrite = mutableListOf<String>()
        override fun onCreate() = true
        override fun getType(uri: Uri): String = if (DocumentsContract.getDocumentId(uri) == "root") DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: COLUMNS
            val extras = Bundle().apply {
                if (loading) putBoolean(DocumentsContract.EXTRA_LOADING, true)
                if (error) putString(DocumentsContract.EXTRA_ERROR, "fixture still loading")
            }
            val cursor = object : MatrixCursor(columns) { override fun getExtras() = extras }
            val children = uri.pathSegments.lastOrNull() == "children"
            val id = DocumentsContract.getDocumentId(uri)
            val selected = if (children) items.values.toList() else if (id == "root") emptyList() else listOf(requireNotNull(items[id]))
            fun row(item: Item?): Array<Any?> = columns.map<String, Any?> { name -> when (name) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> item?.id ?: "root"
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> item?.name ?: "fixture-project"
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (item == null) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                DocumentsContract.Document.COLUMN_SIZE -> item?.file?.length()?.plus(declaredExtra)
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> item?.version ?: 1L
                DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                else -> null
            } }.toTypedArray()
            if (!children && id == "root") cursor.addRow(row(null))
            selected.forEach { cursor.addRow(row(it)) }
            if (duplicate && children && selected.isNotEmpty()) cursor.addRow(row(selected.first()))
            return cursor
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
            val item = items[DocumentsContract.getDocumentId(uri)] ?: throw FileNotFoundException("fixture document not found")
            if (mode.contains('w')) {
                if (refuseWrites) return null
                check(item.name.startsWith(".building-stage-")) { "Fixture forbids any in-place original write" }
                openedForWrite += item.name
            }
            return ParcelFileDescriptor.open(item.file, ParcelFileDescriptor.parseMode(mode))
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            val uri = requireNotNull(extras?.getParcelable<Uri>("uri"))
            return when (method) {
                "android:createDocument" -> {
                    check(DocumentsContract.getDocumentId(uri) == "root")
                    val name = requireNotNull(extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    check(items.values.none { it.name == name })
                    val item = create(name)
                    Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, item.id)) }
                }
                "android:renameDocument" -> {
                    val item = requireNotNull(items[DocumentsContract.getDocumentId(uri)])
                    val name = requireNotNull(extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    check(items.values.none { it.id != item.id && it.name == name })
                    item.name = name
                    item.version++
                    Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, item.id)) }
                }
                else -> throw IOException("Unexpected fixture provider call: $method")
            }
        }
        fun put(name: String, text: String) { (items.values.firstOrNull { it.name == name } ?: create(name)).file.writeText(text) }
        fun text(name: String) = items.values.single { it.name == name }.file.readText()
        fun backups() = items.values.filter { it.name.startsWith(".building-backup-") }
        private fun create(name: String): Item {
            val id = "document-${next++}"
            return Item(id, name, File(storage, id).also { it.createNewFile() }).also { items[id] = it }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw AssertionError("Original documents must never be deleted")
    }

    companion object {
        private const val AUTHORITY = "dev.ide.fixture.external-workspace"
        private val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
