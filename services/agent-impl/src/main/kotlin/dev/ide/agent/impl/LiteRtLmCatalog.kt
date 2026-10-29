package dev.ide.agent.impl

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Hardware execution delegates supported by Google LiteRT-LM (`.litertlm` / TFLite GenAI).
 */
enum class LiteRtHardwareDelegate(val id: String, val displayName: String) {
    CPU("CPU", "CPU (XNNPACK / ARM NEON)"),
    GPU("GPU", "GPU (OpenCL / Vulkan / Metal)"),
    NPU("NPU", "NPU (Google Tensor / Qualcomm QNN)"),
    ;

    companion object {
        fun fromId(raw: String?): LiteRtHardwareDelegate? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * Lifecycle state of an on-device LiteRT-LM model artifact in the shared catalog.
 *
 * Per engineering law, [INSTALLED] and [ACTIVE] are ONLY reported when a verified `.litertlm`
 * artifact file physically exists on disk and passes header + SHA-256 verification.
 */
enum class LiteRtModelInstallState {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLED,
    ACTIVE,
    ERROR,
}

/**
 * Static specification for a model in the Self-Serve LiteRT-LM Catalog.
 */
data class LiteRtModelCatalogSpec(
    val id: String,
    val displayName: String,
    val family: String,
    val parameterCount: String,
    val quantization: String,
    val artifactFileName: String,
    val estimatedSizeBytes: Long,
    val contextLength: Int,
    val supportedDelegates: List<LiteRtHardwareDelegate>,
    val supportsToolCalling: Boolean,
    val supportsThinking: Boolean,
    val downloadUrl: String,
    val description: String,
)

/**
 * Curated catalog of on-device LiteRT-LM (`.litertlm`) models for local IDE agent execution
 * and for guest apps running on the phone.
 */
object LiteRtLmCatalog {
    const val DEFAULT_MODEL_ID: String = "gemma-3-1b-it-int4"
    const val DEFAULT_SERVER_PORT: Int = 11435
    const val DEFAULT_IPC_AUTHORITY: String = "dev.ide.android.litertlm"

    val MODELS: List<LiteRtModelCatalogSpec> = listOf(
        LiteRtModelCatalogSpec(
            id = "gemma-3-1b-it-int4",
            displayName = "Gemma 3 1B Instruct (INT4)",
            family = "Gemma 3",
            parameterCount = "1B",
            quantization = "INT4",
            artifactFileName = "gemma3-1b-it-int4.litertlm",
            estimatedSizeBytes = 786_432_000L,
            contextLength = 32_768,
            supportedDelegates = listOf(
                LiteRtHardwareDelegate.NPU,
                LiteRtHardwareDelegate.GPU,
                LiteRtHardwareDelegate.CPU,
            ),
            supportsToolCalling = true,
            supportsThinking = false,
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            description = "Compact 1B edge model tuned for sub-second responses on CPU, GPU, and Tensor NPU.",
        ),
        LiteRtModelCatalogSpec(
            id = "gemma-3-4b-it-int4",
            displayName = "Gemma 3 4B Instruct (INT4)",
            family = "Gemma 3",
            parameterCount = "4B",
            quantization = "INT4",
            artifactFileName = "gemma3-4b-it-int4.litertlm",
            estimatedSizeBytes = 2_621_440_000L,
            contextLength = 128_000,
            supportedDelegates = listOf(
                LiteRtHardwareDelegate.GPU,
                LiteRtHardwareDelegate.NPU,
                LiteRtHardwareDelegate.CPU,
            ),
            supportsToolCalling = true,
            supportsThinking = false,
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-4B-IT/resolve/main/gemma3-4b-it-int4.litertlm",
            description = "High-capacity 4B instruction model for coding, local agent tool use, and guest apps.",
        ),
        LiteRtModelCatalogSpec(
            id = "qwen-2.5-coder-1.5b-int4",
            displayName = "Qwen 2.5 Coder 1.5B (INT4)",
            family = "Qwen 2.5 Coder",
            parameterCount = "1.5B",
            quantization = "INT4",
            artifactFileName = "qwen2.5-coder-1.5b-instruct-int4.litertlm",
            estimatedSizeBytes = 1_153_433_600L,
            contextLength = 32_768,
            supportedDelegates = listOf(
                LiteRtHardwareDelegate.GPU,
                LiteRtHardwareDelegate.CPU,
            ),
            supportsToolCalling = true,
            supportsThinking = false,
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-Coder-1.5B-Instruct/resolve/main/qwen2.5-coder-1.5b-instruct-int4.litertlm",
            description = "Code-specialized local model for completion, refactoring, and structured JSON output.",
        ),
        LiteRtModelCatalogSpec(
            id = "deepseek-r1-distill-qwen-1.5b-int4",
            displayName = "DeepSeek R1 Distill 1.5B (INT4)",
            family = "DeepSeek R1",
            parameterCount = "1.5B",
            quantization = "INT4",
            artifactFileName = "deepseek-r1-distill-qwen-1.5b-int4.litertlm",
            estimatedSizeBytes = 1_205_862_400L,
            contextLength = 32_768,
            supportedDelegates = listOf(
                LiteRtHardwareDelegate.GPU,
                LiteRtHardwareDelegate.NPU,
                LiteRtHardwareDelegate.CPU,
            ),
            supportsToolCalling = false,
            supportsThinking = true,
            downloadUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/deepseek-r1-distill-qwen-1.5b-int4.litertlm",
            description = "Reasoning-distilled model with chain-of-thought <think> blocks for step-by-step problem solving.",
        ),
    )

    fun find(id: String): LiteRtModelCatalogSpec? = MODELS.firstOrNull { it.id == id }
}

/**
 * Hardware capability snapshot discovered on the current device or host.
 */
data class LiteRtHardwareCapabilities(
    val socName: String,
    val availableDelegates: List<LiteRtHardwareDelegate>,
    val recommendedDelegate: LiteRtHardwareDelegate,
)

/**
 * Probes the host hardware (Android SoC properties, Linux `/dev` nodes, or desktop GPU/NPU architecture)
 * to determine which LiteRT-LM delegates (`CPU`, `GPU`, `NPU`) are supported.
 */
object LiteRtHardwareProbe {

    fun probe(
        osNameOverride: String? = null,
        osArchOverride: String? = null,
        socModelOverride: String? = null,
        hardwareOverride: String? = null,
        deviceModelOverride: String? = null,
    ): LiteRtHardwareCapabilities {
        val osName = (osNameOverride ?: System.getProperty("os.name").orEmpty()).trim()
        val osArch = (osArchOverride ?: System.getProperty("os.arch").orEmpty()).trim()
        val androidSoc = socModelOverride ?: readAndroidBuildField("SOC_MODEL")
        val androidHardware = hardwareOverride ?: readAndroidBuildField("HARDWARE")
        val androidModel = deviceModelOverride ?: readAndroidBuildField("MODEL")
        val androidManufacturer = readAndroidBuildField("SOC_MANUFACTURER")

        val combinedAndroid = listOf(androidSoc, androidHardware, androidModel, androidManufacturer)
            .joinToString(" ")
            .lowercase()

        val hasTensorNpu = combinedAndroid.contains("tensor") ||
            combinedAndroid.contains("zuma") ||
            combinedAndroid.contains("laguna") ||
            combinedAndroid.contains("gs201") ||
            combinedAndroid.contains("pixel 8") ||
            combinedAndroid.contains("pixel 9") ||
            combinedAndroid.contains("pixel 10") ||
            combinedAndroid.contains("pixel 11") ||
            File("/dev/edgetpu").exists() ||
            File("/dev/litert_npu").exists()

        val hasQualcommNpu = combinedAndroid.contains("sm8") ||
            combinedAndroid.contains("snapdragon") ||
            combinedAndroid.contains("qcom") ||
            File("/dev/adsprpc-smd").exists()

        val hasMediatekNpu = combinedAndroid.contains("mt68") ||
            combinedAndroid.contains("mt69") ||
            combinedAndroid.contains("dimensity") ||
            File("/dev/apusys").exists()

        val isAppleSilicon = osName.contains("Mac", ignoreCase = true) &&
            (osArch.contains("aarch64", ignoreCase = true) || osArch.contains("arm64", ignoreCase = true))

        val isAndroidOrLinuxArm = osArch.contains("aarch64", ignoreCase = true) ||
            osArch.contains("arm64", ignoreCase = true) ||
            combinedAndroid.isNotBlank()

        val gpuAvailable = isAppleSilicon ||
            isAndroidOrLinuxArm ||
            File("/dev/kgsl-3d0").exists() ||
            File("/dev/mali0").exists() ||
            File("/dev/dri").exists() ||
            osName.contains("Windows", ignoreCase = true) ||
            osName.contains("Linux", ignoreCase = true)

        val npuAvailable = hasTensorNpu || hasQualcommNpu || hasMediatekNpu || isAppleSilicon

        val delegates = buildList {
            add(LiteRtHardwareDelegate.CPU)
            if (gpuAvailable) add(LiteRtHardwareDelegate.GPU)
            if (npuAvailable) add(LiteRtHardwareDelegate.NPU)
        }

        val recommended = when {
            hasTensorNpu || hasQualcommNpu || hasMediatekNpu -> LiteRtHardwareDelegate.NPU
            gpuAvailable -> LiteRtHardwareDelegate.GPU
            else -> LiteRtHardwareDelegate.CPU
        }

        val socLabel = when {
            hasTensorNpu -> {
                val modelSuffix = androidModel.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                val socPrefix = androidSoc.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                    ?: "Google Tensor NPU"
                "$socPrefix$modelSuffix · ARM64 + GPU + Tensor NPU"
            }
            hasQualcommNpu -> {
                val socPrefix = androidSoc.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                    ?: "Qualcomm Snapdragon"
                "$socPrefix · ARM64 + Adreno GPU + Hexagon QNN"
            }
            hasMediatekNpu -> {
                val socPrefix = androidSoc.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                    ?: "MediaTek Dimensity"
                "$socPrefix · ARM64 + Mali GPU + NeuroPilot NPU"
            }
            isAppleSilicon -> "Apple Silicon ($osArch) · CPU + Metal GPU + Neural Engine"
            androidModel.isNotBlank() -> "$androidModel ($osArch) · On-Device Runtime"
            else -> "$osName ($osArch) · Local Host Runtime"
        }

        return LiteRtHardwareCapabilities(
            socName = socLabel,
            availableDelegates = delegates,
            recommendedDelegate = recommended,
        )
    }

    fun usableDiskSpaceBytes(dir: Path): Long = runCatching {
        val target = if (Files.exists(dir)) dir else dir.parent ?: dir
        Files.getFileStore(target).usableSpace
    }.getOrElse {
        runCatching { dir.toFile().usableSpace }.getOrDefault(0L)
    }

    private fun readAndroidBuildField(fieldName: String): String = runCatching {
        val clazz = Class.forName("android.os.Build")
        val value = clazz.getField(fieldName).get(null) as? String
        value?.takeIf { !it.equals("unknown", ignoreCase = true) }.orEmpty()
    }.getOrDefault("")
}
