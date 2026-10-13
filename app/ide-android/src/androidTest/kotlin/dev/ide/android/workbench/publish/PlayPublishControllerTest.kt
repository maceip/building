package dev.ide.android.workbench.publish

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ide.workbench.publish.PlayPublisherClient
import dev.ide.workbench.publish.PlayPublisherConfig
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only a loopback fake receives synthetic credentials. This suite must never call a Google endpoint. */
@RunWith(AndroidJUnit4::class)
class PlayPublishControllerTest {
    @Test
    fun constructingStateDoesNotWriteAndOnlyConfirmedCreateAndDiscardSendRequests() = runBlocking {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val auth = session()
            val state = state(auth, scope, server)
            try {
                withContext(Dispatchers.Main) { state.synchronizeIdentity() }
                assertTrue(server.requests.isEmpty())
                create(state, auth, server)
                assertEquals(listOf("POST /androidpublisher/v3/applications/example.app/edits", "GET /androidpublisher/v3/applications/example.app/edits/edit123/tracks"), server.requests.toList())
                assertEquals("edit123", state.edit?.id)
                assertNull(state.commitPreview)
                val edit = requireNotNull(state.edit)
                server.reply(204, "")
                awaitOperation(state) { state.discardAfterConfirmation(edit, auth.identityKey) }
                assertNull(state.edit)
                assertTrue(server.requests.last().startsWith("DELETE "))
                assertFalse(server.requests.any { ":commit" in it || "bundles" in it })
            } finally { withContext(Dispatchers.Main) { state.close() }; scope.cancel() }
        }
    }

    @Test
    fun manualReplacementInvalidatesOldClientWithoutDeletingOldEdit() = runBlocking {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val auth = session()
            val state = state(auth, scope, server)
            try {
                create(state, auth, server)
                withContext(Dispatchers.Main) {
                    auth.manualTokenInput = "fake-second-access-token"
                    auth.acceptManual() // Same label is not proof of the same identity.
                    state.synchronizeIdentity()
                }
                assertNull(state.edit)
                assertNull(state.commitPreview)
                assertTrue(state.tracks.isEmpty())
                assertEquals("edit123", state.recoveryEdits.single().edit.id)
                assertEquals(2, server.requests.size)
                assertFalse(server.requests.any { it.startsWith("DELETE") })
            } finally { withContext(Dispatchers.Main) { state.close() }; scope.cancel() }
        }
    }

    @Test
    fun wrongIdentityCannotExecuteAConfirmedAction() = runBlocking {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val auth = session()
            val state = state(auth, scope, server)
            try {
                awaitOperation(state) { state.createEditAfterConfirmation("example.app", "a-previous-identity") }
                assertTrue(server.requests.isEmpty())
                assertNull(state.edit)
                assertNotNull(state.error)
                assertFalse(auth.tokens.toString().contains("fake-test-access-token"))
            } finally { withContext(Dispatchers.Main) { state.close() }; scope.cancel() }
        }
    }

    @Test
    fun unauthorizedReadClearsTokenRetainsNonsecretFormAndDoesNotRetry() = runBlocking {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val auth = session()
            val state = state(auth, scope, server)
            try {
                withContext(Dispatchers.Main) { state.form = state.form.copy(packageName = "example.app", title = "Retained listing") }
                server.reply(401, """{"error":{"message":"Bearer secret-response-marker"}}""")
                awaitOperation(state) { state.monitorReleases() }
                assertFalse(auth.connected)
                assertFalse(auth.tokens.hasToken)
                assertEquals("Retained listing", state.form.title)
                assertEquals(1, server.requests.size)
                assertFalse(state.error.orEmpty().contains("secret-response-marker"))
                assertTrue(auth.error.orEmpty().contains("Authorize again"))
            } finally { withContext(Dispatchers.Main) { state.close() }; scope.cancel() }
        }
    }

    @Test
    fun createNetworkFailureIsUncertainAndNeverRetried() = runBlocking {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val auth = session()
            val state = state(auth, scope, server)
            try {
                server.reply(503, "{}")
                awaitOperation(state) { state.createEditAfterConfirmation("example.app", auth.identityKey) }
                assertTrue(state.resultUncertain)
                assertEquals(1, server.requests.size)
                assertNull(state.commitPreview)
                assertNotNull(state.error)
            } finally { withContext(Dispatchers.Main) { state.close() }; scope.cancel() }
        }
    }

    @Test
    fun stateCloseCancelsLocalWorkAndClearsCredentialsWithoutSendingDiscard() = runBlocking {
        LoopbackPlayServer().use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val auth = session()
            val state = state(auth, scope, server)
            try {
                create(state, auth, server)
                withContext(Dispatchers.Main) { state.close() }
                assertFalse(auth.tokens.hasToken)
                assertFalse(auth.connected)
                assertEquals(2, server.requests.size)
                assertFalse(server.requests.any { it.startsWith("DELETE") || ":commit" in it })
            } finally { scope.cancel() }
        }
    }

    private fun session() = PlayAuthorizationState().also {
        it.manualAccountLabel = "Loopback test"
        it.manualTokenInput = "fake-test-access-token"
        it.acceptManual()
    }

    private fun state(auth: PlayAuthorizationState, scope: CoroutineScope, server: LoopbackPlayServer) = PlayPublishState(auth, scope) { tokens ->
        PlayPublisherClient(tokens, PlayPublisherConfig(apiOrigin = server.origin, allowLoopbackForTests = true, maxUploadRetries = 0, requestTimeoutMillis = 3_000))
    }

    private suspend fun create(state: PlayPublishState, auth: PlayAuthorizationState, server: LoopbackPlayServer) {
        server.reply(200, """{"id":"edit123","expiryTimeSeconds":"${System.currentTimeMillis() / 1000 + 3600}"}""")
        server.reply(200, """{"tracks":[{"track":"internal","releases":[]},{"track":"production","releases":[]}]}""")
        awaitOperation(state) { state.createEditAfterConfirmation("example.app", auth.identityKey) }
        assertNull(state.error)
    }

    private suspend fun awaitOperation(state: PlayPublishState, operation: () -> Unit) {
        val job = withContext(Dispatchers.Main) { operation(); state.operationJob }
        if (job != null) withTimeout(5_000) { job.join() }
        assertNull("Operation must settle before assertions", state.busy)
    }
}

private class LoopbackPlayServer : AutoCloseable {
    private data class Reply(val status: Int, val body: String)
    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val responses = LinkedBlockingQueue<Reply>()
    private val closed = AtomicBoolean(false)
    val requests = CopyOnWriteArrayList<String>()
    val origin = "http://127.0.0.1:${listener.localPort}/"
    private val worker = thread(name = "play-publish-loopback-test", isDaemon = true) {
        while (!closed.get()) {
            val socket = try { listener.accept() } catch (_: Exception) { break }
            socket.use { accepted ->
                runCatching { serve(accepted) }
            }
        }
    }

    fun reply(status: Int, body: String) { responses.put(Reply(status, body)) }

    private fun serve(socket: Socket) {
        socket.soTimeout = 3_000
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() < 32768) {
                val b = input.read()
                if (b < 0 || b == 10) break
                if (b != 13) bytes.write(b)
            }
            return bytes.toString("US-ASCII")
        }
        val first = line().split(' ')
        var length = 0
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
        }
        repeat(length) { input.read() }
        requests += "${first[0]} ${first[1]}"
        val reply = responses.poll() ?: Reply(500, "{}")
        val body = reply.body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 ${reply.status} Test\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    override fun close() {
        closed.set(true)
        listener.close()
        worker.join(4_000)
    }
}
