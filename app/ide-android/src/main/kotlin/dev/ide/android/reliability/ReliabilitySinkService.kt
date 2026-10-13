package dev.ide.android.reliability

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import dev.ide.workbench.reliability.IncidentCaller
import dev.ide.workbench.reliability.IncidentReceiver
import dev.ide.workbench.reliability.IncidentWire
import dev.ide.workbench.reliability.ReliabilityError
import dev.ide.workbench.reliability.ReliabilityException
import java.util.concurrent.Semaphore

/**
 * Explicit exported cross-signature endpoint. Authentication is inside each transaction, not an unsafe
 * shared-signature permission or an intent's claimed package. Runs in the default IDE process.
 */
class ReliabilitySinkService : Service() {
    private val binder = ReliabilitySinkBinder(
        receiver = { ReliabilityHostProvider.get(applicationContext).receiver },
        resolveCaller = { packageName, uid, pid -> ReliabilityHostProvider.get(applicationContext).caller(packageName, uid, pid) },
    )
    override fun onBind(intent: Intent?): IBinder = binder
}

/** Exposed internally for real-Parcel instrumentation; identity always comes from Binder itself. */
internal class ReliabilitySinkBinder(
    private val receiver: () -> IncidentReceiver,
    private val resolveCaller: (String, Int, Int) -> IncidentCaller,
) : Binder() {
    private val slots = Semaphore(2)

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(IncidentWire.BINDER_DESCRIPTOR)
            return true
        }
        if (code != IncidentWire.TXN_SUBMIT) return super.onTransact(code, data, reply, flags)
        // A oneway request can never obtain a durable receipt and is not processed at all.
        if (reply == null || flags and IBinder.FLAG_ONEWAY != 0) return false
        val uid = getCallingUid()
        val pid = getCallingPid()
        if (!slots.tryAcquire()) {
            writeReply(reply, IncidentReceiver.errorReply(ReliabilityError.RETRY_LATER))
            return true
        }
        val result = try {
            if (data.dataSize() > IncidentWire.MAX_PARCEL_BYTES || data.hasFileDescriptors()) invalid()
            data.enforceInterface(IncidentWire.BINDER_DESCRIPTOR)
            val json = readBoundedParcelString(data, IncidentWire.MAX_JSON_CHARS)
            if (data.dataAvail() != 0) invalid()
            receiver().receive(json, uid, pid) { packageName -> resolveCaller(packageName, uid, pid) }
        } catch (_: SecurityException) {
            IncidentReceiver.errorReply(ReliabilityError.UNAUTHORIZED)
        } catch (failure: ReliabilityException) {
            IncidentReceiver.errorReply(if (failure.code == ReliabilityError.STORAGE_UNAVAILABLE) ReliabilityError.RETRY_LATER else failure.code)
        } catch (_: RuntimeException) {
            IncidentReceiver.errorReply(ReliabilityError.INVALID)
        } finally {
            slots.release()
        }
        writeReply(reply, result)
        return true
    }

    private fun writeReply(reply: Parcel, json: String) {
        reply.writeNoException()
        reply.writeString(json)
    }
    private fun invalid(): Nothing = throw ReliabilityException(ReliabilityError.INVALID)
}

/** Check raw UTF16 length and padded remaining bytes BEFORE Android allocates a String. */
internal fun readBoundedParcelString(parcel: Parcel, maxChars: Int): String {
    fun invalid(): Nothing = throw ReliabilityException(ReliabilityError.INVALID)
    if (parcel.dataAvail() < 4) invalid()
    val position = parcel.dataPosition()
    val length = parcel.readInt()
    if (length < 0 || length > maxChars) invalid()
    val paddedBytes = (((length.toLong() + 1L) * 2L + 3L) / 4L) * 4L
    if (paddedBytes > parcel.dataAvail().toLong()) invalid()
    parcel.setDataPosition(position)
    val result = parcel.readString() ?: invalid()
    if (result.length != length) invalid()
    return result
}
