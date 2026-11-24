package com.tuyalan.java

import com.tuyalan.devices.TuyaDeviceOptions
import com.tuyalan.devices.TuyaLocalDevice
import com.tuyalan.devices.TuyaStatus
import com.tuyalan.protocol.TuyaProtocolVersion
import kotlinx.coroutines.runBlocking

/**
 * A blocking front door for Java callers.
 *
 * The library itself is coroutine-based: `connect()`, `getStatus()` and the
 * setters are `suspend` functions, which Java cannot call without building a
 * `Continuation` by hand. This class is the same device with every call wrapped
 * in [runBlocking], so Java gets ordinary methods that return values.
 *
 * Kotlin callers should use [TuyaLocalDevice] directly and stay on coroutines -
 * blocking a thread is a choice only the caller can make.
 *
 * ```java
 * try (TuyaLanDevice plug = TuyaLanDevice.create("192.168.1.42", "bf1234...", "0123456789abcdef")) {
 *     plug.connect();
 *     boolean on = plug.getBool("1");
 *     plug.setBool("1", !on);
 * }
 * ```
 */
class TuyaLanDevice @JvmOverloads constructor(
    /** Host, device id and local key as the Tuya cloud reports them. */
    host: String,
    deviceId: String,
    localKey: String,
    /** "3.3", "3.4" or "3.5"; anything else falls back to 3.3. */
    version: String = "3.5",
    port: Int = 6668,
    connectTimeoutMillis: Int = 5000,
    commandTimeoutMillis: Long = 5000,
) : AutoCloseable {

    private val device = TuyaLocalDevice(
        TuyaDeviceOptions(
            host = host,
            deviceId = deviceId,
            localKey = localKey,
            version = TuyaProtocolVersion.parseLabel(version),
            port = port,
            connectTimeoutMillis = connectTimeoutMillis,
            commandTimeoutMillis = commandTimeoutMillis,
        )
    )

    /** True while the socket is usable. Turns false when the connection dies. */
    val isConnected: Boolean get() = device.isConnected

    /** Connects, or reconnects over a socket that died. */
    fun connect() {
        runBlocking { device.ensureConnected() }
    }

    /** Closes the connection. Safe to call when already closed. */
    fun disconnect() {
        runBlocking { device.disconnect() }
    }

    /** Reads every data point the device reports. */
    fun getStatus(): TuyaStatus = runBlocking { device.getStatus() }

    /** Reads one boolean data point, e.g. relay `"1"`. */
    fun getBool(dpId: String): Boolean = runBlocking { device.getStatus().getBool(dpId) }

    /** Writes one boolean data point, e.g. relay `"1"`. */
    fun setBool(dpId: String, value: Boolean) {
        runBlocking { device.setDp(dpId, value) }
    }

    /** Writes one integer data point, e.g. a brightness or a countdown. */
    fun setInt(dpId: String, value: Int) {
        runBlocking { device.setDp(dpId, value) }
    }

    /** Writes one string data point. */
    fun setString(dpId: String, value: String) {
        runBlocking { device.setDp(dpId, value) }
    }

    /** Sends a heartbeat and waits for the reply. */
    fun ping(): Boolean = runBlocking { device.ping() }

    /** Same as [disconnect], for try-with-resources. */
    override fun close() = disconnect()

    companion object {
        /**
         * Convenience factory, so Java does not have to deal with Kotlin's
         * default arguments.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            host: String,
            deviceId: String,
            localKey: String,
            version: String = "3.5",
            port: Int = 6668,
        ): TuyaLanDevice = TuyaLanDevice(host, deviceId, localKey, version, port)
    }
}
