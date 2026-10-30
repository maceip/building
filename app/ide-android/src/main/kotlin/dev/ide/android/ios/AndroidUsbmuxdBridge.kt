package dev.ide.android.ios

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Direct Android-to-iPhone USB-C USBMux bridge using Android's [UsbManager].
 *
 * Detects physical Apple iOS devices (`vendorId == 0x05AC`, `productId in 0x1290..0x12AF`),
 * locates the Apple USBMux bulk interface (`interfaceClass == 255`, `interfaceSubclass == 254`,
 * `interfaceProtocol == 2`), and runs a local socket relay (`<filesDir>/run/usbmuxd` and
 * `USBMUXD_SOCKET_ADDRESS` loopback) so `xtool` and `libimobiledevice` on Android can discover
 * and deploy to a USB-C connected iPhone.
 */
class AndroidUsbmuxdBridge(
    private val context: Context,
) {
    data class ConnectedIosUsbDevice(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val serialNumber: String?,
        val hasPermission: Boolean,
        val usbmuxInterfaceIndex: Int,
        val bulkInEndpointAddress: Int,
        val bulkOutEndpointAddress: Int,
    )

    private data class ResolvedUsbEndpoints(
        val usbInterface: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    private val running = AtomicBoolean(false)
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var boundPort: Int = 0

    val socketFile: File
        get() = File(File(context.filesDir, "run"), "usbmuxd")

    val isRunning: Boolean
        get() = running.get()

    private fun usbManager(): UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /**
     * Scans Android [UsbManager.getDeviceList] for connected Apple iOS devices exposing
     * the USBMux bulk USB interface (`255 / 254 / 2`).
     */
    fun discoverConnectedIosDevices(): List<ConnectedIosUsbDevice> {
        val manager = usbManager() ?: return emptyList()
        val result = ArrayList<ConnectedIosUsbDevice>()
        for (device in manager.deviceList.values) {
            if (!isAppleIosUsbDevice(device.vendorId, device.productId)) continue
            val resolved = findUsbmuxEndpoints(device) ?: continue
            val hasPerm = runCatching { manager.hasPermission(device) }.getOrDefault(false)
            val serial = if (hasPerm) {
                runCatching { device.serialNumber }.getOrNull()
            } else {
                null
            }
            result += ConnectedIosUsbDevice(
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                serialNumber = serial,
                hasPermission = hasPerm,
                usbmuxInterfaceIndex = resolved.usbInterface.id,
                bulkInEndpointAddress = resolved.bulkIn.address,
                bulkOutEndpointAddress = resolved.bulkOut.address,
            )
        }
        return result
    }

    /**
     * Requests Android USB host permission for the given [UsbDevice].
     */
    fun requestUsbPermission(device: UsbDevice) {
        val manager = usbManager() ?: return
        if (manager.hasPermission(device)) return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        manager.requestPermission(device, pi)
    }

    /**
     * Starts the local USBMux socket relay and writes the endpoint metadata to
     * `<filesDir>/run/usbmuxd` so `xtool` and `libimobiledevice` can communicate with
     * connected iOS devices via [environmentVariables].
     */
    @Synchronized
    fun startRelay(): Int {
        if (running.get() && boundPort > 0) return boundPort
        val runDir = socketFile.parentFile
        if (runDir != null && !runDir.exists()) {
            runDir.mkdirs()
        }
        val srv = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = srv
        boundPort = srv.localPort
        running.set(true)
        runCatching {
            socketFile.writeText("127.0.0.1:$boundPort\n")
        }

        thread(name = "android-usbmuxd-relay", isDaemon = true) {
            while (running.get()) {
                val client = try {
                    srv.accept()
                } catch (_: Exception) {
                    break
                }
                thread(name = "android-usbmuxd-client", isDaemon = true) {
                    handleClientSocket(client)
                }
            }
        }
        return boundPort
    }

    @Synchronized
    fun stopRelay() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = 0
        runCatching { socketFile.delete() }
    }

    /**
     * Environment variables for `xtool` / `libimobiledevice` subprocesses.
     */
    fun environmentVariables(): Map<String, String> {
        val port = if (running.get() && boundPort > 0) boundPort else startRelay()
        return mapOf(
            "USBMUXD_SOCKET_ADDRESS" to "127.0.0.1:$port",
            "USBMUXD_SOCKET_PATH" to socketFile.absolutePath,
        )
    }

    private fun handleClientSocket(client: Socket) {
        client.use { sock ->
            sock.tcpNoDelay = true
            val manager = usbManager()
            val usbDevice = manager?.deviceList?.values?.firstOrNull {
                isAppleIosUsbDevice(it.vendorId, it.productId) &&
                    findUsbmuxEndpoints(it) != null &&
                    runCatching { manager.hasPermission(it) }.getOrDefault(false)
            }
            val endpoints = usbDevice?.let(::findUsbmuxEndpoints)
            val connection = if (usbDevice != null && endpoints != null) {
                manager.openDevice(usbDevice)?.also { conn ->
                    conn.claimInterface(endpoints.usbInterface, true)
                }
            } else {
                null
            }

            try {
                if (connection != null && endpoints != null) {
                    pumpUsbBulkRelay(sock.getInputStream(), sock.getOutputStream(), connection, endpoints)
                } else {
                    handleSyntheticDeviceListSession(sock.getInputStream(), sock.getOutputStream())
                }
            } finally {
                if (connection != null && endpoints != null) {
                    runCatching { connection.releaseInterface(endpoints.usbInterface) }
                    runCatching { connection.close() }
                }
            }
        }
    }

    private fun pumpUsbBulkRelay(
        clientIn: InputStream,
        clientOut: OutputStream,
        connection: UsbDeviceConnection,
        endpoints: ResolvedUsbEndpoints,
    ) {
        val active = AtomicBoolean(true)
        val readerThread = thread(name = "usbmux-usb-in", isDaemon = true) {
            val inBuf = ByteArray(16 * 1024)
            while (active.get() && running.get()) {
                val n = connection.bulkTransfer(endpoints.bulkIn, inBuf, inBuf.size, 250)
                if (n > 0) {
                    try {
                        clientOut.write(inBuf, 0, n)
                        clientOut.flush()
                    } catch (_: Exception) {
                        active.set(false)
                        break
                    }
                }
            }
        }
        val outBuf = ByteArray(16 * 1024)
        while (active.get() && running.get()) {
            val n = try {
                clientIn.read(outBuf)
            } catch (_: Exception) {
                -1
            }
            if (n <= 0) break
            val transferred = connection.bulkTransfer(endpoints.bulkOut, outBuf, n, 2000)
            if (transferred < 0) break
        }
        active.set(false)
        runCatching { readerThread.join(300) }
    }

    private fun handleSyntheticDeviceListSession(
        clientIn: InputStream,
        clientOut: OutputStream,
    ) {
        val header = ByteArray(16)
        while (running.get()) {
            val readHdr = readFully(clientIn, header, 16)
            if (readHdr < 16) break
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val totalLen = bb.int
            val version = bb.int
            val messageType = bb.int
            val tag = bb.int
            val payloadLen = (totalLen - 16).coerceIn(0, 1024 * 1024)
            val payloadBytes = ByteArray(payloadLen)
            if (payloadLen > 0 && readFully(clientIn, payloadBytes, payloadLen) < payloadLen) break
            val payloadStr = String(payloadBytes, Charsets.UTF_8)
            val devices = discoverConnectedIosDevices()
            val responsePlist = if (messageType == 8 && payloadStr.contains("ListDevices")) {
                buildDeviceListPlist(devices)
            } else {
                buildResultPlist(0)
            }
            val respBytes = responsePlist.toByteArray(Charsets.UTF_8)
            val respHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(16 + respBytes.size)
                .putInt(version)
                .putInt(8) // plist message type
                .putInt(tag)
                .array()
            clientOut.write(respHeader)
            clientOut.write(respBytes)
            clientOut.flush()
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n <= 0) return off
            off += n
        }
        return off
    }

    private fun findUsbmuxEndpoints(device: UsbDevice): ResolvedUsbEndpoints? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (!isUsbmuxBulkInterface(intf.interfaceClass, intf.interfaceSubclass, intf.interfaceProtocol)) {
                continue
            }
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
                    else if (ep.direction == UsbConstants.USB_DIR_OUT) bulkOut = ep
                }
            }
            if (bulkIn != null && bulkOut != null) {
                return ResolvedUsbEndpoints(intf, bulkIn, bulkOut)
            }
        }
        return null
    }

    companion object {
        const val APPLE_VENDOR_ID: Int = 0x05AC
        val IOS_PRODUCT_ID_RANGE: IntRange = 0x1290..0x12AF
        const val USBMUX_INTERFACE_CLASS: Int = 255
        const val USBMUX_INTERFACE_SUBCLASS: Int = 254
        const val USBMUX_INTERFACE_PROTOCOL: Int = 2
        const val ACTION_USB_PERMISSION: String = "dev.ide.android.USB_PERMISSION"

        fun isAppleIosUsbDevice(vendorId: Int, productId: Int): Boolean =
            vendorId == APPLE_VENDOR_ID && productId in IOS_PRODUCT_ID_RANGE

        fun isUsbmuxBulkInterface(
            interfaceClass: Int,
            interfaceSubclass: Int,
            interfaceProtocol: Int,
        ): Boolean =
            interfaceClass == USBMUX_INTERFACE_CLASS &&
                interfaceSubclass == USBMUX_INTERFACE_SUBCLASS &&
                interfaceProtocol == USBMUX_INTERFACE_PROTOCOL

        internal fun buildDeviceListPlist(devices: List<ConnectedIosUsbDevice>): String {
            val entries = devices.mapIndexed { index, dev ->
                val serial = dev.serialNumber?.ifBlank { null } ?: "00008020-0000000000000000"
                val devId = index + 1
                """
                <dict>
                    <key>DeviceID</key>
                    <integer>$devId</integer>
                    <key>MessageType</key>
                    <string>Attached</string>
                    <key>Properties</key>
                    <dict>
                        <key>ConnectionType</key>
                        <string>USB</string>
                        <key>DeviceID</key>
                        <integer>$devId</integer>
                        <key>ProductID</key>
                        <integer>${dev.productId}</integer>
                        <key>SerialNumber</key>
                        <string>$serial</string>
                    </dict>
                </dict>
                """.trimIndent()
            }.joinToString("\n")
            return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                    <key>DeviceList</key>
                    <array>
                    $entries
                    </array>
                </dict>
                </plist>
            """.trimIndent()
        }

        internal fun buildResultPlist(resultCode: Int): String = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>MessageType</key>
                <string>Result</string>
                <key>Number</key>
                <integer>$resultCode</integer>
            </dict>
            </plist>
        """.trimIndent()
    }
}
