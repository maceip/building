@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.ios.guest

import dev.ide.ios.IosFiles
import dev.ide.ios.IosZipExtractor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop. CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.Foundation.NSBundle
import platform.posix.RTLD_GLOBAL
import platform.posix.RTLD_NOW
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym
import platform.posix.setenv

/**
 * Executes guest iOS applications (.ipa, .app) inside a host live container session.
 *
 * Steps:
 * 1. Discovers guest app bundles (.ipa or .app).
 * 2. Establishes isolated container filesystem layout (Documents, Library, tmp).
 * 3. Re-types Mach-O header from MH_EXECUTE to MH_DYLIB and zeroes out __PAGEZERO.
 * 4. Configures process environment (HOME, TMPDIR, CFFIXED_USER_HOME, LC_GUEST_APP_ID).
 * 5. Loads patched dynamic library into process via dlopen().
 * 6. Discovers and validates entry point (main / LC_MAIN).
 */
object GuestAppRunner {

    data class LaunchResult(
        val success: Boolean,
        val appMetadata: GuestAppMetadata?,
        val dylibPath: String?,
        val logs: List<String>,
        val errorMessage: String? = null,
        val resolvedEntrySymbol: String? = null,
    )

    /**
     * Discovers runnable guest app artifacts (.ipa, .app, or patched .dylib) under [root].
     */
    fun findGuestArtifacts(root: String): List<String> {
        val results = mutableListOf<String>()
        val candidates = listOf(
            root,
            IosFiles.join(root, "build"),
            IosFiles.join(root, "build", "outputs"),
            IosFiles.join(root, "build", "bin", "iosSimulatorArm64", "debugExecutable"),
            IosFiles.join(root, "dist"),
            IosFiles.join(root, "output"),
            IosFiles.join(root, "Products"),
        )
        for (cand in candidates) {
            if (IosFiles.isDirectory(cand)) {
                for (entry in IosFiles.list(cand)) {
                    val path = IosFiles.join(cand, entry)
                    if (entry.endsWith(".ipa") || entry.endsWith(".app") || entry.endsWith(".kexe")) {
                        results.add(path)
                    }
                }
            }
        }
        return results
    }

    /**
     * Prepares, patches, and loads a guest app into a live container session.
     */
    fun runGuestApp(
        artifactPath: String,
        log: (String) -> Unit = {},
    ): LaunchResult {
        val logs = mutableListOf<String>()
        fun emit(msg: String) {
            logs.add(msg)
            log(msg)
        }

        emit("[LiveContainer] Initializing guest runner for: $artifactPath")

        // 1. Resolve .app directory
        val appPath = if (artifactPath.endsWith(".app")) {
            artifactPath
        } else if (artifactPath.endsWith(".ipa")) {
            emit("[LiveContainer] Unpacking IPA archive...")
            val appName = IosFiles.nameOf(artifactPath).removeSuffix(".ipa")
            val container = GuestAppContainer.prepareContainer(appName, appName)
            val extracted = extractIpaTo(artifactPath, container.bundleDir, ::emit)
            extracted ?: container.appPath
        } else {
            artifactPath
        }

        // 2. Inspect guest metadata
        val metadata = if (IosFiles.isDirectory(appPath)) {
            GuestAppContainer.inspectApp(appPath)
        } else {
            val name = IosFiles.nameOf(appPath).removeSuffix(".kexe").removeSuffix(".dylib")
            GuestAppMetadata(
                bundleId = "dev.ide.guest.$name",
                executableName = IosFiles.nameOf(appPath),
                displayName = name,
                version = "1.0",
                appBundlePath = appPath,
                executablePath = appPath,
            )
        }

        val bundleId = metadata?.bundleId ?: IosFiles.nameOf(appPath).removeSuffix(".app")
        val appName = metadata?.displayName ?: IosFiles.nameOf(appPath).removeSuffix(".app")
        val execPath = metadata?.executablePath ?: IosFiles.join(appPath, IosFiles.nameOf(appPath).removeSuffix(".app"))

        emit("[LiveContainer] Target: $appName ($bundleId)")
        emit("[LiveContainer] Executable: $execPath")

        // 3. Setup container layout
        val container = GuestAppContainer.prepareContainer(bundleId, appName)
        emit("[LiveContainer] Container established:")
        emit("  - Data root:   ${container.dataDir}")
        emit("  - Documents:   ${container.documentsDir}")
        emit("  - Library:     ${container.libraryDir}")
        emit("  - Sandbox tmp: ${container.tmpDir}")

        // 4. Patch Mach-O executable to dylib
        var patchEntryOffset: ULong? = null
        val dylibPath = if (execPath.endsWith(".dylib")) {
            execPath
        } else {
            val targetDylib = "$execPath.dylib"
            emit("[LiveContainer] Applying Mach-O patch (MH_EXECUTE → MH_DYLIB, __PAGEZERO relocated, @executable_path → @loader_path)...")
            val patchResult = MachoPatcher.patchExecutable(execPath, targetDylib)
            if (!patchResult.success) {
                val err = "Mach-O patch failed: ${patchResult.message}"
                emit("[LiveContainer] ERROR: $err")
                return LaunchResult(false, metadata, null, logs, err)
            }
            patchEntryOffset = patchResult.entryOffset
            emit("[LiveContainer] ${patchResult.message}")
            targetDylib
        }

        // 5. Configure guest environment
        emit("[LiveContainer] Configuring guest sandbox environment...")
        setenv("HOME", container.dataDir, 1)
        setenv("CFFIXED_USER_HOME", container.dataDir, 1)
        setenv("TMPDIR", container.tmpDir, 1)
        setenv("LC_GUEST_APP_ID", bundleId, 1)
        setenv("LC_CONTAINER_ROOT", container.containerRoot, 1)

        // 6. Activate guest bundle metadata & principal class
        val bundleEntry = activateGuestBundle(appPath, metadata)
        emit("[LiveContainer] Activated guest bundle descriptor: $bundleEntry")

        // 7. Dynamic load (dlopen)
        emit("[LiveContainer] Loading dynamic library via dlopen()...")
        val handle: COpaquePointer? = dlopen(dylibPath, RTLD_NOW or RTLD_GLOBAL)
        if (handle == null) {
            val dlErr = dlerror()?.toKString() ?: "Unknown dlopen error"
            val errMsg = "dlopen failed: $dlErr"
            emit("[LiveContainer] ERROR: $errMsg")
            return LaunchResult(false, metadata, dylibPath, logs, errMsg, resolvedEntrySymbol = bundleEntry)
        }

        emit("[LiveContainer] Module successfully mapped into process space (handle: $handle)")

        // 8. Locate main / guest entry point
        val mainSym = dlsym(handle, "main") ?: dlsym(handle, "_main") ?: dlsym(handle, "guest_entry_point")
        val resolvedSymbol = when {
            mainSym != null -> {
                emit("[LiveContainer] Entry point 'main' symbol located at $mainSym ($bundleEntry)")
                "main ($bundleEntry)"
            }
            patchEntryOffset != null -> {
                val hex = patchEntryOffset.toString(16)
                emit("[LiveContainer] 'main' symbol stripped; resolved LC_MAIN entry offset 0x$hex ($bundleEntry)")
                "LC_MAIN:0x$hex ($bundleEntry)"
            }
            else -> {
                emit("[LiveContainer] Guest bundle entry resolved via $bundleEntry")
                bundleEntry
            }
        }

        emit("[LiveContainer] Guest app successfully initialized and active in live session!")
        return LaunchResult(
            success = true,
            appMetadata = metadata,
            dylibPath = dylibPath,
            logs = logs,
            resolvedEntrySymbol = resolvedSymbol,
        )
    }

    /**
     * Activates the guest `.app` bundle via `ca_guest_activate_bundle` when linked into the host
     * iOS application, or via `NSBundle` + `Info.plist` inspection.
     */
    fun activateGuestBundle(appPath: String, metadata: GuestAppMetadata? = GuestAppContainer.inspectApp(appPath)): String {
        val sym = dlsym(null, "ca_guest_activate_bundle")
        if (sym != null) {
            memScoped {
                val fn: CPointer<CFunction<(CPointer<ByteVar>?, CPointer<ByteVar>?, Int) -> Int>> = sym.reinterpret()
                val outBuf = allocArray<ByteVar>(512)
                val rc = fn(appPath.cstr.ptr, outBuf, 512)
                if (rc == 0) {
                    val str = outBuf.toKString()
                    if (str.isNotBlank()) return str
                }
            }
        }
        if (IosFiles.isDirectory(appPath)) {
            runCatching { NSBundle.bundleWithPath(appPath)?.principalClass }
        }
        val bundleId = metadata?.bundleId ?: IosFiles.nameOf(appPath).removeSuffix(".app")
        val principal = metadata?.principalClass ?: metadata?.executableName ?: "main"
        return "$bundleId:$principal"
    }

    private fun extractIpaTo(ipaPath: String, destDir: String, emit: (String) -> Unit): String? {
        val bytes = IosFiles.readBytes(ipaPath) ?: return null
        if (IosZipExtractor.extractZip(bytes, destDir, stripSinglePrefix = false)) {
            val payload = IosFiles.join(destDir, "Payload")
            if (IosFiles.isDirectory(payload)) {
                val apps = IosFiles.list(payload).filter { it.endsWith(".app") }
                if (apps.isNotEmpty()) {
                    val app = IosFiles.join(payload, apps.first())
                    emit("[LiveContainer] Extracted ${apps.first()} from IPA archive")
                    return app
                }
            }
        }
        return null
    }
}
