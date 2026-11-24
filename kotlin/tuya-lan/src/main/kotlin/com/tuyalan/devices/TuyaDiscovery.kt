package com.tuyalan.devices

import com.tuyalan.TuyaException
import com.tuyalan.protocol.RetcodePolicy
import com.tuyalan.protocol.TuyaCommand
import com.tuyalan.protocol.TuyaCrypto
import com.tuyalan.protocol.TuyaFrameCodec
import com.tuyalan.protocol.TuyaProtocolVersion
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** A device found by [TuyaDiscovery]. */
data class TuyaDiscoveryResult(
    /** IP address the announcement came from. */
    val ipAddress: String,
    /** Device id, also called `gwId`. */
    val deviceId: String,
    /** Product key (productId) of the device model. */
    val productKey: String? = null,
    /** Protocol revision the device reports. */
    val version: TuyaProtocolVersion = TuyaProtocolVersion.V33,
    /** True when the announcement was encrypted (everything from 3.3 onwards). */
    val encrypted: Boolean = false,
    /** The decoded announcement JSON. */
    val rawJson: String = "",
) {
    override fun toString(): String = "$ipAddress $deviceId v${version.label} productKey=$productKey"
}

/**
 * Finds Tuya devices on the local network.
 *
 * Three announcement formats exist. 3.1 devices broadcast plain JSON on UDP 6666.
 * From 3.3 they broadcast an AES-encrypted blob on UDP 6667. 3.5 devices use the
 * 0x00006699 frame with AES-GCM. All encrypted forms use the same well-known key,
 * `MD5("yGAdlopoPVldABfn")`, which protects only the announcement and never
 * device control.
 *
 * Many 3.5 devices no longer broadcast at all: they wait for a client to announce
 * itself on UDP 7000 and reply directly. [scan] sends that solicitation by default.
 *
 * The scan is blocking and belongs on a background dispatcher; it is a suspend
 * function so callers can simply `launch` it.
 */
object TuyaDiscovery {
    /** Port for plaintext (3.1) announcements. */
    const val PLAINTEXT_PORT = 6666

    /** Port for encrypted (3.3+) announcements. */
    const val ENCRYPTED_PORT = 6667

    /** Port used for app-style discovery and 3.5 solicitation. */
    const val APP_PORT = 7000

    /** The public discovery key: `MD5("yGAdlopoPVldABfn")`. */
    @JvmField
    val discoveryKey: ByteArray = TuyaCrypto.md5("yGAdlopoPVldABfn".toByteArray(Charsets.US_ASCII))

    /**
     * Listens for announcements, optionally solicits them, and returns every
     * distinct device seen within [durationMillis].
     */
    @JvmStatic
    @JvmOverloads
    suspend fun scan(durationMillis: Long = 6_000, solicit: Boolean = true): List<TuyaDiscoveryResult> =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + durationMillis
            val sockets = mutableListOf<DatagramSocket>()

            for (port in intArrayOf(PLAINTEXT_PORT, ENCRYPTED_PORT, APP_PORT)) {
                try {
                    val socket = DatagramSocket(null)
                    socket.reuseAddress = true
                    socket.broadcast = true
                    socket.soTimeout = 400
                    socket.bind(InetSocketAddress(port))
                    sockets.add(socket)
                } catch (e: SocketException) {
                    // port busy (the Smart Life app is a common culprit); try the next one
                }
            }

            if (sockets.isEmpty()) {
                throw TuyaException(
                    "could not bind any discovery port (6666/6667/7000). Another app may be holding them.",
                )
            }

            try {
                if (solicit) sendSolicitation(sockets.first())

                val found = LinkedHashMap<String, TuyaDiscoveryResult>()
                val buffer = ByteArray(4096)

                while (System.currentTimeMillis() < deadline) {
                    for (socket in sockets) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val datagram = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                            val result = decode(datagram, packet.address?.hostAddress ?: "")
                            if (result != null) found[result.deviceId] = result
                        } catch (e: SocketTimeoutException) {
                            // nothing on this port yet
                        } catch (e: IOException) {
                            // a stray datagram must not kill the scan
                        }
                    }
                }

                found.values.toList()
            } finally {
                for (socket in sockets) runCatching { socket.close() }
            }
        }

    /** Decodes one announcement datagram. Returns null if it is not a Tuya announcement. */
    @JvmStatic
    fun decode(datagram: ByteArray, fallbackIp: String): TuyaDiscoveryResult? {
        if (datagram.size < 4) return null

        val prefix = TuyaFrameCodec.readInt(datagram, 0)
        if (prefix == TuyaFrameCodec.PREFIX_6699) return decodeGcm(datagram, fallbackIp)
        if (prefix == TuyaFrameCodec.PREFIX_55AA) return decodeLegacyFrame(datagram, fallbackIp)

        // 3.1 firmware broadcasts bare JSON, or a raw AES-ECB blob with no frame.
        if (datagram[0] == '{'.code.toByte()) return parseJson(String(datagram, Charsets.UTF_8), fallbackIp, false)

        return try {
            val plain = TuyaCrypto.aesEcbDecrypt(discoveryKey, datagram)
            parseJson(String(plain, Charsets.UTF_8), fallbackIp, true)
        } catch (e: TuyaException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun decodeGcm(datagram: ByteArray, fallbackIp: String): TuyaDiscoveryResult? = try {
        val message = TuyaFrameCodec.unpack(TuyaProtocolVersion.V35, discoveryKey, datagram, RetcodePolicy.AUTO)
        parseJson(message.payloadText.trimEnd('\u0000'), fallbackIp, true)
    } catch (e: TuyaException) {
        null
    }

    private fun decodeLegacyFrame(datagram: ByteArray, fallbackIp: String): TuyaDiscoveryResult? = try {
        val message = TuyaFrameCodec.unpack(TuyaProtocolVersion.V33, discoveryKey, datagram, RetcodePolicy.NEVER)
        if (message.payload.isNotEmpty() && message.payload[0] == '{'.code.toByte()) {
            parseJson(message.payloadText, fallbackIp, false)
        } else {
            val plain = TuyaCrypto.aesEcbDecrypt(discoveryKey, message.payload)
            parseJson(String(plain, Charsets.UTF_8), fallbackIp, true)
        }
    } catch (e: TuyaException) {
        null
    }

    private fun parseJson(json: String, fallbackIp: String, encrypted: Boolean): TuyaDiscoveryResult? = try {
        val root = JSONObject(json)
        val deviceId = listOf("gwId", "devId", "id")
            .map { root.optString(it, "") }
            .firstOrNull { it.isNotEmpty() }

        if (deviceId == null) {
            null
        } else {
            val advertised = root.optString("ip", "")
            val ip = if (advertised.isBlank() || advertised == "0.0.0.0") fallbackIp else advertised
            val productKey = listOf("productKey", "productId")
                .map { root.optString(it, "") }
                .firstOrNull { it.isNotEmpty() }

            TuyaDiscoveryResult(
                ipAddress = ip,
                deviceId = deviceId,
                productKey = productKey,
                version = TuyaProtocolVersion.parseLabel(root.optString("version", "")),
                encrypted = encrypted,
                rawJson = json,
            )
        }
    } catch (e: JSONException) {
        null
    }

    /**
     * Broadcasts the 3.5 solicitation payload `{"from":"app","ip":"<this host>"}`
     * to UDP 7000 on every interface so silent devices answer directly.
     */
    private fun sendSolicitation(socket: DatagramSocket) {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())

        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) continue

            for (address in networkInterface.interfaceAddresses) {
                val broadcast = address.broadcast ?: continue
                val localIp = address.address?.hostAddress ?: continue
                val json = """{"from":"app","ip":"$localIp"}"""
                val frame = TuyaFrameCodec.pack(
                    TuyaProtocolVersion.V35,
                    discoveryKey,
                    1,
                    TuyaCommand.REQ_DEVINFO,
                    json.toByteArray(Charsets.UTF_8),
                )
                runCatching { socket.send(DatagramPacket(frame, frame.size, broadcast, APP_PORT)) }
            }
        }
    }
}
