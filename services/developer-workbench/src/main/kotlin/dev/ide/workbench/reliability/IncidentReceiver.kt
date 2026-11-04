package dev.ide.workbench.reliability

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/** Platform-independent receiver; Android supplies an authenticated caller snapshot for every transaction. */
class IncidentReceiver(
    private val repository: DurableIncidentRepository,
    private val pairings: PairingRegistry,
    private val random: SecureRandom = SecureRandom(),
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Optional live-console mirror. Receives new durable records only, never retransmission duplicates. */
    private val onCommitted: (List<Incident>) -> Unit = {},
) {
    private data class Identity(val packageName: String, val buildId: String, val sourceRevision: String, val sessionId: String, val clientNonce: String)
    private data class Challenge(val identity: Identity, val uid: Int, val pid: Int, val serverNonce: String, val proof: String, val expiresAt: Long)
    private data class Bucket(var tokens: Double, var last: Long)
    private val lock = Any()
    private val challenges = LinkedHashMap<String, Challenge>()
    private val callers = LinkedHashMap<Int, Bucket>(16, 0.75f, true)
    private val global = Bucket(240.0, monotonicMillis())

    /** Does not trust the package name until [resolveCaller] binds it to the actual Binder UID. */
    fun receive(json: String, uid: Int, pid: Int, resolveCaller: (String) -> IncidentCaller): String {
        var committed: List<Incident> = emptyList()
        val reply = try {
            synchronized(lock) {
                if (uid <= 0 || pid <= 0) unauthorized()
                limitCaller(uid)
                if (json.length > IncidentWire.MAX_JSON_CHARS || json.toByteArray(Charsets.UTF_8).size > IncidentWire.MAX_JSON_BYTES) invalid()
                val request = reliabilityObject(json)
                if (request.number("version") != IncidentWire.VERSION.toLong()) invalid()
                val identity = identity(request)
                val caller = resolveCaller(identity.packageName)
                if (caller.uid != uid || caller.pid != pid) unauthorized()
                when (request.text("op")) {
                    "hello" -> {
                        request.only("version", "op", "packageName", "buildId", "sourceRevision", "sessionId", "clientNonce", "pairingNonce")
                        pairings.authorizeHello(caller, identity.packageName, identity.buildId, identity.sourceRevision, request.text("pairingNonce")) { proof ->
                            val now = monotonicMillis()
                            challenges.entries.removeAll { it.value.expiresAt <= now ||
                                (it.value.uid == uid && it.value.pid == pid &&
                                    it.value.identity.copy(clientNonce = identity.clientNonce) == identity) }
                            if (challenges.size >= 128 || challenges.values.count { it.uid == uid } >= 16) throw ReliabilityException(ReliabilityError.RETRY_LATER)
                            val challenge = Challenge(identity, uid, pid, randomNonce(random), proof, now + CHALLENGE_LIFETIME_MS)
                            challenges[challenge.serverNonce] = challenge
                            response("hello", challenge).toString()
                        }
                    }
                    "submit" -> {
                        request.only("version", "op", "packageName", "buildId", "sourceRevision", "sessionId", "clientNonce", "serverNonce", "events", "droppedCount")
                        if (request.containsKey("droppedCount") && request.number("droppedCount") < 0) invalid()
                        val serverNonce = request.text("serverNonce")
                        if (!sha256Pattern.matches(serverNonce)) unauthorized()
                        val challenge = challenges[serverNonce] ?: unauthorized()
                        if (challenge.uid != uid || challenge.pid != pid || challenge.identity != identity || challenge.expiresAt <= monotonicMillis()) unauthorized()
                        val array = request["events"] as? JsonArray ?: invalid()
                        if (array.isEmpty() || array.size > IncidentWire.MAX_EVENTS) invalid()
                        val events = array.map { parseIncident(it.objectValue()) }
                        if (events.any { it.key.packageName != identity.packageName || it.key.buildId != identity.buildId ||
                            it.key.sessionId != identity.sessionId || it.sourceRevision != identity.sourceRevision }) invalid()
                        val result = pairings.authorized(caller, identity.packageName, identity.buildId, identity.sourceRevision, challenge.proof) {
                            repository.accept(events)
                        }
                        committed = result.newlyStored
                        buildJsonObject {
                            for ((name, value) in response("receipt", challenge)) put(name, value)
                            put("ackEventIds", JsonArray(result.acceptedKeys.map { JsonPrimitive(it.eventId) }))
                        }.toString()
                    }
                    else -> invalid()
                }
            }
        } catch (failure: ReliabilityException) {
            errorReply(if (failure.code == ReliabilityError.STORAGE_UNAVAILABLE) ReliabilityError.RETRY_LATER else failure.code)
        } catch (_: Exception) {
            // Platform/package-manager exceptions must not expose paths, credentials or installed-app details.
            errorReply(ReliabilityError.RETRY_LATER)
        }
        if (committed.isNotEmpty()) try { onCommitted(committed) } catch (_: Exception) { /* Mirror is not durable delivery. */ }
        return reply
    }

    fun receive(json: String, caller: IncidentCaller): String = receive(json, caller.uid, caller.pid) { caller }

    private fun identity(value: JsonObject): Identity {
        val result = Identity(value.text("packageName"), value.text("buildId"), value.text("sourceRevision"), value.text("sessionId"), value.text("clientNonce"))
        validatePackage(result.packageName); validateId(result.buildId); validateRevision(result.sourceRevision)
        validateId(result.sessionId); validateId(result.clientNonce)
        if (result.clientNonce.length < 16) invalid()
        return result
    }

    private fun response(op: String, challenge: Challenge) = buildJsonObject {
        put("version", IncidentWire.VERSION); put("op", op); put("sessionId", challenge.identity.sessionId)
        put("clientNonce", challenge.identity.clientNonce); put("serverNonce", challenge.serverNonce)
    }

    private fun limitCaller(uid: Int) {
        val now = monotonicMillis()
        if (!consume(global, now, 240.0, 4.0)) throw ReliabilityException(ReliabilityError.RETRY_LATER)
        val bucket = callers.getOrPut(uid) { Bucket(40.0, now) }
        while (callers.size > 256) callers.remove(callers.keys.first())
        if (!consume(bucket, now, 40.0, 1.0)) throw ReliabilityException(ReliabilityError.RETRY_LATER)
    }

    private fun consume(bucket: Bucket, now: Long, capacity: Double, perSecond: Double): Boolean {
        bucket.tokens = minOf(capacity, bucket.tokens + (now - bucket.last).coerceAtLeast(0).toDouble() / 1_000 * perSecond)
        bucket.last = now
        if (bucket.tokens < 1.0) return false
        bucket.tokens -= 1.0
        return true
    }

    companion object {
        const val CHALLENGE_LIFETIME_MS = 10 * 60 * 1_000L
        fun errorReply(code: ReliabilityError): String = buildJsonObject {
            put("version", IncidentWire.VERSION); put("op", "error"); put("code", code.name)
        }.toString()
    }
}
