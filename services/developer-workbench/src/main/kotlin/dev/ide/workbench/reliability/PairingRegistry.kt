package dev.ide.workbench.reliability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.time.Clock

/** No grant secret or its hash is included in UI/AI-facing pairing metadata. */
data class PairingSummary(
    val packageName: String,
    val buildId: String,
    val sourceRevision: String,
    val status: PairingStatus,
    val mode: PairingMode,
    val createdAt: Long,
    val expectedSignerSha256: Set<String>,
    val expectedInstallerPackage: String?,
    val allowUnknownInstaller: Boolean,
)

enum class PairingStatus { PENDING, ACTIVE, REVOKED }
enum class PairingMode { DEBUG, EXPLICIT }

/** One-time return value: never log it or store it in a project. Only a debug private generated asset may use it. */
class PairingSecret internal constructor(val pairing: PairingSummary, val pairingNonce: String) {
    override fun toString(): String = "PairingSecret(pairing=$pairing, pairingNonce=[REDACTED])"
}

/** Platform supplies encrypted private storage. All grant mutations and authorization share a lock. */
class PairingRegistry(
    private val storage: ReliabilityBlobStorage,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    private data class Entry(val summary: PairingSummary, val nonceHash: String)
    private data class Snapshot(val enabled: Boolean, val entries: LinkedHashMap<String, Entry>)
    private val lock = Any()
    private var unavailable = false
    private var snapshot = storage.transaction { load() }
    private val enabled = MutableStateFlow(snapshot.enabled)
    private val updates = MutableStateFlow(snapshot.entries.values.map { it.summary }.reversed())
    val debugCaptureEnabled: StateFlow<Boolean> = enabled.asStateFlow()
    fun observe(): StateFlow<List<PairingSummary>> = updates.asStateFlow()
    fun list(): List<PairingSummary> = latest { updates.value.toList() }
    /** Refreshes process-local observers; authorization/mutations always refresh independently. Call on IO. */
    fun refresh() = latest { Unit }
    fun isDebugCaptureEnabled(): Boolean = latest { snapshot.enabled }

    private fun <T> latest(operation: () -> T): T = synchronized(lock) {
        ensureAvailable()
        storage.transaction {
            try { snapshot = load() } catch (failure: Exception) { unavailable = true; throw failure }
            enabled.value = snapshot.enabled
            updates.value = snapshot.entries.values.map { it.summary }.reversed()
            operation()
        }
    }

    fun setDebugCaptureEnabled(value: Boolean) = latest {
        ensureAvailable()
        if (snapshot.enabled != value) commit(snapshot.copy(enabled = value))
    }

    fun createPendingDebugPairing(packageName: String, buildId: String, sourceRevision: String = ""): PairingSecret = latest {
        if (!snapshot.enabled) unauthorized()
        create(packageName, buildId, sourceRevision, PairingMode.DEBUG, emptySet(), null, false, PairingStatus.PENDING)
    }

    fun prepareDebugPairing(
        packageName: String,
        buildId: String,
        sourceRevision: String = "",
        expectedSignerSha256: Set<String>,
        expectedInstallerPackage: String?,
        allowUnknownInstaller: Boolean = false,
    ): PairingSecret = latest {
        if (!snapshot.enabled) unauthorized()
        validatePins(expectedSignerSha256, expectedInstallerPackage, allowUnknownInstaller)
        create(packageName, buildId, sourceRevision, PairingMode.DEBUG, expectedSignerSha256, expectedInstallerPackage, allowUnknownInstaller, PairingStatus.ACTIVE)
    }

    /** Explicit manual/release consent is separate from debug auto-configuration; never called by a build. */
    fun prepareExplicitPairing(
        packageName: String,
        buildId: String,
        sourceRevision: String = "",
        expectedSignerSha256: Set<String>,
        expectedInstallerPackage: String?,
        consentGranted: Boolean,
        allowUnknownInstaller: Boolean = false,
    ): PairingSecret = latest {
        if (!consentGranted) unauthorized()
        validatePins(expectedSignerSha256, expectedInstallerPackage, allowUnknownInstaller)
        create(packageName, buildId, sourceRevision, PairingMode.EXPLICIT, expectedSignerSha256, expectedInstallerPackage, allowUnknownInstaller, PairingStatus.ACTIVE)
    }

    /** Checks an APK's embedded identity against the inactive private grant without exposing its hash. */
    fun verifyPendingAsset(packageName: String, buildId: String, sourceRevision: String, pairingNonce: String) = latest {
        ensureAvailable()
        val entry = snapshot.entries[id(packageName, buildId)] ?: unauthorized()
        if (entry.summary.status != PairingStatus.PENDING || entry.summary.sourceRevision != sourceRevision ||
            !sha256Pattern.matches(pairingNonce) || !constantTimeEqual(entry.nonceHash, digest(pairingNonce))) unauthorized()
    }

    /** The Android facade derives these pins from the actual signed APK, not from its asset or the caller. */
    fun activateDebugPairing(
        packageName: String,
        buildId: String,
        expectedSignerSha256: Set<String>,
        expectedInstallerPackage: String?,
        allowUnknownInstaller: Boolean = false,
    ) = latest {
        ensureAvailable()
        if (!snapshot.enabled) unauthorized()
        validatePins(expectedSignerSha256, expectedInstallerPackage, allowUnknownInstaller)
        val id = id(packageName, buildId)
        val entry = snapshot.entries[id] ?: unauthorized()
        if (entry.summary.status != PairingStatus.PENDING || entry.summary.mode != PairingMode.DEBUG) unauthorized()
        val next = LinkedHashMap(snapshot.entries)
        next[id] = entry.copy(summary = entry.summary.copy(status = PairingStatus.ACTIVE,
            expectedSignerSha256 = expectedSignerSha256.toSet(), expectedInstallerPackage = expectedInstallerPackage, allowUnknownInstaller = allowUnknownInstaller))
        commit(snapshot.copy(entries = next))
    }

    fun revoke(packageName: String, buildId: String) = latest {
        ensureAvailable()
        val id = id(packageName, buildId)
        val entry = snapshot.entries[id] ?: return@latest
        if (entry.summary.status == PairingStatus.REVOKED) return@latest
        val next = LinkedHashMap(snapshot.entries)
        next[id] = entry.copy(summary = entry.summary.copy(status = PairingStatus.REVOKED))
        commit(snapshot.copy(entries = next))
    }

    internal fun <T> authorizeHello(
        caller: IncidentCaller, packageName: String, buildId: String, sourceRevision: String, nonce: String,
        block: (String) -> T,
    ): T = latest {
        if (!sha256Pattern.matches(nonce)) unauthorized()
        val proof = digest(nonce)
        authorized(caller, packageName, buildId, sourceRevision, proof) { block(proof) }
    }

    /** Holds the revocation lock through durable incident commit; a completed revoke blocks all later writes. */
    internal fun <T> authorized(
        caller: IncidentCaller, packageName: String, buildId: String, sourceRevision: String, proof: String,
        block: () -> T,
    ): T = latest {
        ensureAvailable()
        val entry = snapshot.entries[id(packageName, buildId)] ?: unauthorized()
        val pairing = entry.summary
        if (pairing.status != PairingStatus.ACTIVE || (pairing.mode == PairingMode.DEBUG && !snapshot.enabled) ||
            sourceRevision != pairing.sourceRevision || !constantTimeEqual(entry.nonceHash, proof)) unauthorized()
        // Fail closed for shared UIDs: Binder cannot prove which same-UID package originated an IPC.
        if (caller.uid <= 0 || caller.pid <= 0 || caller.packages != setOf(packageName)) unauthorized()
        val sameSigners = caller.signerSha256 == pairing.expectedSignerSha256
        val legitimateRotation = pairing.expectedSignerSha256.size == 1 && caller.signerSha256.size == 1 &&
            caller.signerHistorySha256.containsAll(pairing.expectedSignerSha256)
        if (!sameSigners && !legitimateRotation) unauthorized()
        if (caller.installerPackage == null) {
            if (!pairing.allowUnknownInstaller) unauthorized()
        } else if (caller.installerPackage != pairing.expectedInstallerPackage) unauthorized()
        block()
    }

    private fun create(
        packageName: String, buildId: String, sourceRevision: String, mode: PairingMode, pins: Set<String>,
        installer: String?, allowUnknown: Boolean, status: PairingStatus,
    ): PairingSecret {
        ensureAvailable()
        validatePackage(packageName); validateId(buildId); validateRevision(sourceRevision)
        val id = id(packageName, buildId)
        if (snapshot.entries.containsKey(id)) throw ReliabilityException(ReliabilityError.ID_CONFLICT)
        if (snapshot.entries.size >= 256 || snapshot.entries.values.count { it.summary.packageName == packageName } >= 64) {
            throw ReliabilityException(ReliabilityError.QUOTA)
        }
        val nonce = randomNonce(random)
        val summary = PairingSummary(packageName, buildId, sourceRevision, status, mode, clock.millis(), pins.toSet(), installer, allowUnknown)
        val next = LinkedHashMap(snapshot.entries)
        next[id] = Entry(summary, digest(nonce))
        commit(snapshot.copy(entries = next))
        return PairingSecret(summary, nonce)
    }

    private fun validatePins(pins: Set<String>, installer: String?, allowUnknown: Boolean) {
        if (pins.isEmpty() || pins.size > 8 || pins.any { !sha256Pattern.matches(it) }) invalid()
        if (installer != null) validatePackage(installer)
        if (installer == null && !allowUnknown) invalid()
    }

    private fun commit(next: Snapshot) {
        val bytes = buildJsonObject {
            put("version", 1)
            put("debugCaptureEnabled", next.enabled)
            put("pairings", JsonArray(next.entries.values.map { entry ->
                val p = entry.summary
                buildJsonObject {
                    put("packageName", p.packageName); put("buildId", p.buildId); put("sourceRevision", p.sourceRevision)
                    put("status", p.status.name); put("mode", p.mode.name); put("createdAt", p.createdAt)
                    put("signers", JsonArray(p.expectedSignerSha256.sorted().map(::JsonPrimitive)))
                    put("installer", p.expectedInstallerPackage?.let(::JsonPrimitive) ?: JsonNull)
                    put("allowUnknownInstaller", p.allowUnknownInstaller); put("nonceHash", entry.nonceHash)
                }
            }))
        }.toString().toByteArray(Charsets.UTF_8)
        try { storage.write(bytes) }
        catch (_: Exception) { unavailable = true; storageFailure() }
        snapshot = next
        enabled.value = next.enabled
        updates.value = next.entries.values.map { it.summary }.reversed()
    }

    private fun load(): Snapshot = try {
        val bytes = storage.read()
        if (bytes == null) Snapshot(false, LinkedHashMap()) else {
            if (bytes.size > MAX_CONFIG_BYTES) storageFailure()
            val root = reliabilityObject(decodeReliabilityUtf8(bytes), MAX_CONFIG_BYTES)
            if (root.number("version") != 1L) storageFailure()
            val values = root["pairings"] as? JsonArray ?: storageFailure()
            if (values.size > 256) storageFailure()
            val entries = LinkedHashMap<String, Entry>()
            for (element in values) {
                val obj = element.objectValue()
                val pins = (obj["signers"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid() }?.toSet() ?: invalid()
                val summary = PairingSummary(obj.text("packageName"), obj.text("buildId"), obj.text("sourceRevision"),
                    PairingStatus.valueOf(obj.text("status")), PairingMode.valueOf(obj.text("mode")), obj.number("createdAt"), pins,
                    if (obj["installer"] == JsonNull) null else obj.text("installer"), obj.bool("allowUnknownInstaller"))
                validatePackage(summary.packageName); validateId(summary.buildId); validateRevision(summary.sourceRevision)
                if (summary.status == PairingStatus.ACTIVE) validatePins(pins, summary.expectedInstallerPackage, summary.allowUnknownInstaller)
                val hash = obj.text("nonceHash")
                if (!sha256Pattern.matches(hash)) storageFailure()
                if (entries.put(id(summary.packageName, summary.buildId), Entry(summary, hash)) != null) storageFailure()
            }
            Snapshot(root.bool("debugCaptureEnabled"), entries)
        }
    } catch (_: Exception) { storageFailure() }

    private fun id(packageName: String, buildId: String) = "$packageName/$buildId"
    private fun ensureAvailable() { if (unavailable) storageFailure() }
    companion object { const val MAX_CONFIG_BYTES = 512 * 1_024 }
}
