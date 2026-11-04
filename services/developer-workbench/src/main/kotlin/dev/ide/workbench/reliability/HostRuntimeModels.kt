package dev.ide.workbench.reliability

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Deliberately independent of runtime/UI models: no command, path, environment, output or Throwable. */
enum class HostRuntimeKind { NODE, NPM, PYTHON, SHELL, STATIC, PREPARATION, UNKNOWN }
enum class HostRuntimeChannel { RUN, TERMINAL }
enum class HostRuntimeStage { PREPARE, LAUNCH, PROCESS, SESSION }
enum class HostRuntimeOutcome { INFRASTRUCTURE_FAILURE, CONFIGURATION_FAILURE, NONZERO_EXIT, TIMEOUT }
enum class HostRuntimeException { IO, SECURITY, INVALID_CONFIGURATION, HOST_API_UNAVAILABLE, OTHER }

/** A snapshot taken before execution, NOT proof of what a process or terminal actually executed. */
enum class HostRuntimeSourceStatus { PRE_RUN_SNAPSHOT, UNAVAILABLE }

/** Supplied by the installed IDE's package/build metadata, never by a project or process. */
data class HostRuntimeIdentity(val hostPackage: String, val versionCode: Long, val versionName: String)

/** Only closed, bounded metadata may cross into the inbox. Source hashes are structured, never scrubbed text. */
data class HostRuntimeContext(
    val sourceRevision: String?,
    val sourceStatus: HostRuntimeSourceStatus,
    val hostPackage: String,
    val versionCode: Long,
    val versionName: String,
    val runtimeKind: HostRuntimeKind,
    val channel: HostRuntimeChannel,
    val stage: HostRuntimeStage,
    val outcome: HostRuntimeOutcome,
    val exception: HostRuntimeException? = null,
    val exitCode: Int? = null,
    val durationMs: Long = 0,
    val packId: String? = null,
    val abi: String? = null,
    val sourcePolicy: String = "runtime-source-v1",
)

/** Host-only API. There is intentionally no generic text, arguments, error, or root property. */
data class HostRuntimeIncident(
    val workspaceId: String,
    val sessionId: String,
    val eventId: String,
    val timestamp: Long,
    val context: HostRuntimeContext,
) {
    val key: IncidentKey get() = IncidentKey("", "", sessionId, eventId, IncidentOrigin.HOST_RUNTIME, workspaceId)
}

/** null outcome closes the handle without an incident (success or user cancellation). */
data class HostRuntimeFinish(
    val outcome: HostRuntimeOutcome?,
    val stage: HostRuntimeStage,
    val exception: HostRuntimeException? = null,
    val exitCode: Int? = null,
    val durationMs: Long = 0,
)

enum class RuntimeCaptureResult {
    READY, DISABLED, CAPTURING, IGNORED, QUEUED, DELIVERED, REVOKED,
    INVALID, QUOTA, ID_CONFLICT, RETRY_LATER, STORAGE_UNAVAILABLE,
}

/** This status never includes a root, raw exception, consent generation or private root hash. */
data class RuntimeCaptureStatus(
    val enabled: Boolean = false,
    val workspaceId: String? = null,
    val queuedEvents: Int = 0,
    val result: RuntimeCaptureResult = RuntimeCaptureResult.DISABLED,
    /** False after an ambiguous/failed operation; enabled must not be presented as confirmed consent. */
    val consentKnown: Boolean = true,
)

internal const val MAX_RUNTIME_DURATION_MS = 7L * 24 * 60 * 60 * 1_000
internal val hostRuntimeRevision = Regex("[a-fA-F0-9]{64}")
private val runtimeUuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
private val runtimePack = Regex("termux-arm64-[a-f0-9]{16}")
private val hostVersionName = Regex("[0-9A-Za-z+._-]{1,64}")

internal fun validateRuntimeUuid(value: String) { if (!runtimeUuid.matches(value)) invalid() }
internal fun validateRuntimeIdentity(value: HostRuntimeIdentity) {
    validatePackage(value.hostPackage)
    if (value.versionCode <= 0 || !hostVersionName.matches(value.versionName)) invalid()
}

/** This is the bundled, trusted manifest namespace, not an arbitrary project-provided pack label. */
internal fun validateRuntimePack(packId: String?, abi: String?) {
    if (packId == null && abi == null) return
    if (packId == null || !runtimePack.matches(packId) || abi != "arm64-v8a") invalid()
}

internal fun validateRuntimeIncident(event: HostRuntimeIncident) {
    validateKey(event.key)
    if (event.timestamp < 0) invalid()
    val c = event.context
    validateRuntimeIdentity(HostRuntimeIdentity(c.hostPackage, c.versionCode, c.versionName))
    if (c.sourceRevision == null) {
        if (c.sourceStatus != HostRuntimeSourceStatus.UNAVAILABLE) invalid()
    } else if (!hostRuntimeRevision.matches(c.sourceRevision) || c.sourceStatus != HostRuntimeSourceStatus.PRE_RUN_SNAPSHOT) invalid()
    if (c.sourcePolicy != "runtime-source-v1" || c.durationMs !in 0..MAX_RUNTIME_DURATION_MS) invalid()
    if (c.outcome == HostRuntimeOutcome.CONFIGURATION_FAILURE && c.exception != HostRuntimeException.INVALID_CONFIGURATION) invalid()
    if (c.outcome == HostRuntimeOutcome.NONZERO_EXIT && (c.exitCode == null || c.exitCode == 0)) invalid()
    validateRuntimePack(c.packId, c.abi)
    if (c.packId == null && c.stage != HostRuntimeStage.PREPARE && c.runtimeKind != HostRuntimeKind.STATIC) invalid()
}

/** Fixed wording generated from validated DTO fields; exact sourceRevision lives only in structured metadata. */
internal fun canonicalRuntimeText(event: HostRuntimeIncident): String = with(event.context) {
    buildString {
        append("Host runtime failure\n")
        append("Runtime: ").append(runtimeKind.name).append("\nChannel: ").append(channel.name)
        append("\nStage: ").append(stage.name).append("\nOutcome: ").append(outcome.name)
        append("\nException: ").append(exception?.name ?: "NONE")
        append("\nExit code: ").append(exitCode?.toString() ?: "UNAVAILABLE")
        append("\nDuration ms: ").append(durationMs)
        append("\nHost: ").append(hostPackage).append("\nHost version: ").append(versionName).append(" (").append(versionCode).append(')')
        append("\nPack: ").append(packId ?: "UNAVAILABLE").append("\nABI: ").append(abi ?: "UNAVAILABLE")
        append("\nSource: ").append(sourceStatus.name).append("\nSource policy: ").append(sourcePolicy)
        append("\nPre-run snapshot only; not an execution attestation. Automatic repair is not eligible.")
    }
}

/** Private serialization only. SDK parseIncident keeps its original, separate field allowlist. */
internal fun runtimeEventJson(event: HostRuntimeIncident) = buildJsonObject {
    put("workspaceId", event.workspaceId); put("sessionId", event.sessionId); put("eventId", event.eventId)
    put("timestamp", event.timestamp)
    val c = event.context
    put("sourceRevision", c.sourceRevision?.let(::JsonPrimitive) ?: JsonNull)
    put("sourceStatus", c.sourceStatus.name); put("sourcePolicy", c.sourcePolicy)
    put("hostPackage", c.hostPackage); put("versionCode", c.versionCode); put("versionName", c.versionName)
    put("runtimeKind", c.runtimeKind.name); put("channel", c.channel.name); put("stage", c.stage.name); put("outcome", c.outcome.name)
    put("exception", c.exception?.name?.let(::JsonPrimitive) ?: JsonNull)
    put("exitCode", c.exitCode?.let(::JsonPrimitive) ?: JsonNull); put("durationMs", c.durationMs)
    put("packId", c.packId?.let(::JsonPrimitive) ?: JsonNull); put("abi", c.abi?.let(::JsonPrimitive) ?: JsonNull)
}

internal fun parseRuntimeIncident(value: JsonObject): HostRuntimeIncident {
    value.only("workspaceId", "sessionId", "eventId", "timestamp", "sourceRevision", "sourceStatus", "sourcePolicy", "hostPackage", "versionCode", "versionName",
        "runtimeKind", "channel", "stage", "outcome", "exception", "exitCode", "durationMs", "packId", "abi")
    val exit = if (value["exitCode"] == JsonNull) null else value.number("exitCode").also { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) invalid() }.toInt()
    return HostRuntimeIncident(value.text("workspaceId"), value.text("sessionId"), value.text("eventId"), value.number("timestamp"),
        HostRuntimeContext(
            value.runtimeNullableText("sourceRevision"), HostRuntimeSourceStatus.valueOf(value.text("sourceStatus")),
            value.text("hostPackage"), value.number("versionCode"), value.text("versionName"),
            HostRuntimeKind.valueOf(value.text("runtimeKind")), HostRuntimeChannel.valueOf(value.text("channel")),
            HostRuntimeStage.valueOf(value.text("stage")), HostRuntimeOutcome.valueOf(value.text("outcome")),
            value.runtimeNullableText("exception")?.let(HostRuntimeException::valueOf), exit, value.number("durationMs"),
            value.runtimeNullableText("packId"), value.runtimeNullableText("abi"), value.text("sourcePolicy"),
        )).also(::validateRuntimeIncident)
}

private fun JsonObject.runtimeNullableText(name: String): String? = if (get(name) == JsonNull) null else text(name)
