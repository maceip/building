package dev.ide.workbench.reliability

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class MemoryReliabilityStorage : ReliabilityBlobStorage {
    var bytes: ByteArray? = null
    var writes = 0
    var failBeforeWrite = false
    var failAfterWrite = false
    override fun read(): ByteArray? = bytes?.clone()
    override fun write(bytes: ByteArray) {
        if (failBeforeWrite) throw java.io.IOException("private-path-must-not-leak")
        this.bytes = bytes.clone()
        writes++
        if (failAfterWrite) throw java.io.IOException("private-token-must-not-leak")
    }
}

internal val testClock: Clock = Clock.fixed(Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC)
internal const val testPackage = "sample.app"
internal val testSigner = "a".repeat(64)
internal val testCaller = IncidentCaller(10_042, 321, setOf(testPackage), setOf(testSigner), "dev.ide.test")
internal fun incident(id: String = "event-1", text: String = "java.lang.IllegalStateException: failed\n at sample.App.run(App.kt:14)", build: String = "build-1", session: String = "session-1") = IncomingIncident(
    IncidentKey(testPackage, build, session, id), "abc123", testClock.millis(), "fatal_java", text,
)

internal class ReceiverFixture {
    val pairingDisk = MemoryReliabilityStorage()
    val incidentDisk = MemoryReliabilityStorage()
    val pairings = PairingRegistry(pairingDisk, testClock)
    val repository = DurableIncidentRepository(incidentDisk, clock = testClock)
    val mirrored = ArrayList<Incident>()
    var now = 100L
    val receiver = IncidentReceiver(repository, pairings, monotonicMillis = { now }, onCommitted = { mirrored += it })
    val clientNonce = "client-nonce-0123456789"
    val secret: PairingSecret
    init {
        pairings.setDebugCaptureEnabled(true)
        secret = pairings.prepareDebugPairing(testPackage, "build-1", "abc123", setOf(testSigner), "dev.ide.test")
    }
    fun hello(caller: IncidentCaller = testCaller, token: String = secret.pairingNonce, event: IncomingIncident = incident(), client: String = clientNonce): JsonObject =
        response(receiver.receive(buildJsonObject {
            identity(event, "hello", client).forEach { (name, value) -> put(name, value) }
            put("pairingNonce", token)
        }.toString(), caller))
    fun submit(serverNonce: String, events: List<IncomingIncident> = listOf(incident()), caller: IncidentCaller = testCaller, client: String = clientNonce): JsonObject =
        response(receiver.receive(buildJsonObject {
            identity(events.first(), "submit", client).forEach { (name, value) -> put(name, value) }
            put("serverNonce", serverNonce)
            put("droppedCount", 0)
            put("events", JsonArray(events.map(::eventJson)))
        }.toString(), caller))
    private fun identity(event: IncomingIncident, op: String, client: String) = buildJsonObject {
        put("version", 1); put("op", op); put("packageName", event.key.packageName); put("buildId", event.key.buildId)
        put("sourceRevision", event.sourceRevision); put("sessionId", event.key.sessionId); put("clientNonce", client)
    }
}
internal fun response(text: String): JsonObject = reliabilityObject(text)
