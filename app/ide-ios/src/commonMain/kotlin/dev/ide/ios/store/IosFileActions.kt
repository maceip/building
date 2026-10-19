package dev.ide.ios.store

import dev.ide.ios.IosFiles
import dev.ide.store.StoreAuth
import dev.ide.ui.backend.FileActions
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile
import platform.UIKit.*

/**
 * The iOS host file actions and link handling.
 *
 * Implements full file operations over Foundation & UIKit: document picking for files and folders,
 * importing into project folders, sharing, exporting via document picker, and image clipboard paste.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFileActions(
    private val authorizePrefix: String,
    private val onSignInRedirect: (String) -> Unit,
) : FileActions {

    private val auth = IosAuthSession(StoreAuth.SCHEME)
    private var activePickerDelegate: Any? = null

    override val canOpenUrl: Boolean = true

    override fun openUrl(url: String) {
        if (authorizePrefix.isNotBlank() && url.startsWith(authorizePrefix)) {
            auth.start(url, onSignInRedirect)
            return
        }
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target)
    }

    override val canImport: Boolean = true

    override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) {
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.item"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
        )
        picker.allowsMultipleSelection = true
        val delegate = PickerDelegate { urls ->
            activePickerDelegate = null
            val created = mutableListOf<String>()
            for (url in urls) {
                val access = url.startAccessingSecurityScopedResource()
                try {
                    val name = url.lastPathComponent ?: continue
                    val dest = "$targetDir/$name"
                    if (IosFiles.exists(dest)) IosFiles.delete(dest)
                    val src = url.path ?: continue
                    if (IosFiles.copy(src, dest)) {
                        created.add(dest)
                    }
                } finally {
                    if (access) url.stopAccessingSecurityScopedResource()
                }
            }
            onImported(created)
        }
        activePickerDelegate = delegate
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    override val canPickFile: Boolean = true

    override fun pickFile(extensions: List<String>, onPicked: (String?) -> Unit) {
        val types = if (extensions.isEmpty()) listOf("public.item") else listOf("public.data", "public.content")
        val picker = UIDocumentPickerViewController(
            documentTypes = types,
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
        )
        picker.allowsMultipleSelection = false
        val delegate = PickerDelegate { urls ->
            activePickerDelegate = null
            val url = urls.firstOrNull()
            if (url == null) {
                onPicked(null)
                return@PickerDelegate
            }
            val access = url.startAccessingSecurityScopedResource()
            try {
                val name = url.lastPathComponent ?: "imported_file"
                val cacheDir = NSTemporaryDirectory()
                val dest = "$cacheDir/$name"
                if (IosFiles.exists(dest)) IosFiles.delete(dest)
                val src = url.path
                if (src != null && IosFiles.copy(src, dest)) {
                    onPicked(dest)
                } else {
                    onPicked(src)
                }
            } finally {
                if (access) url.stopAccessingSecurityScopedResource()
            }
        }
        activePickerDelegate = delegate
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    override val canPickDirectory: Boolean = true

    override fun pickDirectory(onPicked: (String?) -> Unit) {
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.folder"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeOpen,
        )
        picker.allowsMultipleSelection = false
        val delegate = PickerDelegate { urls ->
            activePickerDelegate = null
            val url = urls.firstOrNull()
            if (url == null) {
                onPicked(null)
                return@PickerDelegate
            }
            val access = url.startAccessingSecurityScopedResource()
            try {
                val srcPath = url.path
                if (srcPath == null) {
                    onPicked(null)
                    return@PickerDelegate
                }
                val folderName = url.lastPathComponent ?: "ImportedProject"
                val projectsRoot = "${IosFiles.documentsDir()}/Projects"
                if (!IosFiles.exists(projectsRoot)) IosFiles.mkdirs(projectsRoot)
                var destPath = "$projectsRoot/$folderName"
                var counter = 1
                while (IosFiles.exists(destPath)) {
                    destPath = "$projectsRoot/${folderName}_${counter++}"
                }
                if (IosFiles.copy(srcPath, destPath)) {
                    onPicked(destPath)
                } else {
                    onPicked(srcPath)
                }
            } finally {
                if (access) url.stopAccessingSecurityScopedResource()
            }
        }
        activePickerDelegate = delegate
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    override val canShare: Boolean = true

    override fun share(path: String) {
        val target = NSURL.fileURLWithPath(path)
        val activity = UIActivityViewController(
            activityItems = listOf(target),
            applicationActivities = null,
        )
        val top = topViewController()
        activity.popoverPresentationController?.apply {
            sourceView = top?.view
        }
        top?.presentViewController(activity, animated = true, completion = null)
    }

    override val canExport: Boolean = true

    override fun exportFile(path: String) {
        val target = NSURL.fileURLWithPath(path)
        val picker = UIDocumentPickerViewController(
            forExportingURLs = listOf(target),
            asCopy = true,
        )
        val delegate = PickerDelegate { activePickerDelegate = null }
        activePickerDelegate = delegate
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    override val canReveal: Boolean = true

    override fun reveal(path: String) {
        share(path)
    }

    override val canPasteImage: Boolean = true

    override fun hasClipboardImage(): Boolean = UIPasteboard.generalPasteboard.image != null

    override fun pasteImage(onPasted: (String?) -> Unit) {
        val image = UIPasteboard.generalPasteboard.image ?: run { onPasted(null); return }
        val data = UIImagePNGRepresentation(image) ?: run { onPasted(null); return }
        val path = "${NSTemporaryDirectory()}/pasted_${platform.posix.time(null)}.png"
        data.writeToFile(path, true)
        onPasted(path)
    }

    private fun topViewController(): UIViewController? {
        val app = UIApplication.sharedApplication
        val window = app.keyWindow ?: app.windows.firstOrNull() as? UIWindow
        var top = window?.rootViewController
        while (top?.presentedViewController != null) {
            top = top.presentedViewController
        }
        return top
    }

    private class PickerDelegate(
        private val onDone: (List<NSURL>) -> Unit,
    ) : platform.darwin.NSObject(), UIDocumentPickerDelegateProtocol {

        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            val urls = didPickDocumentsAtURLs.filterIsInstance<NSURL>()
            onDone(urls)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            onDone(emptyList())
        }
    }
}

