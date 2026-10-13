package dev.ide.android.reliability

import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.ide.workbench.reliability.DurableIncidentRepository
import dev.ide.workbench.reliability.IncidentCaller
import dev.ide.workbench.reliability.IncidentReceiver
import dev.ide.workbench.reliability.IncidentWire
import dev.ide.workbench.reliability.PairingRegistry
import dev.ide.workbench.reliability.ReliabilityBlobStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Local real-Parcel/real-own-UID tests. A different-UID APK is deliberately a separate external fixture. */
class ReliabilitySinkBinderTest {
    private class MemoryStorage : ReliabilityBlobStorage {
        var data: ByteArray? = null
        var fail = false
        override fun read() = data?.clone()
        override fun write(bytes: ByteArray) {
            if (fail) throw java.io.IOException("private")
            data = bytes.clone()
        }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = Process.myUid()
        val pid = Process.myPid()
        val packageName = context.packageName
        val packages = context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION") val signer = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo!!.apkContentsSigners else info.signatures!!)
            .map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }.toSet()
        val disk = MemoryStorage()
        val pairings = PairingRegistry(MemoryStorage())
        val store = DurableIncidentRepository(disk)
        val receiver = IncidentReceiver(store, pairings)
        var capturedUid = -1
        var capturedPid = -1
        val binder = ReliabilitySinkBinder({ receiver }) { claimed, callingUid, callingPid ->
            capturedUid = callingUid; capturedPid = callingPid
            val actualPackages = context.packageManager.getPackagesForUid(callingUid)?.toSet().orEmpty()
            IncidentCaller(callingUid, callingPid, actualPackages, if (claimed == packageName) signer else emptySet(), null)
        }
        val grant = run {
            pairings.setDebugCaptureEnabled(true)
            // Instrumentation can be adb-installed: explicit test-only unknown installer policy, not product default.
            pairings.prepareDebugPairing(packageName, "test-build", "abc123", signer, null, true)
        }
        fun request(op: String, nonce: String = "", claimedPackage: String = packageName): String = buildJsonObject {
            put("version", 1); put("op", op); put("packageName", claimedPackage); put("buildId", "test-build")
            put("sourceRevision", "abc123"); put("sessionId", "test-session"); put("clientNonce", "test-client-0123456789")
            if (op == "hello") put("pairingNonce", grant.pairingNonce) else {
                put("serverNonce", nonce)
                put("events", JsonArray(listOf(buildJsonObject {
                    put("packageName", claimedPackage); put("buildId", "test-build"); put("sourceRevision", "abc123")
                    put("sessionId", "test-session"); put("eventId", "test-event"); put("timestamp", 123L)
                    put("type", "fatal_java"); put("redactedText", "java.lang.IllegalStateException: test")
                })))
            }
        }.toString()
        fun transact(text: String): JsonObject = transactParcel(binder) { it.writeString(text) }
    }

    @Test fun actualBinderUidPidAreUsedAndLostReceiptIsIdempotent() {
        val fixture = Fixture()
        assertEquals(setOf(fixture.packageName), fixture.packages)
        val hello = fixture.transact(fixture.request("hello"))
        assertEquals("hello", hello["op"]!!.jsonPrimitive.content)
        assertEquals(fixture.uid, fixture.capturedUid)
        assertEquals(fixture.pid, fixture.capturedPid)
        val nonce = hello["serverNonce"]!!.jsonPrimitive.content
        val receipt = fixture.transact(fixture.request("submit", nonce))
        assertEquals("receipt", receipt["op"]!!.jsonPrimitive.content)
        assertEquals(receipt, fixture.transact(fixture.request("submit", nonce)))
        assertEquals(1, fixture.store.list().size)
        fixture.pairings.revoke(fixture.packageName, "test-build")
        assertEquals("UNAUTHORIZED", fixture.transact(fixture.request("submit", nonce))["code"]!!.jsonPrimitive.content)
    }

    @Test fun claimedPackageWithValidSecretCannotForgeActualUidOwnership() {
        val fixture = Fixture()
        val reply = fixture.transact(fixture.request("hello", claimedPackage = "other.application"))
        assertEquals("UNAUTHORIZED", reply["code"]!!.jsonPrimitive.content)
        assertTrue(fixture.store.list().isEmpty())
    }

    @Test fun forgedHugeAndTruncatedParcelStringLengthsNeverAllocateOrAccept() {
        val fixture = Fixture()
        for (length in listOf(Int.MAX_VALUE, -1, 32_769, 100)) {
            val reply = transactParcel(fixture.binder) { it.writeInt(length) }
            assertEquals("INVALID", reply["code"]!!.jsonPrimitive.content)
            assertFalse(reply.containsKey("ackEventIds"))
        }
        assertTrue(fixture.store.list().isEmpty())
    }

    @Test fun onewayAndTrailingDataDoNotCreateDurableReceipts() {
        val fixture = Fixture()
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(IncidentWire.BINDER_DESCRIPTOR)
            data.writeString(fixture.request("hello"))
            assertFalse(fixture.binder.transact(IncidentWire.TXN_SUBMIT, data, null, IBinder.FLAG_ONEWAY))
        } finally { data.recycle() }
        val reply = transactParcel(fixture.binder) { it.writeString(fixture.request("hello")); it.writeInt(99) }
        assertEquals("INVALID", reply["code"]!!.jsonPrimitive.content)
    }

    @Test fun failedCommitDoesNotReturnAckIds() {
        val fixture = Fixture()
        val hello = fixture.transact(fixture.request("hello"))
        fixture.disk.fail = true
        val reply = fixture.transact(fixture.request("submit", hello["serverNonce"]!!.jsonPrimitive.content))
        assertEquals("RETRY_LATER", reply["code"]!!.jsonPrimitive.content)
        assertFalse(reply.containsKey("ackEventIds"))
    }

    companion object {
        private fun transactParcel(binder: IBinder, write: (Parcel) -> Unit): JsonObject {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(IncidentWire.BINDER_DESCRIPTOR)
                write(data)
                assertTrue(binder.transact(IncidentWire.TXN_SUBMIT, data, reply, 0))
                reply.readException()
                return Json.parseToJsonElement(reply.readString()!!) as JsonObject
            } finally { data.recycle(); reply.recycle() }
        }
    }
}
