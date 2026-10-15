package dev.ide.workbench.reliability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

/**
 * Private, opt-in host capture. All operations belong on IO; construction never replays or hashes sources.
 * The two storages must be distinct app-private blobs. Lock order is always registry, outbox, durable inbox.
 * [deliver] must ONLY durably accept the typed batch, not invoke UI/onCommitted callbacks or reenter capture.
 * It runs under the consent lock so a completed revocation cannot race a later inbox commit. No network/AI.
 */
class HostRuntimeCapture(
    private val registryStorage: ReliabilityBlobStorage,
    private val outboxStorage: ReliabilityBlobStorage,
    private val deliver: (List<HostRuntimeIncident>) -> DurableIncidentRepository.CommitResult,
    private val sourceSnapshot: (Path) -> String?,
    private val identity: HostRuntimeIdentity,
    private val clock: Clock = Clock.systemUTC(),
    private val maxQueuedEvents: Int = 64,
) {
    private data class Grant(val workspaceId: String, val generation: String, val enabled: Boolean)
    private data class Queued(val rootHash: String, val generation: String, val event: HostRuntimeIncident)
    private data class Workspace(val root: Path, val hash: String)
    private val lock = Any()
    private val mutableUpdates = MutableStateFlow(RuntimeCaptureStatus())
    val updates: StateFlow<RuntimeCaptureStatus> = mutableUpdates.asStateFlow()

    init {
        validateRuntimeIdentity(identity)
        require(maxQueuedEvents in 1..MAX_QUEUED_EVENTS) { "Invalid private runtime queue limit." }
        require(registryStorage !== outboxStorage) { "Runtime consent and outbox need distinct private storage." }
    }

    /** Turning capture off does not delete already-saved inbox incidents. Every new grant gets a new generation. */
    fun setEnabled(root: Path, enabled: Boolean): RuntimeCaptureStatus = attemptStatus {
        val workspace = workspace(root)
        synchronized(lock) {
            registryStorage.transaction {
                val registry = loadRegistry()
                val previous = registry[workspace.hash]
                if (!enabled) {
                    // Commit revocation BEFORE touching the queue. Even failed cleanup cannot revive this grant.
                    val revoked = previous?.copy(enabled = false)
                    if (previous?.enabled == true) {
                        registry[workspace.hash] = revoked!!
                        writeRegistry(registry)
                    }
                    outboxStorage.transaction {
                        val queue = loadOutbox()
                        if (queue.entries.removeAll { it.value.rootHash == workspace.hash }) writeOutbox(queue)
                        publish(revoked, queue, RuntimeCaptureResult.DISABLED)
                    }
                } else {
                    outboxStorage.transaction {
                        val queue = loadOutbox()
                        val grant = if (previous?.enabled == true) previous else {
                            if (previous == null && registry.size >= MAX_WORKSPACES) throw ReliabilityException(ReliabilityError.QUOTA)
                            Grant(previous?.workspaceId ?: uuid(), uuid(), true).also {
                                registry[workspace.hash] = it
                                writeRegistry(registry)
                            }
                        }
                        if (queue.entries.removeAll { it.value.rootHash == workspace.hash && !authorized(it.value, registry) }) writeOutbox(queue)
                        publish(grant, queue, RuntimeCaptureResult.READY)
                    }
                }
            }
        }
    }

    /** Reads durable consent, never a cached enabled bit. This does not snapshot sources or replay events. */
    fun status(root: Path): RuntimeCaptureStatus = attemptStatus {
        val workspace = workspace(root)
        locked { registry, queue ->
            val grant = registry[workspace.hash]
            publish(grant, queue, if (grant?.enabled == true) RuntimeCaptureResult.READY else RuntimeCaptureResult.DISABLED)
        }
    }

    /** The callback is completed before returning the handle, and is never invoked without an active grant. */
    fun begin(root: Path, sessionId: String, kind: HostRuntimeKind, channel: HostRuntimeChannel): Handle? = attempt {
        val workspace = workspace(root)
        val grant = locked { registry, queue ->
            val current = registry[workspace.hash]
            if (current?.enabled != true) {
                publish(current, queue, RuntimeCaptureResult.DISABLED)
                null
            } else {
                validateRuntimeUuid(sessionId)
                publish(current, queue, RuntimeCaptureResult.CAPTURING)
                current
            }
        } ?: return@attempt null
        // External source IO must not run under a consent/queue lock. Recheck the generation afterwards.
        val revision = try { sourceSnapshot(workspace.root)?.takeIf(hostRuntimeRevision::matches) }
        catch (_: Exception) { null }
        catch (_: LinkageError) { null }
        locked { registry, queue ->
            val current = registry[workspace.hash]
            if (current?.enabled != true || current.generation != grant.generation || current.workspaceId != grant.workspaceId) {
                publish(current, queue, RuntimeCaptureResult.REVOKED)
                null
            } else {
                publish(current, queue, RuntimeCaptureResult.CAPTURING)
                Handle(workspace.hash, grant.workspaceId, grant.generation, sessionId, kind, channel, revision, uuid())
            }
        }
    }

    /** Explicit app/inbox/runtime-resume hook. There is no timer, background service, or network transport. */
    fun replay(): RuntimeCaptureStatus = attemptStatus {
        locked { registry, queue ->
            var revoked = false
            if (queue.entries.removeAll { !authorized(it.value, registry) }) {
                writeOutbox(queue)
                revoked = true
            }
            var accepted = false
            var failure: RuntimeCaptureResult? = null
            // One blocked workspace must not starve unrelated entries (e.g. its per-workspace inbox quota).
            for (entry in queue.values.toList()) {
                val result = deliverQueued(entry, queue)
                if (result == RuntimeCaptureResult.DELIVERED) accepted = true
                else if (failure == null) failure = result
            }
            publish(null, queue, failure ?: when {
                accepted -> RuntimeCaptureResult.DELIVERED
                revoked -> RuntimeCaptureResult.REVOKED
                else -> RuntimeCaptureResult.READY
            }, registry.values.any { it.enabled })
        }
    }

    /** One stable event per begin/finish, including retries after an uncertain queue write or lost receipt. */
    inner class Handle internal constructor(
        private val rootHash: String,
        private val workspaceId: String,
        private val generation: String,
        private val sessionId: String,
        private val kind: HostRuntimeKind,
        private val channel: HostRuntimeChannel,
        private val revision: String?,
        private val eventId: String,
    ) {
        private val handleLock = Any()
        private var packId: String? = null
        private var abi: String? = null
        private var event: HostRuntimeIncident? = null
        private var closed = false
        private var lastStatus = RuntimeCaptureStatus(true, workspaceId, result = RuntimeCaptureResult.CAPTURING)

        /** Only the bundled installation's trusted manifest identity may be supplied, never a project label. */
        fun prepared(packId: String, abi: String): RuntimeCaptureStatus = synchronized(handleLock) {
            if (closed || event != null) return@synchronized lastStatus
            lastStatus = attemptStatus {
                validateRuntimePack(packId, abi)
                this.packId = packId
                this.abi = abi
                lastStatus
            }
            lastStatus
        }

        fun finish(outcome: HostRuntimeFinish): RuntimeCaptureStatus = synchronized(handleLock) {
            if (closed) return@synchronized lastStatus
            lastStatus = attemptStatus {
                if (event == null && outcome.outcome == null) {
                    return@attemptStatus locked { registry, queue ->
                        val current = registry[rootHash]
                        val allowed = current?.enabled == true && current.workspaceId == workspaceId && current.generation == generation
                        closed = true
                        publish(current, queue, if (allowed) RuntimeCaptureResult.IGNORED else RuntimeCaptureResult.REVOKED)
                    }
                }
                val incident = event ?: HostRuntimeIncident(workspaceId, sessionId, eventId, clock.millis(), HostRuntimeContext(
                    revision, if (revision == null) HostRuntimeSourceStatus.UNAVAILABLE else HostRuntimeSourceStatus.PRE_RUN_SNAPSHOT,
                    identity.hostPackage, identity.versionCode, identity.versionName, kind, channel, outcome.stage,
                    outcome.outcome ?: invalid(), outcome.exception, outcome.exitCode, outcome.durationMs, packId, abi,
                )).also {
                    validateRuntimeIncident(it)
                    event = it
                }
                locked { registry, queue ->
                    val entry = Queued(rootHash, generation, incident)
                    val current = registry[rootHash]
                    if (!authorized(entry, registry)) {
                        closed = true
                        return@locked publish(current, queue, RuntimeCaptureResult.REVOKED)
                    }
                    val previous = queue[incident.key]
                    if (previous != null && previous != entry) throw ReliabilityException(ReliabilityError.ID_CONFLICT)
                    if (previous == null) {
                        if (queue.size >= maxQueuedEvents) throw ReliabilityException(ReliabilityError.QUOTA)
                        queue[incident.key] = entry
                        // The outbox must be durable even when the inbox is currently reachable.
                        writeOutbox(queue)
                    }
                    val result = deliverQueued(entry, queue)
                    if (result == RuntimeCaptureResult.DELIVERED) closed = true
                    publish(current, queue, result)
                }
            }
            lastStatus
        }

        override fun toString(): String = "HostRuntimeCapture.Handle"
    }

    /** Caller holds registry and outbox locks. ACK only exact durable acceptedKeys, never newlyStored alone. */
    private fun deliverQueued(entry: Queued, queue: LinkedHashMap<IncidentKey, Queued>): RuntimeCaptureResult {
        val result = try { deliver(listOf(entry.event)) }
        catch (failure: Exception) { return failureResult(failure) }
        catch (_: LinkageError) { return RuntimeCaptureResult.STORAGE_UNAVAILABLE }
        if (entry.event.key !in result.acceptedKeys) return RuntimeCaptureResult.QUEUED
        queue.remove(entry.event.key)
        // If this fails, replay resends the same DTO/key; inbox dedup prevents a second new incident.
        writeOutbox(queue)
        return RuntimeCaptureResult.DELIVERED
    }

    private fun authorized(entry: Queued, registry: Map<String, Grant>): Boolean {
        val grant = registry[entry.rootHash] ?: return false
        return grant.enabled && grant.workspaceId == entry.event.workspaceId && grant.generation == entry.generation
    }

    private fun <T> locked(operation: (LinkedHashMap<String, Grant>, LinkedHashMap<IncidentKey, Queued>) -> T): T = synchronized(lock) {
        registryStorage.transaction {
            val registry = loadRegistry()
            outboxStorage.transaction { operation(registry, loadOutbox()) }
        }
    }

    private fun publish(grant: Grant?, queue: Map<IncidentKey, Queued>, result: RuntimeCaptureResult, enabled: Boolean = grant?.enabled == true): RuntimeCaptureStatus {
        val queued = if (grant == null) queue.size else queue.values.count { it.event.workspaceId == grant.workspaceId }
        return RuntimeCaptureStatus(enabled, grant?.workspaceId, queued, result).also { mutableUpdates.value = it }
    }

    private fun <T> attempt(operation: () -> T): T? = try { operation() }
    catch (failure: Exception) { failed(failureResult(failure)); null }
    catch (_: LinkageError) { failed(RuntimeCaptureResult.STORAGE_UNAVAILABLE); null }

    private fun attemptStatus(operation: () -> RuntimeCaptureStatus): RuntimeCaptureStatus = attempt(operation) ?: mutableUpdates.value
    private fun failed(result: RuntimeCaptureResult): RuntimeCaptureStatus = mutableUpdates.value.copy(result = result, consentKnown = false).also { mutableUpdates.value = it }
    private fun failureResult(failure: Exception): RuntimeCaptureResult = when ((failure as? ReliabilityException)?.code) {
        ReliabilityError.INVALID -> RuntimeCaptureResult.INVALID
        ReliabilityError.UNAUTHORIZED -> RuntimeCaptureResult.REVOKED
        ReliabilityError.ID_CONFLICT -> RuntimeCaptureResult.ID_CONFLICT
        ReliabilityError.QUOTA -> RuntimeCaptureResult.QUOTA
        ReliabilityError.RETRY_LATER -> RuntimeCaptureResult.RETRY_LATER
        else -> RuntimeCaptureResult.STORAGE_UNAVAILABLE
    }

    private fun workspace(root: Path): Workspace {
        val canonical = root.toRealPath()
        if (!Files.isDirectory(canonical)) invalid()
        return Workspace(canonical, digest(canonical.toString()))
    }

    private fun loadRegistry(): LinkedHashMap<String, Grant> = try {
        val entries = LinkedHashMap<String, Grant>()
        val bytes = registryStorage.read()
        if (bytes != null) {
            if (bytes.size > MAX_REGISTRY_BYTES) storageFailure()
            val root = reliabilityObject(decodeReliabilityUtf8(bytes), MAX_REGISTRY_BYTES)
            root.only("version", "workspaces")
            if (root.number("version") != 1L) storageFailure()
            val array = root["workspaces"] as? JsonArray ?: storageFailure()
            if (array.size > MAX_WORKSPACES) storageFailure()
            val workspaceIds = HashSet<String>()
            for (item in array) {
                val value = item.objectValue()
                value.only("rootHash", "workspaceId", "generation", "enabled")
                val hash = value.text("rootHash")
                if (!sha256Pattern.matches(hash)) storageFailure()
                val grant = Grant(value.text("workspaceId"), value.text("generation"), value.bool("enabled"))
                validateRuntimeUuid(grant.workspaceId); validateRuntimeUuid(grant.generation)
                if (!workspaceIds.add(grant.workspaceId) || entries.put(hash, grant) != null) storageFailure()
            }
        }
        entries
    } catch (_: Exception) { storageFailure() }

    private fun loadOutbox(): LinkedHashMap<IncidentKey, Queued> = try {
        val queue = LinkedHashMap<IncidentKey, Queued>()
        val bytes = outboxStorage.read()
        if (bytes != null) {
            if (bytes.size > MAX_OUTBOX_BYTES) storageFailure()
            val root = reliabilityObject(decodeReliabilityUtf8(bytes), MAX_OUTBOX_BYTES)
            root.only("version", "events")
            if (root.number("version") != 1L) storageFailure()
            val array = root["events"] as? JsonArray ?: storageFailure()
            if (array.size > MAX_QUEUED_EVENTS) storageFailure()
            for (item in array) {
                val value = item.objectValue()
                value.only("rootHash", "generation", "event")
                val hash = value.text("rootHash")
                val generation = value.text("generation")
                if (!sha256Pattern.matches(hash)) storageFailure()
                validateRuntimeUuid(generation)
                val event = parseRuntimeIncident(value["event"]?.objectValue() ?: storageFailure())
                if (queue.put(event.key, Queued(hash, generation, event)) != null) storageFailure()
            }
        }
        queue
    } catch (_: Exception) { storageFailure() }

    private fun writeRegistry(registry: Map<String, Grant>) = write(registryStorage, buildJsonObject {
        put("version", 1)
        put("workspaces", JsonArray(registry.map { (hash, grant) -> buildJsonObject {
            put("rootHash", hash); put("workspaceId", grant.workspaceId); put("generation", grant.generation); put("enabled", grant.enabled)
        } }))
    }.toString(), MAX_REGISTRY_BYTES)

    private fun writeOutbox(queue: Map<IncidentKey, Queued>) = write(outboxStorage, buildJsonObject {
        put("version", 1)
        put("events", JsonArray(queue.values.map { entry -> buildJsonObject {
            put("rootHash", entry.rootHash); put("generation", entry.generation); put("event", runtimeEventJson(entry.event))
        } }))
    }.toString(), MAX_OUTBOX_BYTES)

    private fun write(storage: ReliabilityBlobStorage, text: String, maxBytes: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) throw ReliabilityException(ReliabilityError.QUOTA)
        try { storage.write(bytes) } catch (_: Exception) { storageFailure() }
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    companion object {
        const val MAX_REGISTRY_BYTES = 512 * 1_024
        const val MAX_OUTBOX_BYTES = 512 * 1_024
        private const val MAX_WORKSPACES = 256
        private const val MAX_QUEUED_EVENTS = 64
    }
}
