package dev.ide.android.applog

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import dev.ide.android.AppLogSinkRegistry
import dev.ide.android.reliability.readBoundedParcelString
import dev.ide.core.applog.AppLogEvent
import dev.ide.core.applog.AppLogWire

/**
 * The exported Binder service the log bridge injected into a running debug app binds to (resolved by the
 * [AppLogWire.SINK_ACTION] intent) and pushes its log frames over. A `LocalSocket` can't cross the
 * untrusted-app boundary — SELinux denies one untrusted app connecting to another's abstract socket
 * (`avc: denied { connectto }`) — so Binder is the sanctioned channel. Each [AppLogWire.TXN_SUBMIT]
 * transaction carries a `String[]` of wire payloads, routed to the active [dev.ide.android.AppLogChannelImpl]
 * via [AppLogSinkRegistry] (both live in the IDE process).
 *
 * Exported across developer signatures. This legacy best-effort console is NOT the durable incident inbox.
 * Both HELLO and every subsequent record are bound to the actual Binder UID/PID; claimed package/PID alone
 * are not authentication. Shared UIDs fail closed, and lengths are checked before allocation.
 */
class AppLogSinkService : Service() {
    private val clients = LinkedHashMap<Pair<Int, Int>, String>()
    private val clientsLock = Any()

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != AppLogWire.TXN_SUBMIT) return super.onTransact(code, data, reply, flags)
            val uid = getCallingUid()
            val pid = getCallingPid()
            try {
                if (uid <= 0 || pid <= 0 || data.dataSize() > 512 * 1024 || data.hasFileDescriptors()) return true
                data.enforceInterface(AppLogWire.BINDER_DESCRIPTOR)
                if (data.dataAvail() < 4) return true
                val count = data.readInt() // String[] framing, but never allocate from an unchecked count.
                if (count !in 1..256) return true
                val frames = ArrayList<String>(count)
                repeat(count) { frames += readBoundedParcelString(data, 65_536) }
                if (data.dataAvail() != 0) return true
                synchronized(clientsLock) {
                    val caller = uid to pid
                    val owned = packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
                    val safe = ArrayList<String>(frames.size)
                    for (frame in frames) {
                        when (val event = AppLogWire.parse(frame)) {
                            is AppLogEvent.Hello -> {
                                if (event.protocolVersion != AppLogWire.PROTOCOL_VERSION || event.pid != pid || owned != setOf(event.packageName)) {
                                    clients.remove(caller)
                                    return true
                                }
                                clients[caller] = event.packageName
                                while (clients.size > 32) clients.remove(clients.keys.first())
                                safe += frame
                            }
                            is AppLogEvent.Record -> {
                                if (event.entry.pid == pid && clients[caller]?.let { owned == setOf(it) } == true) safe += frame
                            }
                            null -> Unit
                        }
                    }
                    if (safe.isNotEmpty()) AppLogSinkRegistry.active?.acceptFrames(safe)
                }
            } catch (_: RuntimeException) {
                // Malformed/unauthorized legacy traffic is dropped, never logged with its payload.
            }
            return true // legacy oneway console only; reliability uses durable synchronous receipts.
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        synchronized(clientsLock) { clients.clear() }
        AppLogSinkRegistry.active?.onClientDisconnected()
        return false
    }
}
