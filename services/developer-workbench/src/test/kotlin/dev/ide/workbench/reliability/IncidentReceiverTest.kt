package dev.ide.workbench.reliability

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncidentReceiverTest {
    @Test fun `hello binds actual caller and receipt waits for commit while lost receipt retry mirrors once`() {
        val f = ReceiverFixture()
        val challenge = f.hello().text("serverNonce")
        val first = f.submit(challenge)
        assertEquals("receipt", first.text("op"))
        assertEquals(listOf("event-1"), (first["ackEventIds"] as JsonArray).map { (it as JsonPrimitive).content })
        assertTrue(f.incidentDisk.writes > 0)
        assertEquals(first, f.submit(challenge))
        assertEquals(1, f.repository.list().size)
        assertEquals(1, f.mirrored.size)
    }

    @Test fun `commit failure returns no ack and new host can handshake for old persisted session`() {
        val f = ReceiverFixture()
        val nonce = f.hello().text("serverNonce")
        f.incidentDisk.failAfterWrite = true
        val result = f.submit(nonce)
        assertEquals("error", result.text("op"))
        assertEquals("RETRY_LATER", result.text("code"))
        assertFalse(result.containsKey("ackEventIds"))
        assertTrue(f.mirrored.isEmpty())
        f.incidentDisk.failAfterWrite = false
        val store = DurableIncidentRepository(f.incidentDisk, clock = testClock)
        val receiver = IncidentReceiver(store, PairingRegistry(f.pairingDisk, testClock))
        val hello = buildJsonObject {
            put("version", 1); put("op", "hello"); put("packageName", testPackage); put("buildId", "build-1")
            put("sourceRevision", "abc123"); put("sessionId", "session-1"); put("clientNonce", f.clientNonce)
            put("pairingNonce", f.secret.pairingNonce)
        }
        assertEquals("hello", response(receiver.receive(hello.toString(), testCaller.copy(pid = 999))).text("op"))
        assertEquals(1, store.list().size)
    }

    @Test fun `challenge cannot transfer UID PID session client or build identity and expires`() {
        val f = ReceiverFixture()
        val nonce = f.hello().text("serverNonce")
        for (result in listOf(
            f.submit(nonce, caller = testCaller.copy(uid = 10_043)),
            f.submit(nonce, caller = testCaller.copy(pid = 444)),
            f.submit(nonce, client = "different-client-nonce"),
            f.submit(nonce, events = listOf(incident(session = "old-session"))),
            f.submit(nonce, events = listOf(incident(build = "build-2"))),
        )) assertEquals("UNAUTHORIZED", result.text("code"))
        f.now += IncidentReceiver.CHALLENGE_LIFETIME_MS + 1
        assertEquals("UNAUTHORIZED", f.submit(nonce).text("code"))
        assertTrue(f.repository.list().isEmpty())
    }

    @Test fun `revoking after hello blocks receipt and forging package with token is rejected`() {
        val f = ReceiverFixture()
        val nonce = f.hello().text("serverNonce")
        assertEquals("UNAUTHORIZED", f.hello(testCaller.copy(packages = setOf("other.app"))).text("code"))
        assertEquals("UNAUTHORIZED", f.hello(token = "f".repeat(64)).text("code"))
        f.pairings.revoke(testPackage, "build-1")
        assertEquals("UNAUTHORIZED", f.submit(nonce).text("code"))
        assertTrue(f.repository.list().isEmpty())
    }

    @Test fun `mixed original identity and changed stable ids reject entire submit`() {
        val f = ReceiverFixture()
        val nonce = f.hello().text("serverNonce")
        assertEquals("INVALID", f.submit(nonce, listOf(incident(), incident("two", build = "build-2"))).text("code"))
        assertTrue(f.repository.list().isEmpty())
        f.submit(nonce)
        assertEquals("ID_CONFLICT", f.submit(nonce, listOf(incident(text = "changed"))).text("code"))
    }

    @Test fun `oversized deep invalid and unknown operation requests fail without data or ack`() {
        val f = ReceiverFixture()
        for (json in listOf("x".repeat(IncidentWire.MAX_JSON_CHARS + 1), "{\"x\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}", "not json")) {
            val response = response(f.receiver.receive(json, testCaller))
            assertEquals("INVALID", response.text("code"))
            assertFalse(response.containsKey("ackEventIds"))
        }
        assertTrue(f.repository.list().isEmpty())
    }

    @Test fun `flood is bounded and recovers after monotonic refill`() {
        val f = ReceiverFixture()
        var reply = f.hello()
        repeat(50) { reply = f.hello() }
        assertEquals("RETRY_LATER", reply.text("code"))
        f.now += 60_000
        assertEquals("hello", f.hello().text("op"))
    }

    @Test fun `normal SDK rehandshakes supersede the same process session without exhausting challenge slots`() {
        val f = ReceiverFixture()
        var old = f.hello().text("serverNonce")
        repeat(20) { index ->
            val client = "fresh-client-nonce-$index"
            val hello = f.hello(client = client)
            assertEquals("hello", hello.text("op"))
            assertEquals("UNAUTHORIZED", f.submit(old).text("code"))
            old = hello.text("serverNonce")
            f.now += 2_000 // Normal paced sender attempts, not a flood.
        }
    }

    @Test fun `caller resolver cannot replace Binder uid and errors never echo private exceptions`() {
        val f = ReceiverFixture()
        val hello = buildJsonObject {
            put("version", 1); put("op", "hello"); put("packageName", testPackage); put("buildId", "build-1")
            put("sourceRevision", "abc123"); put("sessionId", "session-1"); put("clientNonce", f.clientNonce)
            put("pairingNonce", f.secret.pairingNonce)
        }.toString()
        val wrong = response(f.receiver.receive(hello, 42, 43) { testCaller })
        assertEquals("UNAUTHORIZED", wrong.text("code"))
        val failed = f.receiver.receive(hello, testCaller.uid, testCaller.pid) { throw IllegalStateException("secret-token-private-path") }
        assertFalse("secret-token" in failed)
        assertEquals("RETRY_LATER", response(failed).text("code"))
    }
}
