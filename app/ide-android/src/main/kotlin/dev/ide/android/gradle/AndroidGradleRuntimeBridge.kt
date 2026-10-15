package dev.ide.android.gradle

import android.content.Context
import android.os.Looper
import dev.ide.android.workbench.runtime.AndroidRuntimeHost
import dev.ide.gradle.GradleRuntimeCommandBridge
import dev.ide.gradle.GradleScriptEnvironment
import dev.ide.workbench.runtime.RuntimeHost
import dev.ide.workbench.runtime.RuntimeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/**
 * Lazy APK-only Node/npm provisioning for trusted Gradle scripts, not an optional downloader.
 * RuntimeInstallation owns the native/header/compiler environment. Gradle gets only Node command
 * wrappers; it keeps its existing JDK/prefix/linker environment until one of those commands executes.
 */
class AndroidGradleRuntimeBridge(
    context: Context,
    private val runtimeHost: RuntimeHost = AndroidRuntimeHost(context),
) {
    private val files = context.applicationContext.filesDir.toPath()

    fun prepare(): GradleScriptEnvironment {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "The bundled Gradle script runtime must be prepared off the main thread."
        }
        // GradleHost's synchronous API already runs on a build/import/approved-repair worker. The
        // suspend APK installer also remains on IO; do not runBlocking on the UI or at IDE startup.
        return runBlocking(Dispatchers.IO) {
            // Keep Java-only builds usable and never provision the pack just to expose disabled tools.
            if (runtimeHost.disabledReason(RuntimeKind.NODE) != null) return@runBlocking GradleScriptEnvironment()
            val runtime = runtimeHost.prepare()
            GradleRuntimeCommandBridge.install(
                directory = files.resolve("developer-runtime/gradle-commands"),
                interpreter = files.resolve("usr/bin/sh"),
                node = requireNotNull(runtime.node) { "The APK must bundle Node for trusted Gradle scripts" },
                npmCli = requireNotNull(runtime.npmCli) { "The APK must bundle npm for trusted Gradle scripts" },
                runtimeEnvironment = runtime.environment,
                extraNodeCommands = listOf("pnpm", "pnpx").mapNotNull { name ->
                    val entrypoint = runtime.prefix.resolve("lib/node_modules/pnpm/bin/$name.mjs")
                    if (Files.isRegularFile(entrypoint)) name to entrypoint else null
                }.toMap(),
            )
        }
    }
}
