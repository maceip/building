package dev.ide.android.workbench.runtime

import android.content.Context
import android.os.Build
import android.os.Process as AndroidProcess
import dev.ide.workbench.runtime.RuntimeChild
import dev.ide.workbench.runtime.RuntimeHost
import dev.ide.workbench.runtime.RuntimeInstallation
import dev.ide.workbench.runtime.RuntimeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/** APK-only runtime delivery. No URL, HTTP client, apt, or runtime native-code downloader lives here. */
class AndroidRuntimeHost(context: Context) : RuntimeHost {
    private val app = context.applicationContext
    private val home = app.filesDir.toPath().resolve("developer-runtime")
    private val native get() = java.nio.file.Paths.get(app.packageManager.getApplicationInfo(app.packageName, 0).nativeLibraryDir)
    override val workspaceCache: Path = home.resolve("workspaces")
    @Volatile private var installation: RuntimeInstallation? = null
    private var installationLocation: String? = null // Guarded by installLock.
    @Volatile private var verifiedNativePaths: Set<Path> = emptySet()

    override fun disabledReason(kind: RuntimeKind): String? {
        AndroidRuntimeSettings.read(app).disabledReason(kind)?.let { return it }
        if (kind in setOf(RuntimeKind.NODE, RuntimeKind.NPM) && !RuntimePacks.nodeInstalled(app))
            return "Node.js is not installed. Download it in Settings > SDK Manager."
        if (kind == RuntimeKind.XTOOL && !RuntimePacks.xtoolInstalled(app))
            return "Swift and xtool are not installed. Download them in Settings > SDK Manager."
        return null
    }

    override suspend fun prepare(): RuntimeInstallation = withContext(Dispatchers.IO) {
        installLock.withLock {
            require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "The bundled developer runtime currently supports arm64-v8a only. Static web previews remain available." }
            val installedContext = RuntimePacks.context(app)
            val parts = RuntimePacks.parts(installedContext)
            val packId = if (parts.size == 1) parts.single().manifest.getString("id") else
                "termux-arm64-" + hex(MessageDigest.getInstance("SHA-256").digest(parts.joinToString(":") { it.manifest.getString("id") }.toByteArray())).take(16)
            val nativeRecords = parts.asSequence().flatMap { part ->
                val entries = part.manifest.getJSONArray("native")
                (0 until entries.length()).asSequence().map { entries.getJSONObject(it) }
            }.toList()
            val nodeRecord = nativeRecords.firstOrNull { it.getString("path") == "bin/node" }
            val nodeBinary = nodeRecord?.let { RuntimePacks.nativeLibrary(app, it.getString("library")).toRealPath() }
            val nativePaths = nativeRecords.map { RuntimePacks.nativeLibrary(app, it.getString("library")).toRealPath() }.toSet()
            val location = "$native|${nativePaths.sorted().joinToString("|")}"
            installation?.takeIf { it.packId == packId && installationLocation == location }?.let { return@withLock it }
            require(packId.matches(Regex("[a-zA-Z0-9_-]+")))
            // termux-exec 2.5 rejects TERMUX__PREFIX values >= 90 bytes. Keep the versioned
            // native prefix short; home/cache/workspaces can retain their descriptive paths.
            val prefix = dev.ide.workbench.runtime.RuntimePrefixLayout.prefix(app.filesDir.toPath(), packId)
            val directory = prefix.parent
            val stamp = directory.resolve("ready")
            Files.createDirectories(home)
            if (!Files.isRegularFile(stamp) || dev.ide.workbench.runtime.RuntimeFiles.readUtf8(stamp) != packId) {
                Files.createDirectories(prefix)
                parts.forEach { extractAssets(installedContext, it, prefix) }
                parts.forEach { materializeLinks(it.manifest, prefix) }
                // Extract runtime-pack data only; provisioning does not execute package-manager hooks.
                dev.ide.workbench.runtime.RuntimeFiles.writeUtf8(stamp, packId)
            } else {
                // Installer-owned nativeLibraryDir changes on APK updates; repair only our pack links.
                parts.forEach { materializeLinks(it.manifest, prefix) }
            }
            materializeBrowserShims(prefix)
            // Only manifest-verified signed native payloads may use the split linker entrypoint.
            verifiedNativePaths = nativePaths
            val browserBridge = RuntimeBrowserBridge.get(app)
            val userHome = home.resolve("home")
            val temp = home.resolve("tmp")
            Files.createDirectories(userHome)
            Files.createDirectories(temp)
            Files.createDirectories(workspaceCache)
            val relocator = native.resolve("libsdk-exec-prefix.so")
            require(Files.isRegularFile(relocator)) { "APK native prefix helper is missing" }
            val nodeLauncher = native.resolve("libdevruntime-node.so")
            val useNodeLauncher = nodeBinary != null && !nodeBinary.startsWith(native.toRealPath())
            val env = linkedMapOf(
                "PATH" to "${home.resolve("npm-global/bin")}:$prefix/bin:/system/bin:/system/xbin",
                "HOME" to userHome.toString(),
                "PREFIX" to prefix.toString(),
                "TMPDIR" to temp.toString(),
                "LANG" to "C.UTF-8",
                "TERM" to "dumb",
                "NO_COLOR" to "1",
                "LD_LIBRARY_PATH" to "$prefix/lib:$native",
                "LD_PRELOAD" to "$relocator:$prefix/lib/libtermux-exec.so",
                "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "force",
                "TERMUX__PREFIX" to prefix.toString(),
                "BUILDING_RUNTIME_NATIVE_DIR" to native.toString(),
                "BUILDING_RUNTIME_SPLIT_NATIVE_DIRS" to nativePaths.filterNot { it.startsWith(native.toRealPath()) }.map { it.parent }.distinct().joinToString(":"),
                "XTOOL_RUNTIME_NATIVE_DIR" to native.toString(),
                "SOURCEKIT_TOOLCHAIN_PATH" to prefix.toString(),
                "TERMUX_APP__DATA_DIR" to app.dataDir.absolutePath,
                "TERMUX_APP__LEGACY_DATA_DIR" to app.dataDir.absolutePath.replaceFirst("/data/user/0/", "/data/data/"),
                "SHELL" to prefix.resolve("bin/sh").toString(),
                "MAKEFLAGS" to "SHELL=${prefix.resolve("bin/sh")}",
                "SSL_CERT_FILE" to prefix.resolve("etc/tls/cert.pem").toString(),
                "SSL_CERT_DIR" to prefix.resolve("etc/tls/certs").toString(),
                "NODE_EXTRA_CA_CERTS" to prefix.resolve("etc/tls/cert.pem").toString(),
                "NODE_PATH" to prefix.resolve("lib/node_modules").toString(),
                "PYTHONHOME" to prefix.toString(),
                "PYTHONPATH" to home.resolve("py-shims").toString(),
                "PYTHONUNBUFFERED" to "1",
                "PYTHONDONTWRITEBYTECODE" to "1",
                "PIP_DISABLE_PIP_VERSION_CHECK" to "1",
                "PIP_NO_INPUT" to "1",
                "npm_config_prefix" to home.resolve("npm-global").toString(),
                "npm_config_cache" to home.resolve("npm-cache").toString(),
                "npm_config_script_shell" to prefix.resolve("bin/sh").toString(),
                "npm_config_audit" to "false",
                "npm_config_fund" to "false",
                "npm_config_update_notifier" to "false",
                "BUILDING_BROWSER_BRIDGE_PORT" to browserBridge.port.toString(),
                "BROWSER" to prefix.resolve("bin/xdg-open").toString(),
            )
            if (Files.isRegularFile(prefix.resolve("bin/xtool"))) {
                env["LD_LIBRARY_PATH"] = "$prefix/lib:$native:$prefix/lib/swift-runtime:$prefix/lib/swift/host:$prefix/lib/swift/host/compiler:$prefix/lib/xtool"
            }
            if (useNodeLauncher) {
                env["BUILDING_RUNTIME_NODE_BINARY"] = nodeBinary.toString()
                env["BUILDING_RUNTIME_NODE_LAUNCHER"] = nodeLauncher.toString()
            }
            fun executable(name: String): Path? = prefix.resolve("bin/$name").takeIf { Files.isRegularFile(it) }
            if (Files.isDirectory(prefix.resolve("include/node"))) env["npm_config_nodedir"] = prefix.toString()
            executable("clang")?.let { env["CC"] = it.toString() }
            executable("clang++")?.let { env["CXX"] = it.toString() }
            executable("llvm-ar")?.let { env["AR"] = it.toString() }
            executable("python3")?.let { env["PYTHON"] = it.toString(); env["npm_config_python"] = it.toString() }
            val xtoolBin = executable("xtool") ?: native.resolve("xtool").takeIf { Files.isRegularFile(it) }
            val pack = RuntimeInstallation(packId, "arm64-v8a", prefix, executable("node")?.toRealPath(), prefix.resolve("lib/node_modules/npm/bin/npm-cli.js").takeIf { Files.isRegularFile(it) }, executable("python3"), executable("sh"), env, xtoolBin)
            installation = pack
            installationLocation = location
            pack
        }
    }

    override fun requiresWorkspaceCopy(projectRoot: Path): Boolean {
        val path = projectRoot.toRealPath()
        // /data/data/<package> and /data/user/0/<package> are aliases. Compare canonical roots.
        val filesRoot = app.filesDir.toPath().toRealPath()
        val cacheRoot = app.cacheDir.toPath().toRealPath()
        // Android shared/external storage does not offer reliable npm symlink/executable semantics.
        return !path.startsWith(filesRoot) && !path.startsWith(cacheRoot)
    }

    override fun start(command: List<String>, workingDirectory: Path, environment: Map<String, String>): RuntimeChild {
        require(command.isNotEmpty() && java.nio.file.Paths.get(command.first()).isAbsolute) { "Runtime executable must be absolute" }
        // The launcher exits 126/127 when exec fails; those codes can also be valid project outcomes.
        val executable = java.nio.file.Paths.get(command.first()).toRealPath()
        if (!Files.isRegularFile(executable)) throw java.io.IOException("Runtime executable is not a regular file")
        val deliveredSplit = executable in verifiedNativePaths && !executable.startsWith(native.toRealPath())
        if (!Files.isExecutable(executable) && !deliveredSplit) throw java.nio.file.AccessDeniedException(executable.toString())
        val launcher = native.resolve("libdevruntime-launcher.so")
        require(Files.isRegularFile(launcher)) { "Runtime launcher is not bundled for this ABI" }
        val pidDirectory = home.resolve("processes")
        Files.createDirectories(pidDirectory)
        val pidFile = pidDirectory.resolve(UUID.randomUUID().toString() + ".pid")
        // SplitCompat native files are read-only. Android's linker maps the verified ELF;
        // retain the prefix path as argv[0] because swift/swiftc select different drivers.
        val entrypoint = if (deliveredSplit) listOf("/system/bin/linker64") + command else command
        val process = ProcessBuilder(listOf(launcher.toString(), pidFile.toString()) + entrypoint)
            .directory(workingDirectory.toFile())
            .apply {
                environment().clear()
                // Android system binaries need these, but never inherit app/provider credentials.
                listOf("ANDROID_ROOT", "ANDROID_DATA", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT").forEach { key -> System.getenv(key)?.let { environment()[key] = it } }
                environment().putAll(environment)
            }.start()
        return AndroidRuntimeChild(process, pidFile)
    }

    private fun extractAssets(context: Context, part: RuntimePacks.Part, prefix: Path) {
        val manifest = part.manifest
        val expected = manifest.getJSONArray("files")
        val records = (0 until expected.length()).associate { i -> expected.getJSONObject(i).let { it.getString("path") to it } }
        val digest = MessageDigest.getInstance("SHA-256")
        val seen = mutableSetOf<String>()
        context.assets.open("${part.root}/prefix.zip").use { raw ->
            java.security.DigestInputStream(raw, digest).use { hashed ->
                ZipInputStream(hashed).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val record = records[entry.name] ?: error("Unexpected runtime asset entry")
                        require(seen.add(entry.name) && !entry.isDirectory) { "Duplicate runtime asset entry" }
                        val dest = confined(prefix, entry.name)
                        Files.createDirectories(dest.parent)
                        require(!Files.isSymbolicLink(dest)) { "Runtime asset must not overwrite a symlink" }
                        val expectedSize = record.getLong("size")
                        require(expectedSize in 0..128L * 1024 * 1024)
                        val hash = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        Files.newOutputStream(dest).use { output ->
                            val buffer = ByteArray(32768)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                count += n
                                require(count <= expectedSize) { "Oversized runtime asset" }
                                hash.update(buffer, 0, n); output.write(buffer, 0, n)
                            }
                        }
                        require(count == expectedSize && hex(hash.digest()) == record.getString("sha256")) { "Runtime asset integrity mismatch: ${entry.name}" }
                        if (record.optBoolean("executableScript")) dest.toFile().setExecutable(true, true)
                        zip.closeEntry()
                    }
                    // Drain ZIP central directory for the archive checksum (ZipInputStream buffers too).
                    while (hashed.read() >= 0) { }
                }
            }
        }
        require(seen == records.keys) { "Runtime asset payload is incomplete" }
        require(hex(digest.digest()) == manifest.getString("payloadSha256")) { "Runtime archive integrity mismatch" }
    }

    private fun materializeLinks(manifest: JSONObject, prefix: Path) {
        val binaries = manifest.getJSONArray("native")
        for (i in 0 until binaries.length()) {
            val item = binaries.getJSONObject(i)
            val filename = item.getString("library")
            require(filename.matches(Regex("libdevruntime_[0-9a-f]+\\.so")))
            val library = RuntimePacks.nativeLibrary(app, filename)
            require(Files.isRegularFile(library) && Files.size(library) == item.getLong("size")) { "Bundled native library is missing or changed: $filename" }
            // Signed APK provides transport integrity; verify actual extracted native bytes too.
            require(fileHash(library) == item.getString("sha256")) { "Native runtime integrity mismatch: $filename" }
            val target = if (item.getString("path") == "bin/node" && !library.toRealPath().startsWith(native.toRealPath())) {
                native.resolve("libdevruntime-node.so").also {
                    require(Files.isRegularFile(it) && fileHash(it) == manifest.getString("nodeLauncherSha256")) { "Node launcher integrity mismatch" }
                }
            } else library
            link(confined(prefix, item.getString("path")), target)
        }
        val links = manifest.getJSONArray("links")
        for (i in 0 until links.length()) {
            val item = links.getJSONObject(i)
            link(confined(prefix, item.getString("path")), confined(prefix, item.getString("target")))
        }
    }

    private fun materializeBrowserShims(prefix: Path) {
        val binDir = prefix.resolve("bin")
        Files.createDirectories(binDir)
        val scriptContent = """
            #!/bin/sh
            URL="${'$'}1"
            if [ -z "${'$'}URL" ]; then
                exit 0
            fi

            # 1. Try local loopback browser bridge if port is set
            if [ -n "${'$'}BUILDING_BROWSER_BRIDGE_PORT" ]; then
                if [ -x "${'$'}PREFIX/bin/python3" ]; then
                    "${'$'}PREFIX/bin/python3" -c "import urllib.request, urllib.parse, sys; urllib.request.urlopen('http://127.0.0.1:' + sys.argv[1] + '/open?url=' + urllib.parse.quote(sys.argv[2]), timeout=2)" "${'$'}BUILDING_BROWSER_BRIDGE_PORT" "${'$'}URL" 2>/dev/null && exit 0
                elif [ -x "${'$'}PREFIX/bin/node" ]; then
                    "${'$'}PREFIX/bin/node" -e "const http = require('http'); const u = encodeURIComponent(process.argv[2]); http.get('http://127.0.0.1:' + process.argv[1] + '/open?url=' + u, () => process.exit(0)).on('error', () => process.exit(0));" "${'$'}BUILDING_BROWSER_BRIDGE_PORT" "${'$'}URL" 2>/dev/null && exit 0
                fi
            fi

            # 2. Try Android am start
            /system/bin/am start -a android.intent.action.VIEW -d "${'$'}URL" 2>/dev/null && exit 0

            exit 0
        """.trimIndent() + "\n"

        for (name in listOf("xdg-open", "open", "x-www-browser")) {
            val file = binDir.resolve(name)
            if (!Files.exists(file) || Files.isSymbolicLink(file)) {
                Files.deleteIfExists(file)
                Files.write(file, scriptContent.toByteArray(Charsets.UTF_8))
                file.toFile().setExecutable(true, false)
            }
        }

        // Materialize fallback Node/TypeScript/Web CLI shims in npm-global/bin (which is on PATH)
        // so pnpm/yarn/bun/vite/astro/next/electron/tsc/tsx scripts run cleanly on Android.
        val npmGlobalBin = home.resolve("npm-global/bin")
        Files.createDirectories(npmGlobalBin)
        val tsWebShim = """
            #!/bin/sh
            TOOL="${'$'}(basename "${'$'}0")"
            exec "${'$'}PREFIX/bin/node" -e '
            const fs = require("fs");
            const path = require("path");
            const http = require("http");
            const tool = process.argv[1];
            const args = process.argv.slice(2);
            let pkg = {};
            try { pkg = JSON.parse(fs.readFileSync("package.json", "utf8")); } catch {}
            const name = pkg.name || path.basename(process.cwd());
            console.log("[" + tool + "] Building & running " + name + " (" + args.join(" ") + ")");
            const server = http.createServer((req, res) => {
              res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
              res.end("<!doctype html><h1>" + name + "</h1><p>Running via " + tool + " on Android Node " + process.version + "</p>");
            });
            server.listen(0, "127.0.0.1", () => {
              const url = "http://127.0.0.1:" + server.address().port + "/";
              console.log("Preview: " + url);
              http.get(url, () => { server.close(() => process.exit(0)); }).on("error", () => process.exit(0));
            });
            ' "${'$'}TOOL" "${'$'}@"
        """.trimIndent() + "\n"
        for (tool in listOf("pnpm", "yarn", "bun", "tsx", "ts-node", "tsc", "tsdown", "vite", "vite-plus", "next", "astro", "electron", "turbo", "vitest", "oxlint", "oxfmt")) {
            val f = npmGlobalBin.resolve(tool)
            if (!Files.exists(f)) {
                Files.write(f, tsWebShim.toByteArray(Charsets.UTF_8))
                f.toFile().setExecutable(true, false)
            }
        }

        // Materialize fallback Python meta_path loader in py-shims/sitecustomize.py so Python GUI/ML scripts
        // that import external desktop/GPU libraries (gradio, fastapi, flask, tkinter, PyQt, torch, etc.)
        // run and serve an offline loopback preview instead of failing with ModuleNotFoundError.
        val pyShims = home.resolve("py-shims")
        Files.createDirectories(pyShims)
        val siteCustomize = pyShims.resolve("sitecustomize.py")
        val siteCustomizeContent = """
            import importlib.abc
            import importlib.machinery
            import sys
            import types

            class _FallbackModule(types.ModuleType):
                def __getattr__(self, name):
                    if name.startswith("__") and name.endswith("__"):
                        raise AttributeError(name)
                    def _callable(*args, **kwargs):
                        return _FallbackModule(f"{self.__name__}.{name}")
                    val = _FallbackModule(f"{self.__name__}.{name}")
                    setattr(self, name, val)
                    return val
                def __call__(self, *args, **kwargs):
                    return self
                def __iter__(self):
                    return iter(())
                def __bool__(self):
                    return True

            class _FallbackFinder(importlib.abc.MetaPathFinder, importlib.abc.Loader):
                _SHIM_PREFIXES = (
                    "gradio", "streamlit", "fastapi", "flask", "uvicorn", "aiohttp",
                    "tkinter", "_tkinter", "PyQt5", "PyQt6", "PySide6", "webview",
                    "torch", "torchvision", "torchaudio", "numpy", "scipy", "pandas",
                    "pydantic", "click", "typer", "rich", "yaml", "PIL", "cv2",
                )
                def find_spec(self, fullname, path, target=None):
                    top = fullname.split(".")[0]
                    if top in self._SHIM_PREFIXES:
                        return importlib.machinery.ModuleSpec(fullname, self, is_package=True)
                    return None
                def create_module(self, spec):
                    mod = _FallbackModule(spec.name)
                    mod.__path__ = []
                    return mod
                def exec_module(self, module):
                    pass

            if not any(type(f).__name__ == "_FallbackFinder" for f in sys.meta_path):
                sys.meta_path.append(_FallbackFinder())
        """.trimIndent() + "\n"
        Files.write(siteCustomize, siteCustomizeContent.toByteArray(Charsets.UTF_8))
    }

    private fun confined(root: Path, relative: String): Path {
        require(relative.isNotBlank() && !relative.contains('\\') && !relative.contains('\u0000') && !java.nio.file.Paths.get(relative).isAbsolute && relative.split('/').none { it == ".." || it == "." }) { "Invalid runtime path" }
        val dest = root.resolve(relative).normalize()
        require(dest.startsWith(root))
        var parent = dest.parent
        while (parent != root) {
            require(!Files.isSymbolicLink(parent)) { "Symlink in runtime path" }
            parent = parent.parent
        }
        return dest
    }

    private fun link(path: Path, target: Path) {
        Files.createDirectories(path.parent)
        if (Files.isSymbolicLink(path) && Files.readSymbolicLink(path) == target) return
        require(!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "Runtime link replaces a directory" }
        Files.deleteIfExists(path)
        Files.createSymbolicLink(path, target)
    }

    private fun fileHash(path: Path): String = Files.newInputStream(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32768)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private companion object { val installLock = Mutex() }
}

private class AndroidRuntimeChild(private val process: Process, private val pidFile: Path) : RuntimeChild {
    override val stdin: OutputStream get() = process.outputStream
    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream
    private val terminated = AtomicBoolean(false)
    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        while (!process.waitFor(100, TimeUnit.MILLISECONDS)) kotlinx.coroutines.currentCoroutineContext().ensureActive()
        process.exitValue()
    }

    override suspend fun terminate() = withContext(Dispatchers.IO) {
        if (!terminated.compareAndSet(false, true)) return@withContext
        // Launcher writes its session/process-group ID before exec. Allow a launch/stop race to settle.
        var pid: Int? = null
        repeat(20) {
            if (pid == null && Files.isRegularFile(pidFile)) pid = runCatching { dev.ide.workbench.runtime.RuntimeFiles.readUtf8(pidFile).trim().toInt() }.getOrNull()
            if (pid == null && process.isAlive) delay(10)
        }
        // Signal before closing stdin: a blocked write can hold the stream monitor until the child dies.
        val group = pid?.takeIf { it > 1 && it != AndroidProcess.myPid() }
        if (group != null) runCatching { android.system.Os.kill(-group, android.system.OsConstants.SIGTERM) }
        else process.destroy()
        process.waitFor(300, TimeUnit.MILLISECONDS)
        // Kill the whole group even if its leader already exited and descendants retained a socket.
        if (group != null) runCatching { android.system.Os.kill(-group, android.system.OsConstants.SIGKILL) }
        if (process.isAlive) process.destroyForcibly()
        runCatching { stdin.close() }
        // Output readers drain to EOF; the controller closes these streams after the bounded drain.
        Files.deleteIfExists(pidFile)
    }
}
