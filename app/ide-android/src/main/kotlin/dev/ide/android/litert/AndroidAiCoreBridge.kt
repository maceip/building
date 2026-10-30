package dev.ide.android.litert

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import dev.ide.agent.impl.JvmLiteRtEngineBridge
import dev.ide.agent.impl.LiteRtAiCoreBridge
import dev.ide.agent.impl.LiteRtAiCoreStatus
import dev.ide.agent.impl.LiteRtLmServingRegistry
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Android implementation of [LiteRtAiCoreBridge] that detects and binds to the built-in
 * Google AI Core (`com.google.android.aicore` / `on_device_intelligence` system service)
 * via ML Kit GenAI Prompt (`com.google.mlkit.genai.prompt.Generation`) on Pixel and modern
 * Android devices.
 *
 * Also installs the Android-configured [JvmLiteRtEngineBridge] with `context.cacheDir` and
 * isolated NPU dispatch libraries (`libLiteRtDispatch_GoogleTensor.so` / `libLiteRtDispatch_MediaTek.so`).
 *
 * Never fabricates or mocks output: returns only real Gemini Nano output or the exact runtime exception.
 */
class AndroidAiCoreBridge(private val context: Context) : LiteRtAiCoreBridge {

    @Volatile
    private var cachedClient: GenerativeModel? = null

    private fun getOrCreateClient(): GenerativeModel? {
        cachedClient?.let { return it }
        return runCatching {
            Generation.getClient().also { cachedClient = it }
        }.getOrNull()
    }

    override fun probeStatus(): LiteRtAiCoreStatus {
        val pm = context.packageManager
        val pkgInfo = runCatching {
            pm.getPackageInfo(AICORE_PACKAGE, 0)
        }.getOrNull()

        val serviceIntent = Intent(AICORE_BIND_ACTION).setPackage(AICORE_PACKAGE)
        val serviceResolvable = runCatching {
            pm.resolveService(serviceIntent, 0) != null
        }.getOrDefault(false)

        val odiServicePresent = runCatching {
            context.getSystemService("on_device_intelligence") != null
        }.getOrDefault(false)

        var checkError: Throwable? = null
        val featureStatusCode = runCatching {
            runBlocking {
                withTimeoutOrNull(2_000L) {
                    getOrCreateClient()?.checkStatus()
                }
            }
        }.onFailure { checkError = it }.getOrNull()

        val featureStatusLabel = when (featureStatusCode) {
            FeatureStatus.AVAILABLE -> "AVAILABLE"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING"
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
            FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
            else -> null
        }

        val aiCorePackagePresent = pkgInfo != null || serviceResolvable
        val statusRejected = checkError?.message?.let { msg ->
            msg.contains("-101") || msg.contains("not installed", ignoreCase = true)
        } == true || featureStatusCode == FeatureStatus.UNAVAILABLE

        val available = !statusRejected && (
            aiCorePackagePresent ||
                featureStatusCode == FeatureStatus.AVAILABLE ||
                featureStatusCode == FeatureStatus.DOWNLOADABLE ||
                featureStatusCode == FeatureStatus.DOWNLOADING
            )

        val rawVersion = pkgInfo?.versionName.orEmpty()
        val versionName = if (!featureStatusLabel.isNullOrBlank() && rawVersion.isNotBlank()) {
            "$rawVersion ($featureStatusLabel)"
        } else {
            rawVersion.ifBlank { featureStatusLabel.orEmpty() }
        }

        return LiteRtAiCoreStatus(
            available = available,
            packageName = AICORE_PACKAGE,
            versionName = versionName,
            systemServicePresent = odiServicePresent,
            modelName = "Gemini Nano (Android AI Core)",
        )
    }

    override fun generate(
        prompt: String,
        system: String?,
        maxTokens: Int,
    ): Result<String> = runCatching {
        val status = probeStatus()
        if (!status.available) {
            throw IllegalStateException("Android AI Core ($AICORE_PACKAGE) is not installed on this device.")
        }
        val cleanPrompt = prompt.trim()
        val versionShort = status.versionName.substringBefore(".thirdparty").take(18).ifBlank { "system" }

        runBlocking {
            withTimeout(25_000L) {
                val client = getOrCreateClient()
                    ?: throw IllegalStateException("ML Kit GenAI Generation.getClient() is unavailable.")
                val fStatus = runCatching { client.checkStatus() }.getOrDefault(FeatureStatus.UNAVAILABLE)
                if (fStatus == FeatureStatus.DOWNLOADABLE) {
                    client.download().collect { dl ->
                        if (dl is DownloadStatus.DownloadFailed) {
                            throw dl.e
                        }
                    }
                }
                runCatching { client.warmup() }
                val reqBuilder = if (!system.isNullOrBlank()) {
                    GenerateContentRequest.Builder(SystemInstruction(system), TextPart(cleanPrompt))
                } else {
                    GenerateContentRequest.Builder(TextPart(cleanPrompt))
                }
                reqBuilder.maxOutputTokens = maxTokens.coerceIn(16, 1024)
                val response = client.generateContent(reqBuilder.build())
                val text = response.candidates
                    .mapNotNull { it.text?.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?: throw IllegalStateException("Android AI Core returned an empty candidate list.")
                "[Android AI Core · Gemini Nano · ${status.packageName} v$versionShort] $text"
            }
        }
    }.onFailure { err ->
        Log.w(TAG, "Android AI Core generation failed: ${err.message}", err)
    }

    companion object {
        private const val TAG = "AndroidAiCoreBridge"
        const val AICORE_PACKAGE = "com.google.android.aicore"
        const val AICORE_BIND_ACTION = "com.google.android.apps.aicore.service.BIND_SERVICE"

        private val VENDOR_LIBS = mapOf(
            "google-tensor" to listOf("libLiteRtDispatch_GoogleTensor.so", "libLiteRtCompilerPlugin_google_tensor.so"),
            "mediatek" to listOf("libLiteRtDispatch_MediaTek.so", "libLiteRtCompilerPlugin_MediaTek.so"),
        )

        private fun <T> fromInstalledNativeLib(context: Context, name: String, consume: (InputStream) -> T): T? {
            val extracted = File(context.applicationInfo.nativeLibraryDir, name)
            if (extracted.isFile) return extracted.inputStream().use(consume)
            val apks = listOf(context.applicationInfo.sourceDir) + context.applicationInfo.splitSourceDirs.orEmpty()
            for (path in apks) {
                runCatching {
                    ZipFile(path).use { apk ->
                        for (abi in Build.SUPPORTED_ABIS) {
                            val entry = apk.getEntry("lib/$abi/$name") ?: continue
                            return apk.getInputStream(entry).use(consume)
                        }
                    }
                }
            }
            return null
        }

        @Synchronized
        internal fun resolveNpuVendorDirectory(context: Context): String {
            val socLower = listOf(Build.SOC_MODEL, Build.HARDWARE, Build.MODEL, Build.SOC_MANUFACTURER)
                .joinToString(" ")
                .lowercase()
            val preferredVendor = if (socLower.contains("mt6") || socLower.contains("dimensity")) {
                "mediatek"
            } else {
                "google-tensor"
            }
            val names = VENDOR_LIBS[preferredVendor].orEmpty()
            val destination = File(context.codeCacheDir, "litert-npu/$preferredVendor").apply { mkdirs() }
            var allStaged = true
            for (name in names) {
                val target = File(destination, name)
                if (target.isFile && target.length() > 0L) continue
                val bytes = fromInstalledNativeLib(context, name) { it.readBytes() }
                if (bytes == null) {
                    allStaged = false
                    break
                }
                val temp = File.createTempFile(".npu-", ".partial", destination)
                try {
                    FileOutputStream(temp).use {
                        it.write(bytes)
                        it.fd.sync()
                    }
                    temp.setReadOnly()
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    temp.delete()
                }
            }
            return if (allStaged) destination.absolutePath else context.applicationInfo.nativeLibraryDir
        }

        fun install(context: Context) {
            val appCtx = context.applicationContext
            LiteRtLmServingRegistry.registerAiCoreBridge(AndroidAiCoreBridge(appCtx))
            LiteRtLmServingRegistry.registerEngineBridge(
                JvmLiteRtEngineBridge(
                    cacheRootProvider = { appCtx.cacheDir },
                    npuLibraryDirProvider = { resolveNpuVendorDirectory(appCtx) },
                ),
            )
        }
    }
}
