package com.tuyalan.device

import com.tuyalan.devices.TuyaDeviceOptions
import com.tuyalan.devices.TuyaDeviceType
import com.tuyalan.protocol.TuyaProtocolVersion

/**
 * Builds [TuyaDeviceOptions] from Java.
 *
 * A Kotlin data class with default arguments has no Java-friendly constructor:
 * every parameter is required positionally, in declaration order, and adding one
 * breaks every caller. A builder is the boring answer, and the right one for a
 * public API.
 *
 * ```java
 * TuyaDeviceOptions options = new TuyaDeviceOptionsBuilder()
 *         .host("192.168.1.42")
 *         .deviceId("bf0123...")
 *         .localKey("0123456789abcdef")
 *         .version(TuyaProtocolVersion.V33)
 *         .build();
 * ```
 */
class TuyaDeviceOptionsBuilder {
    private var host: String = ""
    private var deviceId: String = ""
    private var localKey: String = ""
    private var version: TuyaProtocolVersion = TuyaProtocolVersion.V35
    private var port: Int = 6668
    private var deviceType: TuyaDeviceType = TuyaDeviceType.STANDARD
    private var cid: String? = null
    private var connectTimeoutMillis: Int = 5_000
    private var commandTimeoutMillis: Long = 5_000
    private var heartbeatIntervalMillis: Long = 20_000
    private var enableHeartbeat: Boolean = true
    private var waitForControlAck: Boolean = true
    private var localKeyBytes: ByteArray? = null

    fun host(value: String) = apply { host = value }
    fun deviceId(value: String) = apply { deviceId = value }
    fun localKey(value: String) = apply { localKey = value }
    fun version(value: TuyaProtocolVersion) = apply { version = value }
    fun port(value: Int) = apply { port = value }
    fun deviceType(value: TuyaDeviceType) = apply { deviceType = value }
    fun cid(value: String?) = apply { cid = value }
    fun connectTimeoutMillis(value: Int) = apply { connectTimeoutMillis = value }
    fun commandTimeoutMillis(value: Long) = apply { commandTimeoutMillis = value }
    fun heartbeatIntervalMillis(value: Long) = apply { heartbeatIntervalMillis = value }
    fun enableHeartbeat(value: Boolean) = apply { enableHeartbeat = value }
    fun waitForControlAck(value: Boolean) = apply { waitForControlAck = value }
    fun localKeyBytes(value: ByteArray?) = apply { localKeyBytes = value }

    fun build(): TuyaDeviceOptions = TuyaDeviceOptions(
        host = host,
        deviceId = deviceId,
        localKey = localKey,
        version = version,
        port = port,
        deviceType = deviceType,
        cid = cid,
        connectTimeoutMillis = connectTimeoutMillis,
        commandTimeoutMillis = commandTimeoutMillis,
        heartbeatIntervalMillis = heartbeatIntervalMillis,
        enableHeartbeat = enableHeartbeat,
        waitForControlAck = waitForControlAck,
        localKeyBytes = localKeyBytes,
    )
}
