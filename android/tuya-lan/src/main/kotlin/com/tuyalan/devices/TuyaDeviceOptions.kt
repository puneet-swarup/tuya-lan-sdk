package com.tuyalan.devices

import com.tuyalan.protocol.RetcodePolicy
import com.tuyalan.protocol.TuyaCrypto
import com.tuyalan.protocol.TuyaDeviceType
import com.tuyalan.protocol.TuyaProtocolVersion
import com.tuyalan.protocol.VersionHeaderMode

/** Everything needed to talk to one device on the LAN. */
data class TuyaDeviceOptions(
    /** Device IP address, e.g. "192.168.1.42". */
    val host: String,
    /** The `gwId` / device id reported by discovery. */
    val deviceId: String,
    /** The 16-character local key. */
    val localKey: String,
    /** Protocol revision. Use the `version` field from discovery. */
    val version: TuyaProtocolVersion = TuyaProtocolVersion.V35,
    /** Device TCP port. Tuya devices listen on 6668. */
    val port: Int = 6668,
    /** Use the "device22" command variants for older 22-character gwId devices. */
    val deviceType: TuyaDeviceType = TuyaDeviceType.STANDARD,
    /** Sub-device id for Zigbee devices behind a gateway. */
    val cid: String? = null,
    /** How long to wait for the TCP connection. */
    val connectTimeoutMillis: Int = 5_000,
    /** How long to wait for a command reply. */
    val commandTimeoutMillis: Long = 5_000,
    /** How often to send a heartbeat. */
    val heartbeatIntervalMillis: Long = 20_000,
    /** Keep the connection alive with periodic heartbeat frames. */
    val enableHeartbeat: Boolean = true,
    /** Whether control commands should wait for the device to acknowledge. */
    val waitForControlAck: Boolean = true,
    /** How to treat a leading return code when decoding replies. */
    val retcodePolicy: RetcodePolicy = RetcodePolicy.AUTO,
    /** Where the 15-byte version header goes for 3.3/3.4 frames. */
    val headerMode: VersionHeaderMode = VersionHeaderMode.INSIDE_CIPHERTEXT,
) {
    init {
        require(host.isNotBlank()) { "host is required" }
        require(deviceId.isNotBlank()) { "deviceId is required" }
        require(localKey.length == TuyaCrypto.KEY_SIZE) {
            "localKey must be exactly ${TuyaCrypto.KEY_SIZE} characters, got ${localKey.length}"
        }
        require(port in 1..65535) { "port $port is out of range" }
    }
}
